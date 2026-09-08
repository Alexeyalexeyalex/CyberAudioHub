package com.cyberaudio.hub;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Полученные достижения — уведомлением телефона.
 *
 * На сайте они выпадают карточкой поверх страницы, но приложение чаще
 * слушают с погашенным экраном: карточка выпала бы в пустоту. Уведомление
 * доходит и из кармана, и остаётся в шторке, если человек был занят.
 *
 * Канал отдельный от воспроизведения и со звуком: это событие, а не
 * служебная строка, которая просто висит, пока идёт глава.
 */
public final class Awards {

    private static final String CHANNEL = "cah_awards";
    /** Чтобы два достижения подряд не затирали друг друга в шторке. */
    private static int nextId = 7100;

    private Awards() {
    }

    public static void show(Context context, JSONArray granted) {
        if (granted == null || granted.length() == 0) return;
        NotificationManager manager =
                context.getSystemService(NotificationManager.class);
        if (manager == null) return;

        NotificationChannel channel = new NotificationChannel(CHANNEL,
                "Достижения", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Когда открывается новое достижение");
        manager.createNotificationChannel(channel);

        Intent open = new Intent(context, AchievementsActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent tap = PendingIntent.getActivity(context, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        for (int i = 0; i < granted.length(); i++) {
            JSONObject item = granted.optJSONObject(i);
            if (item == null) continue;
            String title = item.optString("title", "Новое достижение");
            String about = item.optString("description", "");
            String rarity = rarity(item.optString("rarity"));

            Notification note = new Notification.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_note)
                    .setContentTitle("Достижение: " + title)
                    .setContentText(about.isEmpty() ? rarity : about)
                    // Длинный текст описания в свёрнутом виде обрезается,
                    // а достижение как раз им и интересно
                    .setStyle(new Notification.BigTextStyle()
                            .bigText((about.isEmpty() ? "" : about + "\n") + rarity))
                    .setContentIntent(tap)
                    .setAutoCancel(true)
                    .build();
            manager.notify(nextId++, note);
        }
    }

    /** Подписи те же, что на странице достижений. */
    private static String rarity(String key) {
        switch (key) {
            case "rare":
                return "Редкое достижение";
            case "mythic":
                return "Мифическое достижение";
            case "legendary":
                return "Легендарное достижение";
            default:
                return "Обычное достижение";
        }
    }
}
