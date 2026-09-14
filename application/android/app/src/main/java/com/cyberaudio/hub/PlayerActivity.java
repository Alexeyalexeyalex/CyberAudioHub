package com.cyberaudio.hub;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Плеер: главы книги, управление и скачивание на телефон.
 *
 * Сам звук играет служба, а не этот экран, — иначе воспроизведение обрывалось
 * бы при сворачивании. Экран только показывает её состояние и шлёт команды.
 */
public class PlayerActivity extends AppCompatActivity
        implements PlaybackService.Listener, Downloads.Watcher {

    public static final String EXTRA_PATH = "path";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_URLS = "urls";
    public static final String EXTRA_NAMES = "names";
    public static final String EXTRA_OFFLINE = "offline";
    public static final String EXTRA_COVER = "cover";

    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private Api api;
    private Store store;
    private History history;

    private String path = "";
    private String title = "";
    private String cover = "";
    private String[] urls = new String[0];
    private String[] names = new String[0];
    private boolean offline;

    private LinearLayout contentColumn, fullPanel;
    private ReaderView reader;
    private Button expandButton;
    private View[] readerParts;
    private int[] readerIndexes;
    private LinearLayout.LayoutParams[] readerParams;
    private int textGeneration;
    private TextView titleView;
    private android.widget.ImageView coverView;
    private TextView nowPlaying;
    private TextView clock;
    private SeekBar bar;
    private LinearLayout controls;
    private LinearLayout topRow;
    private LinearLayout textRow;
    private Button folderButton;
    private Button collapseButton;
    private ScrollView outerScroll;
    /** Текст развёрнут на весь экран: сверху только управление. */
    private boolean fullText;
    /** Пока включён, системная «назад» сворачивает текст, а не закрывает книгу. */
    private androidx.activity.OnBackPressedCallback backToPlayer;
    private android.widget.ImageButton playIcon;
    private Button downloadButton;
    private LinearLayout chapters;
    private boolean dragging;

    // --- слежение за текстом ---
    private Button textButton;
    private android.widget.CheckBox wholeBox;
    private android.widget.CheckBox followBox;
    private LinearLayout textToolbar;
    private Transcripts.Timeline timeline;
    private int timelineTrack = -1;      // для какой главы построена
    private boolean wholeBook;           // текст всей книги, а не одной главы
    private boolean following = true;    // вести подсветку за звуком
    private boolean showingText;
    private int[] litWord;               // что подсвечено сейчас
    private int litEdge = -1;            // докуда погашено прочитанное

    /** Звучащая глава: тот же --neon-green, что у .is-current на сайте. */
    private static final int CURRENT_CHAPTER =
            android.graphics.Color.parseColor("#00FF9D");

    /** Настройки те же, что хранит сайт в localStorage. */
    private static final String WHOLE_KEY = "transcript_whole";
    private static final String FOLLOW_KEY = "transcript_follow";
    private final android.os.Handler beat =
            new android.os.Handler(android.os.Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        api = new Api(this);
        store = new Store(this);
        history = new History(this);

        Intent intent = getIntent();
        path = orEmpty(intent.getStringExtra(EXTRA_PATH));
        title = orEmpty(intent.getStringExtra(EXTRA_TITLE));
        cover = orEmpty(intent.getStringExtra(EXTRA_COVER));
        offline = intent.getBooleanExtra(EXTRA_OFFLINE, false);
        urls = intent.getStringArrayExtra(EXTRA_URLS);
        names = intent.getStringArrayExtra(EXTRA_NAMES);
        if (urls == null) urls = new String[0];
        if (names == null) names = new String[0];

        if (offline) {
            List<String> saved = store.trackNames(path);
            names = saved.toArray(new String[0]);
            urls = new String[names.length];
            for (int i = 0; i < names.length; i++) {
                urls[i] = store.trackFile(path, i).getAbsolutePath();
            }
        }

        buildUi();
        Downloads.watch(this);
        // Из полного экрана системная «назад» должна возвращать к плееру,
        // а не выбрасывать из книги целиком. Через диспетчер, а не
        // onBackPressed(): тот объявлен устаревшим с Android 13
        backToPlayer = new androidx.activity.OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                setFullText(false);
            }
        };
        getOnBackPressedDispatcher().addCallback(this, backToPlayer);
        askNotificationPermission();

        PlaybackService.start(this);
        waitForService(20);
    }

    private void buildUi() {
        LinearLayout box = Ui.column(this);
        contentColumn = box;

        topRow = Ui.row(this);
        Button back = Ui.button(this, "Назад", Ui.SECONDARY);
        back.setOnClickListener(v -> finish());
        topRow.addView(back);
        Ui.add(box, topRow, 8);

        coverView = new android.widget.ImageView(this);
        coverView.setImageResource(R.drawable.cover_placeholder);
        coverView.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        coverView.setBackground(Ui.card(this, 0));
        coverView.setClipToOutline(true);
        int side = Ui.dp(this, Math.min(260, getResources().getConfiguration().screenWidthDp - 64));
        LinearLayout.LayoutParams artSize = new LinearLayout.LayoutParams(side, side);
        artSize.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        artSize.topMargin = Ui.dp(this, 8); artSize.bottomMargin = Ui.dp(this, 24);
        box.addView(coverView, artSize);
        if (!cover.isEmpty()) Covers.into(coverView, api, cover, getCacheDir());

        titleView = Ui.title(this, title);
        titleView.setTextSize(25);
        Ui.add(box, titleView, 4);

        nowPlaying = Ui.label(this, "Выберите главу", Ui.TEXT, 16);
        Ui.add(box, nowPlaying, 12);

        bar = new SeekBar(this);
        bar.setMax(1000);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar view, int value, boolean fromUser) {
            }

            @Override
            public void onStartTrackingTouch(SeekBar view) {
                dragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar view) {
                dragging = false;
                PlaybackService service = PlaybackService.get();
                if (service != null && service.duration() > 0) {
                    service.seekTo((int) ((long) service.duration()
                            * view.getProgress() / 1000));
                }
            }
        });
        Ui.add(box, bar, 4);

        clock = Ui.label(this, "0:00 / 0:00", Ui.DIM, 13);
        Ui.add(box, clock, 12);

        // Ряд управления повторяет плеер на сайте: круглые кнопки,
        // перемотка по краям от Play, тот же порядок и те же цвета.
        controls = Ui.row(this);
        controls.setGravity(android.view.Gravity.CENTER);

        // Кнопки разведены по всей ширине: пальцем в узком ряду легко
        // промахнуться мимо перемотки и попасть в соседнюю главу.
        // Распорки между кнопками растягиваются, сами кнопки — нет.
        controls.addView(playerButton(R.drawable.ic_prev, Ui.PRIMARY, 48,
                "Предыдущая глава", s -> s.previous()));
        controls.addView(spacer());
        controls.addView(playerButton(R.drawable.ic_rewind, Ui.PRIMARY, 48,
                "На 5 секунд назад", s -> s.seekBy(-PlaybackService.SEEK_STEP)));
        controls.addView(spacer());

        // Единственная заливная кнопка в ряду: главное действие должно
        // читаться сразу, остальные — просто значки
        playIcon = Ui.roundButton(this, R.drawable.ic_play, Ui.PRIMARY, 66,
                "Играть", true);
        playIcon.setOnClickListener(v -> withService(service -> {
            if (service.isPlaying()) service.pause();
            else service.resume();
        }));
        controls.addView(playIcon);

        controls.addView(spacer());
        controls.addView(playerButton(R.drawable.ic_forward, Ui.PRIMARY, 48,
                "На 5 секунд вперёд", s -> s.seekBy(PlaybackService.SEEK_STEP)));
        controls.addView(spacer());
        controls.addView(playerButton(R.drawable.ic_next, Ui.PRIMARY, 48,
                "Следующая глава", s -> s.next()));
        Ui.add(box, controls, 16);

        // Текст читается с сервера или из сохранённой офлайн-копии.
        textRow = Ui.row(this);
        textButton = Ui.button(this, "Текст книги", Ui.PRIMARY);
        textButton.setOnClickListener(v -> toggleText());
        textRow.addView(textButton, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button expand = Ui.button(this, "Развернуть", Ui.SECONDARY);
        expandButton = expand;
        expand.setOnClickListener(v -> setFullText(true));
        LinearLayout.LayoutParams expandParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        expandParams.leftMargin = Ui.dp(this, 8);
        textRow.addView(expand, expandParams);
        Ui.add(box, textRow, 6);

        // Те же две галочки, что в панели текста на сайте
        android.content.SharedPreferences settings =
                getSharedPreferences("cyberaudio", MODE_PRIVATE);
        wholeBook = settings.getBoolean(WHOLE_KEY, false);
        following = settings.getBoolean(FOLLOW_KEY, true);

        textToolbar = new LinearLayout(this);
        textToolbar.setOrientation(LinearLayout.VERTICAL);
        LinearLayout checks = Ui.row(this);
        wholeBox = Ui.check(this, "Вся книга", wholeBook);
        wholeBox.setOnCheckedChangeListener((v, on) -> {
            wholeBook = on;
            settings.edit().putBoolean(WHOLE_KEY, on).apply();
            timelineTrack = -1;                 // текст пересобираем целиком
            if (showingText) {
                PlaybackService service = PlaybackService.get();
                ensureTimeline(service == null ? 0 : service.index());
            }
        });
        followBox = Ui.check(this, "Следить за текстом", following);
        followBox.setOnCheckedChangeListener((v, on) -> {
            following = on;
            settings.edit().putBoolean(FOLLOW_KEY, on).apply();
            // Выключили — возвращаем текст в исходный вид, как на сайте:
            // ни подсветки, ни затемнения. Раньше подсветка продолжала
            // ползти по строкам, и галочка выглядела сломанной
            if (!on) clearHighlight();
            else if (showingText) highlight();
        });
        checks.addView(wholeBox, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.45f);
        gap.leftMargin = Ui.dp(this, 8);
        checks.addView(followBox, gap);
        textToolbar.addView(checks);

        // Выход из полного экрана держим рядом с галочками: наверху должно
        // остаться только управление проигрыванием
        collapseButton = Ui.button(this, "Свернуть", Ui.SECONDARY);
        collapseButton.setOnClickListener(v -> setFullText(false));
        collapseButton.setVisibility(View.GONE);
        // В одну строку и помельче: иначе на узком экране подпись рвётся
        // пополам и кнопка раздувается на треть ряда
        collapseButton.setSingleLine(true);
        collapseButton.setTextSize(13);
        collapseButton.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 12), 0);
        LinearLayout.LayoutParams collapseParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        collapseParams.leftMargin = Ui.dp(this, 12);
        textToolbar.addView(collapseButton, collapseParams);

        textToolbar.setVisibility(View.GONE);
        Ui.add(box, textToolbar, 8);

        reader = new ReaderView(this, (chapter, millis) -> withService(service -> {
            if (service.index() != chapter) service.playAt(chapter, millis);
            else service.seekTo(millis);
        }));
        reader.setVisibility(View.GONE);
        box.addView(reader, new LinearLayout.LayoutParams(-1, Ui.dp(this, 320)));

        if (!offline) {
            folderButton = Ui.button(this, "В папку", Ui.SECONDARY);
            folderButton.setOnClickListener(v -> chooseFolders());
            Ui.add(box, folderButton, 10);
        }

        downloadButton = Ui.button(this, "Скачать на телефон", Ui.PRIMARY);
        downloadButton.setOnClickListener(v -> toggleDownload());
        if (!offline) Ui.add(box, downloadButton, 16);
        refreshDownloadButton();

        chapters = new LinearLayout(this);
        chapters.setOrientation(LinearLayout.VERTICAL);
        Ui.add(box, chapters, 0);
        fillChapters();

        outerScroll = new ScrollView(this);
        outerScroll.setBackgroundColor(Ui.DARK);
        // Без этого вес не сработает и текст не растянется на весь экран
        outerScroll.setFillViewport(true);
        outerScroll.addView(box);
        android.widget.FrameLayout surfaces = new android.widget.FrameLayout(this);
        surfaces.addView(outerScroll, new android.widget.FrameLayout.LayoutParams(-1, -1));
        fullPanel = new LinearLayout(this);
        fullPanel.setOrientation(LinearLayout.VERTICAL);
        int inset = Ui.dp(this, 12);
        fullPanel.setPadding(inset, inset, inset, inset);
        fullPanel.setVisibility(View.GONE);
        surfaces.addView(fullPanel, new android.widget.FrameLayout.LayoutParams(-1, -1));
        Ui.screen(this, surfaces, -1);
    }

    /**
     * Разворачивает текст на весь экран и обратно.
     *
     * Звук при этом не трогаем вовсе: играет служба, а экран лишь меняет
     * раскладку. Сверху остаётся только ряд управления — всё остальное
     * (заголовок, полоса, список глав) отнимало бы у текста половину
     * экрана ради того, что при чтении не нужно.
     */
    private void setFullText(boolean full) {
        if (full == fullText) return;
        if (full && !store.isDownloaded(path)) { toast("Сначала скачайте книгу вместе с текстом"); return; }
        if (full && !showingText) toggleText();
        fullText = full;
        if (backToPlayer != null) backToPlayer.setEnabled(full);
        collapseButton.setVisibility(full ? View.VISIBLE : View.GONE);
        followBox.setText(full ? "Следить" : "Следить за текстом");
        if (full) {
            readerParts = new View[]{controls, textToolbar, reader};
            readerIndexes = new int[readerParts.length];
            readerParams = new LinearLayout.LayoutParams[readerParts.length];
            for (int i = 0; i < readerParts.length; i++) {
                readerIndexes[i] = contentColumn.indexOfChild(readerParts[i]);
                readerParams[i] = (LinearLayout.LayoutParams) readerParts[i].getLayoutParams();
            }
            for (int i = 0; i < readerParts.length; i++) {
                contentColumn.removeView(readerParts[i]);
                fullPanel.addView(readerParts[i], i == 2
                        ? new LinearLayout.LayoutParams(-1, 0, 1)
                        : new LinearLayout.LayoutParams(-1, -2));
            }
            outerScroll.setVisibility(View.GONE);
            fullPanel.setVisibility(View.VISIBLE);
        } else {
            fullPanel.removeAllViews();
            for (int i = 0; i < readerParts.length; i++)
                contentColumn.addView(readerParts[i], readerIndexes[i], readerParams[i]);
            fullPanel.setVisibility(View.GONE);
            outerScroll.setVisibility(View.VISIBLE);
        }
        reader.post(() -> highlight(true));
    }

    /** Растяжимая пустота между кнопками: она и разводит их по краям. */
    private View spacer() {
        View gap = new View(this);
        gap.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        return gap;
    }

    /** Круглая кнопка плеера с уже привязанным действием. */
    private android.widget.ImageButton playerButton(int icon, int accent, int size,
                                                    String description, Action action) {
        android.widget.ImageButton view =
                Ui.roundButton(this, icon, accent, size, description);
        view.setOnClickListener(v -> withService(action));
        return view;
    }

    // --- Текст с подсветкой ---

    private void toggleText() {
        if (!store.isDownloaded(path)) { toast("Текст доступен только в скачанных книгах"); return; }
        showingText = !showingText;
        reader.setVisibility(showingText ? View.VISIBLE : View.GONE);
        textButton.setText(showingText ? "Скрыть текст" : "Текст книги");
        textToolbar.setVisibility(showingText ? View.VISIBLE : View.GONE);
        if (!showingText && fullText) setFullText(false);
        if (showingText) { ensureTimeline(currentIndex()); startBeat(); }
        else beat.removeCallbacksAndMessages(null);
    }

    private int currentIndex() {
        PlaybackService service = PlaybackService.get();
        return service == null ? store.savedIndex(path) : service.index();
    }

    /** Text is read exclusively from downloaded files, never over the network. */
    private void ensureTimeline(int index) {
        final int wanted = wholeBook ? -2 : index;
        if (timelineTrack == wanted || !store.isDownloaded(path)) return;
        timelineTrack = wanted;
        timeline = null;
        final int generation = ++textGeneration;
        reader.message(wholeBook ? "Собираю текст книги…" : "Открываю текст главы…");
        final boolean everything = wholeBook;
        final String[] titles = names.clone();
        pool.execute(() -> {
            Transcripts.Timeline built;
            if (everything) {
                org.json.JSONArray[] parts = new org.json.JSONArray[titles.length];
                for (int i = 0; i < titles.length; i++) {
                    if (Thread.currentThread().isInterrupted()) return;
                    parts[i] = Transcripts.load(store, path, i);
                    titles[i] = (i + 1) + ". " + titles[i];
                }
                built = Transcripts.build(parts, titles, 0);
            } else {
                built = Transcripts.build(new org.json.JSONArray[]{Transcripts.load(store, path, index)},
                        new String[]{""}, index);
            }
            runOnUiThread(() -> {
                if (isDestroyed() || generation != textGeneration) return;
                timeline = built;
                if (built.text.isEmpty()) reader.message("Для этой главы нет скачанного текста.");
                else {
                    reader.timeline(built);
                    reader.post(() -> highlight(true));
                }
            });
        });
    }

    private void startBeat() {
        beat.removeCallbacksAndMessages(null);
        beat.post(new Runnable() {
            @Override public void run() {
                highlight(false);
                if (showingText) beat.postDelayed(this, 200);
            }
        });
    }

    private void clearHighlight() { reader.clearHighlight(); }
    private void highlight() { highlight(false); }
    private void highlight(boolean force) {
        if (!showingText) return;
        PlaybackService service = PlaybackService.get();
        int index = currentIndex();
        ensureTimeline(index);
        if (timeline == null) return;
        double seconds = (service == null ? store.savedMillis(path) : service.position()) / 1000.0;
        reader.follow(index, seconds, following, force);
    }

    private void fillChapters() {
        chapters.removeAllViews();
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            LinearLayout row = new LinearLayout(this);
            row.setBackground(Ui.card(this, 0x3300F2FF));
            int pad = Ui.dp(this, 12);
            row.setPadding(pad, pad, pad, pad);
            row.setMinimumHeight(Ui.dp(this, 52));
            row.setClickable(true);
            row.setOnClickListener(v -> withService(service -> service.play(index)));
            row.addView(Ui.label(this, (i + 1) + ". " + names[i], Ui.TEXT, 15));

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = Ui.dp(this, 6);
            chapters.addView(row, params);
        }
    }

    /**
     * Служба поднимается не мгновенно, а ждать её фиксированной паузой —
     * гадание: на медленном телефоне триста миллисекунд не хватит, и книга
     * не доедет вовсе. Поэтому переспрашиваем, пока не появится.
     */
    private void waitForService(int attemptsLeft) {
        if (isFinishing() || isDestroyed()) return;
        if (PlaybackService.get() != null) {
            handOverBook();
            return;
        }
        if (attemptsLeft <= 0) {
            nowPlaying.setText("Не удалось запустить проигрывание");
            return;
        }
        new android.os.Handler(getMainLooper())
                .postDelayed(() -> waitForService(attemptsLeft - 1), 100);
    }

    private void handOverBook() {
        PlaybackService service = PlaybackService.get();
        if (service == null) return;
        String[] sources = new String[urls.length];
        for (int i = 0; i < urls.length; i++) {
            // Скачанная книга играет с диска, остальное — потоком с сервера
            File local = store.trackFile(path, i);
            sources[i] = (offline || local.exists())
                    ? local.getAbsolutePath() : api.trackUrl(urls[i]);
        }
        service.setBook(path, title, cover, sources, names, api.cookieHeader());
        service.setListener(this);
        onPlaybackChanged();
    }

    // --- Личные папки ---

    /**
     * «В папку»: список подборок с галочками — какие отмечены, в тех книга
     * и лежит. Ровно то же меню, что у карточки книги на сайте, и та же
     * запись на сервере, поэтому подборки в браузере и в приложении одни.
     */
    private void chooseFolders() {
        pool.execute(() -> {
            JSONObject data = null;
            String error = null;
            try {
                data = api.foldersForBook(path);
            } catch (Exception e) {
                error = Api.describe(e);
            }
            JSONObject finalData = data;
            String finalError = error;
            runOnUiThread(() -> {
                if (finalError != null) {
                    toast(finalError);
                    return;
                }
                showFolders(finalData);
            });
        });
    }

    private void showFolders(JSONObject data) {
        org.json.JSONArray all = data.optJSONArray("folders");
        org.json.JSONArray chosen = data.optJSONArray("selected");
        if (all == null || all.length() == 0) {
            toast("Папок пока нет — создайте их в разделе «Мои папки»");
            return;
        }

        final int[] ids = new int[all.length()];
        String[] names = new String[all.length()];
        boolean[] inside = new boolean[all.length()];
        for (int i = 0; i < all.length(); i++) {
            JSONObject folder = all.optJSONObject(i);
            if (folder == null) continue;
            ids[i] = folder.optInt("id");
            names[i] = folder.optString("name");
            for (int j = 0; chosen != null && j < chosen.length(); j++) {
                if (chosen.optInt(j) == ids[i]) inside[i] = true;
            }
        }
        final boolean[] wanted = inside.clone();

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("В какие папки положить")
                .setMultiChoiceItems(names, wanted,
                        (d, which, checked) -> wanted[which] = checked)
                .setPositiveButton("Сохранить", (d, w) -> {
                    // Шлём только изменения: лишние запросы к серверу
                    // ничего не меняют, но каждый — это ожидание сети
                    for (int i = 0; i < ids.length; i++) {
                        if (wanted[i] != inside[i]) saveFolder(ids[i], wanted[i]);
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void saveFolder(int id, boolean inside) {
        pool.execute(() -> {
            try {
                api.setInFolder(id, path, inside);
            } catch (Exception e) {
                String message = Api.describe(e);
                runOnUiThread(() -> toast(message));
            }
        });
    }

    private void toast(String text) {
        android.widget.Toast.makeText(this, text,
                android.widget.Toast.LENGTH_LONG).show();
    }

    // --- Скачивание ---

    private void toggleDownload() {
        if (Downloads.busy(path)) return;          // уже качается
        if (store.isDownloaded(path)) {
            if (showingText) toggleText();
            store.forget(path);
            Downloads.forget(path);
            // Текст ушёл вместе с книгой — следить больше не за чем
            timelineTrack = -1;
            textButton.setText("Текст книги");
            refreshDownloadButton();
            return;
        }
        Downloads.start(this, api, store, path, title, cover,
                urls.clone(), names.clone());
        refreshDownloadButton();
    }

    /**
     * Подпись кнопки собирается из состояния загрузчика, а не хранится в
     * экране. Экран пересобирается при каждом возврате, а книга качается
     * минутами — раньше после выхода и возврата кнопка снова предлагала
     * скачать уже качающуюся книгу.
     */
    private void refreshDownloadButton() {
        if (downloadButton == null) return;
        boolean saved = store.isDownloaded(path);
        textButton.setEnabled(saved);
        expandButton.setEnabled(saved);
        textButton.setAlpha(saved ? 1f : .5f);
        expandButton.setAlpha(saved ? 1f : .5f);
        if (!saved) textButton.setText("Текст после скачивания");
        Downloads.Progress progress = Downloads.progress(path);
        if (progress != null && progress.running()) {
            downloadButton.setEnabled(false);
            downloadButton.setText("Скачиваю " + (progress.done + 1)
                    + " из " + progress.total);
            return;
        }
        downloadButton.setEnabled(true);
        if (progress != null && progress.error != null) {
            downloadButton.setText("Не вышло: " + progress.error);
            return;
        }
        downloadButton.setText(store.isDownloaded(path)
                ? "Удалить с телефона" : "Скачать на телефон");
    }

    @Override
    public void onDownloadChanged(String which) {
        if (!path.equals(which)) return;
        refreshDownloadButton();
        if (!store.isDownloaded(path)) return;
        // Книга доехала: звук переключаем на файлы с телефона, а текст
        // теперь есть — открываем и его
        handOverBook();
        textButton.setText("Показать текст");
        textButton.setEnabled(true);
        textButton.setAlpha(1f);
    }

    // --- Состояние ---

    @Override
    public void onPlaybackChanged() {
        PlaybackService service = PlaybackService.get();
        if (service == null || !path.equals(service.path())) return;
        runOnUiThread(() -> {
            String problem = service.error();
            nowPlaying.setText(!problem.isEmpty() ? problem
                    : service.track().isEmpty() ? "Выберите главу" : service.track());
            boolean playing = service.isPlaying();
            playIcon.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
            playIcon.setContentDescription(playing ? "Пауза" : "Играть");
            int duration = service.duration();
            int position = service.position();
            if (!dragging && duration > 0) {
                bar.setProgress((int) ((long) position * 1000 / duration));
            }
            clock.setText(Ui.time(position) + " / " + Ui.time(duration));
            // Позицию и историю сохраняет PlaybackService даже в фоне.
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Пока экрана не было, книга могла докачаться
        refreshDownloadButton();
        PlaybackService service = PlaybackService.get();
        if (service != null && path.equals(service.path())) service.setListener(this);
        if (showingText) startBeat();
    }

    @Override protected void onPause() {
        beat.removeCallbacksAndMessages(null);
        super.onPause();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (!path.equals(orEmpty(intent.getStringExtra(EXTRA_PATH)))) {
            setIntent(intent); recreate();
        }
    }

    private interface Action {
        void run(PlaybackService service);
    }

    /**
     * Выполняет действие над службой, подняв её, если она успела уйти.
     *
     * Дослушав последнюю главу, служба останавливает сама себя — и экран
     * плеера оставался живым только на вид: кнопки и список глав молчали,
     * потому что службы, которой они шлют команды, больше не было.
     */
    private void withService(Action action) {
        PlaybackService service = PlaybackService.get();
        if (service != null) {
            action.run(service);
            return;
        }
        PlaybackService.start(this);
        revive(20, action);
    }

    private void revive(int attemptsLeft, Action action) {
        if (isFinishing() || isDestroyed()) return;
        PlaybackService service = PlaybackService.get();
        if (service != null) {
            // Новая служба про книгу ничего не знает — отдаём заново
            handOverBook();
            action.run(service);
            return;
        }
        if (attemptsLeft <= 0) {
            nowPlaying.setText("Не удалось запустить проигрывание");
            return;
        }
        new android.os.Handler(getMainLooper())
                .postDelayed(() -> revive(attemptsLeft - 1, action), 100);
    }

    private void askNotificationPermission() {
        // С Android 13 без разрешения уведомление не покажется, а вместе с ним
        // пропадёт и виджет на экране блокировки
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    @Override
    protected void onDestroy() {
        Downloads.unwatch(this);
        // Уходя с экрана, пробуем отдать накопленное сразу: ждать
        // следующего запуска ради уже готовой записи незачем
        history.flush(api, true);
        beat.removeCallbacksAndMessages(null);
        PlaybackService service = PlaybackService.get();
        if (service != null) service.clearListener(this);
        pool.shutdownNow();
        super.onDestroy();
    }
}
