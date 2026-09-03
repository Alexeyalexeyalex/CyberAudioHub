package com.cyberaudio.hub;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Скачанные книги на телефоне.
 *
 * Список держим в SharedPreferences одной строкой JSON: книг у человека
 * десятки, а не тысячи, и заводить ради этого базу — лишняя сложность.
 * Сами главы лежат файлами .cah в личной папке приложения.
 */
public class Store {

    private static final String PREFS = "cyberaudio";
    private static final String KEY = "downloads";

    private final SharedPreferences prefs;
    private final File root;

    public Store(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        // getFilesDir — личная папка приложения: другие программы туда
        // не заглядывают, и при удалении приложения книги уйдут с ним
        root = new File(context.getFilesDir(), "books");
        if (!root.exists() && !root.mkdirs()) {
            // Место могло кончиться. Скачивание потом честно сообщит об ошибке
        }
    }

    /**
     * Ключ для контейнера. Лежит в самом приложении, и это осознанно:
     * настоящая защита потребовала бы выдачи ключей сервером и DRM.
     * Здесь задача скромнее — чтобы файл не открывался обычным плеером.
     */
    public static byte[] containerKey() {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return sha.digest("CyberAudioHub/v1".getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return "CyberAudioHub/v1".getBytes(StandardCharsets.UTF_8);
        }
    }

    public File root() {
        return root;
    }

    /** Папка книги на диске: путь превращаем в безопасное имя. */
    public File bookDir(String path) {
        String safe = path.replaceAll("[^A-Za-z0-9А-Яа-яЁё _-]", "_");
        if (safe.length() > 80) safe = safe.substring(0, 80);
        return new File(root, safe);
    }

    public File trackFile(String path, int index) {
        // Locale.ROOT обязателен: на телефоне с арабскими или деванагари
        // цифрами %03d выдал бы не ASCII, и файл потом не нашёлся бы
        return new File(bookDir(path),
                String.format(java.util.Locale.ROOT, "%03d.cah", index + 1));
    }

    // --- Список книг ---

    public JSONArray all() {
        try {
            return new JSONArray(prefs.getString(KEY, "[]"));
        } catch (JSONException e) {
            return new JSONArray();
        }
    }

    public JSONObject find(String path) {
        JSONArray list = all();
        for (int i = 0; i < list.length(); i++) {
            JSONObject book = list.optJSONObject(i);
            if (book != null && path.equals(book.optString("path"))) return book;
        }
        return null;
    }

    public boolean isDownloaded(String path) {
        return find(path) != null;
    }

    /** Запоминает скачанную книгу или обновляет запись о ней. */
    public void remember(String path, String title, List<String> trackNames,
                         String cover) {
        try {
            JSONArray list = all();
            JSONArray cleaned = new JSONArray();
            for (int i = 0; i < list.length(); i++) {
                JSONObject book = list.optJSONObject(i);
                if (book != null && !path.equals(book.optString("path"))) {
                    cleaned.put(book);
                }
            }
            JSONArray names = new JSONArray();
            for (String name : trackNames) names.put(name);
            cleaned.put(new JSONObject()
                    .put("path", path)
                    .put("title", title)
                    .put("tracks", names)
                    .put("cover", cover == null ? "" : cover)
                    .put("saved_at", System.currentTimeMillis()));
            prefs.edit().putString(KEY, cleaned.toString()).apply();
        } catch (JSONException ignored) {
            // Не смогли записать список — книга останется на диске, но
            // не покажется в офлайне. Не повод ронять скачивание.
        }
    }

    public void forget(String path) {
        try {
            JSONArray list = all();
            JSONArray cleaned = new JSONArray();
            for (int i = 0; i < list.length(); i++) {
                JSONObject book = list.optJSONObject(i);
                if (book != null && !path.equals(book.optString("path"))) {
                    cleaned.put(book);
                }
            }
            prefs.edit().putString(KEY, cleaned.toString()).apply();
        } catch (Exception ignored) {
        }
        deleteTree(bookDir(path));
    }

    private void deleteTree(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (!file.delete()) file.deleteOnExit();
            }
        }
        if (!dir.delete()) dir.deleteOnExit();
    }

    /** Названия глав скачанной книги. */
    public List<String> trackNames(String path) {
        List<String> out = new ArrayList<>();
        JSONObject book = find(path);
        if (book == null) return out;
        JSONArray names = book.optJSONArray("tracks");
        if (names == null) return out;
        for (int i = 0; i < names.length(); i++) out.add(names.optString(i));
        return out;
    }

    // --- Позиция прослушивания ---
    //
    // Хранится на телефоне, а не на сервере: скачанную книгу слушают как раз
    // тогда, когда сети нет, и отправлять прогресс всё равно некуда.

    public void savePosition(String path, int index, int millis) {
        prefs.edit()
                .putInt("pos_index_" + path, index)
                .putInt("pos_millis_" + path, millis)
                .apply();
    }

    public int savedIndex(String path) {
        return prefs.getInt("pos_index_" + path, 0);
    }

    public int savedMillis(String path) {
        return prefs.getInt("pos_millis_" + path, 0);
    }
}
