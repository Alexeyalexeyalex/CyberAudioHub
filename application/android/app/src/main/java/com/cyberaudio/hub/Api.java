package com.cyberaudio.hub;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Разговор с сервером CyberAudio Hub.
 *
 * Намеренно на HttpURLConnection, без OkHttp и прочего: приложению нужны
 * пять запросов и скачивание файла, а лишняя библиотека — это лишние
 * мегабайты в APK и лишний повод ему сломаться при обновлении.
 *
 * Сессия держится на той же cookie, что и в браузере: сервер не знает, что
 * с ним говорит приложение, и отдельного протокола для него не понадобилось.
 */
public class Api {

    /** Ошибка, которую есть смысл показать человеку. */
    public static class ApiException extends IOException {
        public final int status;

        ApiException(String message, int status) {
            super(message);
            this.status = status;
        }
    }

    /**
     * Понятное объяснение вместо системного текста.
     *
     * Без этого при выключенной сети на экран выводилось
     * «Failed to connect to /192.168.31.254:2077» — по-английски и про
     * внутренности, тогда как человеку нужно понять, что делать.
     */
    public static String describe(Exception e) {
        if (e instanceof ApiException || e instanceof SessionChangedException) return e.getMessage();
        String text = e.getMessage() == null ? "" : e.getMessage().toLowerCase(java.util.Locale.ROOT);
        if (text.contains("failed to connect") || text.contains("econnrefused")
                || text.contains("unreachable")) {
            return "Сервер не отвечает. Проверьте подключение к сети "
                    + "и адрес сайта в настройках.";
        }
        if (text.contains("timeout") || text.contains("timed out")) {
            return "Сервер долго не отвечает. Проверьте связь.";
        }
        if (text.contains("unable to resolve host")) {
            return "Не удалось найти сервер по этому адресу.";
        }
        return "Не получилось связаться с сервером.";
    }

    private static final String PREFS = "cyberaudio";
    private static final int TIMEOUT = 20000;

    private final AccountSession account;

    public Api(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        account = new AccountSession(new AccountSession.Persistence() {
            public String get(String key, String fallback) { return prefs.getString(key, fallback); }
            public void put(Map<String, String> changes) {
                SharedPreferences.Editor editor = prefs.edit();
                for (Map.Entry<String, String> item : changes.entrySet()) {
                    if (item.getValue() == null) editor.remove(item.getKey());
                    else editor.putString(item.getKey(), item.getValue());
                }
                // A successful login is reported only after its state is on disk.
                if (!editor.commit()) throw new IllegalStateException("Не удалось сохранить вход на телефоне");
            }
        });
    }

    Api(AccountSession account) { this.account = account; }

    // --- Настройки ---

    public String server() {
        return account.snapshot().server;
    }

    public boolean hasServer() {
        return !server().isEmpty();
    }

    /**
     * Запоминает адрес сервера. Человек вводит «192.168.1.5:2077» или
     * «http://…» — приводим к пригодному виду, чтобы не заставлять его
     * помнить про схему и слэши.
     */
    public void setServer(String raw) {
        account.setServer(raw);
    }

    public boolean isSignedIn() {
        return !cookieHeader().isEmpty();
    }

    public void forgetSession() {
        account.forget(false);
    }

    public JSONObject cachedProfile() {
        try { return new JSONObject(account.cachedProfile()); }
        catch (JSONException e) { return null; }
    }

    static class SessionChangedException extends IOException {
        SessionChangedException() { super("Настройки входа изменились. Повторите действие."); }
    }

    // --- Низкий уровень ---

    private static boolean isLoginRequest(String path) {
        return path.equals("/api/auth/login") || path.equals("/api/auth/register");
    }

