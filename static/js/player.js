document.addEventListener('DOMContentLoaded', () => {
    const playerContainer = document.getElementById('player-container');
    const audioPlayer = document.getElementById('audio-player');
    const API_ENDPOINT = '/api/browse';

    if (!playerContainer || !audioPlayer) return;

    const initPlayer = async () => {
        const urlParams = new URLSearchParams(window.location.search);
        const albumPath = urlParams.get('path');
        if (!albumPath) {
            playerContainer.innerHTML = '<h2>ОШИБКА: ПУТЬ К АЛЬБОМУ НЕ УКАЗАН</h2><p>Пожалуйста, вернитесь на главную и выберите альбом.</p>';
            return;
        }

        try {
            const response = await fetch(`${API_ENDPOINT}?path=${encodeURIComponent(albumPath)}`);
            if (!response.ok) throw new Error('Альбом не найден или произошла ошибка.');
            const currentAlbum = await response.json();

            if (currentAlbum.type !== 'album') {
                playerContainer.innerHTML = '<h2>ОШИБКА: УКАЗАННЫЙ ПУТЬ НЕ ЯВЛЯЕТСЯ АЛЬБОМОМ</h2>';
                return;
            }

            // --- Состояние плеера ---
            let currentTrackIndex = 0;
            let isPlaying = false;
            let isShuffle = false;


            function loadPlayerUI() {
                playerContainer.innerHTML = `
                    <div class="player-album-art-container">
                        <img src="${currentAlbum.cover}" alt="${currentAlbum.title}" class="player-album-art" onerror="this.onerror=null;this.src='/static/assets/default_cover.png';">
                    </div>
                    <div class="player-details">
                        <h3>${currentAlbum.title}</h3>
                        <p id="current-track-title">Название трека</p>
                        <div class="progress-container" id="progress-container"><div class="progress-bar" id="progress-bar"></div></div>
                        <div class="time-stamps"><span id="current-time">0:00</span><span id="total-duration">0:00</span></div>
                        <div class="player-controls">
                            <button class="control-btn shuffle-btn" id="shuffle-btn" title="Перемешать"><i class="fas fa-shuffle"></i></button>
                            <button class="control-btn" id="prev-btn" title="Предыдущий трек"><i class="fas fa-backward-step"></i></button>
                            <button class="control-btn seek-btn" id="rewind-btn" title="-5 секунд"><i class="fas fa-rotate-left"></i></button>
                            <button class="control-btn play-btn" id="play-btn" title="Воспроизвести/Пауза"><i class="fas fa-play"></i></button>
                            <button class="control-btn seek-btn" id="forward-btn" title="+5 секунд"><i class="fas fa-rotate-right"></i></button>
                            <button class="control-btn" id="next-btn" title="Следующий трек"><i class="fas fa-forward-step"></i></button>
                        </div>
                        <ol class="track-list" id="track-list"></ol>
                    </div>`;
                loadTrackList();
                addEventListeners();
                loadTrack(currentTrackIndex);
                loadLastPlayedState();
            }

            function loadTrackList() {
                const trackList = document.getElementById('track-list');
                trackList.innerHTML = '';
                currentAlbum.tracks.forEach((track, index) => {
                    const trackName = track.name.replace(/\.mp3|\.ogg|\.wav|\.m4a/i, '').replace(/_/g, ' ');
                    const li = document.createElement('li');
                    li.className = 'track-item';
                    li.dataset.index = index;
                    li.innerHTML = `<span class="track-number">${(index + 1).toString().padStart(2, '0')}</span> ${trackName}`;
                    li.addEventListener('click', () => { currentTrackIndex = index; loadTrack(currentTrackIndex); playTrack(); });
                    trackList.appendChild(li);
                });
            }

            function loadTrack(index) {
                const track = currentAlbum.tracks[index];
                audioPlayer.src = track.url;
                document.getElementById('current-track-title').textContent = track.name.replace(/\.mp3|\.ogg|\.wav|\.m4a/i, '').replace(/_/g, ' ');
                updateTrackListHighlight();
                audioPlayer.onloadedmetadata = () => {
                    document.getElementById('total-duration').textContent = formatTime(audioPlayer.duration);
                };
            }

            function playTrack() { isPlaying = true; document.getElementById('play-btn').innerHTML = '<i class="fas fa-pause"></i>'; audioPlayer.play(); updateTrackListHighlight(); }
            function pauseTrack() { isPlaying = false; document.getElementById('play-btn').innerHTML = '<i class="fas fa-play"></i>'; audioPlayer.pause(); }
            function prevTrack() { currentTrackIndex = (currentTrackIndex - 1 + currentAlbum.tracks.length) % currentAlbum.tracks.length; loadTrack(currentTrackIndex); playTrack(); }

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

            function seek(seconds) { audioPlayer.currentTime = Math.max(0, audioPlayer.currentTime + seconds); }

            function updateProgress() {
                const { duration, currentTime } = audioPlayer;
                if (duration) {
                    const progressPercent = (currentTime / duration) * 100;
                    document.getElementById('progress-bar').style.width = `${progressPercent}%`;
                    document.getElementById('current-time').textContent = formatTime(currentTime);
                }
            }

            function setProgress(e) { const width = this.clientWidth; const clickX = e.offsetX; const duration = audioPlayer.duration; if (duration) audioPlayer.currentTime = (clickX / width) * duration; }

            function updateTrackListHighlight() {
                document.querySelectorAll('.track-item').forEach(item => item.classList.remove('playing'));
                const activeTrack = document.querySelector(`.track-item[data-index="${currentTrackIndex}"]`);
                if (activeTrack) {
                    if (isPlaying) activeTrack.classList.add('playing');
                    activeTrack.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
                }
            }

            function saveLastPlayedState() {
                if (!audioPlayer.currentTime || audioPlayer.currentTime === 0) return;
                const state = { path: albumPath, trackIndex: currentTrackIndex, currentTime: audioPlayer.currentTime };
                localStorage.setItem('cyberAudioLastPlayed', JSON.stringify(state));
            }

            function loadLastPlayedState() {
                const savedState = JSON.parse(localStorage.getItem('cyberAudioLastPlayed'));
                if (savedState && savedState.path === albumPath) {
                    currentTrackIndex = savedState.trackIndex;
                    loadTrack(currentTrackIndex);
                    audioPlayer.oncanplay = () => {
                        audioPlayer.currentTime = savedState.currentTime;
                        audioPlayer.oncanplay = null; // убираем обработчик
                    };
                }
            }

            function formatTime(seconds) {
                if (isNaN(seconds)) return '0:00';
                const minutes = Math.floor(seconds / 60);
                const secs = Math.floor(seconds % 60);
                return `${minutes}:${secs.toString().padStart(2, '0')}`;
            }

            function addEventListeners() {
                document.getElementById('play-btn').addEventListener('click', () => { isPlaying ? pauseTrack() : playTrack(); });
                document.getElementById('prev-btn').addEventListener('click', prevTrack);
                document.getElementById('next-btn').addEventListener('click', nextTrack);
                document.getElementById('rewind-btn').addEventListener('click', () => seek(-5));
                document.getElementById('forward-btn').addEventListener('click', () => seek(5));
                document.getElementById('shuffle-btn').addEventListener('click', () => { isShuffle = !isShuffle; document.getElementById('shuffle-btn').classList.toggle('active', isShuffle); });

                audioPlayer.addEventListener('timeupdate', updateProgress);
                audioPlayer.addEventListener('ended', nextTrack);
                audioPlayer.addEventListener('pause', saveLastPlayedState);
                document.getElementById('progress-container').addEventListener('click', setProgress);
            }

            // --- Инициализация ---
            loadPlayerUI();

        } catch (error) {
            console.error("Не удалось инициализировать плеер:", error);
            playerContainer.innerHTML = `<p class="error-message">Ошибка загрузки данных плеера. Убедитесь, что сервер запущен.</p>`;
        }
    };

    initPlayer();
});