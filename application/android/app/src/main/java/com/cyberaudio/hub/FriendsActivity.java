package com.cyberaudio.hub;

import android.content.Intent;
import android.os.Bundle;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import org.json.*;
import java.util.concurrent.*;

/** Поиск, заявки и сравнение: сервер остаётся единственным источником данных. */
public class FriendsActivity extends AppCompatActivity {
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private Api api;
    private LinearLayout list;
    private EditText search;
    private TextView note;
    private int revision;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        api = new Api(this);
        LinearLayout box = Ui.column(this);
        Ui.add(box, Ui.title(this, "На одной волне"), 6);
        Ui.add(box, Ui.label(this, "Друзья и общие открытия", Ui.DIM, 14), 18);
        Button back = Ui.button(this, "Назад", Ui.DIM);
        back.setOnClickListener(v -> finish()); Ui.add(box, back, 16);
        search = Ui.field(this, "Никнейм или логин друга");
        search.setSingleLine(true); Ui.add(box, search, 8);
        Button find = Ui.button(this, "Найти", Ui.PRIMARY, true);
        find.setOnClickListener(v -> load()); Ui.add(box, find, 12);
        Button all = Ui.button(this, "Мои друзья и заявки", Ui.DIM);
        all.setOnClickListener(v -> { search.setText(""); load(); }); Ui.add(box, all, 12);
        note = Ui.label(this, "", Ui.DIM, 14); Ui.add(box, note, 10);
        list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
        box.addView(list);
        ScrollView scroll = new ScrollView(this); scroll.addView(box);
        Ui.screen(this, scroll, -1); load();
    }

    private void load() {
        final String query = search.getText().toString().trim();
        if (!query.isEmpty() && query.length() < 2) { note.setText("Введите хотя бы два символа"); return; }
        final int wanted = ++revision;
        note.setText("Загружаю…");
        pool.execute(() -> {
            try {
                JSONObject data = api.friends(query);
                runOnUiThread(() -> {
                    if (isDestroyed() || wanted != revision) return;
                    list.removeAllViews(); note.setText("");
                    if (!query.isEmpty()) section("Результаты", data.optJSONArray("results"), "");
                    else {
                        section("Друзья", data.optJSONArray("friends"), "friends");
                        section("Входящие заявки", data.optJSONArray("incoming"), "incoming");
                        section("Отправленные заявки", data.optJSONArray("outgoing"), "outgoing");
                    }
                });
            } catch (Exception e) { runOnUiThread(() -> note.setText(Api.describe(e))); }
        });
    }

    private void section(String title, JSONArray users, String kind) {
        Ui.add(list, Ui.label(this, title, Ui.SECONDARY, 18), 10);
        if (users == null || users.length() == 0) {
            Ui.add(list, Ui.label(this, "Пока никого", Ui.DIM, 14), 20); return;
        }
        for (int i = 0; i < users.length(); i++) {
            JSONObject user = users.optJSONObject(i); if (user == null) continue;
            int id = user.optInt("id");
            String relation = kind.isEmpty() ? user.optString("state") : kind;
            LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(Ui.card(this, 0)); int pad = Ui.dp(this, 18);
            card.setPadding(pad, pad, pad, pad);
            Ui.add(card, Ui.label(this, user.optString("nickname"), Ui.TEXT, 21), 4);
            Ui.add(card, Ui.label(this, "@" + user.optString("login"), Ui.DIM, 13), 12);
            if (relation.equals("friends")) {
                Button stats = Ui.button(this, "Статистика", Ui.PRIMARY);
                stats.setOnClickListener(v -> startActivity(new Intent(this, StatsActivity.class).putExtra("user", String.valueOf(id))));
                Ui.add(card, stats, 6);
                Button compare = Ui.button(this, "Сравнить достижения", Ui.SECONDARY);
                compare.setOnClickListener(v -> startActivity(new Intent(this, AchievementsActivity.class).putExtra("friend", id)));
                Ui.add(card, compare, 6);
            } else if (!relation.equals("outgoing")) {
                Button add = Ui.button(this, relation.equals("incoming") ? "Принять заявку" : "Добавить в друзья", Ui.PRIMARY);
                add.setOnClickListener(v -> change(id, relation.equals("incoming") ? "accept" : "request"));
                Ui.add(card, add, 6);
            }
            if (relation.equals("friends") || relation.equals("incoming") || relation.equals("outgoing")) {
                Button remove = Ui.button(this, relation.equals("friends") ? "Удалить из друзей"
                        : relation.equals("incoming") ? "Отклонить" : "Отменить заявку", Ui.DIM);
                remove.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                        .setTitle(remove.getText()).setMessage(user.optString("nickname"))
                        .setPositiveButton("Подтвердить", (d, w) -> change(id, "delete"))
                        .setNegativeButton("Отмена", null).show());
                Ui.add(card, remove, 0);
            }
            Ui.add(list, card, 16);
        }
    }

    private void change(int id, String action) {
        pool.execute(() -> {
            try { api.friendship(id, action); runOnUiThread(this::load); }
            catch (Exception e) { runOnUiThread(() -> note.setText(Api.describe(e))); }
        });
    }
    @Override protected void onDestroy() { pool.shutdownNow(); super.onDestroy(); }
}
