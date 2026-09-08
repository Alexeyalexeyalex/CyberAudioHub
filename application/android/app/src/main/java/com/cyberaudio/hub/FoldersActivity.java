package com.cyberaudio.hub;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
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
 * «Мои папки» — личные подборки читателя, те же, что на сайте.
 *
 * Подборки живут на сервере, а не на телефоне: собранная в браузере папка
 * должна открываться в приложении и наоборот. Поэтому список каждый раз
 * спрашиваем заново, а не храним копию, которая разъедется с сайтом.
 */
public class FoldersActivity extends AppCompatActivity {

    private static final int COLUMNS = 3;

    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private Api api;
    private TextView note;
    private LinearLayout list;
    private Button backButton;

    /** Открытая подборка: её книги показываем вместо списка подборок. */
    private JSONObject opened;
    private JSONArray folders;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        api = new Api(this);

        LinearLayout box = Ui.column(this);
        Ui.add(box, Ui.title(this, "Мои папки"), 6);

        note = Ui.label(this, "Загружаю...", Ui.DIM, 13);
        Ui.add(box, note, 10);

        LinearLayout tools = Ui.row(this);
        Button leave = Ui.button(this, "Назад", Ui.SECONDARY);
        leave.setOnClickListener(v -> finish());
        tools.addView(leave);

        backButton = Ui.button(this, "К списку папок", Ui.SECONDARY);
        backButton.setOnClickListener(v -> {
            opened = null;
            render();
        });
        backButton.setVisibility(View.GONE);
        tools.addView(backButton);

