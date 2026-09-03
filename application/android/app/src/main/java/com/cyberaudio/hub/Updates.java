package com.cyberaudio.hub;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Проверка обновлений.
 *
 * Приложение раздаётся не через магазин, а тем же сервером, что и книги, —
 * значит и следить за новыми версиями должно само. Спрашиваем у сервера
 * версию собранного APK и, если она новее установленной, предлагаем скачать.
 *
 * Скачанные книги обновление переживают: они лежат в личной папке
 * приложения, а установка поверх (тот же пакет и та же подпись) её не
 * трогает. Настройки и позиции прослушивания сохраняются по той же причине.
 * Единственное, что могло бы их сломать, — смена формата контейнера или
 * ключа, поэтому и то, и другое закреплено тестами.
 */
public final class Updates {

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor();
    private static final Handler UI = new Handler(Looper.getMainLooper());

    /** Чтобы не спрашивать про одну и ту же версию на каждом запуске. */
    private static final String SKIPPED = "update_skipped";

    private Updates() {
    }

    @SuppressWarnings("deprecation")
    private static int installedCode(Activity activity) {
        try {
            android.content.pm.PackageInfo info = activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0);
            // getLongVersionCode появился только в Android 9, а приложение
            // ставится с 8.0: на «восьмёрке» вызов уронил бы проверку
            // обновлений при первом же запуске
            return android.os.Build.VERSION.SDK_INT >= 28
                    ? (int) info.getLongVersionCode() : info.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    /**
     * Тихо спрашивает сервер про версию и, если она новее, показывает окно.
     * Любая неудача проглатывается: не достучались — не повод мешать работе.
     */
    public static void check(Activity activity, Api api) {
        POOL.execute(() -> {
            final JSONObject info;
            try {
                info = api.appInfo();
            } catch (Exception e) {
                return;
            }
            if (info == null || !info.optBoolean("ready")) return;

            int theirs = info.optInt("version_code", 0);
            int mine = installedCode(activity);
            if (theirs <= mine) return;

            int skipped = activity
                    .getSharedPreferences("cyberaudio", Activity.MODE_PRIVATE)
                    .getInt(SKIPPED, 0);
            if (skipped == theirs) return;

            String name = info.optString("version_name", String.valueOf(theirs));
            long size = info.optLong("size", 0);
            UI.post(() -> offer(activity, api, theirs, name, size));
        });
    }

    private static void offer(Activity activity, Api api,
                              int code, String name, long size) {
        if (activity.isFinishing()) return;
        String weight = size > 0
                ? String.format(java.util.Locale.ROOT, " (%.1f МБ)", size / 1048576.0)
                : "";
        new AlertDialog.Builder(activity)
                .setTitle("Есть новая версия")
                .setMessage("Доступна версия " + name + weight
                        + ".\n\nСкачанные книги и прогресс сохранятся.")
                .setPositiveButton("Обновить", (d, w) -> download(activity, api))
                .setNegativeButton("Потом", null)
                .setNeutralButton("Пропустить эту", (d, w) ->
                        activity.getSharedPreferences("cyberaudio", Activity.MODE_PRIVATE)
                                .edit().putInt(SKIPPED, code).apply())
                .show();
    }

    /** Качает APK во внешний кеш и передаёт системному установщику. */
    private static void download(Activity activity, Api api) {
        POOL.execute(() -> {
            File target = new File(activity.getExternalCacheDir(), "update.apk");
            HttpURLConnection connection = null;
            try {
                connection = api.openTrack("/download/app");
                try (InputStream in = connection.getInputStream();
                     FileOutputStream out = new FileOutputStream(target)) {
                    byte[] buffer = new byte[65536];
                    int read;
                    while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
                }
            } catch (Exception e) {
                UI.post(() -> toast(activity, "Не удалось скачать обновление"));
                return;
            } finally {
                if (connection != null) connection.disconnect();
            }
            UI.post(() -> install(activity, target));
        });
    }

    private static void install(Activity activity, File apk) {
        try {
            // С Android 7 передавать file:// другому приложению запрещено,
            // поэтому отдаём ссылку через FileProvider
            Uri uri = FileProvider.getUriForFile(activity,
                    activity.getPackageName() + ".files", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception e) {
            toast(activity, "Не удалось открыть установщик");
        }
    }

    private static void toast(Activity activity, String text) {
        android.widget.Toast.makeText(activity, text,
                android.widget.Toast.LENGTH_LONG).show();
    }
}
