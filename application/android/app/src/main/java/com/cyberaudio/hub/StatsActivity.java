package com.cyberaudio.hub;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Статистика прослушивания — то же, что на странице /stats сайта,
 * включая переключатель на друга.
 *
 * Графики рисуем сами, без библиотеки: их всего два, оба — набор
 * столбиков. Чужая библиотека утяжелила бы APK ради десятка прямоугольников
 * и притащила бы своё оформление, которое пришлось бы перекрашивать.
 */
public class StatsActivity extends AppCompatActivity {

    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private Api api;
    private TextView who;
    private LinearLayout friendRow;
    private LinearLayout body;
    /** Чью статистику смотрим: пусто — свою. */
    private String user = "";

    private static final String[] WEEKDAYS = {"Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс"};

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        api = new Api(this);
        user = getIntent().getStringExtra("user");
        if (user == null) user = "";

        LinearLayout box = Ui.column(this);
        Ui.add(box, Ui.title(this, "Статистика"), 6);

        who = Ui.label(this, "Загружаю...", Ui.DIM, 14);
        Ui.add(box, who, 12);

        // Уйти с экрана должно быть видно, а не только системной кнопкой:
        // на телефонах с жестами её попросту нет
        LinearLayout backRow = Ui.row(this);
        android.widget.Button back = Ui.button(this, "Назад", Ui.SECONDARY);
        back.setOnClickListener(v -> finish());
        backRow.addView(back);
        Ui.add(box, backRow, 12);

