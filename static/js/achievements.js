// achievements.js — страница достижений читателя.
//
// Показывает и полученные, и ещё не полученные: закрытые видно силуэтом,
// иначе непонятно, к чему стремиться.
(() => {
    const grid = document.getElementById('achievement-grid');
    const summary = document.getElementById('achievements-summary');

    const RARITY = {
        common: 'обычное',
        rare: 'редкое',
        mythic: 'мифическое',
        legendary: 'легендарное',
    };

    const escapeHtml = (value) => String(value === null || value === undefined ? '' : value)
        .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;').replace(/'/g, '&#39;');

    const targetLabel = (item) => {
        if (!item.target_path) return 'за что угодно';
        const book = item.target_path.split('/').pop().replace(/_/g, ' ');
        const chapters = (item.target_tracks || []).map(n => n + 1);
        if (!chapters.length) return `книга «${book}»`;
        return chapters.length > 1
            ? `книга «${book}», главы ${chapters.join(', ')}`
            : `книга «${book}», глава ${chapters[0]}`;
    };

    const card = (item) => {
        const earned = Boolean(item.earned_at);
        const box = document.createElement('article');
        box.className = `achievement achievement--${item.rarity}`
            + (earned ? ' is-earned' : ' is-locked');

        const picture = item.image
            ? `<img src="${escapeHtml(item.image)}" alt="" loading="lazy" decoding="async"
                 onerror="this.onerror=null;this.replaceWith(Object.assign(document.createElement('i'),{className:'fas fa-trophy achievement__fallback'}));">`
            : '<i class="fas fa-trophy achievement__fallback"></i>';

        box.innerHTML = `
            <div class="achievement__pic">${picture}</div>
            <div class="achievement__body">
                <h3 class="achievement__title">${escapeHtml(item.title)}</h3>
                <p class="achievement__desc">${escapeHtml(item.description)}</p>
                <p class="achievement__meta">
                    <span class="achievement__rarity">${RARITY[item.rarity] || item.rarity}</span>
                </p>
            </div>
        `;
        return box;
    };

    const RARITY_ORDER = { legendary: 4, mythic: 3, rare: 2, common: 1 };
    const sortSelect = document.getElementById('achievements-sort');
    const hideBox = document.getElementById('achievements-hide');
    // По умолчанию показываем только заработанное: список недостижимого
    // ничего не говорит, а пустая группа подсказывает, что делать
    hideBox.checked = true;
    let lastData = null;
    // Книги свёрнуты сразу: список из десятков разделов иначе не окинуть взглядом
    const openBooks = new Set();

    const byName = (a, b) => a.title.localeCompare(b.title, 'ru',
        { numeric: true, sensitivity: 'base' });

    const byDescription = (a, b) => (a.description || '').localeCompare(
        b.description || '', 'ru', { numeric: true, sensitivity: 'base' }) || byName(a, b);

    /** Внутри группы — от легендарных к обычным, это порядок по умолчанию. */
    const byRarity = (a, b) => {
        const d = (RARITY_ORDER[b.rarity] || 0) - (RARITY_ORDER[a.rarity] || 0);
        return d || byDescription(a, b);
    };

    const sorted = (items, mode) => {
        const copy = items.slice();
        if (mode === 'rarity-asc') return copy.sort((a, b) => -byRarity(a, b));
        if (mode === 'name') return copy.sort(byName);
        if (mode === 'earned') {
            return copy.sort((a, b) =>
                Number(Boolean(b.earned_at)) - Number(Boolean(a.earned_at)) || byRarity(a, b));
        }
        return copy.sort(byRarity);
    };

    const render = () => {
        const data = lastData;
        if (!data) return;
        const all = data.achievements || [];
        grid.innerHTML = '';
        grid.className = '';

        if (!all.length) {
            grid.className = 'achievement-grid';
            grid.innerHTML = '<p class="error-message">Достижений пока не придумали.</p>';
            summary.textContent = '';
            return;
        }
        const earned = Math.max(0, Math.min(all.length, Number(data.earned) || 0));
        summary.innerHTML = `<div><span class="eyebrow">ВАШИ ОТКРЫТИЯ</span><strong>${earned}<small> / ${all.length}</small></strong><span>историй в коллекции достижений</span></div><div class="achievement-meter" role="progressbar" aria-label="Полученные достижения" aria-valuemin="0" aria-valuemax="${all.length}" aria-valuenow="${earned}"><span style="width:${earned / all.length * 100}%"></span></div>`;

        const hideLocked = hideBox.checked;
        const visible = hideLocked ? all.filter(a => a.earned_at) : all;
        const mode = sortSelect.value;

        // Любая выбранная сортировка распускает группы и показывает всё вместе
        if (mode !== 'groups') {
            grid.className = 'achievement-grid';
            if (!visible.length) {
                grid.innerHTML =
                    '<p class="error-message">Начните слушать, чтобы получить достижения.</p>';
                return;
            }
            sorted(visible, mode).forEach(item => grid.appendChild(card(item)));
            return;
        }

        // По умолчанию — дерево как в медиатеке: папка → папка → книга
        const root = { children: new Map(), items: [] };
        all.forEach((item) => {
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

        const drawNode = (node, path, depth, host) => {
            const key = path.join('/');
            const inGroup = node.items.filter(a => !hideLocked || a.earned_at);
            const earnedHere = node.items.filter(a => a.earned_at).length;
            const open = openBooks.has(key);

            const section = document.createElement('section');
            section.className = 'achievement-group';
            section.dataset.book = node.name || key;
            section.style.marginLeft = `${depth * 1.1}rem`;

            const title = document.createElement('button');
            title.type = 'button';
            title.className = 'achievement-group__title';
            title.setAttribute('aria-expanded', String(open));
            const isBook = node.children.size === 0;
            title.innerHTML = `<i class="fas fa-chevron-${open ? 'down' : 'right'}"></i> ` +
                `<i class="fas fa-${isBook ? 'book' : 'folder'}"></i> ` +
                `${escapeHtml(node.name || 'Медиатека')}` +
                (node.items.length
                    ? ` <span class="achievement-group__count">${earnedHere} из ${node.items.length}</span>`
                    : '');
            title.addEventListener('click', () => {
                if (openBooks.has(key)) openBooks.delete(key);
                else openBooks.add(key);
                render();
            });
            section.appendChild(title);
            host.appendChild(section);
            if (!open) return;

            if (node.items.length) {
                if (!inGroup.length) {
                    const empty = document.createElement('p');
                    empty.className = 'achievement-group__empty';
                    empty.textContent = 'Начните слушать, чтобы получить достижения.';
                    section.appendChild(empty);
                } else {
                    const box = document.createElement('div');
                    box.className = 'achievement-grid';
                    sorted(inGroup, 'groups').forEach(a => box.appendChild(card(a)));
                    section.appendChild(box);
                }
            }
            [...node.children.entries()]
                .sort((a, b) => a[0].localeCompare(b[0], 'ru', { numeric: true }))
                .forEach(([part, child]) => drawNode(child, path.concat(part), depth + 1, host));
        };

        [...root.children.entries()]
            .sort((a, b) => a[0].localeCompare(b[0], 'ru', { numeric: true }))
            .forEach(([part, child]) => drawNode(child, [part], 0, grid));
        if (root.items.length) {
            drawNode({ children: new Map(), items: root.items, name: 'Без книги' },
                     ['__none'], 0, grid);
        }
    };

    /** Открыть одну книгу и свернуть остальные — по переходу из уведомления. */
    const focusBook = (book) => {
        openBooks.clear();
        if (book) openBooks.add(book);
        sortSelect.value = 'groups';
        render();
        const section = grid.querySelector(`[data-book="${CSS.escape(book)}"]`);
        if (section) section.scrollIntoView({ block: 'start', behavior: 'smooth' });
    };

    sortSelect.addEventListener('change', render);
    hideBox.addEventListener('change', render);

    const load = async () => {
        try {
            const resp = await fetch('/api/achievements', { credentials: 'same-origin' });
            if (resp.status === 401) {
                grid.innerHTML = '<p class="error-message">Войдите, чтобы видеть достижения.</p>';
                summary.textContent = '';
                return;
            }
            lastData = await resp.json();
            render();
        } catch (e) {
            grid.innerHTML = '<p class="error-message">Не удалось загрузить достижения.</p>';
        }
    };

    window.CyberAuth.ready.then(async () => {
        await load();
        // Пришли по клику из всплывающего уведомления
        const wanted = new URLSearchParams(location.search).get('book');
        if (wanted) focusBook(wanted);
    });
    window.CyberAuth.onChange(load);
})();
