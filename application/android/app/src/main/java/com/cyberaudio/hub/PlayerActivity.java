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

    private TextView titleView;
    private android.widget.ImageView coverView;
    private Button requestText;
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
    private ScrollView textScroll;
    private TextView textView;
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

        // Текст лежит рядом со скачанной книгой. Пока её нет на телефоне,
        // следить не за чем: тянуть текст с сервера отдельно значило бы
        // держать книгу наполовину онлайн, наполовину офлайн
        textRow = Ui.row(this);
        textButton = Ui.button(this, "Текст книги", Ui.PRIMARY);
        textButton.setOnClickListener(v -> toggleText());
        textRow.addView(textButton, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button expand = Ui.button(this, "Развернуть", Ui.SECONDARY);
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
        checks.addView(wholeBox);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        gap.leftMargin = Ui.dp(this, 12);
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

        requestText = Ui.button(this, "Запросить текст книги", Ui.DIM);
        requestText.setVisibility(View.GONE);
        requestText.setOnClickListener(v -> {
            requestText.setEnabled(false);
            pool.execute(() -> {
                try {
                    api.requestTranscript(path);
                    runOnUiThread(() -> requestText.setText("Запрос отправлен"));
                } catch (Exception e) {
                    runOnUiThread(() -> { requestText.setEnabled(true); toast(Api.describe(e)); });
                }
            });
        });
        textToolbar.addView(requestText);

        textToolbar.setVisibility(View.GONE);
        Ui.add(box, textToolbar, 8);

        textView = Ui.label(this, "", Ui.TEXT, 16);
        textView.setLineSpacing(Ui.dp(this, 4), 1.15f);
        textView.setPadding(Ui.dp(this, 12), Ui.dp(this, 12),
                Ui.dp(this, 12), Ui.dp(this, 12));
        textView.setBackground(Ui.card(this, 0));
        textView.setOnTouchListener(this::tapWord);
        textScroll = new ScrollView(this);
        textScroll.addView(textView);
        textScroll.setVisibility(View.GONE);
        // Ограничиваем высоту: иначе текст выдавил бы управление за экран
        LinearLayout.LayoutParams textBox = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 320));
        textBox.bottomMargin = Ui.dp(this, 14);
        box.addView(textScroll, textBox);

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
        Ui.screen(this, outerScroll, -1);
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
        fullText = full;
        // Перехват держим включённым ровно пока текст развёрнут: иначе
        // «назад» на обычном экране плеера перестала бы закрывать книгу
        if (backToPlayer != null) backToPlayer.setEnabled(full);
        if (full && !showingText) toggleText();

        int hidden = full ? View.GONE : View.VISIBLE;
        topRow.setVisibility(hidden);
        titleView.setVisibility(hidden);
        coverView.setVisibility(hidden);
        nowPlaying.setVisibility(hidden);
        bar.setVisibility(hidden);
        clock.setVisibility(hidden);
        textRow.setVisibility(hidden);
        chapters.setVisibility(hidden);
        downloadButton.setVisibility(offline ? View.GONE : hidden);
        if (folderButton != null) folderButton.setVisibility(hidden);
        collapseButton.setVisibility(full ? View.VISIBLE : View.GONE);
        // Полной подписи рядом с кнопкой не хватает ширины, а смысл
        // галочки от короткой не теряется
        followBox.setText(full ? "Следить" : "Следить за текстом");

        LinearLayout.LayoutParams params =
                (LinearLayout.LayoutParams) textScroll.getLayoutParams();
        if (full) {
            // Ноль плюс вес: текст забирает всё, что осталось от управления
            params.height = 0;
            params.weight = 1f;
        } else {
            params.height = Ui.dp(this, 320);
            params.weight = 0f;
        }
        textScroll.setLayoutParams(params);
        outerScroll.scrollTo(0, 0);
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
        showingText = !showingText;
        textScroll.setVisibility(showingText ? View.VISIBLE : View.GONE);
        textButton.setText(showingText ? "Скрыть текст" : "Показать текст");
        textToolbar.setVisibility(showingText ? View.VISIBLE : View.GONE);
        if (!showingText && fullText) setFullText(false);
        if (showingText) {
            PlaybackService service = PlaybackService.get();
            ensureTimeline(service == null ? 0 : service.index());
            startBeat();
        } else {
            beat.removeCallbacksAndMessages(null);
        }
    }

    /**
     * Тычок по слову перематывает на него — так же, как клик на сайте.
     *
     * Слушаем отпускание, а не нажатие: иначе прокрутка текста пальцем
     * каждый раз уводила бы звук в случайное место.
     */
    private boolean tapWord(View view, android.view.MotionEvent event) {
        if (event.getAction() != android.view.MotionEvent.ACTION_UP) return false;
        if (timeline == null) return false;
        android.text.Layout layout = textView.getLayout();
        if (layout == null) return false;

        int line = layout.getLineForVertical(
                (int) event.getY() - textView.getTotalPaddingTop()
                        + textView.getScrollY());
        int offset = layout.getOffsetForHorizontal(line,
                event.getX() - textView.getTotalPaddingLeft());
        double[] at = timeline.timeAt(offset);
        if (at == null) return false;

        final int chapter = (int) at[0];
        final int millis = (int) (at[1] * 1000);
        withService(service -> {
            if (service.index() != chapter) {
                service.play(chapter);
                // Главу ещё готовят: перематываем, когда её длительность
                // станет известна, иначе seek уйдёт в пустоту
                beat.postDelayed(() -> withService(s -> s.seekTo(millis)), 400);
            } else {
                service.seekTo(millis);
            }
        });
        view.performClick();
        return true;
    }

    /**
     * Готовит текст: сначала с телефона, иначе с сервера.
     *
     * В режиме «вся книга» собираем все главы разом — так текст читается
     * подряд и подсветка не обрывается на границе главы. Глав бывает под
     * сотню, поэтому сборка идёт в фоне, а экран ждёт с подписью.
     */
    private void ensureTimeline(int index) {
        // В режиме всей книги текст один на все главы, пересобирать нечего
        final int wanted = wholeBook ? -2 : index;
        if (timelineTrack == wanted) return;
        timelineTrack = wanted;
        timeline = null;
        litWord = null;
        litEdge = -1;
        textView.setText(wholeBook ? "Собираю текст книги..." : "Загружаю текст...");
        final boolean everything = wholeBook;
        final int count = names.length;
        pool.execute(() -> {
            Transcripts.Timeline built;
            if (everything) {
                org.json.JSONArray[] parts = new org.json.JSONArray[count];
                String[] titles = new String[count];
                for (int i = 0; i < count; i++) {
                    parts[i] = chapterText(i);
                    titles[i] = (i + 1) + ". " + names[i];
                }
                built = Transcripts.build(parts, titles, 0);
            } else {
                built = Transcripts.build(
                        new org.json.JSONArray[]{
                                chapterText(index)},
                        new String[]{""}, index);
            }
            final Transcripts.Timeline ready = built;
            runOnUiThread(() -> {
                if (timelineTrack != wanted) return;   // режим уже сменился
                if (isDestroyed()) return;
                requestText.setVisibility(ready.isEmpty() && !offline ? View.VISIBLE : View.GONE);
                if (ready.isEmpty()) {
                    textView.setText(everything
                            ? "У этой книги нет текста."
                            : "Для этой главы текста нет.");
                    timeline = null;
                    return;
                }
                timeline = ready;
                textView.setText(marked(ready.text, -1, null));
            });
        });
    }

    private org.json.JSONArray chapterText(int index) {
        org.json.JSONArray data = Transcripts.load(store, path, index);
        if ((data == null || data.length() == 0) && !offline && !Thread.currentThread().isInterrupted()) {
            try { Transcripts.save(api, store, path, index); } catch (Exception ignored) { }
            data = Transcripts.load(store, path, index);
        }
        return data;
    }

    /**
     * Подсветку двигаем чаще, чем обновляется остальной экран: раз в
     * секунду слово «прыгает» через несколько соседних, и следить
     * за строкой становится невозможно.
     */
    private void startBeat() {
        beat.removeCallbacksAndMessages(null);
        beat.post(new Runnable() {
            @Override
            public void run() {
                highlight();
                if (showingText) beat.postDelayed(this, 200);
            }
        });
    }

    /** Убирает всю разметку: текст читают глазами сами. */
    private void clearHighlight() {
        litWord = null;
        litEdge = -1;
        if (timeline != null) textView.setText(marked(timeline.text, -1, null));
    }

    private void highlight() {
        PlaybackService service = PlaybackService.get();
        if (service == null) return;
        ensureTimeline(service.index());
        if (timeline == null) return;
        // Без галочки текст стоит нетронутым — ни подсветки, ни затемнения
        if (!following) return;
        ensureTimeline(service.index());
        if (timeline == null) return;

        int chapter = service.index();
        double seconds = service.position() / 1000.0;
        int[] word = timeline.wordAt(chapter, seconds);
        int edge = timeline.spokenUntil(chapter, seconds);
        if (java.util.Arrays.equals(word, litWord) && edge == litEdge) return;
        litWord = word;
        litEdge = edge;

        textView.setText(marked(timeline.text, edge, word));
        if (word != null) scrollToWord(word[0]);
    }

    /**
     * Собирает размеченный текст: погашенное прочитанное, подсвеченное
     * слово и — в режиме всей книги — выделение глав, как на сайте.
     *
     * edge < 0 и word == null означают «без разметки»: так текст выглядит
     * с выключенной галочкой слежения.
     */
    private CharSequence marked(String source, int edge, int[] word) {
        android.text.SpannableString out = new android.text.SpannableString(source);

        // Прочитанное гаснет — как .word.is-spoken на сайте
        if (edge > 0) {
            out.setSpan(new android.text.style.ForegroundColorSpan(Ui.SPOKEN),
                    0, Math.min(edge, out.length()),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        // Заголовки глав: звучащая — зелёная, как .is-current на сайте,
        // остальные приглушены. Без этого в сплошном тексте всей книги
        // невозможно понять, где ты находишься
        if (wholeBook && timeline != null) {
            PlaybackService service = PlaybackService.get();
            int now = service == null ? -1 : service.index();
            for (int i = 0; i < timeline.headings(); i++) {
                int[] span = timeline.heading(i);
                int colour = span[2] == now ? CURRENT_CHAPTER : Ui.SECONDARY;
                out.setSpan(new android.text.style.ForegroundColorSpan(colour),
                        span[0], span[1], android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                out.setSpan(new android.text.style.StyleSpan(
                                android.graphics.Typeface.BOLD),
                        span[0], span[1], android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }

        if (word != null) {
            out.setSpan(new android.text.style.BackgroundColorSpan(
                            android.graphics.Color.argb(70, 0, 242, 255)),
                    word[0], word[1], android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            out.setSpan(new android.text.style.ForegroundColorSpan(Ui.PRIMARY),
                    word[0], word[1], android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return out;
    }

    /** Держим подсвеченное слово в поле зрения, не дёргая экран зря. */
    private void scrollToWord(int offset) {
        android.text.Layout layout = textView.getLayout();
        if (layout == null) return;
        int line = layout.getLineForOffset(offset);
        int y = layout.getLineTop(line);
        int visible = textScroll.getHeight();
        int current = textScroll.getScrollY();
        // Двигаем, только когда слово ушло из середины экрана
        if (y < current + visible / 4 || y > current + visible * 3 / 4) {
            textScroll.smoothScrollTo(0, Math.max(0, y - visible / 3));
        }
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
        service.setBook(title, sources, names, api.cookieHeader());
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

        new android.app.AlertDialog.Builder(this)
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
            store.forget(path);
            Downloads.forget(path);
            // Текст ушёл вместе с книгой — следить больше не за чем
            if (showingText) toggleText();
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
        if (service == null) return;
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
            // Позицию храним у себя: скачанную книгу слушают без сети,
            // и отправлять прогресс на сервер в этот момент некуда.
            // Отдельно копим историю — она уедет на сервер, когда связь
            // появится, вместе с дослушанными главами
            store.savePosition(path, service.index(), position);
            rememberProgress(service.index(), position, duration);
        });
    }

    /**
     * Запоминает, где человек остановился, и отмечает дослушанную главу.
     *
     * Главу засчитываем за десять секунд до конца — ровно как плеер на
     * сайте: последние секунды это чаще всего тишина или заставка, и
     * ждать их значило бы терять засчитанные главы на каждом переходе.
     */
    private void rememberProgress(int index, int position, int duration) {
        if (duration <= 0) return;
        boolean tail = duration - position <= 10_000;
        boolean last = index >= names.length - 1;
        history.note(path, title, cover, index, position, tail && last);
        if (tail) history.complete(path, index);
        history.flush(api, false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Пока экрана не было, книга могла докачаться
        refreshDownloadButton();
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
