package com.cyberaudio.hub;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Обложки книг: качает, уменьшает и запоминает.
 *
 * Своей библиотеки для картинок здесь нет намеренно — Glide или Coil ради
 * десятка обложек утроили бы размер приложения. Нужно ровно три вещи:
 * не грузить одно и то же дважды, не держать в памяти полноразмерные
 * снимки и не блокировать прокрутку.
 *
 * Кеша два. В памяти — чтобы прокрутка не дёргалась. На диске — чтобы
 * обложки были видны и без сети, когда человек слушает скачанное.
 */
public final class Covers {

    /** Во сколько раз обложка в списке меньше исходной. */
    private static final int TARGET = 320;

    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Handler UI = new Handler(Looper.getMainLooper());

    /**
     * Восьмая часть доступной памяти под картинки: больше держать незачем,
     * меньше — и обложки начнут вылетать при прокрутке туда-обратно.
     */
    private static final LruCache<String, Bitmap> MEMORY =
            new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 8192)) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getByteCount() / 1024;   // в килобайтах
                }
            };

    private Covers() {
    }

    private static File cacheFile(File dir, String url) {
        return new File(dir, "cover_" + Integer.toHexString(url.hashCode()) + ".jpg");
    }

    /**
     * Ставит обложку в картинку. Пока грузится — заглушка, чтобы список
     * не прыгал; когда придёт, подменяем, если ячейку не переиспользовали.
     */
    public static void into(ImageView view, Api api, String url, File cacheDir) {
        if (url == null || url.isEmpty()) return;
        final String full = api.trackUrl(url);
        // Метка ячейки: пока обложка летит по сети, список могли прокрутить,
        // и этот же ImageView уже показывает другую книгу
        view.setTag(full);

        Bitmap cached = MEMORY.get(full);
        if (cached != null) {
            view.setImageBitmap(cached);
            return;
        }

        POOL.execute(() -> {
            Bitmap bitmap = fromDisk(cacheDir, full);
            if (bitmap == null) bitmap = fromNetwork(api, cacheDir, full);
            if (bitmap == null) return;
            MEMORY.put(full, bitmap);
            final Bitmap ready = bitmap;
            UI.post(() -> {
                if (full.equals(view.getTag())) view.setImageBitmap(ready);
            });
        });
    }

    private static Bitmap fromDisk(File cacheDir, String url) {
        File file = cacheFile(cacheDir, url);
        if (!file.exists()) return null;
        return BitmapFactory.decodeFile(file.getAbsolutePath());
    }

    private static Bitmap fromNetwork(Api api, File cacheDir, String url) {
        HttpURLConnection connection = null;
        try {
            connection = api.openTrack(url);
            byte[] raw;
            try (InputStream in = connection.getInputStream()) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[16384];
                int read;
                while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
                raw = out.toByteArray();
            }

            // Сначала узнаём размер, не разворачивая картинку в память:
            // обложка книги бывает и в три тысячи пикселей, а нужна
            // в триста — иначе десяток таких выест всю память телефона.
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(raw, 0, raw.length, probe);

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = Math.max(1,
                    Math.min(probe.outWidth, probe.outHeight) / TARGET);
            Bitmap bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.length, options);
            if (bitmap == null) return null;

            try (FileOutputStream file = new FileOutputStream(cacheFile(cacheDir, url))) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, file);
            } catch (Exception ignored) {
                // Не записался кеш — обложка всё равно покажется
            }
            return bitmap;
        } catch (Exception e) {
            return null;      // нет обложки — останется заглушка
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
