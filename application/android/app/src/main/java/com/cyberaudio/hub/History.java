package com.cyberaudio.hub;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * История прослушивания: сначала на телефон, потом — на сервер.
 *
 * Скачанную книгу слушают в метро и в самолёте, где сервера нет вовсе.
 * Поэтому позиция и дослушанные главы сначала ложатся в память телефона,
 * а на сервер уезжают, когда связь появится. Иначе прогресс за поездку
 * пропадал бы целиком, а достижения не засчитывались.
 *
 * Копим по книге и по главе, а не по каждому тику: серверу важно, где
 * человек остановился, а не весь путь до этого места.
 */
public final class History {

    private static final String PREFS = "cyberaudio";
    private static final String KEY = "history";
    /** Столько ждём между попытками отправки, чтобы не долбить сеть. */
    private static final long QUIET_MILLIS = 30_000;

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    private static long lastTry;

    private final SharedPreferences prefs;

    public History(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private JSONObject read() {
        try {
            return new JSONObject(prefs.getString(KEY, "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void write(JSONObject data) {
        prefs.edit().putString(KEY, data.toString()).apply();
    }

    /**
     * Где остановились в книге. Перезаписывает прежнюю запись по этой книге:
     * серверу нужна последняя позиция, а не все, что были до неё.
     */
    public void note(String path, String title, String cover,
                     int track, int millis, boolean finished) {
        if (path == null || path.isEmpty()) return;
        try {
            JSONObject data = read();
            JSONObject books = data.optJSONObject("progress");
            if (books == null) books = new JSONObject();
            books.put(path, new JSONObject()
                    .put("path", path)
                    .put("title", title)
                    .put("cover", cover)
                    .put("track_index", track)
                    .put("position", millis / 1000.0)
                    .put("finished", finished));
            data.put("progress", books);
            write(data);
        } catch (Exception ignored) {
            // История — не то, ради чего стоит ронять проигрывание
        }
    }

    /**
     * Глава дослушана. Из этого сервер собирает статистику и достижения,
     * поэтому запись должна дойти даже через сутки без сети.
     */
    public void complete(String path, int track) {
        if (path == null || path.isEmpty()) return;
        try {
            JSONObject data = read();
            JSONArray done = data.optJSONArray("done");
            if (done == null) done = new JSONArray();
            String mark = path + "\n" + track;
            for (int i = 0; i < done.length(); i++) {
                if (mark.equals(done.optString(i))) return;   // уже записана
            }
            done.put(mark);
            data.put("done", done);
            write(data);
        } catch (Exception ignored) {
        }
    }

    public boolean isEmpty() {
        JSONObject data = read();
        JSONObject books = data.optJSONObject("progress");
        JSONArray done = data.optJSONArray("done");
        return (books == null || books.length() == 0)
                && (done == null || done.length() == 0);
    }

    /**
     * Пытается отправить накопленное. Молча: связи может не быть, и это
     * обычное дело — тогда записи просто дождутся следующего раза.
     *
     * Каждую запись удаляем только после того, как сервер её принял.
     */
    public void flush(Api api, boolean force) {
        if (!api.hasServer() || !api.isSignedIn() || isEmpty()) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastTry < QUIET_MILLIS) return;
        lastTry = now;

        POOL.execute(() -> {
            JSONObject data = read();

            JSONObject books = data.optJSONObject("progress");
            if (books != null) {
                java.util.Iterator<String> paths = books.keys();
                java.util.List<String> sent = new java.util.ArrayList<>();
                while (paths.hasNext()) {
                    String path = paths.next();
                    JSONObject row = books.optJSONObject(path);
                    if (row == null) {
                        sent.add(path);
                        continue;
                    }
                    try {
                        api.saveProgress(row);
                        sent.add(path);
                    } catch (Exception e) {
                        // Сети нет или книги уже нет на сервере: если книги
                        // нет — запись не уедет никогда, поэтому выбрасываем
                        if (gone(e)) sent.add(path);
                        else break;
                    }
                }
                for (String path : sent) books.remove(path);
            }

            JSONArray done = data.optJSONArray("done");
            if (done != null) {
                JSONArray left = new JSONArray();
                boolean stop = false;
                for (int i = 0; i < done.length(); i++) {
                    String mark = done.optString(i);
                    int cut = mark.lastIndexOf('\n');
                    if (cut < 0) continue;
                    if (stop) {
                        left.put(mark);
                        continue;
                    }
                    try {
                        api.claimAchievements(mark.substring(0, cut),
                                Integer.parseInt(mark.substring(cut + 1)));
                    } catch (Exception e) {
                        if (!gone(e)) {
                            left.put(mark);
                            stop = true;   // сети нет — остальное тоже не уедет
                        }
                    }
                }
                try {
                    data.put("done", left);
                } catch (Exception ignored) {
                }
            }

            write(data);
        });
    }

    /** Книги на сервере больше нет — держать её запись бессмысленно. */
    private static boolean gone(Exception e) {
        return e instanceof Api.ApiException
                && ((Api.ApiException) e).status == 404;
    }
}
