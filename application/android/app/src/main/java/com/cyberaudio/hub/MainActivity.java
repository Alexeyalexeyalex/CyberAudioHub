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
 * Первым делом спрашиваем адрес сервера: медиатека своя, домашняя, и никакого
 * «облака по умолчанию» у приложения нет. Адрес запоминается, дальше человек
 * его не видит.
 */
public class MainActivity extends AppCompatActivity {

    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private Api api;
    private EditText serverField;
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

        LinearLayout box = Ui.column(this);
        LinearLayout welcome = new LinearLayout(this);
        welcome.setOrientation(LinearLayout.VERTICAL);
        welcome.setBackground(Ui.hero(this));
        int pad = Ui.dp(this, 24);
        welcome.setPadding(pad, pad, pad, pad);
        Ui.add(welcome, Ui.label(this, "ВКЛЮЧИТЕ СВОЮ ИСТОРИЮ", Ui.SECONDARY, 11), 20);
        Ui.add(welcome, Ui.title(this, "Мир за пределами обычного."), 14);
        Ui.add(welcome, Ui.label(this, "Любимые книги. Ваш ритм.", Ui.DIM, 15), 0);
        Ui.add(box, welcome, 26);

        serverField = Ui.field(this, "Адрес сервера, например 192.168.1.5:2077");
        serverField.setText(api.server());
        Ui.add(box, serverField, 12);

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
        if (api.hasServer() && api.isSignedIn()) {
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
        String server = serverField.getText().toString().trim();
        String login = loginField.getText().toString().trim();
        String nick = nickField.getText().toString().trim();
        String password = passwordField.getText().toString();

        if (server.isEmpty()) {
            message.setText("Укажите адрес сервера");
            return;
        }
        if (login.isEmpty() || password.isEmpty()) {
            message.setText("Заполните логин и пароль");
            return;
        }

        api.setServer(server);
        submit.setEnabled(false);
        message.setText("Соединяюсь...");

        pool.execute(() -> {
            String error = null;
            try {
                if (registering) {
                    api.register(login, nick.isEmpty() ? login : nick, password);
                } else {
                    api.login(login, password);
                }
            } catch (Exception e) {
                error = Api.describe(e);
            }
            String finalError = error;
            runOnUiThread(() -> {
                submit.setEnabled(true);
                if (finalError == null) {
                    openShelf();
                } else {
                    message.setText(finalError);
                }
            });
        });
    }

    private void openShelf() {
        startActivity(new Intent(this, ShelfActivity.class));
        finish();
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        super.onDestroy();
    }
}
