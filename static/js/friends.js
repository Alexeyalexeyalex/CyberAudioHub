// friends.js — друзья и сравнение достижений.
(() => {
    const $ = (id) => document.getElementById(id);
    const searchInput = $('friend-search');
    const results = $('search-results');
    const RARITY = { common: 'обычное', rare: 'редкое',
                     mythic: 'мифическое', legendary: 'легендарное' };
    let searchTimer = null;
    // Какие разделы сравнения свёрнуты. Храним именно свёрнутые, а не
    // раскрытые: пустое множество означает «всё открыто» — ровно то
    // состояние, в котором сравнение было до появления сворачивания.
    const compareClosed = new Set();

    // Кавычки экранируем наравне с угловыми скобками: ник подставляется
    // и внутрь атрибутов title="...", где одной кавычки хватило бы,
    // чтобы разорвать разметку.
    const escapeHtml = (value) => String(value === null || value === undefined ? '' : value)
        .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;').replace(/'/g, '&#39;');

    const send = async (method, url, body) => {
        const resp = await fetch(url, {
            method,
            credentials: 'same-origin',
            headers: body ? { 'Content-Type': 'application/json' } : {},
            body: body ? JSON.stringify(body) : undefined,
        });
        const data = await resp.json().catch(() => ({}));
        if (!resp.ok) throw new Error(data.error || 'Не получилось');
        return data;
    };

    /** Карточка человека: логин и никнейм видны оба, как вы просили. */
    const personCard = (item, buttons) => {
        const row = document.createElement('div');
        row.className = 'friend-row';
        row.innerHTML = `
            <span class="friend-avatar" aria-hidden="true">${escapeHtml(Array.from(item.nickname || item.login || '?')[0].toUpperCase())}</span>
            <div class="friend-row__who">
                <span class="friend-row__nick">${escapeHtml(item.nickname)}</span>
                <span class="friend-row__login">@${escapeHtml(item.login)}</span>
            </div>`;
        const actions = document.createElement('div');
        actions.className = 'friend-row__actions';
        buttons.forEach(btn => actions.appendChild(btn));
        row.appendChild(actions);
        return row;
    };

    const button = (label, icon, className, handler) => {
        const btn = document.createElement('button');
        btn.type = 'button';
        btn.className = `panel-btn ${className || ''}`.trim();
        btn.innerHTML = `<i class="fas ${icon}"></i> ${label}`;
        btn.addEventListener('click', async () => {
            btn.disabled = true;
            try {
                await handler();
            } catch (e) {
                CyberUI.alert(e.message, 'Не получилось');
            } finally {
                // Списки, которые обновляются, перерисуют кнопку заново,
                // а остальным она нужна рабочей — например «Сравнить»
                btn.disabled = false;
            }
        });
        return btn;
    };

    const STATE_LABEL = {
        outgoing: 'Заявка отправлена',
        incoming: 'Ждёт вашего ответа',
        friends: 'Уже в друзьях',
    };

    const renderSearch = (list) => {
        results.innerHTML = '';
        if (!list.length) {
            results.innerHTML = '<p class="friend-hint">Никого не нашли.</p>';
            return;
        }
        list.forEach((item) => {
            const buttons = [];
            if (item.state === 'none') {
                buttons.push(button('Добавить', 'fa-user-plus', '', async () => {
                    await send('POST', '/api/friends', { id: item.id });
                    await Promise.all([runSearch(searchInput.value.trim()), loadAll()]);
                }));
            } else if (item.state === 'incoming') {
                buttons.push(button('Принять', 'fa-check', '', async () => {
                    await send('POST', '/api/friends', { id: item.id, action: 'accept' });
                    await Promise.all([runSearch(searchInput.value.trim()), loadAll()]);
                }));
            } else {
                const note = document.createElement('span');
                note.className = 'friend-hint';
                note.textContent = STATE_LABEL[item.state] || '';
                buttons.push(note);
            }
            results.appendChild(personCard(item, buttons));
        });
    };

    const runSearch = async (query) => {
        if (query.length < 2) {
            results.innerHTML = '';
            return;
        }
        try {
            const data = await send('GET', `/api/friends/search?q=${encodeURIComponent(query)}`);
            renderSearch(data.results || []);
        } catch (e) {
            results.innerHTML = '<p class="friend-hint">Поиск не удался.</p>';
        }
    };

    searchInput.addEventListener('input', () => {
        clearTimeout(searchTimer);
        const query = searchInput.value.trim();
        searchTimer = setTimeout(() => runSearch(query), 300);
    });

    const fillList = (box, section, items, makeButtons, emptyText) => {
        box.innerHTML = '';
        if (section) section.hidden = items.length === 0;
        if (!items.length) {
            if (!section) box.innerHTML = `<p class="friend-hint">${emptyText}</p>`;
            return;
        }
        items.forEach(item => box.appendChild(personCard(item, makeButtons(item))));
    };

    const loadAll = async () => {
        let data;
        try {
            data = await send('GET', '/api/friends');
        } catch (e) {
            $('friends-list').innerHTML =
                '<p class="friend-hint">Войдите, чтобы видеть друзей.</p>';
            return;
        }

        const total = document.getElementById('friends-total');
        if (total) total.textContent = data.friends.length;
        const pending = document.getElementById('friends-pending');
        if (pending) pending.textContent = data.incoming.length;

        fillList($('incoming-list'), $('incoming-section'), data.incoming, (item) => [
            button('Принять', 'fa-check', '', async () => {
                await send('POST', '/api/friends', { id: item.id, action: 'accept' });
                await loadAll();
            }),
            button('Отклонить', 'fa-xmark', 'panel-btn--danger', async () => {
                await send('DELETE', '/api/friends', { id: item.id });
                await loadAll();
            }),
        ]);

        fillList($('outgoing-list'), $('outgoing-section'), data.outgoing, (item) => [
            button('Отменить', 'fa-xmark', 'panel-btn--danger', async () => {
                await send('DELETE', '/api/friends', { id: item.id });
                await loadAll();
            }),
        ]);

        fillList($('friends-list'), null, data.friends, (item) => [
            button('Сравнить достижения', 'fa-trophy', '', () => compare(item.id)),
            button('Удалить', 'fa-user-minus', 'panel-btn--danger', async () => {
                if (!await CyberUI.confirm(`Удалить ${item.nickname} из друзей?`,
                                           'Удаление друга', 'Удалить')) return;
                await send('DELETE', '/api/friends', { id: item.id });
                await loadAll();
            }),
        ], 'Друзей пока нет. Найдите человека через поиск выше.');

        if (window.CyberAuth && CyberAuth.refreshAdminAlert) CyberAuth.refreshAdminAlert();
    };

    /** Сравнение: у кого что есть, разбито по книгам. */
    const compare = async (friendId) => {
        const section = $('compare-section');
        const body = $('compare-body');
        section.hidden = false;
        body.innerHTML = '<div class="loading-state"><div class="loader"></div></div>';
        section.scrollIntoView({ behavior: 'smooth', block: 'start' });

        let data;
        try {
            data = await send('GET', `/api/friends/${friendId}/achievements`);
        } catch (e) {
            body.innerHTML = '<p class="friend-hint">Не удалось загрузить сравнение.</p>';
            return;
        }

        $('compare-title').textContent = `Вы и ${data.friend.nickname}`;
        const total = data.achievements.length;
        $('compare-summary').innerHTML = `
            <span class="compare-side">Вы: <b>${data.mine_total}</b> из ${total}</span>
            <span class="compare-side">${escapeHtml(data.friend.nickname)}:
                <b>${data.their_total}</b> из ${total}</span>`;

        // Дерево как в медиатеке: папка → папка → книга
        const root = { children: new Map(), items: [], name: '' };
        data.achievements.forEach((item) => {
            const parts = (item.book_path || '').split('/').filter(Boolean);
            let node = root;
            parts.forEach((part) => {
                if (!node.children.has(part)) {
                    node.children.set(part, { children: new Map(), items: [],
                                              name: part.replace(/_/g, ' ') });
                }
                node = node.children.get(part);
            });
            node.items.push(item);
        });

        const mark = (has) => has
            ? '<i class="fas fa-circle-check compare-mark compare-mark--yes"></i>'
            : '<i class="far fa-circle compare-mark"></i>';

        const drawNode = (node, path, depth) => {
            const key = path.join('/');
            const open = !compareClosed.has(key);
            const isBook = node.children.size === 0;
            const mine = node.items.filter((i) => i.mine).length;
            const theirs = node.items.filter((i) => i.theirs).length;

            const group = document.createElement('section');
            group.className = 'achievement-group';
            group.style.marginLeft = `${depth * 1.1}rem`;

            // Кнопка, а не заголовок: раздел должен открываться и с клавиатуры.
            // Оформление то же, что на странице достижений, — там сворачивание
            // было с самого начала, и разъезжаться им незачем.
            const head = document.createElement('button');
            head.type = 'button';
            head.className = 'achievement-group__title';
            head.setAttribute('aria-expanded', open ? 'true' : 'false');
            head.innerHTML = `<i class="fas fa-chevron-${open ? 'down' : 'right'}"></i> ` +
                `<i class="fas fa-${isBook ? 'book' : 'folder'}"></i> ` +
                escapeHtml(node.name) +
                (node.items.length
                    ? ` <span class="achievement-group__count"
                            title="Вы: ${mine}, ${escapeHtml(data.friend.nickname)}: ${theirs}"
                            >${mine} / ${theirs} из ${node.items.length}</span>`
                    : '');
            head.addEventListener('click', () => {
                if (compareClosed.has(key)) compareClosed.delete(key);
                else compareClosed.add(key);
                draw();
            });
            group.appendChild(head);
            body.appendChild(group);

            // Свёрнутый раздел прячет и вложенные папки: иначе они повисли бы
            // под закрытым родителем без всякой связи с ним.
            if (!open) return;

            node.items.forEach((item) => {
                const row = document.createElement('div');
                row.className = 'compare-row';
                row.innerHTML = `
                    <div class="compare-row__info">
                        <span class="compare-row__title">${escapeHtml(item.title)}</span>
                        <span class="compare-row__desc">${escapeHtml(item.description)}</span>
                        <span class="compare-row__rarity">${RARITY[item.rarity] || item.rarity}</span>
                    </div>
                    <div class="compare-row__marks">
                        <span class="compare-col" title="Вы">${mark(item.mine)}</span>
                        <span class="compare-col"
                            title="${escapeHtml(data.friend.nickname)}">${mark(item.theirs)}</span>
                    </div>`;
                group.appendChild(row);
            });

            [...node.children.entries()]
                .sort((a, b) => a[0].localeCompare(b[0], 'ru', { numeric: true }))
                .forEach(([part, child]) => drawNode(child, path.concat(part), depth + 1));
        };

        const draw = () => {
            body.innerHTML = '';

            // Подписи столбцов: без них непонятно, чей кружок какой
            const header = document.createElement('div');
            header.className = 'compare-head';
            header.innerHTML = `
                <span class="compare-head__spacer">Достижение</span>
                <span class="compare-head__cols">
                    <span class="compare-col" title="Вы">Вы</span>
                    <span class="compare-col" title="${escapeHtml(data.friend.nickname)}"
                        >${escapeHtml(data.friend.nickname)}</span>
                </span>`;
            body.appendChild(header);

            [...root.children.entries()]
                .sort((a, b) => a[0].localeCompare(b[0], 'ru', { numeric: true }))
                .forEach(([part, child]) => drawNode(child, [part], 0));
        };

        draw();
    };

    $('compare-close').addEventListener('click', () => {
        $('compare-section').hidden = true;
    });

    window.CyberAuth.ready.then(loadAll);
    window.CyberAuth.onChange(loadAll);
})();