    private HttpURLConnection open(String path, AccountSession.Snapshot session) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(session.server + path).openConnection();
        connection.setConnectTimeout(TIMEOUT);
        connection.setReadTimeout(TIMEOUT);
        connection.setUseCaches(false);
        connection.setInstanceFollowRedirects(false);
        // Login is anonymous on the wire, but the old durable account remains
        // intact until the new response is committed. Cache/process cleanup or
        // a failed request must not turn a re-login attempt into a logout.
        if (!session.cookie.isEmpty() && !isLoginRequest(path)) {
            connection.setRequestProperty("Cookie", session.cookie);
        }
        return connection;
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
        return out.toString("UTF-8");
    }

    private JSONObject request(String method, String path, JSONObject body)
            throws IOException {
        return request(method, path, body, account.snapshot());
    }

    private JSONObject request(String method, String path, JSONObject body, AccountSession.Snapshot session)
            throws IOException {
        HttpURLConnection connection = open(path, session);
        try {
            connection.setRequestMethod(method);
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(payload);
                }
            }
            int status = connection.getResponseCode();
            String text = readAll(status >= 400
                    ? connection.getErrorStream() : connection.getInputStream());
            if (status >= 400) {
                String message = "Ошибка " + status;
                try {
                    JSONObject error = new JSONObject(text);
                    if (error.has("error")) message = error.getString("error");
                } catch (JSONException ignored) {
                    // Сервер мог ответить не JSON — оставляем номер ошибки
                }
                throw new ApiException(message, status);
            }
            try {
                JSONObject data = text.isEmpty() ? new JSONObject() : new JSONObject(text);
                boolean hasProfile = data.has("user") && (path.equals("/api/me") || path.startsWith("/api/auth/"));
                JSONObject user = data.optJSONObject("user");
                String cookie = SessionCookies.session(connection.getHeaderFields());
                boolean accepted;
                if (isLoginRequest(path)) {
                    if (cookie == null || cookie.isEmpty() || user == null)
                        throw new ApiException("Сервер не подтвердил новый вход. Повторите попытку.", status);
                    accepted = account.acceptLogin(session, cookie, user.toString());
                } else {
                    accepted = account.accept(session, cookie, hasProfile, user == null ? null : user.toString());
                    // Parallel requests can refresh the same account cookie.
                    // Keep the successful catalogue/action response, but never
                    // write its older cookie back or cross an account boundary.
                    if (!accepted && !hasProfile && account.sameSignedInAccount(session)) accepted = true;
                }
                if (!accepted) throw new SessionChangedException();
                return data;
            } catch (JSONException e) {
                throw new ApiException("Непонятный ответ сервера", status);
            }
        } finally {
            connection.disconnect();
        }
    }

    // --- Операции ---

    public JSONObject login(String login, String password) throws IOException {
        try {
            JSONObject body = new JSONObject()
                    .put("login", login).put("password", password);
            return request("POST", "/api/auth/login", body, account.beginLogin());
        } catch (JSONException e) {
            throw new ApiException("Не удалось собрать запрос", 0);
        }
    }

    public JSONObject register(String login, String nickname, String password)
            throws IOException {
        try {
            JSONObject body = new JSONObject()
                    .put("login", login).put("nickname", nickname)
                    .put("password", password);
            return request("POST", "/api/auth/register", body, account.beginLogin());
        } catch (JSONException e) {
            throw new ApiException("Не удалось собрать запрос", 0);
        }
    }

    /** Текст главы с привязкой ко времени — для слежения за чтением. */
    public JSONObject transcript(String path, int track) throws IOException {
        return request("GET", "/api/transcript?path="
                + URLEncoder.encode(path, "UTF-8") + "&track=" + track, null);
    }

    /** Что за версия приложения лежит на сервере. */
    public JSONObject appInfo() throws IOException {
        return request("GET", "/api/app", null);
    }

    public JSONObject me() throws IOException {
        String origin = server();
        try { return request("GET", "/api/me", null); }
        catch (SessionChangedException e) {
            if (!origin.equals(server())) throw e;
            return request("GET", "/api/me", null);
        }
    }

    public JSONObject recommendation() throws IOException {
        return request("GET", "/api/recommendation", null);
    }

    public JSONObject updateProfile(JSONObject fields) throws IOException {
        return request("PATCH", "/api/me", fields);
    }

    public JSONObject friends(String query) throws IOException {
        return request("GET", query.isEmpty() ? "/api/friends"
                : "/api/friends/search?q=" + URLEncoder.encode(query, "UTF-8"), null);
    }

    public void friendship(int id, String action) throws IOException {
        try {
            request(action.equals("delete") ? "DELETE" : "POST", "/api/friends",
                    new JSONObject().put("id", id).put("action", action));
        } catch (JSONException e) { throw new IOException(e); }
    }

    public JSONObject compareAchievements(int id) throws IOException {
        return request("GET", "/api/friends/" + id + "/achievements", null);
    }

    public void requestTranscript(String path) throws IOException {
        request("POST", "/api/transcript/request", field("path", path));
    }

    public void renameFolder(int id, String name) throws IOException {
        request("PATCH", "/api/folders/" + id, field("name", name));
    }

    public void logout() {
        AccountSession.Snapshot previous = account.snapshot();
        forgetSession();
        try {
            request("POST", "/api/auth/logout", null, previous);
        } catch (IOException ignored) {
            // Не достучались — всё равно забываем вход на своей стороне
        }
    }

    /** Содержимое папки медиатеки: вложенные папки, книги и дорожки. */
    public JSONObject browse(String path) throws IOException {
        String query = "";
        if (path != null && !path.isEmpty()) {
            query = "?path=" + URLEncoder.encode(path, "UTF-8");
        }
        return request("GET", "/api/browse" + query, null);
    }

    /**
     * Поиск по всей медиатеке — тот же адрес, что у строки поиска на сайте,
     * поэтому и находит он ровно то же самое.
     */
    public JSONObject search(String query) throws IOException {
        return request("GET", "/api/search?q="
                + URLEncoder.encode(query, "UTF-8"), null);
    }

    /** Прогресс по книгам: нужен для сортировок «недавние» и «меньше осталось». */
    public JSONObject progress() throws IOException {
        return request("GET", "/api/progress", null);
    }

    /** Отправляет запись из локальной истории: где человек остановился. */
    public JSONObject saveProgress(JSONObject row) throws IOException {
        return request("PUT", "/api/progress", row);
    }

    /**
     * Сообщает, что глава дослушана. Сервер сам решает, что засчитать:
     * присылать список достижений с телефона было бы наивно.
     */
    public JSONObject claimAchievements(String path, int track) throws IOException {
        try {
            return request("POST", "/api/achievements/claim", new JSONObject()
                    .put("path", path).put("track", track));
        } catch (JSONException e) {
            throw new ApiException("Не удалось собрать запрос", 0);
        }
    }

    /** Достижения читателя: полученные и ещё открытые. */
    public JSONObject achievements() throws IOException {
        return request("GET", "/api/achievements", null);
    }

    /** Статистика прослушивания. Пустой user — своя. */
    public JSONObject stats(String user) throws IOException {
        String query = user == null || user.isEmpty()
                ? "" : "?user=" + URLEncoder.encode(user, "UTF-8");
        return request("GET", "/api/stats" + query, null);
    }

    /** Личные подборки — те же «Мои папки», что и на сайте. */
    public JSONObject folders() throws IOException {
        return request("GET", "/api/folders", null);
    }

    /** В каких подборках уже лежит книга — для галочек в меню «в папку». */
    public JSONObject foldersForBook(String path) throws IOException {
        return request("GET", "/api/folders/for-book?path="
                + URLEncoder.encode(path, "UTF-8"), null);
    }

    public JSONObject createFolder(String name) throws IOException {
        return request("POST", "/api/folders", field("name", name));
    }

    public void deleteFolder(int id) throws IOException {
        request("DELETE", "/api/folders/" + id, null);
    }

    /** Кладёт книгу в подборку или убирает её оттуда. */
    public void setInFolder(int id, String path, boolean inside) throws IOException {
        request(inside ? "POST" : "DELETE", "/api/folders/" + id + "/items",
                field("path", path));
    }

    /** Тело запроса из одного поля: JSONException тут случиться не может. */
    private static JSONObject field(String name, String value) throws IOException {
        try {
            return new JSONObject().put(name, value);
        } catch (JSONException e) {
            throw new ApiException("Не удалось собрать запрос", 0);
        }
    }

    /**
     * Открывает поток аудиодорожки. Адрес приходит от сервера уже готовым,
     * но может быть относительным — тогда дописываем адрес сервера.
     */
    public HttpURLConnection openTrack(String url) throws IOException {
        AccountSession.Snapshot session = account.snapshot();
        String full = url.startsWith("http") ? url : session.server + url;
        HttpURLConnection connection = (HttpURLConnection) new URL(full).openConnection();
        connection.setConnectTimeout(TIMEOUT);
        // Читать книгу дольше, чем говорить с API: файл может быть большим
        connection.setReadTimeout(120000);
        if (!session.cookie.isEmpty() && full.startsWith(session.server + "/")) {
            connection.setRequestProperty("Cookie", session.cookie);
        }
        return connection;
    }

    /** Полный адрес дорожки — для проигрывания по сети, без скачивания. */
    public String trackUrl(String url) {
        return url.startsWith("http") ? url : server() + url;
    }

    public String cookieHeader() {
        return account.snapshot().cookie;
    }
}