        friendRow = Ui.row(this);
        android.widget.HorizontalScrollView scroller =
                new android.widget.HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.addView(friendRow);
        Ui.add(box, scroller, 14);

        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        Ui.add(box, body, 0);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.DARK);
        scroll.addView(box);
        Ui.screen(this, scroll, -1);

        load();
    }

    private void load() {
        who.setText("Загружаю...");
        final String wanted = user;
        pool.execute(() -> {
            JSONObject data = null;
            String error = null;
            try {
                data = api.stats(wanted);
            } catch (Exception e) {
                error = Api.describe(e);
            }
            JSONObject finalData = data;
            String finalError = error;
            runOnUiThread(() -> {
                if (finalError != null) {
                    who.setText(finalError);
                    return;
                }
                show(finalData);
            });
        });
    }

    private void show(JSONObject data) {
        body.removeAllViews();

        JSONObject person = data.optJSONObject("person");
        boolean self = data.optBoolean("self");
        String name = person == null ? "" : person.optString("nickname");
        who.setText(self ? "Моё чтение" : "Читает " + name);

        showFriends(data.optJSONArray("friends"), data.optString("self_id"));

        JSONObject stats = data.optJSONObject("stats");
        if (stats == null) {
            body.addView(Ui.label(this, "Статистики пока нет.", Ui.DIM, 15));
            return;
        }

        // --- Плитки с числами ---
        long seconds = stats.optLong("seconds");
        tile("Прослушано", hours(seconds));
        tile("Глав дослушано", String.valueOf(stats.optInt("chapters")));
        tile("Книг начато", String.valueOf(stats.optInt("books_started")));
        tile("Книг дочитано", String.valueOf(stats.optInt("books_finished")));
        tile("Достижений", stats.optInt("achievements")
                + " из " + stats.optInt("achievements_total"));
        tile("Дней подряд", String.valueOf(stats.optInt("streak")));

        // --- Графики ---
        JSONArray days = stats.optJSONArray("days");
        if (days != null && days.length() > 0) {
            heading("Главы по дням");
            int[] values = new int[days.length()];
            String[] labels = new String[days.length()];
            for (int i = 0; i < days.length(); i++) {
                JSONObject day = days.optJSONObject(i);
                values[i] = day == null ? 0 : day.optInt("chapters");
                // Подписи под ежедневным графиком не поместятся — только
                // края: этого хватает, чтобы понять, где начало отрезка
                labels[i] = "";
            }
            if (days.optJSONObject(0) != null) {
                labels[0] = shortDate(days.optJSONObject(0).optString("date"));
            }
            int last = days.length() - 1;
            if (days.optJSONObject(last) != null) {
                labels[last] = shortDate(days.optJSONObject(last).optString("date"));
            }
            body.addView(new Bars(this, values, labels, Ui.PRIMARY),
                    chartParams(150));
        }

        JSONArray weekday = stats.optJSONArray("weekday");
        if (weekday != null && weekday.length() == 7) {
            heading("По дням недели");
            int[] values = new int[7];
            for (int i = 0; i < 7; i++) values[i] = weekday.optInt(i);
            body.addView(new Bars(this, values, WEEKDAYS, Ui.SECONDARY), chartParams(150));
        }

        JSONArray top = stats.optJSONArray("top_books");
        if (top != null && top.length() > 0) {
            heading("Больше всего слушали");
            long best = 1;
            for (int i = 0; i < top.length(); i++) {
                JSONObject book = top.optJSONObject(i);
                if (book != null) best = Math.max(best, book.optLong("seconds"));
            }
            for (int i = 0; i < top.length(); i++) {
                JSONObject book = top.optJSONObject(i);
                if (book == null) continue;
                body.addView(bookRow(book.optString("title"),
                        book.optLong("seconds"), best));
            }
        }
    }

    /**
     * Чипы друзей. Чужую статистику сервер отдаёт только друзьям, так что
     * список здесь — ровно то, что можно посмотреть.
     */
    private void showFriends(JSONArray friends, String ignored) {
        friendRow.removeAllViews();
        friendRow.addView(chip("Я", user.isEmpty(), () -> {
            user = "";
            load();
        }));
        if (friends == null) return;
        for (int i = 0; i < friends.length(); i++) {
            JSONObject friend = friends.optJSONObject(i);
            if (friend == null) continue;
            final String id = String.valueOf(friend.optInt("id"));
            friendRow.addView(chip(friend.optString("nickname"),
                    id.equals(user), () -> {
                        user = id;
                        load();
                    }));
        }
    }

    private View chip(String text, boolean active, Runnable action) {
        android.widget.Button view = Ui.button(this, text,
                active ? Ui.PRIMARY : Ui.DIM, active);
        view.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.rightMargin = Ui.dp(this, 8);
        view.setLayoutParams(params);
        return view;
    }

    private void tile(String caption, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Ui.card(this, 0));
        int pad = Ui.dp(this, 14);
        row.setPadding(pad, pad, pad, pad);

        TextView label = Ui.label(this, caption, Ui.DIM, 14);
        row.addView(label, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView number = Ui.label(this, value, Ui.PRIMARY, 18);
        number.setTypeface(number.getTypeface(), android.graphics.Typeface.BOLD);
        row.addView(number);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = Ui.dp(this, 8);
        body.addView(row, params);
    }

    private void heading(String text) {
        TextView view = Ui.label(this, text, Ui.SECONDARY, 15);
        view.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 8));
        body.addView(view);
    }

    /** Книга из топа: название и полоса, длина которой — доля от лучшей. */
    private View bookRow(String title, long seconds, long best) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackground(Ui.card(this, 0));
        int pad = Ui.dp(this, 12);
        column.setPadding(pad, pad, pad, pad);

        LinearLayout head = Ui.row(this);
        TextView name = Ui.label(this, title, Ui.TEXT, 14);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(name, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(Ui.label(this, hours(seconds), Ui.DIM, 13));
        column.addView(head);

        View bar = new View(this);
        android.graphics.drawable.GradientDrawable fill =
                new android.graphics.drawable.GradientDrawable();
        fill.setColor(Ui.PRIMARY);
        fill.setCornerRadius(Ui.dp(this, 3));
        bar.setBackground(fill);
        LinearLayout track = new LinearLayout(this);
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.addView(bar, new LinearLayout.LayoutParams(
                0, Ui.dp(this, 6), Math.max(0.02f, (float) seconds / best)));
        track.addView(new View(this), new LinearLayout.LayoutParams(
                0, Ui.dp(this, 6), 1f - Math.max(0.02f, (float) seconds / best)));
        LinearLayout.LayoutParams trackParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        trackParams.topMargin = Ui.dp(this, 8);
        column.addView(track, trackParams);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = Ui.dp(this, 8);
        column.setLayoutParams(params);
        return column;
    }

    private LinearLayout.LayoutParams chartParams(int heightDp) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, heightDp));
        params.bottomMargin = Ui.dp(this, 8);
        return params;
    }

    private static String hours(long seconds) {
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        if (h == 0) return m + " мин";
        return h + " ч " + m + " мин";
    }

    /** «2026-09-03» -> «03.09»: в подпись под графиком год не влезает. */
    private static String shortDate(String iso) {
        if (iso == null || iso.length() < 10) return "";
        return iso.substring(8, 10) + "." + iso.substring(5, 7);
    }

    /** Столбчатый график. Всё, что нужно обеим диаграммам на этом экране. */
    private static class Bars extends View {

        private final int[] values;
        private final String[] labels;
        private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);

        Bars(android.content.Context context, int[] values, String[] labels,
             int accent) {
            super(context);
            this.values = values;
            this.labels = labels;
            bar.setColor(accent);
            text.setColor(Ui.DIM);
            text.setTextSize(Ui.dp(context, 10));
            line.setColor(Color.argb(60, 158, 163, 181));
            line.setStrokeWidth(Math.max(1, Ui.dp(context, 1) / 2f));
        }

        /** Насколько подпись шире своей ячейки — на столько отступаем от края. */
        private float overhang(String label, float slot) {
            if (label == null || label.isEmpty()) return 0;
            return Math.max(0, text.measureText(label) / 2 - slot / 2);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (values.length == 0) return;
            int max = 1;
            for (int value : values) max = Math.max(max, value);

            float bottom = getHeight() - Ui.dp(getContext(), 16);

            /*
             * Поля под крайние подписи.
             *
             * За месяц столбик выходит уже 33 точек, а дата «03.09» занимает
             * все семьдесят. Раньше такая подпись не влезала и её поджимали
             * к краю графика — из-за чего она вставала не под своим столбиком,
             * а на полстолбика в сторону. Теперь наоборот: сдвигаем сами
             * столбики внутрь ровно настолько, чтобы дата встала точно под
             * своей. Второй проход — потому что от отступа ячейка становится
             * уже, и подпись начинает выступать чуть сильнее.
             */
            String first = labels.length > 0 ? labels[0] : "";
            String last = labels.length > 0 ? labels[labels.length - 1] : "";
            float slot = getWidth() / (float) values.length;
            float padLeft = 0;
            float padRight = 0;
            for (int pass = 0; pass < 2; pass++) {
                padLeft = overhang(first, slot);
                padRight = overhang(last, slot);
                slot = (getWidth() - padLeft - padRight) / values.length;
            }

            float thickness = Math.max(2f, slot * 0.6f);
            float radius = Math.min(thickness / 2f, Ui.dp(getContext(), 3));

            canvas.drawLine(0, bottom, getWidth(), bottom, line);
            for (int i = 0; i < values.length; i++) {
                float centre = padLeft + slot * (i + 0.5f);
                float height = bottom * values[i] / (float) max;
                // Нулю тоже оставляем след: пустой день должен читаться
                // как день без чтения, а не как обрыв графика
                float top = bottom - Math.max(height, values[i] > 0 ? 2 : 0);
                canvas.drawRoundRect(centre - thickness / 2, top,
                        centre + thickness / 2, bottom, radius, radius, bar);
                if (i < labels.length && !labels[i].isEmpty()) {
                    // Поля уже посчитаны так, что подпись помещается целиком,
                    // поэтому просто ставим её по центру столбика
                    canvas.drawText(labels[i],
                            centre - text.measureText(labels[i]) / 2,
                            getHeight() - Ui.dp(getContext(), 3), text);
                }
            }
        }
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        super.onDestroy();
    }
}
