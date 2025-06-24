// static/js/app.js
document.addEventListener('DOMContentLoaded', () => {
    const albumGrid = document.getElementById('album-grid');
    const API_URL = '/api/music-data'; // Относительный URL, т.к. JS и API на одном сервере

    if (!albumGrid) return;

    const loadAlbums = async () => {
        try {
            const response = await fetch(API_URL);
            if (!response.ok) {
                throw new Error(`Ошибка сети: ${response.status}`);
            }
            const albumsData = await response.json();

            albumGrid.innerHTML = ''; // Очищаем индикатор загрузки

            if (albumsData.length === 0) {
                albumGrid.innerHTML = `<p class="error-message">Медиатека пуста. Добавьте папки с музыкой в 'static/music'.</p>`;
                return;
            }

            albumsData.forEach(album => {
                const card = document.createElement('a');
                // Передаем ID альбома в URL
                card.href = `/player?album=${album.id}`;
                card.className = 'album-card';

                card.innerHTML = `
                    <img src="${album.cover}" alt="Обложка альбома ${album.title}" class="album-card__image">
                    <div class="album-card__info">
                        <h3 class="album-card__title">${album.title}</h3>
                    </div>
                `;

                albumGrid.appendChild(card);
            });

        } catch (error) {
            console.error("Не удалось загрузить альбомы:", error);
            albumGrid.innerHTML = `<p class="error-message">Ошибка загрузки данных. Убедитесь, что сервер запущен и доступен.</p>`;
        }
    };

    loadAlbums();
});