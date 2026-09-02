document.addEventListener('DOMContentLoaded', () => {
    const $ = (id) => document.getElementById(id);

    const els = {
        warning: $('admin-warning'),
        stats: $('admin-stats'),
        users: document.querySelector('#users-table tbody'),
        progress: document.querySelector('#progress-table tbody'),
        albums: document.querySelector('#albums-table tbody'),
        tree: $('library-tree'),
        roots: document.querySelector('#roots-table tbody'),
        themeList: $('theme-list'),
        messages: document.querySelector('#messages-table tbody'),
        messagesToggle: $('messages-toggle'),
        achievements: document.querySelector('#achievements-table tbody'),
        userAchievements: document.querySelector('#user-achievements-table tbody'),
        userAchievementsNote: $('user-achievements-note'),
        requests: document.querySelector('#requests-table tbody'),
        textbooks: document.querySelector('#textbooks-table tbody'),
        textbooksSection: $('textbooks-section'),
        textbooksNote: $('textbooks-note'),
        voiceSection: $('voice-section'),
        voiceList: $('voice-list'),
        voiceNote: $('voice-note'),
        requestsSection: $('requests-section'),
        requestsNote: $('requests-note'),
        engineNote: $('engine-note'),
        addBtn: $('add-user-btn'),
        overlay: $('user-form-overlay'),
        form: $('user-form'),
        formTitle: $('user-form-title'),
        formClose: $('user-form-close'),
        login: $('form-login'),
        nickname: $('form-nickname'),
        password: $('form-password'),
        passwordHint: $('form-password-hint'),
        admin: $('form-admin'),
        error: $('form-error'),
        submit: $('form-submit'),
    };

    let editingId = null;      // null — создание, число — редактирование
    let currentUserId = null;
    let lastBooks = [];
    let lastTree = [];

    // --- Сеть ---

    async function api(url, options = {}) {
        const response = await fetch(url, {
            credentials: 'same-origin',
            headers: options.body ? { 'Content-Type': 'application/json' } : {},
            ...options
        });
        let data = null;
        try { data = await response.json(); } catch (e) { /* пустой ответ */ }
        if (!response.ok) throw new Error((data && data.error) || `Ошибка ${response.status}`);
        return data;
    }

    const send = (method, url, body) => api(url, { method, body: JSON.stringify(body || {}) });

    // --- Вспомогательное ---

    const text = (value) => {
        const td = document.createElement('td');
        td.textContent = value === null || value === undefined ? '—' : String(value);
        return td;
    };

    const shortDate = (iso) => {
        if (!iso) return '—';
        const d = new Date(iso);
        return isNaN(d) ? iso : d.toLocaleString('ru-RU', {
            day: '2-digit', month: '2-digit', year: '2-digit', hour: '2-digit', minute: '2-digit'
        });
    };

    function iconButton(icon, title, className, handler) {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = `row-btn ${className || ''}`.trim();
        btn.title = title;
        btn.setAttribute('aria-label', title);
        btn.innerHTML = `<i class="fas ${icon}"></i>`;
        btn.addEventListener('click', handler);
        return btn;
    }

    async function guarded(button, action) {
        button.disabled = true;
        try {
            await action();
            await load();
        } catch (e) {
            CyberUI.alert(e.message);
            button.disabled = false;
        }
    }

    // --- Отрисовка ---

    function renderStats(stats) {
        els.stats.innerHTML = '';
        const cards = [
            ['Пользователей', stats.users, 'fa-users'],
            ['Из них админов', stats.admins, 'fa-user-shield'],
            ['Записей прогресса', stats.progress, 'fa-bookmark'],
            ['Книг в кэше', stats.albums, 'fa-compact-disc'],
            ['Запрошено книг', stats.requests, 'fa-hand'],
        ];
        cards.forEach(([label, value, icon]) => {
            const box = document.createElement('div');
            box.className = 'stat-card';
            box.innerHTML = `<i class="fas ${icon}"></i><span class="stat-card__value">${value}</span>
                             <span class="stat-card__label">${label}</span>`;
            els.stats.appendChild(box);
        });
    }

    function renderUsers(users) {
        els.users.innerHTML = '';
        users.forEach(user => {
            const tr = document.createElement('tr');
            const isSelf = user.id === currentUserId;

            const role = document.createElement('td');
            role.innerHTML = user.is_admin
                ? '<span class="badge badge--admin">админ</span>'
                : '<span class="badge">пользователь</span>';

            const login = text(user.login);
            if (isSelf) {
                const me = document.createElement('span');
                me.className = 'badge badge--self';
                me.textContent = 'вы';
                login.appendChild(document.createTextNode(' '));
                login.appendChild(me);
            }

            const actions = document.createElement('td');
            actions.className = 'row-actions';
            actions.appendChild(iconButton('fa-pen', 'Редактировать', '', () => openForm(user)));

            const del = iconButton('fa-trash', 'Удалить', 'row-btn--danger', async () => {
                if (!await CyberUI.confirm(`Удалить пользователя «${user.login}»? Его прогресс тоже будет стёрт.`)) return;
                guarded(del, () => send('DELETE', `/api/admin/users/${user.id}`));
            });
            if (isSelf) {
                del.disabled = true;
                del.title = 'Нельзя удалить себя';
            }
            actions.appendChild(del);

            tr.append(text(user.id), login, text(user.nickname), role,
                text(user.password_algo), text(user.books), text(shortDate(user.created_at)), actions);
            els.users.appendChild(tr);
        });
    }

    const STATUS_LABEL = {
        none: 'нет',
        pending: 'в очереди',
        running: 'распознаётся',
        done: 'готов',
        error: 'ошибка',
    };

    // Состояние раскрытия веток храним по пути: при самообновлении дерево
    // не должно схлопываться под руками.
    const openBranches = new Set();
    let treeLoaded = false;

    /**
     * Медиатека деревом. Переключатель есть и у книги, и у папки: включённая
     * папка ставит в очередь всё, что внутри, — иначе пришлось бы протыкивать
     * каждую книгу по отдельности.
     */
    let treeSnapshot = '';

    function renderTree(nodes, force) {
        const box = els.tree;
        if (!box) return;
        // Самообновление не должно перерисовывать дерево впустую: иначе оно
        // подменяет элементы прямо под курсором и клик уходит в никуда.
        const snapshot = JSON.stringify(nodes) + '|' + [...openBranches].sort().join(',');
        if (!force && snapshot === treeSnapshot) return;
        treeSnapshot = snapshot;
        box.innerHTML = '';
        if (!nodes || !nodes.length) {
            box.innerHTML = '<p class="table-empty">Книг в медиатеке нет.</p>';
            return;
        }
        if (!treeLoaded) {
            // При первом показе раскрываем верхний уровень: пустое дерево
            // выглядело бы так, будто книг нет
            nodes.forEach(n => { if (n.type === 'directory') openBranches.add(n.path); });
            treeLoaded = true;
            treeSnapshot = JSON.stringify(nodes) + '|' + [...openBranches].sort().join(',');
        }
        nodes.forEach(node => box.appendChild(buildNode(node, 0)));
    }

    /** Собирает книги ветки — чтобы посчитать, сколько из них уже с текстом. */
    function collectAlbums(node, out) {
        if (node.type === 'album') out.push(node);
        else (node.children || []).forEach(child => collectAlbums(child, out));
        return out;
    }

    function buildNode(node, depth) {
        const wrap = document.createElement('div');
        wrap.className = 'tree-node';

        const row = document.createElement('div');
        row.className = 'tree-row tree-row--' + node.type;
        row.style.paddingLeft = `${depth * 1.25}rem`;

        const albums = collectAlbums(node, []);
        const active = albums.filter(a => a.status !== 'none');

        // Раскрывашка
        const caret = document.createElement('button');
        caret.type = 'button';
        caret.className = 'tree-caret';
        if (node.type === 'directory') {
            const open = openBranches.has(node.path);
            caret.innerHTML = `<i class="fas fa-chevron-${open ? 'down' : 'right'}"></i>`;
            caret.addEventListener('click', () => {
                if (openBranches.has(node.path)) openBranches.delete(node.path);
                else openBranches.add(node.path);
                renderTree(lastTree, true);
            });
        } else {
            caret.className += ' tree-caret--leaf';
            caret.disabled = true;
            caret.innerHTML = '<i class="fas fa-book"></i>';
        }

        // Переключатель уровня
        const label = document.createElement('label');
        label.className = 'switch';
        const box = document.createElement('input');
        box.type = 'checkbox';
        box.checked = albums.length > 0 && active.length === albums.length;
        box.indeterminate = active.length > 0 && active.length < albums.length;
        box.disabled = albums.length === 0;
        box.addEventListener('change', async () => {
            const turningOff = !box.checked;
            const what = node.type === 'album'
                ? `«${node.name}»`
                : `все книги в «${node.name}» (${albums.length})`;
            if (turningOff && !await CyberUI.confirm(`Удалить текстовую версию: ${what}?`)) {
                box.checked = true;
                return;
            }
            box.disabled = true;
            try {
                await send('PUT', '/api/admin/transcripts',
                           { path: node.path, enabled: box.checked });
                await refreshTranscripts();
            } catch (e) {
                CyberUI.alert(e.message);
                box.checked = !box.checked;
            } finally {
                box.disabled = false;
            }
        });
        label.append(box, document.createElement('span'));

        const title = document.createElement('span');
        title.className = 'tree-title';
        title.textContent = node.name;

        const info = document.createElement('span');
        info.className = 'tree-info';
        if (node.type === 'album') {
            const cls = { done: 'badge--ok', error: 'badge--warn',
                          running: 'badge--run' }[node.status] || '';
            const label2 = STATUS_LABEL[node.status] || node.status;
            const chapters = node.status === 'none'
                ? '' : ` ${node.done_tracks} / ${node.total_tracks}`;
            info.innerHTML = `<span class="badge ${cls}">${label2}</span>` +
                `<span class="tree-count">${chapters}</span>` +
                (node.engine ? `<span class="tree-engine">${node.engine}</span>` : '');
        } else {
            info.innerHTML = `<span class="tree-count">${active.length} из ${albums.length}` +
                ` ${plural(albums.length, 'книга', 'книги', 'книг')} с текстом</span>`;
        }

        row.append(caret, label, title, info);
        wrap.appendChild(row);

        if (node.type === 'directory' && openBranches.has(node.path)) {
            (node.children || []).forEach(child => {
                wrap.appendChild(buildNode(child, depth + 1));
            });
        }
        return wrap;
    }


    // --- Текстовые книги и озвучка ---
    //
    // Зеркало раздела с текстовыми версиями, только наоборот: там из аудио
    // делают текст, здесь из документа делают аудио.

    const VOICE_STATUS = {
        pending: 'в очереди',
        running: 'озвучивается',
        done: 'готово',
        error: 'ошибка',
    };

    const fileSize = (bytes) => {
        const n = Number(bytes) || 0;
        if (n < 1024) return `${n} Б`;
        if (n < 1024 * 1024) return `${(n / 1024).toFixed(0)} КБ`;
        return `${(n / 1024 / 1024).toFixed(1)} МБ`;
    };

    function renderTextBooks(books) {
        const list = books || [];
        // Раздел прячем целиком, когда книг нет: пустая таблица только
        // отвлекала бы от остальных разделов
        els.textbooksSection.hidden = list.length === 0;
        els.voiceSection.hidden = list.length === 0;
        els.textbooksNote.textContent = list.length
            ? `${list.length} ${plural(list.length, 'книга', 'книги', 'книг')}` : '';

        els.textbooks.innerHTML = '';
        list.forEach((book) => {
            const tr = document.createElement('tr');
            const names = (book.documents || []).map(d => d.name).join(', ');
            const size = (book.documents || []).reduce((s, d) => s + (d.size || 0), 0);
            const audio = document.createElement('td');
            audio.innerHTML = book.has_audio
                ? '<span class="badge badge--ok">есть</span>'
                : '<span class="badge">нет</span>';
            tr.append(text(book.path), text(names), text(fileSize(size)), audio);
            els.textbooks.appendChild(tr);
        });
    }

    function renderVoiceList(books) {
        const list = books || [];
        els.voiceList.innerHTML = '';
        if (!list.length) {
            els.voiceList.innerHTML =
                '<p class="table-empty">Книг с документами не найдено.</p>';
            els.voiceNote.textContent = '';
            return;
        }
        const busy = list.filter(b => b.voice && b.voice.status !== 'done').length;
        els.voiceNote.textContent = busy
            ? `в работе: ${busy}` : '';

        list.forEach((book) => {
            const row = document.createElement('div');
            row.className = 'tree-row tree-row--album';

            const caret = document.createElement('button');
            caret.type = 'button';
            caret.className = 'tree-caret tree-caret--leaf';
            caret.disabled = true;
            caret.innerHTML = '<i class="fas fa-file-lines"></i>';

            const label = document.createElement('label');
            label.className = 'switch';
            const box = document.createElement('input');
            box.type = 'checkbox';
            box.checked = Boolean(book.voice);
            box.addEventListener('change', async () => {
                if (!box.checked && !await CyberUI.confirm(
                        `Удалить озвучку книги «${book.title}»? ` +
                        'Сгенерированные аудиофайлы и текст будут стёрты.')) {
                    box.checked = true;
                    return;
                }
                box.disabled = true;
                try {
                    const data = await send('PUT', '/api/admin/voiceovers',
                                            { path: book.path, enabled: box.checked });
                    renderTextBooks(data.books);
                    renderVoiceList(data.books);
                } catch (e) {
                    CyberUI.alert(e.message);
                    box.checked = !box.checked;
                } finally {
                    box.disabled = false;
                }
            });
            label.append(box, document.createElement('span'));

            const title = document.createElement('span');
            title.className = 'tree-title';
            title.textContent = book.title;

            const info = document.createElement('span');
            info.className = 'tree-info';
            const state = book.voice;
            if (!state) {
                info.innerHTML = '<span class="tree-count">не озвучена</span>';
            } else {
                const cls = { done: 'badge--ok', error: 'badge--warn',
                              running: 'badge--run' }[state.status] || '';
                const chapters = state.total_tracks
                    ? ` ${state.done_tracks} / ${state.total_tracks}` : ' разбор документа';
                info.innerHTML =
                    `<span class="badge ${cls}">${VOICE_STATUS[state.status] || state.status}</span>` +
                    `<span class="tree-count">${chapters}</span>` +
                    (state.engine ? `<span class="tree-engine">${state.engine}</span>` : '');
                if (state.error) {
                    info.title = state.error;
                }
            }

            row.append(caret, label, title, info);
            els.voiceList.appendChild(row);
        });
    }

    /** Пока идёт озвучка, обновляем раздел короткими опросами. */
    let voiceTimer = null;

    async function refreshTextBooks() {
        try {
            const data = await api('/api/admin/textbooks');
            renderTextBooks(data.books);
            renderVoiceList(data.books);
            // Озвучка книги идёт часами, но главы приходят по одной —
            // пока работа есть, смотрим чаще
            const busy = (data.queue || []).length > 0;
            clearTimeout(voiceTimer);
            voiceTimer = setTimeout(refreshTextBooks, busy ? 5000 : 30000);
        } catch (e) {
            clearTimeout(voiceTimer);
            voiceTimer = setTimeout(refreshTextBooks, 60000);
        }
    }

    /** Пока идёт распознавание, обновляем таблицу книг короткими опросами. */
    let pollTimer = null;

    async function refreshTranscripts() {
        try {
            const data = await api('/api/admin/transcripts');
            const byPath = new Map(data.items.map(i => [i.path, i]));
            lastBooks = lastBooks.map(b => Object.assign({}, b, byPath.get(b.path) || { status: 'none' }));
            lastTree = data.tree || lastTree;
            renderTree(lastTree);
            renderRequests(data.requests);
            renderQueueNote(data.queue);

            // Пока идёт распознавание — частый опрос, иначе редкий: дерево
            // должно подхватывать и книги, добавленные на диск со стороны.
            const busy = (data.items || []).some(
                b => b.status === 'running' || b.status === 'pending');
            clearTimeout(pollTimer);
            pollTimer = setTimeout(refreshTranscripts, busy ? 2000 : 15000);
        } catch (e) {
            // Сервер мог перезапуститься — не бросаем опрос совсем
            clearTimeout(pollTimer);
            pollTimer = setTimeout(refreshTranscripts, 30000);
        }
    }

    /**
     * Книги, для которых читатели просят текст. Раздел прячется целиком,
     * когда просьб нет: пустая таблица только отвлекала бы.
     */
    /** Папки с музыкой: основная плюс подключённые с других дисков. */
    function renderRoots(roots) {
        els.roots.innerHTML = '';
        (roots || []).forEach(root => {
            const tr = document.createElement('tr');

            const state = document.createElement('td');
            state.innerHTML = root.exists
                ? '<span class="badge badge--ok">доступна</span>'
                : '<span class="badge badge--warn">папки нет</span>';

            const actions = document.createElement('td');
            actions.className = 'row-actions';

            const edit = iconButton('fa-pen',
                root.main ? 'Изменить путь основной папки' : 'Изменить', '', async () => {
                const path = await CyberUI.prompt(
                    `Путь к папке «${root.name}»:`, root.path, 'Изменение папки', 'Сохранить');
                if (path === null) return;
                let name = root.name;
                if (!root.main) {
                    const asked = await CyberUI.prompt(
                        'Название раздела на главной:', root.name, 'Изменение папки', 'Сохранить');
                    if (asked === null) return;
                    name = asked;
                }
                try {
                    await send('PATCH', '/api/admin/roots',
                               { id: root.id, name, path });
                    await load();
                } catch (e) {
                    CyberUI.alert(e.message);
                }
            });
            actions.appendChild(edit);

            if (!root.main) {
                const del = iconButton('fa-trash', 'Убрать папку из медиатеки',
                                       'row-btn--danger', async () => {
                    if (!await CyberUI.confirm(`Убрать «${root.name}» из медиатеки? ` +
                                 'Файлы на диске останутся на месте.')) return;
                    guarded(del, () => send('DELETE', '/api/admin/roots', { id: root.id }));
                });
                actions.appendChild(del);
            }

            const nameCell = text(root.name);
            if (root.main) {
                const badge = document.createElement('span');
                badge.className = 'badge';
                badge.textContent = 'основная';
                nameCell.appendChild(document.createTextNode(' '));
                nameCell.appendChild(badge);
            }

            const pathCell = text(root.path);
            pathCell.className = 'cell-path';

            const stateCell = state;
            if (root.exists) {
                stateCell.innerHTML += ` <span class="tree-count">книг: ${root.books}</span>`;
            }

            tr.append(nameCell, pathCell, stateCell, actions);
            els.roots.appendChild(tr);
        });
    }

    // Раскрытые ветки в сгруппированных таблицах помним по ключу,
    // чтобы обновление данных не схлопывало их под руками
    const openRows = new Set();

    /** Строка-заголовок группы: сворачивает и разворачивает вложенное. */
    function groupRow(key, label, extra, columns, className) {
        const tr = document.createElement('tr');
        tr.className = className || 'group-row';
        const cell = document.createElement('td');
        cell.colSpan = columns;
        const open = openRows.has(key);
        cell.innerHTML = `<i class="fas fa-chevron-${open ? 'down' : 'right'}"></i>` +
            `${label}` + (extra ? ` <span class="tree-count">${extra}</span>` : '');
        tr.appendChild(cell);
        tr.addEventListener('click', () => {
            if (openRows.has(key)) openRows.delete(key);
            else openRows.add(key);
            load();
        });
        return tr;
    }

    const COLOR_NAMES = { pink: 'розовый', green: 'зелёный',
                          yellow: 'жёлтый', red: 'красный' };
    const RARITY_NAMES = { common: 'обычное', rare: 'редкое',
                           mythic: 'мифическое', legendary: 'легендарное' };
    let achievementTargets = [];

    /** Праздники: галочка включает оформление на всём сайте. */
    function renderThemes(data) {
        const themes = data.themes || [];
        const intensity = $('theme-intensity');
        const video = $('theme-video');
        if (intensity) intensity.value = String(data.intensity ?? 1);
        if (video) video.value = data.video || '';
        els.themeList.innerHTML = '';
        themes.forEach((theme) => {
            const item = document.createElement('div');
            item.className = 'theme-item';

            const label = document.createElement('label');
            label.className = 'switch';
            const box = document.createElement('input');
            box.type = 'checkbox';
            box.checked = Boolean(theme.enabled);
            box.addEventListener('change', async () => {
                box.disabled = true;
                try {
                    await send('PUT', '/api/admin/themes',
                               { id: theme.id, enabled: box.checked });
                } catch (e) {
                    box.checked = !box.checked;
                    CyberUI.alert(e.message, 'Не получилось');
                } finally {
                    box.disabled = false;
                }
            });
            label.append(box, document.createElement('span'));

            const textBox = document.createElement('div');
            textBox.className = 'theme-item__text';
            const name = document.createElement('span');
            name.className = 'theme-item__name';
            name.textContent = theme.name;
            const hint = document.createElement('span');
            hint.className = 'theme-item__hint';
            hint.textContent = theme.hint;
            textBox.append(name, hint);

            item.append(label, textBox);
            els.themeList.appendChild(item);
        });
    }

    /** Бегущие строки: текст, цвет и показ по галочке. */
    function renderMessages(data) {
        els.messagesToggle.checked = Boolean(data.enabled);
        els.messages.innerHTML = '';
        (data.messages || []).forEach((item) => {
            const tr = document.createElement('tr');

            const textCell = text(item.text);
            textCell.className = 'cell-path';

            const colorCell = document.createElement('td');
            colorCell.innerHTML =
                `<span class="color-dot color-dot--${item.color}"></span> ` +
                (COLOR_NAMES[item.color] || item.color);

            const showCell = document.createElement('td');
            const label = document.createElement('label');
            label.className = 'switch';
            const box = document.createElement('input');
            box.type = 'checkbox';
            box.checked = item.enabled;
            box.addEventListener('change', async () => {
                box.disabled = true;
                try {
                    await send('PATCH', '/api/admin/messages', {
                        id: item.id, text: item.text, color: item.color,
                        enabled: box.checked });
                    await load();
                } catch (e) {
                    box.checked = !box.checked;
                    CyberUI.alert(e.message, 'Не получилось');
                } finally {
                    box.disabled = false;
                }
            });
            label.append(box, document.createElement('span'));
            showCell.appendChild(label);

            const actions = document.createElement('td');
            actions.className = 'row-actions';
            actions.appendChild(iconButton('fa-pen', 'Изменить', '', () => editMessage(item)));
            actions.appendChild(iconButton('fa-trash', 'Удалить', 'row-btn--danger',
                async (e) => {
                    const btn = e.currentTarget;
                    if (!await CyberUI.confirm('Удалить сообщение?', 'Удаление', 'Удалить')) return;
                    guarded(btn, () => send('DELETE', '/api/admin/messages', { id: item.id }));
                }));

            tr.append(textCell, colorCell, showCell, actions);
            els.messages.appendChild(tr);
        });
    }

    async function editMessage(item) {
        const value = await CyberUI.prompt('Текст бегущей строки',
            item ? item.text : '', item ? 'Изменение сообщения' : 'Новое сообщение',
            'Дальше');
        if (value === null) return;
        const color = await CyberUI.choose('Цвет сообщения',
            Object.entries(COLOR_NAMES).map(([key, name]) => ({
                label: name, value: key, icon: 'fa-circle' })),
            'Цвет сообщения');
        if (color === null) return;
        try {
            if (item) {
                await send('PATCH', '/api/admin/messages',
                           { id: item.id, text: value, color, enabled: item.enabled });
            } else {
                await send('POST', '/api/admin/messages', { text: value, color });
            }
            await load();
        } catch (e) {
            CyberUI.alert(e.message, 'Не получилось');
        }
    }

    /** Достижения: за что выдаются и как выглядят. */
    function renderAchievements(items) {
        els.achievements.innerHTML = '';
        const books = new Map();
        (items || []).forEach((item) => {
            const book = item.target_path
                ? item.target_path.split('/').pop().replace(/_/g, ' ') : 'Без книги';
            if (!books.has(book)) books.set(book, []);
            books.get(book).push(item);
        });

        books.forEach((rows, book) => {
            const key = `ach:${book}`;
            els.achievements.appendChild(groupRow(key, book, `${rows.length}`, 6));
            if (!openRows.has(key)) return;
            rows.forEach(item => els.achievements.appendChild(achievementRow(item)));
        });
    }

    function achievementRow(item) {
        {
            const tr = document.createElement('tr');

            const pic = document.createElement('td');
            pic.className = 'cell-path';
            pic.textContent = item.image || '—';

            const target = document.createElement('td');
            const book = item.target_path
                ? item.target_path.split('/').pop().replace(/_/g, ' ') : '—';
            const chapters = (item.target_tracks || []).map(n => n + 1);
            target.textContent = chapters.length
                ? `${book}, ${chapters.length > 1 ? 'главы' : 'глава'} ${chapters.join(', ')}`
                : book;

            const actions = document.createElement('td');
            actions.className = 'row-actions';
            actions.appendChild(iconButton('fa-pen', 'Изменить', '',
                                           () => editAchievement(item)));
            actions.appendChild(iconButton('fa-trash', 'Удалить', 'row-btn--danger',
                async (e) => {
                    const btn = e.currentTarget;
                    if (!await CyberUI.confirm(`Удалить «${item.title}»?`,
                                               'Удаление', 'Удалить')) return;
                    guarded(btn, () => send('DELETE', '/api/admin/achievements',
                                            { id: item.id }));
                }));

            tr.className = 'child-row';
            tr.append(text(item.title), pic, text(item.description || '—'), target,
                      text(RARITY_NAMES[item.rarity] || item.rarity), actions);
            return tr;
        }
    }

    async function editAchievement(item) {
        const title = await CyberUI.prompt('Название достижения',
            item ? item.title : '', 'Достижение', 'Дальше');
        if (title === null) return;
        const image = await CyberUI.prompt('Путь к картинке, например /static/assets/cup.png',
            item ? item.image : '', 'Достижение', 'Дальше');
        if (image === null) return;
        const description = await CyberUI.prompt('Описание',
            item ? item.description : '', 'Достижение', 'Дальше');
        if (description === null) return;

        if (!achievementTargets.length) {
            CyberUI.alert('В медиатеке нет книг', 'Не из чего выбрать');
            return;
        }
        const targetPath = await CyberUI.chooseTree('За какую книгу выдавать',
                                                    buildTargetTree(), 'Достижение');
        if (targetPath === null) return;

        const chosen = achievementTargets.find(t => t.path === targetPath);
        // Глав можно отметить несколько: достижение выдастся, когда
        // дослушаны все отмеченные. «Все главы» — отдельной строкой сверху.
        const trackOptions = [{ label: 'Все главы книги', value: 'all' }].concat(
            (chosen ? chosen.tracks : []).map((name, i) => ({
                label: `Глава ${i + 1}: ${name}`, value: String(i) })));
        const already = (item && item.target_tracks || []).map(String);
        const picked = await CyberUI.chooseMany(
            'Отметьте главы. Ничего не отмечено — засчитается любая глава книги.',
            trackOptions, 'За какие главы', 'Готово', already);
        if (picked === null) return;
        const targetTracks = picked.includes('all')
            ? (chosen ? chosen.tracks.map((_n, i) => String(i)) : [])
            : picked;

        const rarity = await CyberUI.choose('Редкость',
            Object.entries(RARITY_NAMES).map(([key, name]) => ({
                label: name, value: key, icon: 'fa-gem',
                hint: item && item.rarity === key ? 'сейчас' : '' })), 'Достижение');
        if (rarity === null) return;

        try {
            const payload = { title, image, description, target_path: targetPath,
                              target_tracks: targetTracks, rarity };
            if (item) payload.id = item.id;
            await send(item ? 'PATCH' : 'POST', '/api/admin/achievements', payload);
            await load();
        } catch (e) {
            CyberUI.alert(e.message, 'Не получилось');
        }
    }

    /** Дерево целей: папки с вложенностью, книги — листьями. */
    function buildTargetTree() {
        const root = { children: [] };
        const index = new Map();

        const folderNode = (path) => {
            if (index.has(path)) return index.get(path);
            const parts = path.split('/');
            const node = { label: parts[parts.length - 1].replace(/_/g, ' '),
                           value: path, children: [] };
            index.set(path, node);
            const parentPath = parts.slice(0, -1).join('/');
            (parentPath ? folderNode(parentPath) : root).children.push(node);
            return node;
        };

        achievementTargets.filter(t => t.type === 'directory')
            .sort((a, b) => a.path.localeCompare(b.path, 'ru'))
            .forEach(t => folderNode(t.path));

        achievementTargets.filter(t => t.type !== 'directory').forEach((t) => {
            const parentPath = t.path.split('/').slice(0, -1).join('/');
            const parent = parentPath ? folderNode(parentPath) : root;
            parent.children.push({ label: t.name, value: t.path, children: [] });
        });
        return root.children;
    }

    async function loadExtras() {
        try {
            const [messages, achievements, earned, themes] = await Promise.all([
                api('/api/admin/messages'),
                api('/api/admin/achievements'),
                api('/api/admin/user-achievements'),
                api('/api/admin/themes'),
            ]);
            renderThemes(themes);
            renderMessages(messages);
            achievementTargets = achievements.targets || [];
            renderAchievements(achievements.achievements);
            renderUserAchievements(earned.users);
        } catch (e) {
            /* раздел просто останется пустым */
        }
    }

    /** Кто какие достижения получил: человек → книга → достижения. */
    function renderUserAchievements(users) {
        els.userAchievements.innerHTML = '';
        const list = users || [];
        els.userAchievementsNote.textContent = list.length
            ? `${list.length} ${plural(list.length, 'пользователь', 'пользователя', 'пользователей')} с достижениями`
            : 'Пока никто ничего не получил.';

        list.forEach((user) => {
            const userKey = `ua:${user.id}`;
            els.userAchievements.appendChild(groupRow(
                userKey, `${user.nickname} (${user.login})`, `${user.total}`, 4));
            if (!openRows.has(userKey)) return;

            user.books.forEach((book) => {
                const bookKey = `${userKey}:${book.book}`;
                els.userAchievements.appendChild(groupRow(
                    bookKey, book.book, `${book.items.length}`, 4, 'subgroup-row'));
                if (!openRows.has(bookKey)) return;

                book.items.forEach((item) => {
                    const tr = document.createElement('tr');
                    tr.className = 'child-row';
                    const actions = document.createElement('td');
                    actions.className = 'row-actions';
                    actions.appendChild(iconButton('fa-trash', 'Снять достижение',
                        'row-btn--danger', async (e) => {
                            const btn = e.currentTarget;
                            if (!await CyberUI.confirm(
                                `Снять «${item.title}» у ${user.nickname}?`,
                                'Снятие достижения', 'Снять')) return;
                            guarded(btn, () => send('DELETE', '/api/admin/user-achievements',
                                { user_id: user.id, achievement_id: item.id }));
                        }));
                    tr.append(text(item.title), text(RARITY_NAMES[item.rarity] || item.rarity),
                              text(shortDate(item.earned_at)), actions);
                    els.userAchievements.appendChild(tr);
                });
            });
        });
    }

    function renderRequests(items) {
        const list = items || [];
        els.requestsSection.hidden = list.length === 0;
        els.requests.innerHTML = '';
        if (!list.length) return;

        const people = list.reduce((n, b) => n + b.users.length, 0);
        els.requestsNote.textContent =
            `${list.length} ${plural(list.length, 'книга', 'книги', 'книг')}, ` +
            `${people} ${plural(people, 'просьба', 'просьбы', 'просьб')}`;

        list.forEach(book => {
            const tr = document.createElement('tr');

            const names = document.createElement('td');
            names.textContent = book.users.map(u => `${u.nickname} (${u.login})`).join(', ');

            const actions = document.createElement('td');
            actions.className = 'row-actions';

            const make = document.createElement('button');
            make.type = 'button';
            make.className = 'icon-btn';
            make.title = 'Поставить книгу в очередь на текст';
            make.innerHTML = '<i class="fas fa-align-left"></i>';
            make.disabled = !book.exists;
            make.addEventListener('click', async () => {
                make.disabled = true;
                try {
                    // Включение текста само снимает просьбы по этой книге
                    await send('PUT', '/api/admin/transcripts',
                               { path: book.path, enabled: true });
                    await load();
                } catch (e) {
                    make.disabled = false;
                    CyberUI.alert(e.message);
                }
            });

            const drop = document.createElement('button');
            drop.type = 'button';
            drop.className = 'icon-btn icon-btn--danger';
            drop.title = 'Убрать просьбы по этой книге';
            drop.innerHTML = '<i class="fas fa-xmark"></i>';
            drop.addEventListener('click', () => {
                guarded(drop, () => send('DELETE', '/api/admin/requests',
                                         { path: book.path }));
            });

            actions.append(make, drop);

            const title = text(book.title);
            if (!book.exists) {
                const warn = document.createElement('span');
                warn.className = 'badge badge--warn';
                warn.textContent = 'нет на диске';
                title.appendChild(document.createTextNode(' '));
                title.appendChild(warn);
            }

            tr.append(title, names, text(shortDate(book.first_at)), actions);
            els.requests.appendChild(tr);
        });
    }

    function renderProgress(items) {
        els.progress.innerHTML = '';
        if (!items.length) {
            els.progress.innerHTML = '<tr><td colspan="7" class="table-empty">Записей пока нет.</td></tr>';
            return;
        }
        // Группируем: человек -> книга -> записи по главам
        const people = new Map();
        items.forEach((item) => {
            const who = `${item.nickname} (${item.login})`;
            if (!people.has(who)) people.set(who, new Map());
            const books = people.get(who);
            const book = item.title || item.path;
            if (!books.has(book)) books.set(book, []);
            books.get(book).push(item);
        });

        people.forEach((books, who) => {
            const total = [...books.values()].reduce((n, list) => n + list.length, 0);
            const userKey = `pr:${who}`;
            els.progress.appendChild(groupRow(userKey, who, `${total}`, 7));
            if (!openRows.has(userKey)) return;

            books.forEach((rows, book) => {
                const bookKey = `${userKey}:${book}`;
                els.progress.appendChild(groupRow(bookKey, book, `${rows.length}`, 7,
                                                 'subgroup-row'));
                if (!openRows.has(bookKey)) return;
                rows.forEach(item => els.progress.appendChild(progressRow(item)));
            });
        });
    }

    function progressRow(item) {
        {
            const tr = document.createElement('tr');
            tr.className = 'child-row';
            const actions = document.createElement('td');
            actions.className = 'row-actions';
            const del = iconButton('fa-trash', 'Удалить запись', 'row-btn--danger', async () => {
                if (!await CyberUI.confirm(`Сбросить прогресс «${item.title || item.path}» у ${item.login}?`)) return;
                guarded(del, () => send('DELETE', '/api/admin/progress',
                    { user_id: item.user_id, path: item.path }));
            });
            actions.appendChild(del);

            const remaining = item.remaining === null || item.remaining === undefined
                ? '—'
                : window.CyberAuth.formatDuration(item.remaining);

            tr.append(
                text(`Глава ${item.track_index + 1}`),
                text(item.title || item.path),
                text(item.track_index + 1),
                text(window.CyberAuth.formatClock(item.position)),
                text(remaining),
                text(shortDate(item.updated_at)),
                actions
            );
            return tr;
        }
    }

    function renderAlbums(albums) {
        els.albums.innerHTML = '';
        if (!albums.length) {
            els.albums.innerHTML = '<tr><td colspan="6" class="table-empty">Кэш пуст.</td></tr>';
            return;
        }
        // Группируем по книге: в кэше бывает много разделов одной серии
        const books = new Map();
        albums.forEach((album) => {
            const book = album.path.split('/').slice(0, -1).join('/') || 'Медиатека';
            if (!books.has(book)) books.set(book, []);
            books.get(book).push(album);
        });

        books.forEach((rows, book) => {
            const key = `al:${book}`;
            els.albums.appendChild(groupRow(key, book.replace(/_/g, ' '),
                                            `${rows.length}`, 6));
            if (!openRows.has(key)) return;
            rows.forEach(album => els.albums.appendChild(albumRow(album)));
        });
    }

    function albumRow(album) {
        {
            const tr = document.createElement('tr');
            tr.className = 'child-row';
            const exists = document.createElement('td');
            exists.innerHTML = album.exists
                ? '<span class="badge badge--ok">да</span>'
                : '<span class="badge badge--warn">нет</span>';

            const actions = document.createElement('td');
            actions.className = 'row-actions';
            const del = iconButton('fa-trash', 'Очистить кэш книги', 'row-btn--danger', async () => {
                if (!await CyberUI.confirm(`Удалить кэш длительностей для «${album.path}»?`)) return;
                guarded(del, () => send('DELETE', '/api/admin/albums', { path: album.path }));
            });
            actions.appendChild(del);

            tr.append(text(album.path.split('/').pop().replace(/_/g, ' ')),
                text(album.track_count),
                text(window.CyberAuth.formatDuration(album.total_duration)),
                text(shortDate(album.updated_at)), exists, actions);
            return tr;
        }
    }

    // --- Форма пользователя ---

    function openForm(user) {
        editingId = user ? user.id : null;
        els.error.textContent = '';
        els.formTitle.textContent = user ? `ПОЛЬЗОВАТЕЛЬ #${user.id}` : 'НОВЫЙ ПОЛЬЗОВАТЕЛЬ';
        els.submit.textContent = user ? 'СОХРАНИТЬ' : 'СОЗДАТЬ';
        els.login.value = user ? user.login : '';
        els.nickname.value = user ? user.nickname : '';
        els.password.value = '';
        els.passwordHint.textContent = user
            ? 'Оставьте пустым, чтобы не менять пароль.'
            : 'Не короче 6 символов.';
        els.admin.checked = user ? user.is_admin : false;
        els.admin.disabled = Boolean(user && user.id === currentUserId);
        els.overlay.hidden = false;
        setTimeout(() => els.login.focus(), 50);
    }

    function closeForm() {
        els.overlay.hidden = true;
        els.form.reset();
        els.error.textContent = '';
        editingId = null;
    }

    async function submitForm(event) {
        event.preventDefault();
        els.error.textContent = '';
        els.submit.disabled = true;

        const payload = {
            login: els.login.value.trim(),
            nickname: els.nickname.value.trim(),
            is_admin: els.admin.checked,
        };

        try {
            if (editingId === null) {
                payload.password = els.password.value;
                await send('POST', '/api/admin/users', payload);
            } else {
                if (els.password.value) payload.new_password = els.password.value;
                if (els.admin.disabled) delete payload.is_admin;
                await send('PATCH', `/api/admin/users/${editingId}`, payload);
            }
            closeForm();
            await load();
        } catch (e) {
            els.error.textContent = e.message;
        } finally {
            els.submit.disabled = false;
        }
    }

    // --- Загрузка ---

    async function load() {
        try {
            const data = await api('/api/admin/overview');
            currentUserId = data.current_user_id;
            els.warning.hidden = !data.default_password;
            lastBooks = data.books || [];
            lastTree = data.tree || [];
            renderStats(data.stats);
            renderUsers(data.users);
            renderTree(lastTree);
            renderQueueNote(data.queue);
            renderRequests(data.requests);
            renderRoots(data.roots);
            loadExtras();
            const dl = document.getElementById('downloads-toggle');
            if (dl) dl.checked = Boolean(data.downloads);
            renderWorkerHint(data);
            // Плашка «запрошены текстовые книги» живёт в auth.js и сама
            // о правках в панели не узнает — пересчитываем её здесь
            if (window.CyberAuth && CyberAuth.refreshAdminAlert) {
                CyberAuth.refreshAdminAlert();
            }
            refreshTranscripts();
            refreshTextBooks();
            renderProgress(data.progress);
            renderAlbums(data.albums);
        } catch (e) {
            if (String(e.message).includes('401') || String(e.message).includes('403')) {
                window.location.href = '/';
                return;
            }
            els.users.innerHTML = `<tr><td colspan="8" class="table-empty">${e.message}</td></tr>`;
        }
    }

    /**
     * Сервер сам речь не распознаёт: галочка ставит книгу в очередь, а текст
     * создаёт программа на другом компьютере. Показываем, что в очереди
     * и кто её сейчас разбирает.
     */
    function renderQueueNote(queue) {
        const books = queue || [];
        const waiting = books.reduce(
            (n, b) => n + b.pending.filter(t => !t.taken_by).length, 0);
        const busy = books.reduce(
            (n, b) => n + b.pending.filter(t => t.taken_by).length, 0);
        const workers = [...new Set(books.flatMap(
            b => b.pending.map(t => t.taken_by).filter(Boolean)))];

        if (!waiting && !busy) {
            els.engineNote.textContent = 'Очередь пуста.';
            els.engineNote.className = 'engine-note';
            return;
        }
        const parts = [];
        if (busy) {
            parts.push(`распознаётся ${busy} ${plural(busy, 'глава', 'главы', 'глав')}` +
                (workers.length ? ` (${workers.join(', ')})` : ''));
        }
        if (waiting) {
            parts.push(`ждёт очереди ${waiting} ${plural(waiting, 'глава', 'главы', 'глав')}`);
        }
        els.engineNote.textContent = 'В очереди: ' + parts.join(', ') + '.';
        els.engineNote.className = busy ? 'engine-note' : 'engine-note engine-note--warn';
    }

    /** Что вписать в config.ini на компьютере-распознавателе. */
    function renderWorkerHint(data) {
        const token = document.getElementById('worker-token');
        const host = document.getElementById('worker-host');
        if (token) token.textContent = data.worker_token || '—';
        if (host) host.textContent = `${window.location.hostname}:${data.worker_port || 2077}`;
    }

    function plural(n, one, few, many) {
        const a = Math.abs(n) % 100, b = a % 10;
        if (a > 10 && a < 20) return many;
        if (b > 1 && b < 5) return few;
        if (b === 1) return one;
        return many;
    }

    document.getElementById('root-add').addEventListener('click', async () => {
        const path = (await CyberUI.prompt(
            'Полный путь к папке с книгами, например D:\\Аудиокниги',
            '', 'Новая папка с музыкой', 'Добавить') || '').trim();
        if (!path) return;
        const name = (await CyberUI.prompt(
            'Название раздела на главной', '', 'Новая папка с музыкой', 'Добавить') || '').trim();
        if (!name) return;
        try {
            await send('POST', '/api/admin/roots', { name, path });
            await load();
        } catch (e) {
            CyberUI.alert(e.message);
        }
    });

    document.getElementById('downloads-toggle').addEventListener('change', async (e) => {
        const box = e.target;
        box.disabled = true;
        try {
            await send('PUT', '/api/admin/settings', { downloads: box.checked });
        } catch (err) {
            box.checked = !box.checked;
            CyberUI.alert(err.message);
        } finally {
            box.disabled = false;
        }
    });

    els.addBtn.addEventListener('click', () => openForm(null));

    document.getElementById('theme-intensity').addEventListener('change', async (e) => {
        try {
            await send('PUT', '/api/admin/theme-settings', { intensity: e.target.value });
        } catch (err) {
            CyberUI.alert(err.message, 'Не получилось');
        }
    });
    document.getElementById('theme-video-save').addEventListener('click', async () => {
        try {
            await send('PUT', '/api/admin/theme-settings',
                       { video: document.getElementById('theme-video').value });
            CyberUI.alert('Ролик сохранён', 'Готово');
        } catch (err) {
            CyberUI.alert(err.message, 'Не получилось');
        }
    });

    document.getElementById('message-add')
        .addEventListener('click', () => editMessage(null));
    document.getElementById('achievement-add')
        .addEventListener('click', () => editAchievement(null));
    els.messagesToggle.addEventListener('change', async () => {
        try {
            await send('PUT', '/api/admin/messages',
                       { enabled: els.messagesToggle.checked });
        } catch (e) {
            els.messagesToggle.checked = !els.messagesToggle.checked;
            CyberUI.alert(e.message, 'Не получилось');
        }
    });
    els.formClose.addEventListener('click', closeForm);
    els.form.addEventListener('submit', submitForm);
    document.addEventListener('keydown', (e) => {
        if (e.key === 'Escape' && !els.overlay.hidden) closeForm();
    });

    // Если администратор вышел из аккаунта прямо со страницы — уводим на главную
    window.CyberAuth.onChange((user) => {
        if (!user || !user.is_admin) window.location.href = '/';
    });

    window.CyberAuth.ready.then((user) => {
        if (!user || !user.is_admin) {
            window.location.href = '/';
            return;
        }
        load();
    });
});
