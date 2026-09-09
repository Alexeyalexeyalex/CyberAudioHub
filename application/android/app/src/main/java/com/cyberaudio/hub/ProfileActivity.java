package com.cyberaudio.hub;

import android.content.Intent;
import android.os.Bundle;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Личный кабинет работает с той же учётной записью, что и сайт. */
public class ProfileActivity extends AppCompatActivity {
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private Api api;
    private LinearLayout identity;
    private TextView note;
    private JSONObject person;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        api = new Api(this);
        LinearLayout box = Ui.column(this);
        LinearLayout top = Ui.row(this);
        top.addView(Ui.title(this, "Ваше пространство"), new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton settings = Ui.roundButton(this, R.drawable.ic_gear, Ui.DIM, 48, "Настройки");
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        top.addView(settings);
        Ui.add(box, top, 16);
        Button back = Ui.button(this, "Назад", Ui.DIM);
        back.setOnClickListener(v -> finish());
        Ui.add(box, back, 16);
        identity = new LinearLayout(this);
        identity.setOrientation(LinearLayout.VERTICAL);
        identity.setBackground(Ui.hero(this));
        int pad = Ui.dp(this, 22);
        identity.setPadding(pad, pad, pad, pad);
        note = Ui.label(this, "Загружаю профиль…", Ui.DIM, 14);
        identity.addView(note);
        Ui.add(box, identity, 20);
        link(box, "Достижения", "Коллекция ваших открытий", AchievementsActivity.class);
        link(box, "Статистика", "Ваш ритм чтения в цифрах", StatsActivity.class);
        link(box, "Друзья", "Читайте вместе и сравнивайте достижения", FriendsActivity.class);
        Button site = Ui.button(this, "Кабинет на сайте ↗", Ui.DIM);
        site.setOnClickListener(v -> SiteActivity.open(this, "/", "CyberAudio Hub"));
        Ui.add(box, site, 12);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        Ui.screen(this, scroll, 3);
    }

    private void link(LinearLayout box, String title, String subtitle, Class<?> target) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.card(this, 0));
        int pad = Ui.dp(this, 20);
        card.setPadding(pad, pad, pad, pad);
        Ui.add(card, Ui.label(this, title + "  →", Ui.TEXT, 20), 5);
        card.addView(Ui.label(this, subtitle, Ui.DIM, 13));
        card.setOnClickListener(v -> startActivity(new Intent(this, target)));
        Ui.add(box, card, 10);
    }

    @Override protected void onResume() { super.onResume(); load(); }

    private void load() {
        pool.execute(() -> {
            try {
                JSONObject user = api.me().optJSONObject("user");
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    person = user;
                    identity.removeAllViews();
                    if (user == null) {
                        identity.addView(Ui.label(this, "Войдите, чтобы сохранить свой прогресс", Ui.TEXT, 18));
                        Button login = Ui.button(this, "Войти", Ui.PRIMARY, true);
                        login.setOnClickListener(v -> {
                            api.forgetSession();
                            startActivity(new Intent(this, MainActivity.class));
                            finish();
                        });
                        identity.addView(login);
                        return;
                    }
                    Ui.add(identity, Ui.label(this, "НА ВАШЕЙ ВОЛНЕ", Ui.SECONDARY, 11), 10);
                    Ui.add(identity, Ui.title(this, user.optString("nickname")), 4);
                    Ui.add(identity, Ui.label(this, "@" + user.optString("login"), Ui.DIM, 14), 16);
                    Button edit = Ui.button(this, "Изменить профиль", Ui.PRIMARY);
                    edit.setOnClickListener(v -> editProfile());
                    Ui.add(identity, edit, 8);
                    if (user.optBoolean("is_admin")) {
                        Button admin = Ui.button(this, "Админ-панель", Ui.SECONDARY);
                        admin.setOnClickListener(v -> SiteActivity.open(this, "/admin", "Админ-панель"));
                        Ui.add(identity, admin, 0);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    identity.removeAllViews();
                    identity.addView(Ui.label(this, Api.describe(e), Ui.DIM, 15));
                });
            }
        });
    }

    private void editProfile() {
        if (person == null) return;
        LinearLayout fields = Ui.column(this);
        EditText nickname = Ui.field(this, "Никнейм");
        nickname.setText(person.optString("nickname"));
        Ui.add(fields, nickname, 12);
        EditText current = Ui.field(this, "Текущий пароль (для смены пароля)");
        EditText next = Ui.field(this, "Новый пароль (необязательно)");
        current.setInputType(129); next.setInputType(129);
        Ui.add(fields, current, 12); Ui.add(fields, next, 0);
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle("Ваш профиль").setView(fields)
                .setPositiveButton("Сохранить", null).setNegativeButton("Отмена", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(-1).setOnClickListener(v -> {
            final JSONObject data = new JSONObject();
            try {
                data.put("nickname", nickname.getText().toString().trim());
                data.put("current_password", current.getText().toString());
                data.put("new_password", next.getText().toString());
            } catch (Exception ignored) { return; }
            dialog.getButton(-1).setEnabled(false);
            pool.execute(() -> {
                try {
                    api.updateProfile(data);
                    runOnUiThread(() -> { dialog.dismiss(); load(); });
                } catch (Exception e) {
                    runOnUiThread(() -> {
                        dialog.getButton(-1).setEnabled(true);
                        Toast.makeText(this, Api.describe(e), Toast.LENGTH_LONG).show();
                    });
                }
            });
        }));
        dialog.show();
    }

    @Override protected void onDestroy() { pool.shutdownNow(); super.onDestroy(); }
}
