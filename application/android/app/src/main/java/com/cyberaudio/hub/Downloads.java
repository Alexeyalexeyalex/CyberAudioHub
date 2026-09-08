package com.cyberaudio.hub;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;

import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Скачивание книг, переживающее уход с экрана.
 *
 * Раньше загрузка жила в самом плеере: стоило выйти в медиатеку и вернуться,
 * как экран собирался заново, о начатой работе не знал — кнопка снова
 * предлагала скачать, а прогресс пропадал из виду. Книга на десяток глав
 * качается минутами, всё это время смотреть в один экран никто не будет.
 *
 * Поэтому состояние вынесено сюда, в статику: оно живёт столько же, сколько
 * само приложение, и любой экран может спросить, что сейчас качается.
 * Прогресс дублируется в уведомление — чтобы он был виден и из другого
 * приложения, а не только внутри нашего.
 */
public final class Downloads {

    private static final String CHANNEL = "cah_downloads";
    private static final int NOTE_ID = 4201;

    /** Одна книга за раз: десять параллельных качаний только мешают друг другу. */
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();

    /** Что сейчас происходит с книгой. */
    public static class Progress {
        public final int done;
        public final int total;
        public final String error;

        Progress(int done, int total, String error) {
            this.done = done;
            this.total = total;
            this.error = error;
        }

        public boolean running() {
            return error == null && done < total;
        }
    }

    /** path книги -> её состояние. Записи держим и после конца: экран мог не успеть узнать. */
    private static final Map<String, Progress> STATE = new ConcurrentHashMap<>();

    public interface Watcher {
        void onDownloadChanged(String path);
    }

    private static final List<Watcher> WATCHERS = new ArrayList<>();
    private static final android.os.Handler UI =
            new android.os.Handler(android.os.Looper.getMainLooper());

    private Downloads() {
    }

    public static void watch(Watcher watcher) {
        synchronized (WATCHERS) {
            if (!WATCHERS.contains(watcher)) WATCHERS.add(watcher);
        }
    }

    public static void unwatch(Watcher watcher) {
        synchronized (WATCHERS) {
            WATCHERS.remove(watcher);
        }
    }

    private static void tell(String path) {
        final List<Watcher> copy;
        synchronized (WATCHERS) {
            copy = new ArrayList<>(WATCHERS);
        }
        UI.post(() -> {
            for (Watcher watcher : copy) watcher.onDownloadChanged(path);
        });
    }

    /** Состояние книги или null, если её никто не качал. */
    public static Progress progress(String path) {
        return STATE.get(path);
    }

    public static boolean busy(String path) {
        Progress progress = STATE.get(path);
        return progress != null && progress.running();
    }

    /**
     * Ставит книгу в очередь. Повторный вызов, пока она качается, ничего
     * не делает: две одинаковые загрузки писали бы в одни и те же файлы.
     */
    public static void start(Context context, Api api, Store store, String path,
                             String title, String cover,
                             String[] urls, String[] names) {
        if (busy(path)) return;
        final Context app = context.getApplicationContext();
        STATE.put(path, new Progress(0, urls.length, null));
        tell(path);
        note(app, title, 0, urls.length, null);

        POOL.execute(() -> {
            String error = null;
            try {
                File dir = store.bookDir(path);
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new java.io.IOException("Не удалось создать папку");
                }
                byte[] key = Store.containerKey();
                for (int i = 0; i < urls.length; i++) {
                    HttpURLConnection connection = api.openTrack(urls[i]);
                    try (InputStream in = connection.getInputStream()) {
                        JSONObject meta = new JSONObject()
                                .put("book", title)
                                .put("track", names[i])
                                .put("index", i);
                        Container.pack(in, store.trackFile(path, i), key, meta);
                    } finally {
                        connection.disconnect();
                    }
                    // Текст весит килобайты против десятков мегабайт звука,
                    // поэтому качаем его сразу: без него офлайн-чтение
                    // с подсветкой было бы невозможно
                    Transcripts.save(api, store, path, i);

                    STATE.put(path, new Progress(i + 1, urls.length, null));
                    tell(path);
                    note(app, title, i + 1, urls.length, null);
                }
                List<String> list = new ArrayList<>();
                for (String name : names) list.add(name);
                store.remember(path, title, list, cover);
            } catch (Exception e) {
                error = e.getMessage() == null ? "не вышло" : e.getMessage();
            }
            STATE.put(path, new Progress(urls.length, urls.length, error));
            tell(path);
            note(app, title, urls.length, urls.length, error);
        });
    }

    /** Книгу удалили с телефона — забываем и её состояние. */
    public static void forget(String path) {
        STATE.remove(path);
        tell(path);
    }

    // --- Уведомление ---

    private static void note(Context context, String title,
                             int done, int total, String error) {
        NotificationManager manager =
                context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, "Скачивание книг", NotificationManager.IMPORTANCE_LOW);
        channel.setShowBadge(false);
        channel.setSound(null, null);
        manager.createNotificationChannel(channel);

        boolean finished = error != null || done >= total;
        Notification.Builder builder = new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_note)
                .setContentTitle(title)
                .setOnlyAlertOnce(true);

        if (error != null) {
            builder.setContentText("Не вышло: " + error);
        } else if (finished) {
            builder.setContentText("Книга на телефоне");
        } else {
            builder.setContentText("Скачиваю " + (done + 1) + " из " + total)
                    .setProgress(total, done, false)
                    .setOngoing(true);
        }
        manager.notify(NOTE_ID, builder.build());

        // Готовую загрузку убираем с глаз: висящее уведомление о том, что
        // всё уже скачано, только копится в шторке
        if (finished && error == null) {
            UI.postDelayed(() -> manager.cancel(NOTE_ID), 4000);
        }
    }
}
