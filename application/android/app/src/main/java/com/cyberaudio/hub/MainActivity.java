package com.cyberaudio.hub;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Вход и регистрация.
 *
 * Адрес сервера задан по умолчанию. Шестерёнка доступна до авторизации,
 * чтобы подключиться к другому серверу, не вводя адрес при каждом входе.
 */
public class MainActivity extends AppCompatActivity {

    public static final String EXTRA_REAUTH = "reauth";

    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private Api api;
    private android.widget.ImageButton settings;
    private String shownServer;
    private EditText loginField;
    private EditText nickField;
    private EditText passwordField;
    private TextView message;
    private Button submit;
    private Button switchMode;
    private boolean registering;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        api = new Api(this);
        shownServer = api.server();

        LinearLayout box = Ui.column(this);
        LinearLayout top = Ui.row(this);
        top.addView(Ui.label(this, "CyberAudio Hub", Ui.TEXT, 18), new LinearLayout.LayoutParams(0, -2, 1));
        settings = Ui.roundButton(this, R.drawable.ic_gear, Ui.DIM, 48, "Настройки сервера");
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        top.addView(settings);
        Ui.add(box, top, 16);
        LinearLayout welcome = new LinearLayout(this);
        welcome.setOrientation(LinearLayout.VERTICAL);
        welcome.setBackground(Ui.hero(this));
        int pad = Ui.dp(this, 24);
        welcome.setPadding(pad, pad, pad, pad);
        Ui.add(welcome, Ui.label(this, "ВКЛЮЧИТЕ СВОЮ ИСТОРИЮ", Ui.SECONDARY, 11), 20);
        Ui.add(welcome, Ui.title(this, "Мир за пределами обычного."), 14);
        Ui.add(welcome, Ui.label(this, "Любимые книги. Ваш ритм.", Ui.DIM, 15), 0);
        Ui.add(box, welcome, 26);

        loginField = Ui.field(this, "Логин");
        Ui.add(box, loginField, 12);

        nickField = Ui.field(this, "Никнейм");
        nickField.setVisibility(View.GONE);
        Ui.add(box, nickField, 12);

        passwordField = Ui.field(this, "Пароль");
        passwordField.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        Ui.add(box, passwordField, 16);

        submit = Ui.button(this, "Войти", Ui.PRIMARY, true);
        submit.setOnClickListener(v -> send());
        Ui.add(box, submit, 8);

        switchMode = Ui.button(this, "Регистрация", Ui.SECONDARY);
        switchMode.setOnClickListener(v -> toggleMode());
        Ui.add(box, switchMode, 16);

        Button offline = Ui.button(this, "Слушать скачанное", Ui.DIM);
        offline.setOnClickListener(v -> {
            Intent intent = new Intent(this, ShelfActivity.class);
            intent.putExtra(ShelfActivity.EXTRA_OFFLINE, true);
            startActivity(intent);
        });
        Ui.add(box, offline, 12);

        message = Ui.label(this, "", Ui.SECONDARY, 14);
        Ui.add(box, message, 0);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.DARK);
        scroll.addView(box);
        Ui.screen(this, scroll, -1);

        // Уже входили — сразу на полку, спрашивать пароль второй раз незачем
        if (api.isSignedIn() && !intentRequestsLogin()) {
            openShelf();
        }
    }

    private void toggleMode() {
        registering = !registering;
        nickField.setVisibility(registering ? View.VISIBLE : View.GONE);
        submit.setText(registering ? "Зарегистрироваться" : "Войти");
        switchMode.setText(registering ? "У меня уже есть аккаунт" : "Регистрация");
        message.setText("");
    }

    private void send() {
        String login = loginField.getText().toString().trim();
        String nick = nickField.getText().toString().trim();
        String password = passwordField.getText().toString();

        if (login.isEmpty() || password.isEmpty()) {
            message.setText("Заполните логин и пароль");
            return;
        }

        submit.setEnabled(false);
        settings.setEnabled(false);
        switchMode.setEnabled(false);
        final boolean createAccount = registering;
        message.setText("Соединяюсь...");

        pool.execute(() -> {
            String error = null;
            try {
                if (createAccount) {
                    api.register(login, nick.isEmpty() ? login : nick, password);
                } else {
                    api.login(login, password);
                }
                if (api.me().optJSONObject("user") == null) {
                    throw new Api.ApiException("Сервер не сохранил вход. Проверьте адрес и повторите вход.", 401);
                }
            } catch (Exception e) {
                error = Api.describe(e);
            }
            String finalError = error;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                submit.setEnabled(true);
                settings.setEnabled(true);
                switchMode.setEnabled(true);
                if (finalError == null) {
                    openShelf();
                } else {
                    message.setText(finalError);
                }
            });
        });
    }

    private void openShelf() {
        // Login is an account boundary: discard guest/offline screens as well
        // as this form, so system Back cannot reveal a stale pre-login screen.
        // Only Activities are cleared; the session, downloads and service stay.
        startActivity(new Intent(this, ShelfActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }

    private boolean intentRequestsLogin() { return getIntent().getBooleanExtra(EXTRA_REAUTH, false); }

    @Override protected void onResume() {
        super.onResume();
        if (!shownServer.equals(api.server())) {
            shownServer = api.server();
            passwordField.setText("");
            message.setText("Сервер изменён. Введите пароль для входа.");
        }
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        super.onDestroy();
    }
}
