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
 * Полка с книгами: та же иерархия папок, что и на сайте.
 *
 * Книги показаны обложками, разложенными по полкам, — так их узнают
 * с одного взгляда, а списком из названий приходилось вчитываться.
 * Сама полка нарисована светящейся линией, а не доской: дерево спорило бы
 * со всем остальным оформлением.
 *
 * Идём по одной папке за раз, без разворачивания дерева: на телефоне
 * вложенное дерево неудобно, а путь наверх всегда один.
 */
public class ShelfActivity extends AppCompatActivity {

    public static final String EXTRA_OFFLINE = "offline";
    /** Открыть сразу этот путь: так в полку заходят из личной подборки. */
    public static final String EXTRA_START = "start";

    /** Сколько обложек помещается в ряд. */
    private static final int COLUMNS = 3;

    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private Api api;
    private Store store;
    private LinearLayout list;
    private TextView crumbs;
    private Button upButton;
    private android.widget.EditText searchField;
    private android.widget.Spinner sortSelect;
    /** Разделы, которым нужен сервер: без него их прячем. */
    private LinearLayout onlineTools;
    private LinearLayout upButtonRow;
    /** Сервер не отвечает — показываем только то, что лежит на телефоне. */
    private boolean serverDown;
    private String path = "";
    private boolean offline;

    /** Последний показанный список — чтобы менять порядок без запроса. */
    private JSONArray shown;
    /** path книги -> когда её слушали и сколько осталось. Для сортировок. */
    private final java.util.Map<String, long[]> progress = new java.util.HashMap<>();
    private final android.os.Handler typing =
            new android.os.Handler(android.os.Looper.getMainLooper());

    /** Порядок карточек — те же четыре варианта, что в списке на сайте. */
    private static final String[] SORTS = {
            "По названию", "По названию, наоборот",
            "Сначала недавние", "Меньше осталось"};
    // Обложка книги, в которую переходим. Когда сервер ответит списком глав,
    // самой обложки в ответе уже не будет — она была в родительской папке.
    private String pendingCover = "";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        api = new Api(this);
        store = new Store(this);
        offline = getIntent().getBooleanExtra(EXTRA_OFFLINE, false);

        LinearLayout box = Ui.column(this);

