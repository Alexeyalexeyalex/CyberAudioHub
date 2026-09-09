document.addEventListener('DOMContentLoaded', () => {
    const playerContainer = document.getElementById('player-container');
    const audioPlayer = document.getElementById('audio-player');
    const backButton = document.getElementById('back-button');
    const API_ENDPOINT = '/api/browse';
    // Прогресс гостя хранится в localStorage, владелец ключа — auth.js
    const localStore = window.CyberAuth.localProgress;

    if (!playerContainer || !audioPlayer) return;

    const stripExtension = (name) => name.replace(/\.(mp3|ogg|wav|m4a|flac|opus|aac)$/i, '');
    const prettyName = (name) => stripExtension(name).replace(/_/g, ' ');

    const initPlayer = async () => {
        const urlParams = new URLSearchParams(window.location.search);
        const albumPath = urlParams.get('path');
        if (!albumPath) {
            playerContainer.innerHTML = '<h2>ОШИБКА: ПУТЬ К АЛЬБОМУ НЕ УКАЗАН</h2><p>Пожалуйста, вернитесь на главную и выберите альбом.</p>';
            return;
        }

        try {
            const [response] = await Promise.all([
                fetch(`${API_ENDPOINT}?path=${encodeURIComponent(albumPath)}`),
                window.CyberAuth.ready
            ]);
            if (!response.ok) throw new Error('Альбом не найден или произошла ошибка.');
            const currentAlbum = await response.json();

            if (currentAlbum.type !== 'album') {
                playerContainer.innerHTML = '<h2>ОШИБКА: УКАЗАННЫЙ ПУТЬ НЕ ЯВЛЯЕТСЯ АЛЬБОМОМ</h2>';
                return;
            }

            // Кнопка «Назад» ведёт в родительскую папку, а не всегда в корень
            if (backButton) {
                const parent = currentAlbum.parent || '';
                backButton.href = parent ? `/?path=${encodeURIComponent(parent)}` : '/';
            }

            // --- Состояние плеера ---
            let currentTrackIndex = 0;
            let isShuffle = false;
            let lastSaveAt = -Infinity;
            let textMode = false;
            let transcriptState = currentAlbum.transcript || { ready: false };
            let wordIndex = [];        // плоский список слов текущей главы со временем
            let loadedTextTrack = null;
            let activeWord = -1;
            let rafId = null;
            let follow = true;
            let followPausedUntil = 0;
            // Сдвиг подсветки в секундах. Больше нуля — подсвечивать позже:
            // тайминги распознавания обычно немного опережают речь.
            let textOffset = 0;
            // Достижение выдаётся на последних секундах главы. Помним, за какие
            // главы уже просили, чтобы не дёргать сервер каждый тик.
            const claimedTracks = new Set();
            const CLAIM_TAIL = 10;
            // Сплошное чтение: в тексте вся книга, а не одна глава
            let wholeBook = false;
            const WHOLE_STORE = 'cyberAudioWholeBook';
            const SYNC_STORE = 'cyberAudioTextOffset';
            const SYNC_STEP = 0.25;
            const SYNC_LIMIT = 5;

            let trackDurations = Array.isArray(currentAlbum.durations)
                && currentAlbum.durations.length === currentAlbum.tracks.length
                ? currentAlbum.durations.slice()
                : null;

            function loadPlayerUI() {
                playerContainer.innerHTML = `
                    <div class="player-album-art-container">
                        <img src="${currentAlbum.cover}" alt="" aria-hidden="true" class="player-album-art-blur" onerror="this.remove();">
                        <img src="${currentAlbum.cover}" alt="${currentAlbum.title}" class="player-album-art" onerror="this.onerror=null;this.src='/static/assets/default_cover.png';">
                    </div>
                    <div class="player-details">
                        <h3>${currentAlbum.title}</h3>
                        <p id="current-track-title">Название трека</p>
                        <p class="book-remaining" id="book-remaining" hidden></p>
                        <div class="progress-container" id="progress-container"><div class="progress-bar" id="progress-bar"></div></div>
                        <div class="time-stamps"><span id="current-time">0:00</span><span id="total-duration">0:00</span></div>
                        <div class="player-controls">
                            <button class="control-btn shuffle-btn" id="shuffle-btn" title="Перемешать"><i class="fas fa-shuffle"></i></button>
                            <button class="control-btn" id="prev-btn" title="Предыдущий трек"><i class="fas fa-backward-step"></i></button>
                            <button class="control-btn seek-btn" id="rewind-btn" title="-5 секунд"><i class="fas fa-rotate-left"></i></button>
                            <button class="control-btn play-btn" id="play-btn" title="Воспроизвести/Пауза"><i class="fas fa-play" id="play-icon"></i></button>
                            <button class="control-btn seek-btn" id="forward-btn" title="+5 секунд"><i class="fas fa-rotate-right"></i></button>
                            <button class="control-btn" id="next-btn" title="Следующий трек"><i class="fas fa-forward-step"></i></button>
                            <button class="control-btn text-btn" id="text-btn" title="Текстовая версия" hidden><i class="fas fa-align-left"></i></button>
                            <button class="control-btn request-btn" id="request-text-btn" title="Запросить текстовый формат" hidden><i class="fas fa-hand"></i></button>
                        </div>
                        <p class="request-note" id="request-note" hidden></p>
                        <ol class="track-list" id="track-list"></ol>
                    </div>`;
                loadTrackList();
                buildMiniPlayer();
                addEventListeners();
                applyTranscriptState(transcriptState);
                setupMediaSession();
                loadTrack(currentTrackIndex);
                syncPlayButton();
            }

            function loadTrackList() {
                const trackList = document.getElementById('track-list');
                trackList.innerHTML = '';
                currentAlbum.tracks.forEach((track, index) => {
                    const li = document.createElement('li');
                    li.className = 'track-item';
                    li.dataset.index = index;

                    const num = document.createElement('span');
                    num.className = 'track-number';
                    num.textContent = (index + 1).toString().padStart(2, '0');

                    const title = document.createElement('span');
                    title.className = 'track-name';
                    title.textContent = prettyName(track.name);

                    li.append(num, title);

                    // Скачивание отдельной главы, если админ его разрешил
                    if (window.CyberAuth && CyberAuth.downloadsEnabled()) {
                        const dl = document.createElement('a');
                        dl.className = 'track-download';
                        dl.href = `/api/download/track?path=${encodeURIComponent(albumPath)}`
                            + `&track=${index}`;
                        dl.title = 'Скачать эту главу';
                        dl.setAttribute('download', '');
                        dl.innerHTML = '<i class="fas fa-download"></i>';
                        // Клик по значку не должен запускать главу
                        dl.addEventListener('click', (e) => e.stopPropagation());
                        li.appendChild(dl);
                    }

                    li.addEventListener('click', () => { currentTrackIndex = index; loadTrack(currentTrackIndex); playTrack(); });
                    trackList.appendChild(li);
                });
            }

            function loadTrack(index) {
                const track = currentAlbum.tracks[index];
                audioPlayer.src = track.url;
                document.getElementById('current-track-title').textContent = prettyName(track.name);
                document.getElementById('progress-bar').style.width = '0%';
                document.getElementById('current-time').textContent = '0:00';
                document.getElementById('total-duration').textContent = '0:00';
                updateTrackListHighlight();
                updateMediaSessionMetadata();
                updateBookRemaining();
                if (textMode) {
                    document.getElementById('mini-track').textContent = prettyName(track.name);
                    document.getElementById('transcript-chapter').textContent =
                        `Глава ${index + 1} из ${currentAlbum.tracks.length}`;
                    loadTranscriptTrack(index);
                }
                audioPlayer.onloadedmetadata = () => {
                    document.getElementById('total-duration').textContent = formatTime(audioPlayer.duration);
                    if (trackDurations && !trackDurations[index]) trackDurations[index] = audioPlayer.duration;
                    updateBookRemaining();
                };
            }

            /**
             * Единственный источник правды об иконке — реальное состояние <audio>.
             * Поэтому пауза с наушников/гарнитуры, с локскрина или из системного
             * виджета тоже переключает кнопку: она реагирует на события play/pause
             * самого элемента, а не только на клик мышью.
             */
            function syncPlayButton() {
                const icon = document.getElementById('play-icon');
                const btn = document.getElementById('play-btn');
                if (!icon || !btn) return;
                const playing = !audioPlayer.paused && !audioPlayer.ended;
                icon.className = playing ? 'fas fa-pause' : 'fas fa-play';
                btn.setAttribute('aria-label', playing ? 'Пауза' : 'Воспроизвести');

                // В текстовом режиме кнопка мини-плеера должна вести себя так же
                const miniIcon = document.getElementById('mini-play-icon');
                if (miniIcon) miniIcon.className = playing ? 'fas fa-pause' : 'fas fa-play';
                if (playing) startTicking(); else stopTicking();
                if ('mediaSession' in navigator) {
                    navigator.mediaSession.playbackState = playing ? 'playing' : 'paused';
                }
                updateTrackListHighlight();
            }

            function playTrack() {
                const p = audioPlayer.play();
                if (p && typeof p.catch === 'function') {
                    p.catch(err => console.warn('Не удалось начать воспроизведение:', err));
                }
            }

            function pauseTrack() { audioPlayer.pause(); }
            function togglePlay() { audioPlayer.paused ? playTrack() : pauseTrack(); }

            function prevTrack() {
                // Если трек играет больше 3 секунд — сначала перематываем в начало
                if (audioPlayer.currentTime > 3) {
                    audioPlayer.currentTime = 0;
                    return;
                }
                currentTrackIndex = (currentTrackIndex - 1 + currentAlbum.tracks.length) % currentAlbum.tracks.length;
                loadTrack(currentTrackIndex);
                playTrack();
            }

            function nextTrack() {
                if (isShuffle) {
                    let newIndex;
                    do { newIndex = Math.floor(Math.random() * currentAlbum.tracks.length); } while (newIndex === currentTrackIndex && currentAlbum.tracks.length > 1);
                    currentTrackIndex = newIndex;
                } else {
                    currentTrackIndex = (currentTrackIndex + 1) % currentAlbum.tracks.length;
                }
                loadTrack(currentTrackIndex);
                playTrack();
            }

            function seek(seconds) {
                const duration = audioPlayer.duration;
                let target = audioPlayer.currentTime + seconds;
                if (!isNaN(duration)) target = Math.min(target, duration);
                audioPlayer.currentTime = Math.max(0, target);
            }

            function updateProgress() {
                maybeClaimAchievement();
                const { duration, currentTime } = audioPlayer;
                if (duration) {
                    const progressPercent = (currentTime / duration) * 100;
                    document.getElementById('progress-bar').style.width = `${progressPercent}%`;
                    document.getElementById('current-time').textContent = formatTime(currentTime);
                }
                // Периодически сохраняем позицию, чтобы прогресс не терялся
                // при закрытии вкладки без паузы
                if (Math.abs(currentTime - lastSaveAt) > 5) {
                    lastSaveAt = currentTime;
                    saveState();
                    updateBookRemaining();
                }
            }

            function setProgress(e) {
                const rect = this.getBoundingClientRect();
                const clickX = e.clientX - rect.left;
                const duration = audioPlayer.duration;
                if (duration) audioPlayer.currentTime = Math.min(Math.max(clickX / rect.width, 0), 1) * duration;
            }

            function updateTrackListHighlight() {
                document.querySelectorAll('.track-item').forEach(item => item.classList.remove('playing', 'current'));
                const activeTrack = document.querySelector(`.track-item[data-index="${currentTrackIndex}"]`);
                if (activeTrack) {
                    activeTrack.classList.add('current');
                    if (!audioPlayer.paused) activeTrack.classList.add('playing');
                    // В режиме чтения список глав скрыт — прокрутка к нему
                    // утащила бы страницу прочь от текста
                    if (!textMode) {
                        activeTrack.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
                    }
                }
            }

            // --- Прогресс ---

            function elapsedSeconds() {
                if (!trackDurations) return null;
                const before = trackDurations.slice(0, currentTrackIndex).reduce((a, b) => a + (b || 0), 0);
                return before + (audioPlayer.currentTime || 0);
            }

            function totalSeconds() {
                if (!trackDurations) return null;
                const total = trackDurations.reduce((a, b) => a + (b || 0), 0);
                return total > 0 ? total : null;
            }

            function updateBookRemaining() {
                const el = document.getElementById('book-remaining');
                if (!el) return;
                const total = totalSeconds();
                const elapsed = elapsedSeconds();
                if (total === null || elapsed === null || currentAlbum.tracks.length < 2) {
                    el.hidden = true;
                    return;
                }
                const remaining = Math.max(0, total - elapsed);
                el.hidden = false;
                el.innerHTML = window.CyberAuth.isFinished({ remaining, total })
                    ? '<i class="fas fa-check"></i> Книга прослушана'
                    : `<i class="fas fa-hourglass-half"></i> До конца книги: ${window.CyberAuth.formatDuration(remaining)} из ${window.CyberAuth.formatDuration(total)}`;
            }

            function saveState() {
                if (!audioPlayer.currentTime) return;
                const entry = { trackIndex: currentTrackIndex, position: audioPlayer.currentTime };
                localStore.set(albumPath, entry);

                if (window.CyberAuth.isLoggedIn()) {
                    const total = totalSeconds();
                    const elapsed = elapsedSeconds();
                    window.CyberAuth.saveProgress({
                        path: albumPath,
                        title: currentAlbum.title,
                        cover: currentAlbum.cover,
                        track_index: currentTrackIndex,
                        position: audioPlayer.currentTime,
                        finished: total !== null && elapsed !== null
                            && window.CyberAuth.isFinished({ remaining: total - elapsed, total })
                    });
                }
            }

            async function restoreState() {
                let saved = null;

                if (window.CyberAuth.isLoggedIn()) {
                    // Для авторизованного пользователя сервер — ЕДИНСТВЕННЫЙ источник
                    // правды. Отката на localStorage тут быть не должно: после сброса
                    // истории локальная копия возвращала бы позицию, и книга снова
                    // появлялась в списке прослушанных.
                    const map = await window.CyberAuth.loadProgress();
                    const remote = map.get(albumPath);
                    if (remote) {
                        saved = { trackIndex: remote.track_index, position: remote.position };
                    } else {
                        localStore.remove(albumPath);   // подчищаем остаток от гостевого сеанса
                    }
                } else {
                    saved = localStore.get(albumPath);
                }
                if (!saved || !currentAlbum.tracks[saved.trackIndex]) return;

                currentTrackIndex = saved.trackIndex;
                loadTrack(currentTrackIndex);
                lastSaveAt = saved.position;
                audioPlayer.oncanplay = () => {
                    audioPlayer.currentTime = saved.position;
                    audioPlayer.oncanplay = null;
                    updateBookRemaining();
                };
            }

            /**
             * Считываем длительности всех файлов книги средствами браузера
             * и отправляем на сервер — так он может показывать «сколько осталось»
             * на карточках, не разбирая аудиотеги на Python.
             */
            async function probeDurations() {
                if (trackDurations && trackDurations.every(d => d > 0)) {
                    updateBookRemaining();
                    return;
                }

                const probe = new Audio();
                probe.preload = 'metadata';
                const measured = [];

                for (const track of currentAlbum.tracks) {
                    const duration = await new Promise((resolve) => {
                        let settled = false;
                        const finish = (value) => {
                            if (settled) return;
                            settled = true;
                            clearTimeout(timer);
                            probe.removeEventListener('loadedmetadata', onMeta);
                            probe.removeEventListener('error', onError);
                            resolve(value);
                        };
                        const onMeta = () => finish(probe.duration);
                        const onError = () => finish(0);
                        const timer = setTimeout(() => finish(0), 15000);
                        probe.addEventListener('loadedmetadata', onMeta);
                        probe.addEventListener('error', onError);
                        probe.src = track.url;
                    });
                    measured.push(isFinite(duration) && duration > 0 ? duration : 0);
                }
                probe.src = '';

                trackDurations = measured;
                updateBookRemaining();

                if (measured.every(d => d > 0)) {
                    window.CyberAuth.reportDurations(albumPath, measured);
                    // Пересохраняем прогресс: теперь сервер сможет посчитать остаток
                    saveState();
                }
            }

            function formatTime(seconds) {
                return window.CyberAuth.formatClock(seconds);
            }

            /**
             * Media Session API: системные кнопки (наушники, локскрин, автомагнитола)
             * получают явные обработчики, а не «угадывают» поведение страницы.
             */
            function setupMediaSession() {
                if (!('mediaSession' in navigator)) return;
                const handlers = {
                    play: playTrack,
                    pause: pauseTrack,
                    previoustrack: prevTrack,
                    nexttrack: nextTrack,
                    seekbackward: (d) => seek(-(d && d.seekOffset ? d.seekOffset : 5)),
                    seekforward: (d) => seek(d && d.seekOffset ? d.seekOffset : 5),
                    seekto: (d) => { if (d && typeof d.seekTime === 'number') audioPlayer.currentTime = d.seekTime; },
                    stop: pauseTrack
                };
                for (const [action, handler] of Object.entries(handlers)) {
                    try {
                        navigator.mediaSession.setActionHandler(action, handler);
                    } catch (e) { /* действие не поддерживается браузером */ }
                }
            }

            function updateMediaSessionMetadata() {
                if (!('mediaSession' in navigator) || !window.MediaMetadata) return;
                const track = currentAlbum.tracks[currentTrackIndex];
                navigator.mediaSession.metadata = new MediaMetadata({
                    title: prettyName(track.name),
                    artist: currentAlbum.title,
                    album: currentAlbum.title,
                    artwork: [{ src: new URL(currentAlbum.cover, window.location.origin).href, sizes: '512x512', type: 'image/png' }]
                });
            }


            // ============================================================
            // ТЕКСТОВАЯ ВЕРСИЯ
            //
            // Текст приходит главами: [{s, e, t, w: [[слово, начало, конец]]}].
            // Из сегментов строится плоский список слов, по которому во время
            // воспроизведения бинарным поиском находится текущее слово.
            // ============================================================

            const textView = document.getElementById('text-view');
            const transcriptBox = document.getElementById('transcript');
            const miniPlayer = document.getElementById('mini-player');

            function buildMiniPlayer() {
                miniPlayer.innerHTML = `
                    <img src="${currentAlbum.cover}" alt="" class="mini-player__art" onerror="this.remove();">
                    <div class="mini-player__info">
                        <span class="mini-player__book">${currentAlbum.title}</span>
                        <span class="mini-player__track" id="mini-track">—</span>
                    </div>
                    <div class="mini-player__controls">
                        <button class="control-btn" id="mini-prev" title="Предыдущая глава"><i class="fas fa-backward-step"></i></button>
                        <button class="control-btn seek-btn" id="mini-rewind" title="-5 секунд"><i class="fas fa-rotate-left"></i></button>
                        <button class="control-btn play-btn" id="mini-play" title="Воспроизвести/Пауза"><i class="fas fa-play" id="mini-play-icon"></i></button>
                        <button class="control-btn seek-btn" id="mini-forward" title="+5 секунд"><i class="fas fa-rotate-right"></i></button>
                        <button class="control-btn" id="mini-next" title="Следующая глава"><i class="fas fa-forward-step"></i></button>
                        <button class="control-btn text-btn" id="mini-audio" title="Вернуться к плееру"><i class="fas fa-headphones"></i></button>
                    </div>
                    <div class="mini-player__progress" id="mini-progress"><div class="progress-bar" id="mini-progress-bar"></div></div>
                    <span class="mini-player__time" id="mini-time">0:00</span>
                    <button type="button" class="reader-menu-toggle" id="reader-menu-toggle" aria-expanded="true" aria-controls="reader-options"><i class="fas fa-chevron-up" aria-hidden="true"></i><span>Свернуть меню</span></button>`;

                const options = document.querySelector('.transcript-toolbar');
                options.id = 'reader-options';
                document.getElementById('reader-menu-toggle').addEventListener('click', () => {
                    const compact = textView.classList.toggle('is-compact');
                    const toggle = document.getElementById('reader-menu-toggle');
                    toggle.setAttribute('aria-expanded', String(!compact));
                    toggle.querySelector('span').textContent = compact ? 'Показать меню' : 'Свернуть меню';
                    toggle.querySelector('i').className = compact ? 'fas fa-chevron-down' : 'fas fa-chevron-up';
                    options.hidden = compact;
                    syncToolbarOffset();
                });

                document.getElementById('mini-play').addEventListener('click', togglePlay);
                document.getElementById('mini-prev').addEventListener('click', prevTrack);
                document.getElementById('mini-next').addEventListener('click', nextTrack);
                document.getElementById('mini-rewind').addEventListener('click', () => seek(-5));
                document.getElementById('mini-forward').addEventListener('click', () => seek(5));
                document.getElementById('mini-audio').addEventListener('click', () => setTextMode(false));
                document.getElementById('mini-progress').addEventListener('click', setProgress);
            }

            async function loadWholeBook() {
                transcriptBox.innerHTML = '<p class="transcript-note">Загрузка книги…</p>';
                wordIndex = [];
                activeWord = -1;

                let data = null;
                try {
                    const res = await fetch(
                        `/api/transcript?all=1&path=${encodeURIComponent(albumPath)}`);
                    if (res.ok) data = await res.json();
                } catch (e) { /* обработаем ниже */ }

                const chapters = (data && data.chapters) || [];
                if (!chapters.length) {
                    transcriptBox.innerHTML =
                        '<p class="transcript-note">Готового текста в этой книге пока нет.</p>';
                    return;
                }
                if (data.state) applyTranscriptState(data.state);
                renderWholeBook(chapters);
                loadedTextTrack = -1;   // отрисована книга целиком, не глава
                highlight(true);
            }

            function renderWholeBook(chapters) {
                const fragment = document.createDocumentFragment();
                wordIndex = [];

                chapters.forEach((chapter) => {
                    const head = document.createElement('h3');
                    head.className = 'transcript-chapter-head';
                    head.dataset.track = chapter.index;
                    head.textContent = `Глава ${chapter.index + 1}`;
                    const sub = document.createElement('span');
                    sub.className = 'transcript-chapter-name';
                    sub.textContent = prettyName(
                        (currentAlbum.tracks[chapter.index] || {}).name || chapter.name);
                    head.appendChild(sub);
                    fragment.appendChild(head);

                    (chapter.segments || []).forEach((seg) => {
                        const p = document.createElement('p');
                        p.className = 'transcript-line';

                        (seg.w || []).forEach(([text, start, end]) => {
                            const span = document.createElement('span');
                            span.className = 'word';
                            span.textContent = text;
                            span.dataset.i = wordIndex.length;
                            span.dataset.track = chapter.index;
                            // Помним, к какой главе относится слово: и подсветка,
                            // и перемотка работают только внутри звучащей главы
                            wordIndex.push({ start, end, el: span, line: p,
                                             track: chapter.index });
                            p.appendChild(span);
                            p.appendChild(document.createTextNode(' '));
                        });

                        if (!seg.w || !seg.w.length) p.textContent = seg.t || '';
                        fragment.appendChild(p);
                    });
                });

                transcriptBox.innerHTML = '';
                transcriptBox.appendChild(fragment);
            }

            async function loadTranscriptTrack(index) {
                if (wholeBook) return;   // книга уже отрисована целиком
                if (loadedTextTrack === index) return;
                transcriptBox.innerHTML = '<p class="transcript-note">Загрузка текста…</p>';
                wordIndex = [];
                activeWord = -1;

                let data = null;
                try {
                    const res = await fetch(`/api/transcript?path=${encodeURIComponent(albumPath)}&track=${index}`);
                    if (res.ok) data = await res.json();
                } catch (e) { /* обработаем ниже */ }

                if (!data || !data.track) {
                    const status = data && data.state ? data.state.status : 'none';
                    transcriptBox.innerHTML = status === 'running' || status === 'pending'
                        ? '<p class="transcript-note">Текст этой главы ещё готовится. Загляните позже.</p>'
                        : '<p class="transcript-note">Для этой главы текста нет.</p>';
                    loadedTextTrack = index;
                    return;
                }

                if (data.state) applyTranscriptState(data.state);
                renderTranscript(data.track.segments);
                loadedTextTrack = index;
                highlight(true);
            }

            /**
             * Чип профиля висит поверх страницы в правом верхнем углу и
             * накрывал крайнюю кнопку мини-плеера. Его ширина зависит от
             * никнейма, поэтому замеряем её и резервируем ровно столько места.
             */
            function syncChipSpace() {
                const chip = document.querySelector('#user-area .user-chip:not([hidden])');
                const view = document.getElementById('text-view');
                if (!view) return;
                const width = chip ? Math.ceil(chip.getBoundingClientRect().width) : 0;
                const space = width ? `${width + 12}px` : '0px';
                view.style.setProperty('--user-chip-space', space);
                // Резервируем место для профиля и кнопке возврата: длинный
                // никнейм не должен заезжать на неё на узком экране.
                document.body.style.setProperty('--reader-user-space', space);
                syncToolbarOffset();
            }

            /** Показывает возврат к началу только после заметной прокрутки. */
            function updateReaderTopButton() {
                const button = document.getElementById('reader-scroll-top');
                if (!button) return;
                button.hidden = !textMode || window.scrollY < 280;
            }

            /**
             * Тулбар закреплён сразу под мини-плеером, а его высота плавает:
             * зависит от длины названия и ширины экрана. Поэтому меряем её
             * и отдаём в CSS, вместо того чтобы вписывать число руками.
             */
            function syncToolbarOffset() {
                const view = document.getElementById('text-view');
                const mini = document.getElementById('mini-player');
                if (!view || !mini) return;
                const height = Math.ceil(mini.getBoundingClientRect().height);
                view.style.setProperty('--mini-player-height', `${height}px`);
            }

            let miniSizeWatcher = null;

            function watchMiniPlayerSize() {
                if (miniSizeWatcher || typeof ResizeObserver === 'undefined') return;
                const mini = document.getElementById('mini-player');
                if (!mini) return;
                miniSizeWatcher = new ResizeObserver(syncToolbarOffset);
                miniSizeWatcher.observe(mini);
            }

            function renderTranscript(segments) {
                const fragment = document.createDocumentFragment();
                wordIndex = [];

                segments.forEach((seg) => {
                    const p = document.createElement('p');
                    p.className = 'transcript-line';

                    (seg.w || []).forEach(([text, start, end]) => {
                        const span = document.createElement('span');
                        span.className = 'word';
                        span.textContent = text;
                        // Клик по слову — перемотка на это место: самый естественный
                        // способ навигации по тексту
                        span.dataset.i = wordIndex.length;
                        wordIndex.push({ start, end, el: span, line: p,
                                         track: currentTrackIndex });
                        p.appendChild(span);
                        p.appendChild(document.createTextNode(' '));
                    });

                    if (!seg.w || !seg.w.length) p.textContent = seg.t || '';
                    fragment.appendChild(p);
                });

                transcriptBox.innerHTML = '';
                transcriptBox.appendChild(fragment);
            }

            // Последнее слово, начавшееся не позже времени t
            function findWord(t) {
                // В режиме всей книги время идёт по каждой главе с нуля,
                // поэтому ищем только среди слов той главы, что звучит сейчас
                let lo = 0, hi = wordIndex.length - 1;
                if (wholeBook) {
                    const range = trackRange(currentTrackIndex);
                    if (!range) return -1;
                    lo = range.from;
                    hi = range.to;
                }
                let found = -1;
                while (lo <= hi) {
                    const mid = (lo + hi) >> 1;
                    if (wordIndex[mid].start <= t) { found = mid; lo = mid + 1; }
                    else { hi = mid - 1; }
                }
                return found;
            }

            /** Границы слов одной главы в общем списке. */
            function trackRange(index) {
                let from = -1, to = -1;
                for (let i = 0; i < wordIndex.length; i += 1) {
                    if (wordIndex[i].track !== index) continue;
                    if (from === -1) from = i;
                    to = i;
                }
                return from === -1 ? null : { from, to };
            }

            function clearHighlight() {
                // Возвращаем текст в исходный вид: ни подсветки, ни затемнения
                wordIndex.forEach((w) => {
                    w.el.classList.remove('is-spoken', 'is-active');
                });
                activeWord = -1;
            }

            /** Помечает главы, кроме звучащей: по ним не кликают и не следят. */
            function markActiveChapter() {
                if (!wholeBook) return;
                transcriptBox.querySelectorAll('.transcript-chapter-head')
                    .forEach((h) => h.classList.toggle('is-current',
                        Number(h.dataset.track) === currentTrackIndex));
                wordIndex.forEach((w) => {
                    w.el.classList.toggle('is-other-chapter', w.track !== currentTrackIndex);
                });
            }

            /**
             * Глава дослушана почти до конца — просим сервер засчитать
             * достижения. Решение принимает сервер, здесь только повод.
             */
            function maybeClaimAchievement() {
                if (!window.CyberAuth || !CyberAuth.isLoggedIn()) return;
                const left = audioPlayer.duration - audioPlayer.currentTime;
                if (!Number.isFinite(left) || left > CLAIM_TAIL || left < 0) return;
                if (claimedTracks.has(currentTrackIndex)) return;
                claimedTracks.add(currentTrackIndex);

                fetch('/api/achievements/claim', {
                    method: 'POST',
                    credentials: 'same-origin',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ path: albumPath, track: currentTrackIndex })
                })
                    .then(r => r.ok ? r.json() : null)
                    .then((data) => {
                        if (!data || !data.granted) return;
                        data.granted.forEach((item, i) => {
                            setTimeout(() => window.CyberAchievements.show(item), i * 600);
                        });
                    })
                    .catch(() => { /* не получилось — попробуем в следующий раз */ });
            }

            function highlight(force) {
                if (!textMode || !wordIndex.length) return;
                // Слежение выключено — текст остаётся нетронутым
                if (!follow) return;
                markActiveChapter();
                const t = audioPlayer.currentTime - textOffset;
                const index = findWord(t);
                if (index === activeWord && !force) return;

                if (force || Math.abs(index - activeWord) > 150) {
                    // Перемотка: пересобираем состояние целиком
                    wordIndex.forEach((w, i) => {
                        w.el.classList.toggle('is-spoken', i < index);
                        w.el.classList.toggle('is-active', i === index);
                    });
                } else {
                    const from = Math.min(activeWord, index), to = Math.max(activeWord, index);
                    for (let i = Math.max(0, from); i <= to && i < wordIndex.length; i++) {
                        wordIndex[i].el.classList.toggle('is-spoken', i < index);
                        wordIndex[i].el.classList.toggle('is-active', i === index);
                    }
                }

                const prevLine = activeWord >= 0 ? wordIndex[activeWord].line : null;
                activeWord = index;

                if (index >= 0 && follow && Date.now() > followPausedUntil) {
                    // Прокручиваем только при переходе на новую строку, иначе
                    // страница дёргалась бы на каждом слове
                    const line = wordIndex[index].line;
                    if (line !== prevLine || force) {
                        line.scrollIntoView({ behavior: force ? 'auto' : 'smooth', block: 'center' });
                    }
                }
            }

            // timeupdate у <audio> срабатывает всего 4 раза в секунду — для слов
            // этого мало, поэтому во время игры опрашиваем позицию через rAF
            function tick() {
                highlight(false);
                updateMiniPlayer();
                rafId = requestAnimationFrame(tick);
            }

            function startTicking() {
                if (rafId === null && textMode) rafId = requestAnimationFrame(tick);
            }

            function stopTicking() {
                if (rafId !== null) { cancelAnimationFrame(rafId); rafId = null; }
            }

            function updateMiniPlayer() {
                const bar = document.getElementById('mini-progress-bar');
                const time = document.getElementById('mini-time');
                if (!bar || !time) return;
                const { duration, currentTime } = audioPlayer;
                if (duration) bar.style.width = `${(currentTime / duration) * 100}%`;
                time.textContent = formatTime(currentTime);
            }

            function applyTranscriptState(state) {
                transcriptState = state || { ready: false };
                const btn = document.getElementById('text-btn');
                if (btn) btn.hidden = !transcriptState.ready;
                syncRequestButton();
            }

            /**
             * Кнопка «запросить текстовый формат» нужна только там, где текста
             * ещё нет и его никто не делает. Один человек просит один раз.
             */
            function syncRequestButton() {
                const btn = document.getElementById('request-text-btn');
                const note = document.getElementById('request-note');
                if (!btn || !note) return;

                const status = transcriptState.status || 'none';
                const noText = !transcriptState.ready && status === 'none';
                btn.hidden = !noText;
                note.hidden = true;

                if (!noText) return;

                if (transcriptState.requested) {
                    btn.disabled = true;
                    btn.classList.add('is-done');
                    btn.title = 'Вы уже запросили текст для этой книги';
                    note.textContent = 'Текст запрошен — администратор увидит вашу просьбу.';
                    note.hidden = false;
                } else {
                    btn.disabled = false;
                    btn.classList.remove('is-done');
                    btn.title = 'Запросить текстовый формат';
                }
            }

            function loadOffset() {
                try {
                    const all = JSON.parse(localStorage.getItem(SYNC_STORE) || '{}');
                    const saved = Number(all[albumPath]);
                    textOffset = Number.isFinite(saved) ? saved : 0;
                } catch (e) {
                    textOffset = 0;
                }
                renderOffset();
            }

            function saveOffset() {
                try {
                    const all = JSON.parse(localStorage.getItem(SYNC_STORE) || '{}');
                    if (textOffset) all[albumPath] = textOffset;
                    else delete all[albumPath];   // ноль не храним, чтобы не копить мусор
                    localStorage.setItem(SYNC_STORE, JSON.stringify(all));
                } catch (e) {
                    /* приватный режим браузера — сдвиг просто не переживёт перезагрузку */
                }
            }

            function renderOffset() {
                const box = document.getElementById('sync-value');
                if (!box) return;
                const sign = textOffset > 0 ? '+' : (textOffset < 0 ? '−' : '');
                box.textContent = sign + Math.abs(textOffset).toFixed(2).replace('.', ',') + ' с';
                box.classList.toggle('is-set', textOffset !== 0);
            }

            function changeOffset(delta) {
                const next = Math.round((textOffset + delta) / SYNC_STEP) * SYNC_STEP;
                textOffset = Math.max(-SYNC_LIMIT, Math.min(SYNC_LIMIT, Number(next.toFixed(2))));
                saveOffset();
                renderOffset();
                highlight(true);
            }

            async function refreshTranscriptState() {
                try {
                    const resp = await fetch(
                        `/api/transcript?path=${encodeURIComponent(albumPath)}`);
                    const data = await resp.json();
                    if (data.state) applyTranscriptState(data.state);
                } catch (e) {
                    /* сеть подвела — оставляем прежнее состояние кнопок */
                }
            }

            async function requestText() {
                // Гостя сначала просим войти: просьбу нужно к кому-то отнести
                if (!window.CyberAuth || !CyberAuth.isLoggedIn()) {
                    CyberAuth.openAuth('login');
                    return;
                }
                const btn = document.getElementById('request-text-btn');
                const note = document.getElementById('request-note');
                btn.disabled = true;
                try {
                    const resp = await fetch('/api/transcript/request', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ path: albumPath })
                    });
                    const data = await resp.json();
                    if (!resp.ok) throw new Error(data.error || 'Не удалось отправить просьбу');
                    applyTranscriptState(data.state);
                } catch (e) {
                    btn.disabled = false;
                    note.textContent = e.message;
                    note.hidden = false;
                }
            }

            function pauseFollow() {
                if (textMode && follow) followPausedUntil = Date.now() + 4000;
            }

            function setTextMode(on) {
                textMode = on;
                document.body.classList.toggle('is-text-mode', on);
                textView.hidden = !on;
                playerContainer.hidden = on;

                if (on) {
                    try {
                        wholeBook = localStorage.getItem(WHOLE_STORE) === '1';
                    } catch (err) { wholeBook = false; }
                    const wholeToggle = document.getElementById('whole-book-toggle');
                    if (wholeToggle) {
                        wholeToggle.checked = wholeBook;
                        // Браузер восстанавливает состояние полей после перезагрузки
                        // уже поверх нашего — выставляем ещё раз следующим тактом
                        setTimeout(() => { wholeToggle.checked = wholeBook; }, 0);
                    }
                    syncChipSpace();
                    watchMiniPlayerSize();
                    document.getElementById('mini-track').textContent =
                        prettyName(currentAlbum.tracks[currentTrackIndex].name);
                    document.getElementById('transcript-chapter').textContent =
                        `Глава ${currentTrackIndex + 1} из ${currentAlbum.tracks.length}`;
                    if (wholeBook) loadWholeBook();
                    else loadTranscriptTrack(currentTrackIndex);
                    updateMiniPlayer();
                    syncPlayButton();
                    if (!audioPlayer.paused) startTicking();
                } else {
                    stopTicking();
                }
                updateReaderTopButton();
                try {
                    localStorage.setItem('cyberAudioTextMode', on ? '1' : '0');
                } catch (e) { /* приватный режим */ }
            }

            function addEventListeners() {
                document.getElementById('play-btn').addEventListener('click', togglePlay);
                document.getElementById('text-btn').addEventListener('click', () => setTextMode(true));
                document.getElementById('request-text-btn').addEventListener('click', requestText);
                document.getElementById('sync-minus').addEventListener('click',
                    () => changeOffset(-SYNC_STEP));
                document.getElementById('sync-plus').addEventListener('click',
                    () => changeOffset(SYNC_STEP));
                document.getElementById('sync-value').addEventListener('click', () => {
                    textOffset = 0;
                    saveOffset();
                    renderOffset();
                    highlight(true);
                });
                document.getElementById('reader-scroll-top').addEventListener('click', () => {
                    window.scrollTo({ top: 0, behavior: 'smooth' });
                });
                loadOffset();

                // Один обработчик на весь текст вместо тысяч на каждом слове
                transcriptBox.addEventListener('click', (e) => {
                    const span = e.target.closest('.word');
                    if (!span) return;
                    const word = wordIndex[Number(span.dataset.i)];
                    if (!word) return;

                    // Перематывать можно только по звучащей главе: у остальных
                    // время идёт от нуля и попало бы не туда
                    if (wholeBook && word.track !== currentTrackIndex) return;

                    audioPlayer.currentTime = word.start;
                    highlight(true);
                });

                document.getElementById('whole-book-toggle').addEventListener('change', (e) => {
                    wholeBook = e.target.checked;
                    try {
                        localStorage.setItem(WHOLE_STORE, wholeBook ? '1' : '0');
                    } catch (err) { /* приватный режим — просто не запомним */ }
                    loadedTextTrack = null;
                    if (wholeBook) {
                        loadWholeBook();
                    } else {
                        loadTranscriptTrack(currentTrackIndex);
                    }
                });

                document.getElementById('follow-toggle').addEventListener('change', (e) => {
                    follow = e.target.checked;
                    followPausedUntil = 0;
                    if (follow) {
                        highlight(true);
                    } else {
                        clearHighlight();
                    }
                });

                window.addEventListener('resize', syncChipSpace);
                // Смена никнейма и вход/выход меняют ширину чипа
                window.CyberAuth.onChange(() => setTimeout(syncChipSpace, 0));

                // Ручная прокрутка временно отключает автопрокрутку, иначе
                // страница выдёргивала бы читателя обратно к текущему слову
                window.addEventListener('wheel', pauseFollow, { passive: true });
                window.addEventListener('touchmove', pauseFollow, { passive: true });
                window.addEventListener('scroll', updateReaderTopButton, { passive: true });
                document.getElementById('prev-btn').addEventListener('click', prevTrack);
                document.getElementById('next-btn').addEventListener('click', nextTrack);
                document.getElementById('rewind-btn').addEventListener('click', () => seek(-5));
                document.getElementById('forward-btn').addEventListener('click', () => seek(5));
                document.getElementById('shuffle-btn').addEventListener('click', (e) => {
                    isShuffle = !isShuffle;
                    e.currentTarget.classList.toggle('active', isShuffle);
                    e.currentTarget.setAttribute('aria-pressed', String(isShuffle));
                });

                // Кнопка следует за реальным состоянием аудио, откуда бы им ни управляли
                audioPlayer.addEventListener('play', syncPlayButton);
                audioPlayer.addEventListener('playing', syncPlayButton);
                audioPlayer.addEventListener('pause', syncPlayButton);
                audioPlayer.addEventListener('ended', syncPlayButton);
                audioPlayer.addEventListener('emptied', syncPlayButton);

                audioPlayer.addEventListener('timeupdate', updateProgress);
                // На паузе rAF не крутится, но перемотка всё равно должна
                // передвинуть подсветку и полосу мини-плеера
                audioPlayer.addEventListener('seeked', () => {
                    if (textMode) { highlight(true); updateMiniPlayer(); }
                });
                audioPlayer.addEventListener('ended', nextTrack);
                audioPlayer.addEventListener('pause', saveState);
                window.addEventListener('pagehide', saveState);

                document.getElementById('progress-container').addEventListener('click', setProgress);

                // Вход в аккаунт прямо со страницы плеера: переносим текущую позицию
                // в профиль, чтобы она не осталась только в этом браузере
                window.CyberAuth.onChange((u) => {
                    if (u) saveState();
                    // Признак «уже запрошено» свой у каждого читателя,
                    // поэтому после входа или выхода состояние перечитываем
                    refreshTranscriptState();
                });

                // Сброс истории по книге, открытой прямо сейчас: отматываем в начало
                // и ставим на паузу. Иначе воспроизведение продолжилось бы с текущей
                // секунды и через пару мгновений записало бы прогресс заново.
                window.addEventListener('cyberaudio:progress-reset', (e) => {
                    if (!e.detail || e.detail.path !== albumPath) return;
                    audioPlayer.pause();
                    currentTrackIndex = 0;
                    loadTrack(currentTrackIndex);
                    lastSaveAt = 0;
                    updateBookRemaining();
                });

                // Пробел / стрелки для управления с клавиатуры
                document.addEventListener('keydown', (e) => {
                    if (e.target.closest('button, a, input, textarea')) return;
                    if (e.code === 'Space') { e.preventDefault(); togglePlay(); }
                    else if (e.code === 'ArrowRight') { e.preventDefault(); seek(5); }
                    else if (e.code === 'ArrowLeft') { e.preventDefault(); seek(-5); }
                });
            }

            // --- Инициализация ---
            loadPlayerUI();
            await restoreState();

            // Возвращаем режим чтения, если пользователь ушёл со страницы в нём
            let wantText = false;
            try {
                wantText = localStorage.getItem('cyberAudioTextMode') === '1';
            } catch (e) { /* приватный режим */ }
            if (wantText && transcriptState.ready) setTextMode(true);

            probeDurations();

        } catch (error) {
            console.error("Не удалось инициализировать плеер:", error);
            playerContainer.innerHTML = `<p class="error-message">Ошибка загрузки данных плеера. Убедитесь, что сервер запущен.</p>`;
        }
    };

    initPlayer();
});
