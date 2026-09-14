package com.cyberaudio.hub;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.media.app.NotificationCompat.MediaStyle;
import androidx.media.session.MediaButtonReceiver;

import java.io.IOException;

/**
 * Воспроизведение и виджет плеера в шторке и на экране блокировки.
 *
 * Служба, а не просто плеер в экране: иначе Android выгрузит приложение,
 * как только человек свернёт его или заблокирует телефон, и звук оборвётся
 * на середине главы.
 *
 * Виджет на локскрине появляется не сам по себе. Нужны три вещи вместе:
 * MediaSession с метаданными и состоянием, уведомление со стилем MediaStyle,
 * ссылающееся на токен этой сессии, и служба на переднем плане. Уберите
 * любую из трёх — и в шторке останется просто строчка текста.
 */
public class PlaybackService extends Service {

    public static final String ACTION_PLAY_PAUSE = "com.cyberaudio.hub.PLAY_PAUSE";
    public static final String ACTION_NEXT = "com.cyberaudio.hub.NEXT";
    public static final String ACTION_PREV = "com.cyberaudio.hub.PREV";
    public static final String ACTION_STOP = "com.cyberaudio.hub.STOP";
    public static final String ACTION_REWIND = "com.cyberaudio.hub.REWIND";
    public static final String ACTION_FORWARD = "com.cyberaudio.hub.FORWARD";

    /**
     * Шаг перемотки. Пять секунд — чтобы переспросить упущенную фразу:
     * у книги это куда нужнее, чем прыжок на целую главу, а именно им
     * ограничивался виджет раньше.
     */
    public static final int SEEK_STEP = 5000;

    private static final String CHANNEL = "cah_playback";
    private static final int NOTE_ID = 1;

    /**
     * Единственный экземпляр: экранам нужно спрашивать у службы состояние,
     * а связывание через Binder ради пары полей было бы церемонией.
     */
    private static PlaybackService current;

    public static PlaybackService get() {
        return current;
    }

    /** Что слушают сейчас — общее для службы и экранов. */
    public interface Listener {
        void onPlaybackChanged();
    }

    private MediaPlayer player;
    private MediaSessionCompat session;
    private Listener listener;

    private String bookTitle = "";
    private String bookPath = "";
    private String bookCover = "";
    private Store store;
    private History history;
    private Api api;
    private int resumeMillis;
    private boolean playWhenReady;
    private String trackTitle = "";
    private String lastError = "";
    // Подготовлен ли плеер. Без этого признака duration() и position()
    // спрашивают у неподготовленного MediaPlayer — а тот отвечает не
    // исключением, которое можно поймать, а ошибкой -38 в onError. Ровно
    // из-за этого глава «не открывалась»: виноват был не файл, а опрос.
    private boolean prepared = false;
    private int trackIndex = 0;
    private final Handler ticker = new Handler(Looper.getMainLooper());

    // Дорожки текущей книги: адреса или локальные файлы
    private String[] sources = new String[0];
    private String[] names = new String[0];
    private String cookie = "";

    @Override
    public void onCreate() {
        super.onCreate();
        current = this;
        store = new Store(this);
        history = new History(this);
        api = new Api(this);
        session = new MediaSessionCompat(this, "CyberAudioHub");
        session.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                resume();
            }

            @Override
            public void onPause() {
                pause();
            }

            @Override
            public void onSkipToNext() {
                next();
            }

            @Override
            public void onSkipToPrevious() {
                previous();
            }

            @Override
            public void onSeekTo(long pos) {
                seekTo((int) pos);
            }

            @Override
            public void onRewind() {
                seekBy(-SEEK_STEP);
            }

            @Override
            public void onFastForward() {
                seekBy(SEEK_STEP);
            }

            @Override
            public void onCustomAction(String action, Bundle extras) {
                // Сюда приходят нажатия кнопок перемотки, которые система
                // рисует на экране блокировки с Android 13
                if (ACTION_REWIND.equals(action)) seekBy(-SEEK_STEP);
                else if (ACTION_FORWARD.equals(action)) seekBy(SEEK_STEP);
            }