        // Заголовок и шестерёнка в одной строке: настройки нужны редко,
        // но должны быть под рукой — в том числе когда сервер не отвечает
        // и поменять адрес больше негде
        LinearLayout head = Ui.row(this);
        TextView heading = Ui.title(this, offline ? "Скачанное" : "Медиатека");
        head.addView(heading, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        android.widget.ImageButton gear = Ui.roundButton(this, R.drawable.ic_gear,
                Ui.PRIMARY, 48, "Настройки");
        gear.setOnClickListener(v -> startActivity(
                new Intent(this, SettingsActivity.class)));
        head.addView(gear);
        Ui.add(box, head, 4);

        crumbs = Ui.label(this, "", Ui.DIM, 13);
        Ui.add(box, crumbs, 10);

        // Кнопка «Наверх» живёт отдельной строкой: она про место в каталоге,
        // а не про раздел, и появляется только когда есть куда подниматься
        upButton = Ui.button(this, "Наверх", Ui.SECONDARY);
        upButton.setOnClickListener(v -> goUp());
        upButtonRow = Ui.row(this);
        upButtonRow.addView(upButton);
        Ui.add(box, upButtonRow, 8);

        // Разделы — одной строкой во всю ширину, без прокрутки вбок:
        // спрятанную за краем кнопку никто не ищет. Подписи мельче, зато
        // все четыре видны сразу.
        onlineTools = Ui.row(this);
        if (!offline) {
            onlineTools.addView(tool("Скачанное", v -> {
                Intent intent = new Intent(this, ShelfActivity.class);
                intent.putExtra(EXTRA_OFFLINE, true);
                startActivity(intent);
            }));
            onlineTools.addView(tool("Папки", v -> startActivity(
                    new Intent(this, FoldersActivity.class))));
            onlineTools.addView(tool("Достижения", v -> startActivity(
                    new Intent(this, AchievementsActivity.class))));
            onlineTools.addView(tool("Статистика", v -> startActivity(
                    new Intent(this, StatsActivity.class))));
        }
        Ui.add(box, onlineTools, 12);

        if (!offline) {
            searchField = Ui.field(this, "Поиск по медиатеке");
            searchField.setSingleLine(true);
            searchField.addTextChangedListener(new android.text.TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence t, int a, int b, int c) {
                }

                @Override
                public void onTextChanged(CharSequence t, int a, int b, int c) {
                }

                @Override
                public void afterTextChanged(android.text.Editable text) {
                    onSearchTyped(text.toString().trim());
                }
            });
            Ui.add(box, searchField, 8);
        }

        sortSelect = new android.widget.Spinner(this);
        android.widget.ArrayAdapter<String> sorts = new android.widget.ArrayAdapter<>(
                this, android.R.layout.simple_spinner_dropdown_item, SORTS);
        sortSelect.setAdapter(sorts);
        sortSelect.setBackground(Ui.card(this, 0));
        sortSelect.setMinimumHeight(Ui.dp(this, 48));
        sortSelect.setOnItemSelectedListener(
                new android.widget.AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(android.widget.AdapterView<?> parent,
                                               View view, int position, long id) {
                        if (offline) showDownloaded();
                        else if (shown != null) fillGrid(shown);
                    }

                    @Override
                    public void onNothingSelected(android.widget.AdapterView<?> parent) {
                    }
                });
        Ui.add(box, sortSelect, 14);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        Ui.add(box, list, 0);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Ui.DARK);
        scroll.addView(box);
        setContentView(scroll);

        // Внутри папки приложение ходит одним экраном, поэтому у системной
        // «назад» нет своей истории — и она закрывала приложение прямо из
        // вложенной папки. Перехватываем: сначала выходим из поиска, потом
        // поднимаемся по каталогу, и только с самого верха закрываемся
        getOnBackPressedDispatcher().addCallback(this,
                new androidx.activity.OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        if (searchField != null
                                && searchField.getText().length() > 0) {
                            searchField.setText("");
                            return;
                        }
                        if (!offline && !path.isEmpty()) {
                            goUp();
                            return;
                        }
                        // Наверх идти некуда — отдаём ход системе
                        setEnabled(false);
                        getOnBackPressedDispatcher().onBackPressed();
                        setEnabled(true);
                    }
                });

        refreshUpButton();
        if (offline) {
            showDownloaded();
        } else {
            load(orEmpty(getIntent().getStringExtra(EXTRA_START)));
            loadProgress();
            // Спрашиваем про новую версию один раз при открытии полки,
            // а не на каждом экране: чаще — навязчиво
            Updates.check(this, api);
        }
    }

    /** Кнопка раздела: делит ширину поровну с соседями. */
    private Button tool(String text, View.OnClickListener action) {
        Button view = Ui.button(this, text, Ui.DIM);
        view.setTextSize(12);
        view.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0);
        view.setOnClickListener(action);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.setMargins(Ui.dp(this, 2), 0, Ui.dp(this, 2), 0);
        view.setLayoutParams(params);
        return view;
    }

    /**
     * Верхняя кнопка нужна, только когда есть куда возвращаться.
     *
     * В корне медиатеки её нет вовсе: выходить из приложения человек
     * привык системной кнопкой «назад», а отдельный «Выход» на главном
     * экране только занимал место и пугал.
     */
    private void refreshUpButton() {
        if (offline) {
            upButton.setText("Назад");
            upButtonRow.setVisibility(View.VISIBLE);
        } else if (path.isEmpty()) {
            upButtonRow.setVisibility(View.GONE);
        } else {
            upButton.setText("Наверх");
            upButtonRow.setVisibility(View.VISIBLE);
        }
    }

    private void goUp() {
        if (offline || path.isEmpty()) {
            finish();
            return;
        }
        int cut = path.lastIndexOf('/');
        load(cut < 0 ? "" : path.substring(0, cut));
    }

    // --- Сеть ---

    private void load(String target) {
        crumbs.setText("Загружаю...");
        pool.execute(() -> {
            JSONObject data = null;
            String error = null;
            try {
                data = api.browse(target);
            } catch (Exception e) {
                error = Api.describe(e);
            }
            JSONObject finalData = data;
            String finalError = error;
            runOnUiThread(() -> {
                if (finalError != null) {
                    showWithoutServer(finalError);
                    return;
                }
                serverDown = false;
                onlineTools.setVisibility(View.VISIBLE);
                if (searchField != null) searchField.setVisibility(View.VISIBLE);
                path = target;
                show(finalData);
            });
        });
    }

    /**
     * Сервера нет. Каталог показывать нечего — ни одна книга оттуда сейчас
     * не откроется, и разделы вроде достижений тоже не ответят. Поэтому
     * прячем всё, что без сервера не работает, и оставляем скачанное: его
     * можно слушать прямо сейчас.
     */
    private void showWithoutServer(String reason) {
        serverDown = true;
        shown = null;
        onlineTools.setVisibility(View.GONE);
        if (searchField != null) {
            searchField.setText("");
            searchField.setVisibility(View.GONE);
        }
        // Очистка строки поиска сама просит перезагрузить каталог — а его
        // сейчас неоткуда взять, и подпись успевала смениться обратно
        // на «Загружаю...». Снимаем отложенный запрос
        typing.removeCallbacksAndMessages(null);
        crumbs.setText(reason + " Показаны книги на телефоне.");
        refreshUpButton();
        showDownloaded();
    }

    private void show(JSONObject data) {
        crumbs.setText(path.isEmpty() ? "Медиатека" : path);
        refreshUpButton();

        JSONArray tracks = data.optJSONArray("tracks");
        // Папка с дорожками — это книга: сразу открываем её в плеере
        if (tracks != null && tracks.length() > 0) {
            openBook(path, path.isEmpty() ? "Книга" : lastPart(path),
                    tracks, pendingCover);
            return;
        }
        shown = data.optJSONArray("items");
        fillGrid(shown);
    }

    /** Рисует полки из уже полученного списка, в выбранном порядке. */
    private void fillGrid(JSONArray items) {
        list.removeAllViews();
        if (items == null || items.length() == 0) {
            list.addView(Ui.label(this, "Здесь пусто.", Ui.DIM, 15));
            return;
        }

        List<JSONObject> order = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null) order.add(item);
        }
        sortItems(order);

        List<View> cells = new ArrayList<>();
        for (JSONObject item : order) {
            final String itemPath = item.optString("path");
            final String cover = item.optString("cover");
            final String name = item.optString("name");
            boolean isAlbum = "album".equals(item.optString("type"));
            cells.add(cell(name, cover, isAlbum, v -> {
                pendingCover = isAlbum ? cover : "";
                // Из результатов поиска уходим в саму книгу: строку запроса
                // при этом гасим, иначе возврат наверх вёл бы обратно в поиск
                if (searchField != null && searchField.getText().length() > 0) {
                    searchField.setText("");
                }
                load(itemPath);
            }));
        }
        fillShelves(list, cells);
    }

    /**
     * Порядок карточек — как на сайте: папки и книги сортируются вместе.
     *
     * «Недавние» и «меньше осталось» опираются на прогресс с сервера;
     * пока он не пришёл, обе сортировки просто раскладывают по названию —
     * это честнее, чем показывать случайный порядок.
     */
    private void sortItems(List<JSONObject> items) {
        final int mode = sortSelect == null ? 0 : sortSelect.getSelectedItemPosition();
        java.util.Comparator<JSONObject> byName = (a, b) -> collator()
                .compare(a.optString("name"), b.optString("name"));
        java.util.Comparator<JSONObject> order;
        switch (mode) {
            case 1:
                order = byName.reversed();
                break;
            case 2:
                // Недавние выше: у книги без прогресса времени нет вовсе
                order = java.util.Comparator
                        .comparingLong((JSONObject i) -> -progressUnder(i)[0])
                        .thenComparing(byName);
                break;
            case 3:
                order = java.util.Comparator
                        .comparingLong((JSONObject i) -> progressUnder(i)[1])
                        .thenComparing(byName);
                break;
            default:
                order = byName;
        }
        java.util.Collections.sort(items, order);
    }

    /** numeric-сравнение: иначе «Глава 10» встаёт перед «Глава 7». */
    private static java.text.Collator collator() {
        java.text.Collator rules = java.text.Collator.getInstance(
                new java.util.Locale("ru"));
        rules.setStrength(java.text.Collator.PRIMARY);
        return rules;
    }

    /**
     * Прогресс папки собираем из книг внутри неё: у самой папки его нет,
     * и без этого сортировки не двигали бы папки вовсе.
     * Возвращает {когда слушали, сколько осталось секунд}.
     */
    private long[] progressUnder(JSONObject item) {
        String itemPath = item.optString("path");
        long[] own = progress.get(itemPath);
        if (own != null) return own;

        long when = 0;
        long left = Long.MAX_VALUE;
        String prefix = itemPath + "/";
        for (java.util.Map.Entry<String, long[]> entry : progress.entrySet()) {
            if (!entry.getKey().startsWith(prefix)) continue;
            when = Math.max(when, entry.getValue()[0]);
            left = Math.min(left, entry.getValue()[1]);
        }
        return new long[]{when, left};
    }

    /**
     * Поиск по всей медиатеке, тот же, что на сайте.
     *
     * Ждём, пока человек допечатает: запрос на каждую букву — это десяток
     * лишних обходов каталога на сервере ради результата, который всё равно
     * успеет смениться.
     */
    private void onSearchTyped(String query) {
        typing.removeCallbacksAndMessages(null);
        if (query.length() < 2) {
            // Строку очистили — возвращаем обычный каталог
            if (query.isEmpty()) typing.postDelayed(() -> load(path), 200);
            return;
        }
        typing.postDelayed(() -> runSearch(query), 300);
    }

    private void runSearch(String query) {
        crumbs.setText("Ищу...");
        pool.execute(() -> {
            JSONObject data = null;
            String error = null;
            try {
                data = api.search(query);
            } catch (Exception e) {
                error = Api.describe(e);
            }
            JSONObject finalData = data;
            String finalError = error;
            runOnUiThread(() -> {
                if (finalError != null) {
                    crumbs.setText(finalError);
                    return;
                }
                crumbs.setText("Поиск: " + query);
                shown = finalData.optJSONArray("items");
                fillGrid(shown);
                if (shown == null || shown.length() == 0) {
                    list.removeAllViews();
                    list.addView(Ui.label(this,
                            "Ничего не нашлось по запросу «" + query + "».",
                            Ui.DIM, 15));
                }
            });
        });
    }

    /**
     * Прогресс по книгам — для сортировок. Спрашиваем один раз при открытии:
     * порядок на полке не обязан меняться, пока экран открыт.
     */
    private void loadProgress() {
        pool.execute(() -> {
            final JSONObject data;
            try {
                data = api.progress();
            } catch (Exception e) {
                return;   // без прогресса просто останутся сортировки по имени
            }
            JSONArray items = data.optJSONArray("items");
            if (items == null) return;
            java.util.Map<String, long[]> fresh = new java.util.HashMap<>();
            for (int i = 0; i < items.length(); i++) {
                JSONObject row = items.optJSONObject(i);
                if (row == null) continue;
                long when = parseTime(row.optString("updated_at"));
                long left = row.isNull("remaining")
                        ? Long.MAX_VALUE : (long) row.optDouble("remaining");
                fresh.put(row.optString("path"), new long[]{when, left});
            }
            runOnUiThread(() -> {
                progress.clear();
                progress.putAll(fresh);
                if (shown != null) fillGrid(shown);
            });
        });
    }

    /** Время сервера в миллисекундах. Непонятное считаем «никогда». */
    private static long parseTime(String value) {
        if (value == null || value.isEmpty()) return 0;
        try {
            java.text.SimpleDateFormat format = new java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT);
            return format.parse(value.replace('T', ' ').substring(0, 19)).getTime();
        } catch (Exception e) {
            return 0;
        }
    }

    // --- Офлайн ---

    private void showDownloaded() {
        list.removeAllViews();
        JSONArray books = store.all();
        if (!serverDown) {
            crumbs.setText(books.length() == 0
                    ? "" : "Книг на телефоне: " + books.length());
        }
        if (books.length() == 0) {
            list.addView(Ui.label(this,
                    "Ничего не скачано. Откройте книгу и нажмите «Скачать».",
                    Ui.DIM, 15));
            return;
        }
        List<JSONObject> order = new ArrayList<>();
        for (int i = 0; i < books.length(); i++) {
            JSONObject book = books.optJSONObject(i);
            if (book == null) continue;
            // Скачанное хранится под title, а сортировка смотрит на name
            order.add(book);
        }
        java.util.Collections.sort(order, (a, b) -> collator()
                .compare(a.optString("title"), b.optString("title")));
        if (sortSelect != null && sortSelect.getSelectedItemPosition() == 1) {
            java.util.Collections.reverse(order);
        }
        List<View> cells = new ArrayList<>();
        for (JSONObject book : order) {
            final String bookPath = book.optString("path");
            final String title = book.optString("title");
            cells.add(cell(title, book.optString("cover"), true,
                    v -> openSaved(bookPath, title)));
        }
        fillShelves(list, cells);
    }

    private void openSaved(String bookPath, String title) {
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra(PlayerActivity.EXTRA_PATH, bookPath);
        intent.putExtra(PlayerActivity.EXTRA_TITLE, title);
        intent.putExtra(PlayerActivity.EXTRA_OFFLINE, true);
        startActivity(intent);
    }

    // --- Раскладка ---

    /** Раскладывает ячейки рядами по COLUMNS, подчёркивая каждый ряд полкой. */
    private void fillShelves(LinearLayout host, List<View> cells) {
        for (int start = 0; start < cells.size(); start += COLUMNS) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = start; i < Math.min(start + COLUMNS, cells.size()); i++) {
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                params.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 4), 0);
                row.addView(cells.get(i), params);
            }
            // Неполный ряд дополняем пустотой: иначе две книги растянулись бы
            // на всю ширину и выглядели крупнее соседей сверху
            for (int i = cells.size(); i < start + COLUMNS; i++) {
                row.addView(new View(this),
                        new LinearLayout.LayoutParams(0, 1, 1f));
            }
            host.addView(row, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            host.addView(shelfEdge(), new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 2)));
            host.addView(new View(this), new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 18)));
        }
    }

    private View shelfEdge() {
        View edge = new View(this);
        edge.setBackgroundResource(R.drawable.shelf_edge);
        return edge;
    }

    /** Одна книга или папка: обложка и подпись под ней. */
    private View cell(String name, String cover, boolean isBook,
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
        // Обложки бывают любых пропорций, поэтому держим квадрат: иначе
        // полка идёт «лесенкой» и ряды перестают читаться как ряды
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

        if (!isBook) {
            TextView hint = Ui.label(this, "папка", Ui.DIM, 11);
            hint.setGravity(Gravity.CENTER_HORIZONTAL);
            item.addView(hint);
        }
        return item;
    }

    // --- Мелочи ---

    private void openBook(String bookPath, String title, JSONArray tracks, String cover) {
        List<String> urls = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (int i = 0; i < tracks.length(); i++) {
            JSONObject track = tracks.optJSONObject(i);
            if (track == null) continue;
            urls.add(track.optString("url"));
            names.add(track.optString("name"));
        }
        Intent intent = new Intent(this, PlayerActivity.class);
        intent.putExtra(PlayerActivity.EXTRA_PATH, bookPath);
        intent.putExtra(PlayerActivity.EXTRA_TITLE, title);
        intent.putExtra(PlayerActivity.EXTRA_COVER, cover);
        intent.putExtra(PlayerActivity.EXTRA_URLS, urls.toArray(new String[0]));
        intent.putExtra(PlayerActivity.EXTRA_NAMES, names.toArray(new String[0]));
        startActivity(intent);
        // Возвращаться в «книгу» бессмысленно: там нет ничего, кроме плеера.
        // А если в полку зашли из подборки — закрываем её целиком, иначе
        // человек попадёт в каталог, из которого он никуда не заходил
        if (getIntent().hasExtra(EXTRA_START)) finish();
        else if (!path.isEmpty()) goUp();
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String lastPart(String value) {
        int cut = value.lastIndexOf('/');
        return cut < 0 ? value : value.substring(cut + 1);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (offline || serverDown) showDownloaded();
        // Каждый раз, когда человек возвращается на полку, пробуем отдать
        // накопленное: связь могла появиться, пока он слушал в дороге.
        // Делать это только при первом открытии было мало — приложение
        // живёт неделями, не перезапускаясь
        new History(this).flush(api, true);
    }

    @Override
    protected void onDestroy() {
        typing.removeCallbacksAndMessages(null);
        pool.shutdownNow();
        super.onDestroy();
    }
}
