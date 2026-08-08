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
                        </div>
                        <ol class="track-list" id="track-list"></ol>
                    </div>`;
                loadTrackList();
                addEventListeners();
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
                    activeTrack.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
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

            function addEventListeners() {
                document.getElementById('play-btn').addEventListener('click', togglePlay);
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
                audioPlayer.addEventListener('ended', nextTrack);
                audioPlayer.addEventListener('pause', saveState);
                window.addEventListener('pagehide', saveState);

                document.getElementById('progress-container').addEventListener('click', setProgress);

                // Вход в аккаунт прямо со страницы плеера: переносим текущую позицию
                // в профиль, чтобы она не осталась только в этом браузере
                window.CyberAuth.onChange((u) => {
                    if (u) saveState();
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
            probeDurations();

        } catch (error) {
            console.error("Не удалось инициализировать плеер:", error);
            playerContainer.innerHTML = `<p class="error-message">Ошибка загрузки данных плеера. Убедитесь, что сервер запущен.</p>`;
        }
    };

    initPlayer();
});
