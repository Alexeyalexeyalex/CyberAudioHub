package com.cyberaudio.hub;

import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Настройки: адрес сервера и экономия трафика.
 *
 * Адрес меняют, когда сервер переехал: раньше для этого приходилось выходить
 * из учётной записи, потому что поле было только на входе.
 */
public class SettingsActivity extends AppCompatActivity {

    private Api api;
    private EditText serverField;
    private CheckBox noImages;
    private TextView note;

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

        Ui.add(box, Ui.label(this, "Адрес сервера", Ui.DIM, 13), 6);
        serverField = Ui.field(this, "например 192.168.1.5:2077");
        serverField.setText(api.server());
        serverField.setSingleLine(true);
        Ui.add(box, serverField, 10);

        Button save = Ui.button(this, "Сохранить адрес", Ui.PRIMARY, true);
        save.setOnClickListener(v -> saveServer());
        Ui.add(box, save, 8);

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
        setContentView(scroll);
    }

    private void saveServer() {
        String value = serverField.getText().toString().trim();
        if (value.isEmpty()) {
            note.setText("Адрес не может быть пустым");
            return;
        }
        api.setServer(value);
        // Обложки с прежнего сервера в кеше больше не годятся
        Covers.clear(getCacheDir());
        note.setText("Адрес сохранён: " + api.server());
    }
}
