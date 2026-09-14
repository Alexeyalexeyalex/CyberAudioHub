package com.cyberaudio.hub;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Настройки: повторный вход, адрес сервера и экономия трафика.
 *
 * Адрес меняют, когда сервер переехал: раньше для этого приходилось выходить
 * из учётной записи, потому что поле было только на входе.
 */
public class SettingsActivity extends AppCompatActivity {

    private Api api;
    private EditText serverField;
    private CheckBox noImages;
    private TextView note;
    private TextView accountSummary;
    private Button logout;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        api = new Api(this);

        LinearLayout box = Ui.column(this);
        Ui.add(box, Ui.title(this, "Настройки"), 10);

        Button back = Ui.button(this, "Назад", Ui.SECONDARY);
        back.setOnClickListener(v -> finish());
        LinearLayout backRow = Ui.row(this);
        backRow.addView(back);
        Ui.add(box, backRow, 18);

        Ui.add(box, Ui.label(this, "Аккаунт", Ui.DIM, 13), 6);
        accountSummary = Ui.label(this, "", Ui.DIM, 13);
        Ui.add(box, accountSummary, 8);
        Button login = Ui.button(this, "Войти / сменить аккаунт", Ui.PRIMARY, true);
        login.setOnClickListener(v -> {
            // A stored cookie may already be invalid. Always show the login form;
            // merely opening it must not clear the session or local downloads.
            startActivity(new Intent(this, MainActivity.class)
                    .putExtra(MainActivity.EXTRA_REAUTH, true)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
            finish();
        });
        Ui.add(box, login, 8);
        logout = Ui.button(this, "Выйти из аккаунта", Ui.SECONDARY);
        logout.setOnClickListener(v -> new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Выйти из аккаунта?")
                .setMessage("Сохранённый вход и профиль будут удалены с телефона. Скачанные книги останутся.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Выйти", (dialog, which) -> {
                    api.forgetSession();
                    android.webkit.CookieManager cookies = android.webkit.CookieManager.getInstance();
                    cookies.setCookie(api.server(), "session=; Max-Age=0; Path=/", accepted -> cookies.flush());
                    startActivity(new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_REAUTH, true)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                    finish();
                }).show());
        Ui.add(box, logout, 8);
        Ui.add(box, Ui.label(this, "Повторно войдите, если профиль недоступен. "
                + "Скачанные книги останутся на телефоне.", Ui.DIM, 13), 22);

        Ui.add(box, Ui.label(this, "Адрес сервера", Ui.DIM, 13), 6);
        serverField = Ui.field(this, ServerAddress.DEFAULT);
        serverField.setText(api.server());
        serverField.setSingleLine(true);
        Ui.add(box, serverField, 10);

        Button save = Ui.button(this, "Сохранить адрес", Ui.PRIMARY, true);
        save.setOnClickListener(v -> saveServer());
        Ui.add(box, save, 8);

        Button reset = Ui.button(this, "Адрес по умолчанию", Ui.SECONDARY);
        reset.setOnClickListener(v -> serverField.setText(ServerAddress.DEFAULT));
        Ui.add(box, reset, 8);

        note = Ui.label(this, "", Ui.DIM, 13);
        Ui.add(box, note, 22);

        Ui.add(box, Ui.label(this, "Трафик", Ui.DIM, 13), 6);
        noImages = Ui.check(this, "Не показывать обложки",
                Settings.noImages(this));
        noImages.setOnCheckedChangeListener((v, on) -> {
            Settings.setNoImages(this, on);
            note.setText(on
                    ? "Обложки не загружаются — полка будет без картинок."
                    : "Обложки показываются.");
        });
        Ui.add(box, noImages, 6);
        Ui.add(box, Ui.label(this,
                "Обложки — самая тяжёлая часть страницы. Выключите, если"
                        + " слушаете с мобильного интернета.", Ui.DIM, 12), 0);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.DARK);
        scroll.addView(box);
        Ui.screen(this, scroll, -1);
    }

    @Override protected void onResume() {
        super.onResume();
        updateAccountSummary();
    }

    private void updateAccountSummary() {
        org.json.JSONObject saved = api.cachedProfile();
        accountSummary.setText(saved == null
                ? (api.isSignedIn() ? "Вход сохранён на телефоне." : "Вы не вошли в аккаунт.")
                : saved.optString("nickname", saved.optString("login")) + "\nПрофиль сохранён на телефоне.");
        logout.setVisibility(saved != null || api.isSignedIn() ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    private void saveServer() {
        String value = serverField.getText().toString().trim();
        if (value.isEmpty()) {
            note.setText("Адрес не может быть пустым");
            return;
        }
        String previous = api.server();
        try { api.setServer(value); }
        catch (IllegalArgumentException e) { note.setText(e.getMessage()); return; }
        // Обложки с прежнего сервера в кеше больше не годятся
        if (!previous.equals(api.server())) Covers.clear(getCacheDir());
        serverField.setText(api.server());
        updateAccountSummary();
        note.setText("Адрес сохранён: " + api.server()
                + (previous.equals(api.server()) ? "" : "\nДля другого сервера нужно войти заново."));
    }
}
