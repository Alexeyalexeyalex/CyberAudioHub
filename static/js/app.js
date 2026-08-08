document.addEventListener('DOMContentLoaded', () => {
    const grid = document.getElementById('album-grid');
    const breadcrumbsContainer = document.getElementById('breadcrumbs-container');

    const API_ENDPOINT = '/api/browse';

    // Путь, который сейчас отрисован в сетке. Нужен, чтобы понимать,
    // осталась ли страница в актуальном состоянии (например, после возврата из bfcache).
    let renderedPath = null;
    let requestId = 0;
    let lastData = null;          // последний успешный ответ — для перерисовки при входе/выходе
    let progressMap = new Map();  // path -> прогресс прослушивания текущего пользователя

    const playerUrl = (path) => `/player?path=${encodeURIComponent(path)}`;
    const indexUrl = (path) => (path ? `/?path=${encodeURIComponent(path)}` : '/');

    const renderBreadcrumbs = (path) => {
        breadcrumbsContainer.innerHTML = '';
        const parts = path ? path.split('/') : [];

        const rootLink = document.createElement('a');
        rootLink.href = '/';
        rootLink.className = 'breadcrumb-item';
        rootLink.textContent = 'Медиатека';
        rootLink.addEventListener('click', (e) => {
            e.preventDefault();
            loadPath('', { push: true });
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
                link.href = indexUrl(currentPath);
                link.className = 'breadcrumb-item';
                link.textContent = part.replace(/_/g, ' ');
                link.dataset.path = currentPath;
                link.addEventListener('click', (e) => {
                    e.preventDefault();
                    loadPath(e.currentTarget.dataset.path, { push: true });
                });
                breadcrumbsContainer.appendChild(link);
            }
        });
    };

    /**
     * Плашка «сколько осталось» на карточке прослушанной книги.
     * Показывается только авторизованному пользователю — у гостя прогресс
     * лежит в localStorage и на сервере о нём ничего не известно.
     */
    const buildProgressBadge = (progress) => {
        const wrap = document.createElement('div');
        wrap.className = 'album-card__progress';

        const label = document.createElement('span');
        label.className = 'album-card__remaining';

        const finished = window.CyberAuth.isFinished(progress);
        if (finished) {
            wrap.classList.add('is-finished');
            label.innerHTML = '<i class="fas fa-check"></i> Прослушано';
        } else if (progress.remaining !== null && progress.remaining !== undefined) {
            label.innerHTML = `<i class="fas fa-hourglass-half"></i> Осталось ${window.CyberAuth.formatDuration(progress.remaining)}`;
        } else {
            // Длительности ещё не посчитаны — показываем хотя бы главу
            label.innerHTML = `<i class="fas fa-bookmark"></i> Глава ${progress.track_index + 1}`;
        }

        const bar = document.createElement('div');
        bar.className = 'album-card__progress-bar';
        const fill = document.createElement('div');
        fill.className = 'album-card__progress-fill';
        fill.style.width = `${finished ? 100 : (progress.percent || 0)}%`;
        bar.appendChild(fill);

        wrap.append(label, bar);
        return wrap;
    };

    const renderGrid = (data) => {
        grid.innerHTML = '';
        if (!data.items || data.items.length === 0) {
            grid.innerHTML = `<p class="error-message">В этой директории пусто.</p>`;
            return;
        }

        data.items.forEach(item => {
            const isAlbum = item.type === 'album';

            // Карточка — это ссылка: работают средний клик, Ctrl+клик и «открыть в новой вкладке».
            const card = document.createElement('a');
            card.className = 'album-card';
            card.href = isAlbum ? playerUrl(item.path) : indexUrl(item.path);
            card.dataset.path = item.path;
            card.dataset.type = item.type;

            const iconClass = isAlbum ? 'fa-compact-disc' : 'fa-folder';
            const safeName = item.name.replace(/</g, '&lt;').replace(/>/g, '&gt;');

            card.innerHTML = `
                <div class="album-card__image-container">
                    <img src="${item.cover}" alt="" aria-hidden="true" class="album-card__image-blur" onerror="this.remove();">
                    <img src="${item.cover}" alt="Обложка для ${safeName}" class="album-card__image" onerror="this.onerror=null;this.src='/static/assets/default_cover.png';">
                    <i class="fas ${iconClass} album-card__type-icon"></i>
                </div>
                <div class="album-card__info">
                    <h3 class="album-card__title">${safeName}</h3>
                </div>
            `;

            const progress = isAlbum ? progressMap.get(item.path) : null;
            if (progress) {
                card.classList.add('album-card--in-progress');
                card.querySelector('.album-card__info').appendChild(buildProgressBadge(progress));
            }

            card.addEventListener('click', (e) => {
                // Не перехватываем Ctrl/Cmd/средний клик — пусть браузер откроет новую вкладку
                if (e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return;
                e.preventDefault();
                if (isAlbum) {
                    // Переход на плеер — обычная навигация, лишней записи в истории не создаём
                    window.location.href = card.href;
                } else {
                    loadPath(card.dataset.path, { push: true });
                }
            });

            grid.appendChild(card);
        });
    };

    const loadPath = async (path = '', { push = false, replace = false } = {}) => {
        const myRequest = ++requestId;
        renderedPath = null;
        grid.innerHTML = `<div class="loading-state"><div class="loader"></div></div>`;
        renderBreadcrumbs(path);

        try {
            const [response] = await Promise.all([
                fetch(`${API_ENDPOINT}?path=${encodeURIComponent(path)}`),
                window.CyberAuth.ready
            ]);
            if (!response.ok) {
                throw new Error(`Ошибка сети: ${response.status}`);
            }
            const data = await response.json();

            // Пока грузились, пользователь мог уйти в другую папку
            if (myRequest !== requestId) return;

            if (data.type === 'album') {
                // Альбом на главной не отображается — уходим в плеер.
                // location.replace(), чтобы не оставлять в истории эту страницу
                // с бесконечным лоадером: иначе кнопка «Назад» возвращала бы
                // на спиннер и тут же снова редиректила в плеер.
                window.location.replace(playerUrl(path));
                return;
            }

            progressMap = await window.CyberAuth.loadProgress();
            if (myRequest !== requestId) return;

            lastData = data;
            renderGrid(data);
            renderedPath = path;

            // Обновляем URL в браузере для навигации
            if (push) {
                history.pushState({ path }, '', indexUrl(path));
            } else if (replace) {
                history.replaceState({ path }, '', indexUrl(path));
            }

        } catch (error) {
            console.error("Не удалось загрузить данные:", error);
            if (myRequest !== requestId) return;
            renderedPath = path;
            grid.innerHTML = `<p class="error-message">Ошибка загрузки данных. Проверьте консоль.</p>`;
        }
    };

    const pathFromUrl = () => new URLSearchParams(window.location.search).get('path') || '';

    // Обработка кнопок "вперед/назад" в браузере.
    // Важно: здесь НЕ пушим новое состояние, иначе история растёт при каждом «Назад».
    window.addEventListener('popstate', (e) => {
        const path = e.state && typeof e.state.path === 'string' ? e.state.path : pathFromUrl();
        loadPath(path, { replace: true });
    });

    // Возврат из bfcache (кнопка «Назад» после плеера): страница восстанавливается
    // ровно в том виде, в каком её покинули. Прогресс мог измениться, поэтому
    // перечитываем его; заодно чиним случай, когда там остался лоадер.
    window.addEventListener('pageshow', (e) => {
        if (!e.persisted) return;
        const path = pathFromUrl();
        if (renderedPath !== path) {
            loadPath(path, { replace: true });
        } else {
            refreshProgressBadges();
        }
    });

    // Вход или выход из аккаунта меняет набор плашек на карточках
    window.CyberAuth.onChange(() => { refreshProgressBadges(); });

    // Сброс книги в панели профиля должен сразу убрать плашку с карточки,
    // а не ждать перезагрузки страницы
    window.addEventListener('cyberaudio:progress-reset', () => { refreshProgressBadges(); });

    async function refreshProgressBadges() {
        if (!lastData) return;
        progressMap = await window.CyberAuth.loadProgress(true);
        renderGrid(lastData);
    }

    // Начальная загрузка
    loadPath(pathFromUrl(), { replace: true });
});