        Button create = Ui.button(this, "Новая папка", Ui.PRIMARY);
        create.setOnClickListener(v -> askName());
        tools.addView(create);
        android.widget.HorizontalScrollView toolScroller =
                new android.widget.HorizontalScrollView(this);
        toolScroller.setHorizontalScrollBarEnabled(false);
        toolScroller.addView(tools);
        Ui.add(box, toolScroller, 14);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        Ui.add(box, list, 0);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.DARK);
        scroll.addView(box);
        setContentView(scroll);

        // Подборка открывается тем же экраном, поэтому системную «назад»
        // перехватываем сами: из папки она возвращает к списку папок
        getOnBackPressedDispatcher().addCallback(this,
                new androidx.activity.OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        if (opened != null) {
                            opened = null;
                            render();
                            return;
                        }
                        setEnabled(false);
                        getOnBackPressedDispatcher().onBackPressed();
                        setEnabled(true);
                    }
                });

        load();
    }

    private void load() {
        note.setText("Загружаю...");
        pool.execute(() -> {
            JSONObject data = null;
            String error = null;
            try {
                data = api.folders();
            } catch (Exception e) {
                error = Api.describe(e);
            }
            JSONObject finalData = data;
            String finalError = error;
            runOnUiThread(() -> {
                if (finalError != null) {
                    note.setText(finalError);
                    return;
                }
                folders = finalData.optJSONArray("folders");
                // Открытая подборка могла измениться на сайте — берём свежую
                if (opened != null) opened = findById(opened.optInt("id"));
                render();
            });
        });
    }

    private JSONObject findById(int id) {
        if (folders == null) return null;
        for (int i = 0; i < folders.length(); i++) {
            JSONObject folder = folders.optJSONObject(i);
            if (folder != null && folder.optInt("id") == id) return folder;
        }
        return null;
    }

    private void render() {
        list.removeAllViews();
        backButton.setVisibility(opened == null ? View.GONE : View.VISIBLE);

        if (opened != null) {
            renderBooks(opened);
            return;
        }

        int count = folders == null ? 0 : folders.length();
        note.setText(count == 0 ? "Папок пока нет" : "Папок: " + count);
        if (count == 0) {
            list.addView(Ui.label(this,
                    "Соберите свою подборку: «Новая папка», а потом добавьте"
                            + " в неё книги кнопкой «В папку» в плеере.",
                    Ui.DIM, 15));
            return;
        }

        List<View> cells = new ArrayList<>();
        for (int i = 0; i < folders.length(); i++) {
            final JSONObject folder = folders.optJSONObject(i);
            if (folder == null) continue;
            JSONArray items = folder.optJSONArray("items");
            int books = items == null ? 0 : items.length();
            View cell = cell(folder.optString("name"), folder.optString("cover"),
                    books + " " + books(books), v -> {
                        opened = folder;
                        render();
                    });
            cell.setOnLongClickListener(v -> {
                askDelete(folder);
                return true;
            });
            cells.add(cell);
        }
        fillShelves(cells);
    }

    private void renderBooks(JSONObject folder) {
        note.setText(folder.optString("name"));
        JSONArray items = folder.optJSONArray("items");
        if (items == null || items.length() == 0) {
            list.addView(Ui.label(this, "В этой папке пока пусто.", Ui.DIM, 15));
            return;
        }
        List<View> cells = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject book = items.optJSONObject(i);
            if (book == null) continue;
            final String path = book.optString("path");
            cells.add(cell(book.optString("name"), book.optString("cover"), "",
                    v -> {
                        // Полка сама разберётся: по пути книги она получит
                        // список глав и откроет плеер
                        Intent intent = new Intent(this, ShelfActivity.class);
                        intent.putExtra(ShelfActivity.EXTRA_START, path);
                        startActivity(intent);
                    }));
        }
        fillShelves(cells);
    }

    private static String books(int count) {
        int last = count % 10;
        int tens = count % 100;
        if (tens >= 11 && tens <= 14) return "книг";
        if (last == 1) return "книга";
        if (last >= 2 && last <= 4) return "книги";
        return "книг";
    }

    // --- Создание и удаление ---

    private void askName() {
        android.widget.EditText field = Ui.field(this, "Название папки");
        new android.app.AlertDialog.Builder(this)
                .setTitle("Новая папка")
                .setView(field)
                .setPositiveButton("Создать", (d, w) -> {
                    String name = field.getText().toString().trim();
                    if (!name.isEmpty()) create(name);
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void create(String name) {
        pool.execute(() -> {
            String error = null;
            try {
                api.createFolder(name);
            } catch (Exception e) {
                error = Api.describe(e);
            }
            String finalError = error;
            runOnUiThread(() -> {
                if (finalError != null) note.setText(finalError);
                else load();
            });
        });
    }

    private void askDelete(JSONObject folder) {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Удалить папку?")
                .setMessage("Папка «" + folder.optString("name")
                        + "» исчезнет и на сайте. Сами книги останутся на месте.")
                .setPositiveButton("Удалить", (d, w) -> delete(folder.optInt("id")))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void delete(int id) {
        pool.execute(() -> {
            String error = null;
            try {
                api.deleteFolder(id);
            } catch (Exception e) {
                error = Api.describe(e);
            }
            String finalError = error;
            runOnUiThread(() -> {
                if (finalError != null) {
                    note.setText(finalError);
                    return;
                }
                opened = null;
                load();
            });
        });
    }

    // --- Раскладка: та же полка, что в медиатеке ---

    private void fillShelves(List<View> cells) {
        for (int start = 0; start < cells.size(); start += COLUMNS) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = start; i < Math.min(start + COLUMNS, cells.size()); i++) {
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                params.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
                row.addView(cells.get(i), params);
            }
            for (int i = cells.size(); i < start + COLUMNS; i++) {
                row.addView(new View(this),
                        new LinearLayout.LayoutParams(0, 1, 1f));
            }
            list.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            View edge = new View(this);
            edge.setBackgroundResource(R.drawable.shelf_edge);
            list.addView(edge, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 2)));
            list.addView(new View(this), new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 18)));
        }
    }

    private View cell(String name, String cover, String hint,
                      View.OnClickListener action) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setClickable(true);
        item.setPadding(0, 0, 0, Ui.dp(this, 6));
        item.setOnClickListener(action);

        ImageView art = new ImageView(this);
        art.setImageResource(R.drawable.cover_placeholder);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        int side = Ui.dp(this, 104);
        item.addView(art, new LinearLayout.LayoutParams(side, side));
        if (cover != null && !cover.isEmpty()) {
            Covers.into(art, api, cover, getCacheDir());
        }

        TextView label = Ui.label(this, name, Ui.TEXT, 12);
        label.setGravity(Gravity.CENTER_HORIZONTAL);
        label.setMaxLines(2);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams text = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        text.topMargin = Ui.dp(this, 6);
        item.addView(label, text);

        if (!hint.isEmpty()) {
            TextView small = Ui.label(this, hint, Ui.DIM, 11);
            small.setGravity(Gravity.CENTER_HORIZONTAL);
            item.addView(small);
        }
        return item;
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        super.onDestroy();
    }
}
