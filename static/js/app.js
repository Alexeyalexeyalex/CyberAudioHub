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

    const searchInput = document.getElementById('library-search');
    const searchClear = document.getElementById('search-clear');
    const sortSelect = document.getElementById('library-sort');
    const foldersBox = document.getElementById('my-folders');
    let foldersView = false;   // показываем подборки вместо каталога
    let myFolders = [];
    let openFolderId = null;   // открыта личная подборка, а не папка на диске
    let folderContext = null;  // подборка, вглубь которой мы зашли
    let searchTimer = null;

    const playerUrl = (path) => `/player?path=${encodeURIComponent(path)}`;
    const indexUrl = (path) => (path ? `/?path=${encodeURIComponent(path)}` : '/');

    /**
     * Крошки для личных подборок. Строятся отдельно: у подборки нет пути
     * на диске, а показывать одну «Медиатеку» было непонятно.
     */
    const renderFolderCrumbs = (folderName) => {
        breadcrumbsContainer.innerHTML = '';

        const rootLink = document.createElement('a');
        rootLink.href = '/';
        rootLink.className = 'breadcrumb-item';
        rootLink.textContent = 'Медиатека';
        rootLink.addEventListener('click', (e) => {
            e.preventDefault();
            leaveFolders();
        });
        breadcrumbsContainer.appendChild(rootLink);

        const crumbs = folderName ? ['Мои папки', folderName] : ['Мои папки'];
        crumbs.forEach((label, index) => {
            const separator = document.createElement('span');
            separator.className = 'breadcrumb-separator';
            separator.textContent = '>';
            breadcrumbsContainer.appendChild(separator);

            if (index === crumbs.length - 1) {
                const current = document.createElement('span');
                current.className = 'breadcrumb-current';
                current.textContent = label;
                breadcrumbsContainer.appendChild(current);
            } else {
                const link = document.createElement('a');
                link.href = '#';
                link.className = 'breadcrumb-item';
                link.textContent = label;
                link.addEventListener('click', (e) => {
                    e.preventDefault();
                    openFolderId = null;
                    foldersView = true;
                    document.getElementById('folder-show').classList.add('is-active');
                    renderFolderCards();
                });
                breadcrumbsContainer.appendChild(link);
            }
        });
    };

    /** Выход из подборок в обычный каталог. */
    const leaveFolders = () => {
        foldersView = false;
        openFolderId = null;
        const btn = document.getElementById('folder-show');
        if (btn) btn.classList.remove('is-active');
        loadPath('', { push: true });
    };

    const crumbSeparator = () => {
        const sep = document.createElement('span');
        sep.className = 'breadcrumb-separator';
        sep.textContent = '>';
        return sep;
    };

    const renderBreadcrumbs = (path) => {
        breadcrumbsContainer.innerHTML = '';
        const parts = path ? path.split('/') : [];

        const rootLink = document.createElement('a');
        rootLink.href = '/';
        rootLink.className = 'breadcrumb-item';
        rootLink.textContent = 'Медиатека';
        rootLink.addEventListener('click', (e) => {
            e.preventDefault();
            folderContext = null;
            loadPath('', { push: true });
        });
        breadcrumbsContainer.appendChild(rootLink);

        // Зашли в книгу из своей подборки — она остаётся в цепочке
        if (folderContext) {
            breadcrumbsContainer.appendChild(crumbSeparator());
            const foldersLink = document.createElement('a');
            foldersLink.href = '#';
            foldersLink.className = 'breadcrumb-item';
            foldersLink.textContent = 'Мои папки';
            foldersLink.addEventListener('click', (e) => {
                e.preventDefault();
                folderContext = null;
                foldersView = true;
                document.getElementById('folder-show').classList.add('is-active');
                renderFolderCards();
            });
            breadcrumbsContainer.appendChild(foldersLink);

            breadcrumbsContainer.appendChild(crumbSeparator());
            const folderLink = document.createElement('a');
            folderLink.href = '#';
            folderLink.className = 'breadcrumb-item';
            folderLink.textContent = folderContext.name;
            const target = folderContext;
            folderLink.addEventListener('click', (e) => {
                e.preventDefault();
                folderContext = null;
                showFolder(target);
            });
            breadcrumbsContainer.appendChild(folderLink);
        }

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

    /**
     * Порядок карточек. Папки и книги сортируются вместе, без разделения:
     * выбранный порядок применяется ко всему списку целиком.
     */
    const sortItems = (items) => {
        const mode = sortSelect ? sortSelect.value : 'name';
        // numeric: true — иначе «07» встаёт после «10», как обычные строки
        const byName = (a, b) => a.name.localeCompare(b.name, 'ru',
            { numeric: true, sensitivity: 'base' });
        const copy = items.slice();

        copy.sort((a, b) => {
            if (mode === 'name-desc') return byName(b, a);
            if (mode === 'recent') {
                const ta = lastListened(a), tb = lastListened(b);
                if (ta !== tb) return tb - ta;   // недавние выше
                return byName(a, b);
            }
            if (mode === 'remaining') {
                const va = leastRemaining(a), vb = leastRemaining(b);
                if (va !== vb) return va - vb;
                return byName(a, b);
            }
            return byName(a, b);
        });
        return copy;
    };

    /**
     * Прогресс папки собираем из книг внутри неё: у самой папки его нет,
     * а без этого сортировки «недавние» и «меньше осталось» её не двигали.
     */
    const progressUnder = (item) => {
        if (item.type === 'album') {
            const own = progressMap.get(item.path);
            return own ? [own] : [];
        }
        const prefix = item.path + '/';
        const found = [];
        progressMap.forEach((value, key) => {
            if (key.startsWith(prefix)) found.push(value);
        });
        return found;
    };

    const lastListened = (item) => progressUnder(item).reduce(
        (best, p) => Math.max(best, Date.parse(p.updated_at) || 0), 0);

    const leastRemaining = (item) => progressUnder(item).reduce(
        (best, p) => (p.remaining != null ? Math.min(best, p.remaining) : best), Infinity);

    const renderGrid = (data, emptyText) => {
        grid.innerHTML = '';
        if (!data.items || data.items.length === 0) {
            grid.innerHTML = `<p class="error-message">${emptyText || 'В этой директории пусто.'}</p>`;
            return;
        }

        sortItems(data.items).forEach(item => {
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
                    ${item.has_text ? `<i class="fas fa-align-left album-card__text-icon"
                        title="Есть текстовая версия" aria-label="Есть текстовая версия"></i>` : ''}
                </div>
                <div class="album-card__info">
                    <h3 class="album-card__title">${safeName}</h3>
                </div>
            `;

            // Скачивание всей книги — только у книг и только если разрешено
            if (isAlbum && window.CyberAuth && CyberAuth.downloadsEnabled()) {
                const dl = document.createElement('a');
                dl.className = 'album-card__download';
                dl.href = `/api/download/book?path=${encodeURIComponent(item.path)}`;
                dl.title = 'Скачать книгу архивом';
                dl.setAttribute('download', '');
                dl.innerHTML = '<i class="fas fa-download"></i>';
                dl.addEventListener('click', (e) => e.stopPropagation());
                card.querySelector('.album-card__image-container').appendChild(dl);
            }

            if (window.CyberAuth && CyberAuth.isLoggedIn()) {
                const pin = document.createElement('button');
                pin.type = 'button';
                pin.className = 'album-card__folder-btn';
                const inFolder = openFolderId !== null;
                pin.title = inFolder ? 'Убрать из папки' : 'Добавить в мою папку';
                pin.innerHTML = inFolder
                    ? '<i class="fas fa-folder-minus"></i>'
                    : '<i class="fas fa-folder-plus"></i>';
                pin.addEventListener('click', async (e) => {
                    e.preventDefault();
                    e.stopPropagation();   // не открываем книгу этим кликом
                    if (inFolder) {
                        await fetch(`/api/folders/${openFolderId}/items`, {
                            method: 'DELETE', credentials: 'same-origin',
                            headers: { 'Content-Type': 'application/json' },
                            body: JSON.stringify({ path: item.path })
                        });
                        await loadFolders();
                        const folder = myFolders.find(f => f.id === openFolderId);
                        if (folder) showFolder(folder);
                    } else {
                        openFolderPicker(item);
                    }
                });
                card.querySelector('.album-card__image-container').appendChild(pin);
            }

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
                    // Заходя вглубь из подборки, сохраняем её в крошках
                    const from = openFolderId !== null
                        ? myFolders.find(f => f.id === openFolderId) : null;
                    loadPath(card.dataset.path, { push: true, fromFolder: from || folderContext });
                }
            });

            grid.appendChild(card);
        });
    };

    const loadPath = async (path = '', { push = false, replace = false,
                                         fromFolder = null } = {}) => {
        // Иначе карточки каталога рисовались бы с кнопкой «убрать из папки»
        foldersView = false;
        openFolderId = null;
        folderContext = fromFolder || null;
        const folderBtn = document.getElementById('folder-show');
        if (folderBtn) folderBtn.classList.remove('is-active');
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

    // --- Поиск ---

    const runSearch = async (query) => {
        openFolderId = null;
        grid.innerHTML = `<div class="loading-state"><div class="loader"></div></div>`;
        try {
            const resp = await fetch(`/api/search?q=${encodeURIComponent(query)}`);
            const data = await resp.json();
            progressMap = await window.CyberAuth.loadProgress();
            lastData = data;
            renderGrid(data, `Ничего не нашлось по запросу «${query}».`);
        } catch (e) {
            grid.innerHTML = `<p class="error-message">Не удалось выполнить поиск.</p>`;
        }
    };

    const onSearchInput = () => {
        const query = searchInput.value.trim();
        searchClear.hidden = !query;
        clearTimeout(searchTimer);
        if (query.length < 2) {
            // Пустой или слишком короткий запрос возвращает обычный каталог
            if (!query) searchTimer = setTimeout(() => loadPath(pathFromUrl(), { replace: true }), 200);
            return;
        }
        searchTimer = setTimeout(() => runSearch(query), 300);
    };

    if (searchInput) {
        searchInput.addEventListener('input', onSearchInput);
        searchClear.addEventListener('click', () => {
            searchInput.value = '';
            searchClear.hidden = true;
            loadPath(pathFromUrl(), { replace: true });
        });
    }
    if (sortSelect) {
        sortSelect.addEventListener('change', () => {
            if (lastData) renderGrid(lastData);
        });
    }

    // --- Личные папки ---

    const loadFolders = async () => {
        if (!window.CyberAuth.isLoggedIn()) {
            myFolders = [];
            foldersBox.hidden = true;
            return;
        }
        try {
            const resp = await fetch('/api/folders', { credentials: 'same-origin' });
            const data = await resp.json();
            myFolders = data.folders || [];
        } catch (e) {
            myFolders = [];
        }
        renderFolderCards();
    };

    /**
     * Папки читателя рисуем такими же карточками, как папки на диске:
     * взгляду не приходится перестраиваться между двумя видами списка.
     * Обложка — первая книга внутри, иначе заглушка.
     */
    const renderFolderCards = () => {
        foldersBox.hidden = !window.CyberAuth.isLoggedIn();
        if (!foldersView) return;
        grid.innerHTML = '';
        renderFolderCrumbs('');
        if (!myFolders.length) {
            grid.innerHTML =
                '<p class="error-message">Папок пока нет. ' +
                'Создайте подборку и складывайте в неё книги и папки.</p>';
            return;
        }

        myFolders.forEach(folder => {
            // Своя картинка важнее: она выбрана человеком осознанно
            const cover = folder.cover
                || (folder.items.length ? folder.items[0].cover
                                        : '/static/assets/default_cover.png');
            const card = document.createElement('a');
            card.className = 'album-card album-card--folder'
                + (openFolderId === folder.id ? ' is-open' : '');
            card.href = '#';
            card.dataset.folderId = folder.id;
            card.innerHTML = `
                <div class="album-card__image-container">
                    <img src="${cover}" alt="" aria-hidden="true" class="album-card__image-blur" onerror="this.remove();">
                    <img src="${cover}" alt="Папка ${escapeHtml(folder.name)}" class="album-card__image" onerror="this.onerror=null;this.src='/static/assets/default_cover.png';">
                    <i class="fas fa-folder-open album-card__type-icon"></i>
                    <span class="album-card__folder-count">${folder.items.length}</span>
                </div>
                <div class="album-card__info">
                    <h3 class="album-card__title">${escapeHtml(folder.name)}</h3>
                </div>
            `;

            const del = document.createElement('button');
            del.type = 'button';
            del.className = 'album-card__folder-btn album-card__folder-del';
            del.title = 'Удалить папку';
            del.innerHTML = '<i class="fas fa-trash"></i>';
            del.addEventListener('click', async (e) => {
                e.preventDefault();
                e.stopPropagation();
                if (!await CyberUI.confirm(
                    `Удалить папку «${folder.name}»? Книги останутся на месте.`,
                    'Удаление папки', 'Удалить')) return;
                await fetch(`/api/folders/${folder.id}`, { method: 'DELETE',
                    credentials: 'same-origin' });
                if (openFolderId === folder.id) {
                    openFolderId = null;
                    loadPath(pathFromUrl(), { replace: true });
                }
                await loadFolders();
            });
            const pic = document.createElement('button');
            pic.type = 'button';
            pic.className = 'album-card__folder-btn album-card__folder-pic';
            pic.title = folder.cover ? 'Сменить или убрать картинку' : 'Поставить свою картинку';
            pic.innerHTML = '<i class="fas fa-image"></i>';
            pic.addEventListener('click', (e) => {
                e.preventDefault();
                e.stopPropagation();
                chooseCover(folder);
            });

            card.querySelector('.album-card__image-container').append(del, pic);

            card.addEventListener('click', (e) => {
                e.preventDefault();
                if (openFolderId === folder.id) {
                    // Повторный клик закрывает подборку и возвращает каталог
                    openFolderId = null;
                    renderFolderCards();
                    loadPath(pathFromUrl(), { replace: true });
                } else {
                    showFolder(folder);
                }
            });
            grid.appendChild(card);
        });
    };

    const showFolder = (folder) => {
        openFolderId = folder.id;
        foldersView = false;
        document.getElementById('folder-show').classList.remove('is-active');
        renderFolderCrumbs(folder.name);
        lastData = { items: folder.items };
        renderGrid(lastData, `В папке «${folder.name}» пока пусто.`);
    };

    const escapeHtml = (value) => String(value)
        .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

    /** Загрузка своей картинки для папки: выбор файла обычным диалогом системы. */
    async function chooseCover(folder) {
        if (folder.cover) {
            const what = await CyberUI.choose(
                `Картинка папки «${folder.name}»`,
                [{ label: 'Выбрать другую', value: 'new', icon: 'fa-image' },
                 { label: 'Убрать картинку', value: 'drop', icon: 'fa-trash' }],
                'Картинка папки');
            if (what === null) return;
            if (what === 'drop') {
                await fetch(`/api/folders/${folder.id}/cover`,
                            { method: 'DELETE', credentials: 'same-origin' });
                await loadFolders();
                return;
            }
        }

        const picker = document.createElement('input');
        picker.type = 'file';
        picker.accept = 'image/*';
        picker.addEventListener('change', async () => {
            const file = picker.files && picker.files[0];
            if (!file) return;
            const form = new FormData();
            form.append('image', file);
            try {
                const resp = await fetch(`/api/folders/${folder.id}/cover`, {
                    method: 'POST', credentials: 'same-origin', body: form });
                const data = await resp.json();
                if (!resp.ok) throw new Error(data.error || 'Не удалось загрузить');
                await loadFolders();
            } catch (e) {
                CyberUI.alert(e.message, 'Картинка не загрузилась');
            }
        });
        picker.click();
    }

    async function openFolderPicker(item) {
        let info;
        try {
            const resp = await fetch(
                `/api/folders/for-book?path=${encodeURIComponent(item.path)}`,
                { credentials: 'same-origin' });
            info = await resp.json();
        } catch (e) {
            return;
        }
        // Выбор кнопкой: вводить номер руками неудобно и легко ошибиться
        const options = info.folders.map(f => ({
            label: f.name,
            value: String(f.id),
            icon: 'fa-folder',
            hint: info.selected.includes(f.id) ? 'уже там' : '',
        }));
        options.push({ label: 'Создать новую папку', value: 'new', icon: 'fa-folder-plus' });

        const picked = await CyberUI.choose(`Куда положить «${item.name}»?`,
                                            options, 'В мою папку');
        if (picked === null) return;

        let folderId = null;
        if (picked === 'new') {
            const name = (await CyberUI.prompt(
                'Название новой папки', '', 'Новая папка', 'Создать') || '').trim();
            if (!name) return;
            const created = await fetch('/api/folders', {
                method: 'POST', credentials: 'same-origin',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ name })
            }).then(r => r.json()).catch(() => null);
            if (!created || !created.id) return;
            folderId = created.id;
        } else {
            folderId = Number(picked);
            if (info.selected.includes(folderId)) return;   // уже лежит там
        }
        await fetch(`/api/folders/${folderId}/items`, {
            method: 'POST', credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ path: item.path })
        });
        await loadFolders();
    }

    if (foldersBox) {
        document.getElementById('folder-show').addEventListener('click', () => {
            const btn = document.getElementById('folder-show');
            foldersView = !foldersView;
            openFolderId = null;
            btn.classList.toggle('is-active', foldersView);
            if (foldersView) {
                renderFolderCards();
            } else {
                loadPath(pathFromUrl(), { replace: true });
            }
        });

        document.getElementById('folder-add').addEventListener('click', async () => {
            const name = (await CyberUI.prompt(
                'Название новой папки', '', 'Новая папка', 'Создать') || '').trim();
            if (!name) return;
            await fetch('/api/folders', {
                method: 'POST', credentials: 'same-origin',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ name })
            });
            foldersView = true;
            document.getElementById('folder-show').classList.add('is-active');
            await loadFolders();
        });
    }

    window.CyberAuth.ready.then(loadFolders);
    window.CyberAuth.onChange(() => { loadFolders(); });

    // Начальная загрузка
    loadPath(pathFromUrl(), { replace: true });
});
