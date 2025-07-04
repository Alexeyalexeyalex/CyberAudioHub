document.addEventListener('DOMContentLoaded', () => {
    const grid = document.getElementById('album-grid');
    const breadcrumbsContainer = document.getElementById('breadcrumbs-container');

    const API_ENDPOINT = '/api/browse';

    const renderBreadcrumbs = (path) => {
        breadcrumbsContainer.innerHTML = '';
        const parts = path ? path.split('/') : [];

        const rootLink = document.createElement('a');
        rootLink.href = '#';
        rootLink.className = 'breadcrumb-item';
        rootLink.textContent = 'Медиатека';
        rootLink.addEventListener('click', (e) => {
            e.preventDefault();
            loadPath('');
        });
        breadcrumbsContainer.appendChild(rootLink);

        let currentPath = '';
        parts.forEach((part, index) => {
            if (!part) return;

            const separator = document.createElement('span');
            separator.className = 'breadcrumb-separator';
            separator.textContent = '>';
            breadcrumbsContainer.appendChild(separator);

            currentPath += (currentPath ? '/' : '') + part;

            if (index === parts.length - 1) {
                const currentEl = document.createElement('span');
                currentEl.className = 'breadcrumb-current';
                currentEl.textContent = part.replace(/_/g, ' ');
                breadcrumbsContainer.appendChild(currentEl);
            } else {
                const link = document.createElement('a');
                link.href = '#';
                link.className = 'breadcrumb-item';
                link.textContent = part.replace(/_/g, ' ');
                link.dataset.path = currentPath;
                link.addEventListener('click', (e) => {
                    e.preventDefault();
                    loadPath(e.target.dataset.path);
                });
                breadcrumbsContainer.appendChild(link);
            }
        });
    };

    const renderGrid = (data) => {
        grid.innerHTML = '';
        if (!data.items || data.items.length === 0) {
            grid.innerHTML = `<p class="error-message">В этой директории пусто.</p>`;
            return;
        }

        data.items.forEach(item => {
            const card = document.createElement('div');
            card.className = 'album-card';
            card.dataset.path = item.path;
            card.dataset.type = item.type;

            const iconClass = item.type === 'directory' ? 'fa-folder' : 'fa-compact-disc';

            card.innerHTML = `
                <div class="album-card__image-container">
                    <img src="${item.cover}" alt="Обложка для ${item.name}" class="album-card__image" onerror="this.onerror=null;this.src='/static/assets/default_cover.png';">
                    <i class="fas ${iconClass} album-card__type-icon"></i>
                </div>
                <div class="album-card__info">
                    <h3 class="album-card__title">${item.name}</h3>
                </div>
            `;

            card.addEventListener('click', () => {
                const path = card.dataset.path;
                const type = card.dataset.type;
                if (type === 'directory') {
                    loadPath(path);
                } else if (type === 'album') {
                    window.location.href = `/player?path=${path}`;
                }
            });

            grid.appendChild(card);
        });
    };

    const loadPath = async (path = '') => {
        grid.innerHTML = `<div class="loading-state"><div class="loader"></div></div>`;
        renderBreadcrumbs(path);

        try {
            const response = await fetch(`${API_ENDPOINT}?path=${encodeURIComponent(path)}`);
            if (!response.ok) {
                throw new Error(`Ошибка сети: ${response.status}`);
            }
            const data = await response.json();

            if (data.type === 'directory') {
                renderGrid(data);
            } else if (data.type === 'album') {
                // Если мы попали на альбом прямо из браузера, перенаправляем на плеер
                window.location.href = `/player?path=${encodeURIComponent(path)}`;
            }

            // Обновляем URL в браузере для навигации
            history.pushState({ path }, '', `/?path=${encodeURIComponent(path)}`);

        } catch (error) {
            console.error("Не удалось загрузить данные:", error);
            grid.innerHTML = `<p class="error-message">Ошибка загрузки данных. Проверьте консоль.</p>`;
        }
    };

    // Обработка кнопок "вперед/назад" в браузере
    window.addEventListener('popstate', (e) => {
        const path = e.state ? e.state.path : '';
        loadPath(path);
    });

    // Начальная загрузка
    const initialPath = new URLSearchParams(window.location.search).get('path') || '';
    loadPath(initialPath);
});