package com.cyberaudio.hub;

import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Достижения читателя — то же, что на странице /achievements сайта.
 *
 * Список приходит целиком: и полученные, и ещё закрытые. Закрытые не
 * прячем — они показывают, за что их дают, и ради этого страница и нужна.
 * Полученные идут первыми: за ними человек сюда и заходит.
 */
public class AchievementsActivity extends AppCompatActivity {

    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private Api api;
    private TextView summary;
    private LinearLayout list;

    /** Подписи и цвета редкости — те же, что в achievements.js и style.css. */
    private static final String[][] RARITY = {
            {"common", "обычное", "#9EA3B5"},
            {"rare", "редкое", "#00F0FF"},
            {"mythic", "мифическое", "#FF2BD1"},
            {"legendary", "легендарное", "#FFE14D"},
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        api = new Api(this);

        LinearLayout box = Ui.column(this);
        Ui.add(box, Ui.title(this, "Достижения"), 6);

        summary = Ui.label(this, "Загружаю...", Ui.DIM, 14);
        Ui.add(box, summary, 12);

        // Уйти с экрана должно быть видно, а не только системной кнопкой:
        // на телефонах с жестами её попросту нет
        LinearLayout backRow = Ui.row(this);
        android.widget.Button back = Ui.button(this, "Назад", Ui.SECONDARY);
        back.setOnClickListener(v -> finish());
        backRow.addView(back);
        Ui.add(box, backRow, 14);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        Ui.add(box, list, 0);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.DARK);
        scroll.addView(box);
        setContentView(scroll);

        load();
    }

    private void load() {
        pool.execute(() -> {
            JSONObject data = null;
            String error = null;
            try {
                data = api.achievements();
            } catch (Exception e) {
                error = Api.describe(e);
            }
            JSONObject finalData = data;
            String finalError = error;
            runOnUiThread(() -> {
                if (finalError != null) {
                    summary.setText(finalError);
                    return;
                }
                show(finalData);
            });
        });
    }

    private void show(JSONObject data) {
        list.removeAllViews();
        JSONArray items = data.optJSONArray("achievements");
        int earned = data.optInt("earned");
        int total = items == null ? 0 : items.length();
        summary.setText("Получено " + earned + " из " + total);

        if (total == 0) {
            list.addView(Ui.label(this, "Достижений пока не придумали.", Ui.DIM, 15));
            return;
        }

        // Сначала полученные, внутри — от легендарных к обычным:
        // тот же порядок, что стоит по умолчанию на сайте
        List<JSONObject> order = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null) order.add(item);
        }
        java.util.Collections.sort(order, (a, b) -> {
            boolean gotA = !a.isNull("earned_at");
            boolean gotB = !b.isNull("earned_at");
            if (gotA != gotB) return gotA ? -1 : 1;
            int weight = rank(b.optString("rarity")) - rank(a.optString("rarity"));
            if (weight != 0) return weight;
            return a.optString("title").compareToIgnoreCase(b.optString("title"));
        });

        for (JSONObject item : order) list.addView(card(item));
    }

    private static int rank(String rarity) {
        for (int i = 0; i < RARITY.length; i++) {
            if (RARITY[i][0].equals(rarity)) return i;
        }
        return 0;
    }

    private static String[] look(String rarity) {
        for (String[] row : RARITY) {
            if (row[0].equals(rarity)) return row;
        }
        return RARITY[0];
    }

    private View card(JSONObject item) {
        boolean earned = !item.isNull("earned_at");
        String[] look = look(item.optString("rarity"));
        int accent = Color.parseColor(look[2]);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setBackground(Ui.card(this, 0));
        int pad = Ui.dp(this, 14);
        row.setPadding(pad, pad, pad, pad);
        // Незаработанное приглушаем целиком: так полученные видно сразу,
        // а закрытые остаются читаемыми — за них ещё можно взяться
        row.setAlpha(earned ? 1f : 0.55f);

        TextView title = Ui.label(this, item.optString("title"), accent, 16);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        row.addView(title);

        TextView rarity = Ui.label(this,
                look[1] + (earned ? " · получено" : " · ещё не получено"),
                earned ? Ui.PRIMARY : Ui.DIM, 12);
        row.addView(rarity);

        String description = item.optString("description");
        if (!description.isEmpty()) {
            TextView text = Ui.label(this, description, Ui.TEXT, 14);
            text.setPadding(0, Ui.dp(this, 6), 0, 0);
            row.addView(text);
        }

        TextView target = Ui.label(this, "За: " + targetLabel(item), Ui.DIM, 12);
        target.setPadding(0, Ui.dp(this, 6), 0, 0);
        row.addView(target);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = Ui.dp(this, 8);
        row.setLayoutParams(params);
        return row;
    }

    /** «За что» — той же фразой, что на сайте. */
    private static String targetLabel(JSONObject item) {
        String path = item.optString("target_path");
        if (path.isEmpty()) return "за что угодно";
        int cut = path.lastIndexOf('/');
        String book = (cut < 0 ? path : path.substring(cut + 1)).replace('_', ' ');

        JSONArray tracks = item.optJSONArray("target_tracks");
        if (tracks == null || tracks.length() == 0) return "книга «" + book + "»";
        StringBuilder chapters = new StringBuilder();
        for (int i = 0; i < tracks.length(); i++) {
            if (i > 0) chapters.append(", ");
            chapters.append(tracks.optInt(i) + 1);   // главы человек считает с единицы
        }
        return "книга «" + book + "», "
                + (tracks.length() > 1 ? "главы " : "глава ") + chapters;
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        super.onDestroy();
    }
}