            @Override
            public void onStop() {
                stopEverything();
            }
        });
        session.setActive(true);
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Уходим на передний план немедленно, ещё до разбора команды.
        //
        // Это не перестраховка: startForegroundService обязывает вызвать
        // startForeground в течение пяти секунд, иначе система убивает
        // приложение с ForegroundServiceDidNotStartInTimeException. А экран
        // плеера поднимает службу сразу при открытии — то есть задолго до
        // того, как человек нажмёт «играть».
        pushState();

        // Нажатия на кнопки виджета приходят сюда же
        MediaButtonReceiver.handleIntent(session, intent);
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case ACTION_PLAY_PAUSE:
                    if (isPlaying()) pause(); else resume();
                    break;
                case ACTION_NEXT:
                    next();
                    break;
                case ACTION_PREV:
                    previous();
                    break;
                case ACTION_REWIND:
                    seekBy(-SEEK_STEP);
                    break;
                case ACTION_FORWARD:
                    seekBy(SEEK_STEP);
                    break;
                case ACTION_STOP:
                    stopEverything();
                    break;
                default:
                    break;
            }
        }
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        rememberPlayback();
        ticker.removeCallbacksAndMessages(null);
        releasePlayer();
        if (session != null) {
            session.setActive(false);
            session.release();
        }
        current = null;
        super.onDestroy();
    }

    // --- Управление ---

    public void setListener(Listener value) {
        listener = value;
    }

    public void clearListener(Listener value) {
        if (listener == value) listener = null;
    }

    /** Загружает книгу целиком: адреса дорожек и их названия. */
    public void setBook(String path, String title, String cover, String[] trackSources, String[] trackNames,
                        String sessionCookie) {
        String nextPath = path == null ? "" : path;
        boolean changed = !nextPath.equals(bookPath);
        boolean useLocal = !changed && player != null && prepared && trackIndex < sources.length
                && trackSources != null && trackIndex < trackSources.length
                && sources[trackIndex].startsWith("http") && !trackSources[trackIndex].startsWith("http");
        int localPosition = useLocal ? position() : 0;
        boolean wasPlaying = isPlaying();
        if (changed) {
            rememberPlayback();
            ticker.removeCallbacksAndMessages(null);
            releasePlayer();
            listener = null;
            lastError = "";
            playWhenReady = false;
        }
        bookPath = nextPath;
        bookCover = cover == null ? "" : cover;
        bookTitle = title == null ? "" : title;
        sources = trackSources == null ? new String[0] : trackSources;
        names = trackNames == null ? new String[0] : trackNames;
        cookie = sessionCookie == null ? "" : sessionCookie;
        if (changed) {
            trackIndex = Math.max(0, Math.min(store.savedIndex(bookPath), sources.length - 1));
            resumeMillis = Math.max(0, store.savedMillis(bookPath));
            trackTitle = trackIndex < names.length ? names[trackIndex] : "";
        }
        if (useLocal) { loadTrack(trackIndex, localPosition); if (!wasPlaying) pause(); }
        pushState();
    }

    public void play(int index) {
        loadTrack(index, 0);
    }

    public void playAt(int index, int millis) {
        loadTrack(index, millis);
    }

    private void loadTrack(int index, int offset) {
        if (index < 0 || index >= sources.length) return;
        rememberPlayback();
        ticker.removeCallbacksAndMessages(null);
        trackIndex = index;
        trackTitle = index < names.length ? names[index] : "";
        prepared = false;
        releasePlayer();
        resumeMillis = Math.max(0, offset);
        playWhenReady = true;

        PlaybackLink.save(this, bookPath, bookTitle, bookCover, sources, names);
        player = new MediaPlayer();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build());
        try {
            String source = sources[index];
            if (source.startsWith("http")) {
                // Простая строка, а не setDataSource(Context, Uri, headers):
                // тот вариант сперва лезет в ContentResolver, на http-адресе
                // спотыкается («No content provider») и оставляет плеер в
                // состоянии, из которого prepareAsync падает с ошибкой -38.
                // Раздача мультимедиа входа не требует, заголовки не нужны.
                player.setDataSource(source);
            } else {
                // Скачанная книга: расшифровываем контейнер на лету
                player.setDataSource(new ContainerDataSource(new java.io.File(source)));
            }

            // Без этого обработчика ошибка превращается в onCompletion, тот
            // зовёт next() — и книга за секунду «пролистывается» до конца,
            // молча, будто все главы прослушаны. Ошибку надо показать.
            player.setOnErrorListener((mp, what, extra) -> {
                lastError = "Глава не открылась (" + what + "/" + extra + ")";
                releasePlayer();
                pushState();
                return true;              // обработали сами, дальше не пускаем
            });
            player.setOnCompletionListener(mp -> next());
            player.setOnPreparedListener(mp -> {
                lastError = "";
                // Не воспроизводим начало главы, пока восстанавливается позиция.
                if (resumeMillis > 0) {
                    mp.setOnSeekCompleteListener(ready -> {
                        ready.setOnSeekCompleteListener(null);
                        startPrepared(ready);
                    });
                    mp.seekTo(Math.min(resumeMillis, Math.max(0, mp.getDuration() - 1)));
                } else startPrepared(mp);
            });
            player.prepareAsync();
        } catch (IOException | IllegalArgumentException | IllegalStateException e) {
            lastError = "Не удалось открыть главу: " + e.getMessage();
            releasePlayer();
        }
        pushState();
    }

    private void startPrepared(MediaPlayer ready) {
        if (ready != player) return;
        prepared = true;
        resumeMillis = 0;
        if (playWhenReady) ready.start();
        pushState();
        if (playWhenReady) startTicker();
    }

    public void resume() {
        playWhenReady = true;
        if (player != null && prepared) {
            player.start();
            startTicker();
        } else if (player == null && sources.length > 0) {
            loadTrack(trackIndex, resumeMillis);
        }
        pushState();
    }

    public void pause() {
        playWhenReady = false;
        if (isPlaying()) player.pause();
        ticker.removeCallbacksAndMessages(null);
        pushState();
    }

    public void next() {
        if (trackIndex + 1 < sources.length) play(trackIndex + 1);
        else stopEverything();
    }

    public void previous() {
        // Как в любом плеере: первые секунды — к началу главы, дальше — назад
        if (position() > 5000) seekTo(0);
        else if (trackIndex > 0) play(trackIndex - 1);
        else seekTo(0);
    }

    /** Сдвиг относительно текущего места, с упором в границы главы. */
    public void seekBy(int deltaMillis) {
        if (player == null || !prepared) return;
        int target = position() + deltaMillis;
        int limit = duration();
        if (target < 0) target = 0;
        if (limit > 0 && target > limit) target = limit;
        seekTo(target);
    }

    public void seekTo(int millis) {
        if (player != null && prepared) player.seekTo(Math.max(0, millis));
        pushState();
    }

    public void stopEverything() {
        rememberPlayback();
        resumeMillis = position();
        releasePlayer();
        ticker.removeCallbacksAndMessages(null);
        // stopForeground(boolean) объявлен устаревшим с Android 13;
        // константа доступна с API 24, а ниже мы не опускаемся
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        if (listener != null) listener.onPlaybackChanged();
    }

    private void releasePlayer() {
        prepared = false;
        if (player != null) {
            try {
                player.release();
            } catch (IllegalStateException ignored) {
                // Уже освобождён — ничего страшного
            }
            player = null;
        }
    }

    // --- Состояние ---

    public boolean isPlaying() {
        try {
            return player != null && prepared && player.isPlaying();
        } catch (IllegalStateException e) {
            return false;
        }
    }

    public int position() {
        if (player == null || !prepared) return resumeMillis;
        try {
            return player.getCurrentPosition();
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    public int duration() {
        if (player == null || !prepared) return 0;
        try {
            return Math.max(0, player.getDuration());
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    public int index() {
        return trackIndex;
    }

    public String track() {
        return trackTitle;
    }

    public String book() {
        return bookTitle;
    }

    public String path() {
        return bookPath;
    }

    // Сохранение принадлежит службе: оно продолжается и без открытого экрана.
    private void rememberPlayback() {
        if (!prepared || bookPath.isEmpty()) return;
        int millis = position();
        int length = duration();
        store.savePosition(bookPath, trackIndex, millis);
        if (length <= 0) return;
        boolean tail = length - millis <= 10_000;
        history.note(bookPath, bookTitle, bookCover, trackIndex, millis,
                tail && trackIndex >= sources.length - 1);
        if (tail) history.complete(bookPath, trackIndex);
        history.flush(api, false);
    }

    /** Что пошло не так на последней главе. Пусто — всё в порядке. */
    public String error() {
        return lastError;
    }

    /** Пока идёт звук, раз в секунду обновляем экран и полосу в виджете. */
    private void startTicker() {
        ticker.removeCallbacksAndMessages(null);
        ticker.postDelayed(new Runnable() {
            @Override
            public void run() {
                rememberPlayback();
                if (listener != null) listener.onPlaybackChanged();
                if (isPlaying()) ticker.postDelayed(this, 1000);
            }
        }, 1000);
    }

    // --- Виджет ---

    private void createChannel() {
        // Проверки версии тут нет: minSdk 26, каналы есть всегда
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, "Воспроизведение", NotificationManager.IMPORTANCE_LOW);
        // На заблокированном экране показываем содержимое целиком, иначе
        // вместо названия книги будет «уведомление скрыто»
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        channel.setShowBadge(false);
        channel.setSound(null, null);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    private PendingIntent button(String action) {
        Intent intent = new Intent(this, PlaybackService.class).setAction(action);
        // FLAG_IMMUTABLE обязателен с Android 12 и доступен с API 23
        return PendingIntent.getService(this, action.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Обновляет метаданные, состояние и само уведомление. */
    private void pushState() {
        rememberPlayback();
        boolean playing = isPlaying();

        session.setMetadata(new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, trackTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, bookTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, bookTitle)
                // Без длительности система не рисует полосу перемотки
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration())
                .build());

        /*
         * С Android 13 система рисует медиа-кнопки сама, по действиям
         * сессии, а не по кнопкам уведомления. Штатные ACTION_REWIND и
         * ACTION_FAST_FORWARD она при этом не показывает — поэтому на
         * таких телефонах на заблокированном экране оставалось три кнопки
         * вместо пяти, без перемотки. Добавляем перемотку своими
         * действиями: их система показывает наравне с остальными.
         */
        session.setPlaybackState(new PlaybackStateCompat.Builder()
                .addCustomAction(new PlaybackStateCompat.CustomAction.Builder(
                        ACTION_REWIND, "На 5 секунд назад",
                        R.drawable.ic_rewind).build())
                .addCustomAction(new PlaybackStateCompat.CustomAction.Builder(
                        ACTION_FORWARD, "На 5 секунд вперёд",
                        R.drawable.ic_forward).build())
                .setActions(PlaybackStateCompat.ACTION_PLAY
                        | PlaybackStateCompat.ACTION_PAUSE
                        | PlaybackStateCompat.ACTION_PLAY_PAUSE
                        | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                        | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackStateCompat.ACTION_SEEK_TO
                        | PlaybackStateCompat.ACTION_REWIND
                        | PlaybackStateCompat.ACTION_FAST_FORWARD
                        | PlaybackStateCompat.ACTION_STOP)
                .setState(playing ? PlaybackStateCompat.STATE_PLAYING
                                  : PlaybackStateCompat.STATE_PAUSED,
                        position(), 1.0f)
                .build());

        Intent open = playerIntent();
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;

        Notification note = new NotificationCompat.Builder(this, CHANNEL)
                .setContentTitle(trackTitle)
                .setContentText(bookTitle)
                .setSmallIcon(R.drawable.ic_note)
                .setContentIntent(PendingIntent.getActivity(this, 0, open, flags))
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setOngoing(playing)
                .addAction(R.drawable.ic_prev, "Предыдущая глава", button(ACTION_PREV))
                .addAction(R.drawable.ic_rewind, "На 5 секунд назад", button(ACTION_REWIND))
                .addAction(playing ? R.drawable.ic_pause : R.drawable.ic_play,
                        playing ? "Пауза" : "Играть", button(ACTION_PLAY_PAUSE))
                .addAction(R.drawable.ic_forward, "На 5 секунд вперёд", button(ACTION_FORWARD))
                .addAction(R.drawable.ic_next, "Следующая глава", button(ACTION_NEXT))
                .setStyle(new MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        // В свёрнутой шторке помещаются только три кнопки.
                        // Для книги полезнее перемотка, чем прыжки по главам:
                        // переспросить фразу хочется куда чаще.
                        .setShowActionsInCompactView(1, 2, 3))
                .build();

        startForeground(NOTE_ID, note);
        if (listener != null) listener.onPlaybackChanged();
    }

    public static void start(Context context) {
        context.startForegroundService(new Intent(context, PlaybackService.class));
    }

    public Intent playerIntent() {
        return new Intent(this, PlayerActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(PlayerActivity.EXTRA_PATH, bookPath)
                .putExtra(PlayerActivity.EXTRA_TITLE, bookTitle)
                .putExtra(PlayerActivity.EXTRA_COVER, bookCover)
                .putExtra(PlayerActivity.EXTRA_URLS, sources)
                .putExtra(PlayerActivity.EXTRA_NAMES, names)
                .putExtra(PlayerActivity.EXTRA_OFFLINE, store.isDownloaded(bookPath));
    }
}
