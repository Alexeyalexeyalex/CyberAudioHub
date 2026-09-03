// stats.js — страница статистики прослушивания.
//
// Графики рисуются вручную, инлайновым SVG. Библиотеку графиков сюда не
// тащим: на весь сайт нужны четыре простые диаграммы, а любая библиотека
// весит больше всего остального кода страницы вместе взятого и всё равно
// требует перекраски под неон. Заодно SVG понимает переменные темы, поэтому
// диаграммы сами меняют цвет вместе с праздничным оформлением.
(() => {
    const $ = (id) => document.getElementById(id);
    const NS = 'http://www.w3.org/2000/svg';

    const WEEKDAYS = ['Пн', 'Вт', 'Ср', 'Чт', 'Пт', 'Сб', 'Вс'];

    // Кого показываем сейчас. null — себя.
    let viewing = null;

    // --- Мелочи ---

    const escapeHtml = (value) => String(value === null || value === undefined ? '' : value)
        .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;').replace(/'/g, '&#39;');

    /** Часы и минуты словами: «12 ч 30 мин». */
    const humanTime = (seconds) => {
        const total = Math.max(0, Math.round(seconds || 0));
        const hours = Math.floor(total / 3600);
        const minutes = Math.round((total % 3600) / 60);
        if (!hours) return `${minutes} мин`;
        return minutes ? `${hours} ч ${minutes} мин` : `${hours} ч`;
    };

    const plural = (n, one, few, many) => {
        const a = Math.abs(n) % 100, b = a % 10;
        if (a > 10 && a < 20) return many;
        if (b > 1 && b < 5) return few;
        return b === 1 ? one : many;
    };

    /** Создаёт SVG-элемент: тег, атрибуты, дети. */
    const el = (tag, attrs = {}, children = []) => {
        const node = document.createElementNS(NS, tag);
        Object.entries(attrs).forEach(([k, v]) => {
            if (v !== null && v !== undefined) node.setAttribute(k, v);
        });
        (Array.isArray(children) ? children : [children])
            .filter(Boolean).forEach(c => node.appendChild(c));
        return node;
    };

    /** Корень диаграммы. viewBox + preserveAspectRatio = тянется по ширине. */
    const chart = (width, height, children) => el('svg', {
        viewBox: `0 0 ${width} ${height}`,
        preserveAspectRatio: 'xMidYMid meet',
        class: 'chart',
        role: 'img',
    }, children);

    const title = (text) => el('title', {}, [document.createTextNode(text)]);

    // --- Диаграммы ---

    /**
     * Столбики активности по дням. Подписи ставим не под каждым столбцом —
     * тридцать дат в ряд не читаются, — а раз в неделю.
     */
    function activityChart(days) {
        const W = 720, H = 240, padL = 34, padB = 26, padT = 12;
        const peak = Math.max(1, ...days.map(d => d.chapters));
        // Шаг оси делаем целым и округляем верх шкалы до кратного ему.
        // Иначе при пике в 2 главы деления считались как 0, 0.67, 1.33, 2
        // и подписывались как «0, 1, 1, 2» — два одинаковых числа подряд.
        const tick = Math.max(1, Math.ceil(peak / 3));
        const max = tick * 3;
        const innerW = W - padL - 10;
        const innerH = H - padB - padT;
        const step = innerW / days.length;
        const barW = Math.max(3, step * 0.62);

        const parts = [];

        // Сетка и подписи оси: четыре линии достаточно, чтобы читать высоту
        for (let i = 0; i <= 3; i += 1) {
            const value = tick * i;
            const y = padT + innerH - (innerH * i / 3);
            parts.push(el('line', {
                x1: padL, x2: W - 10, y1: y, y2: y, class: 'chart__grid',
            }));
            parts.push(el('text', {
                x: padL - 8, y: y + 4, class: 'chart__axis', 'text-anchor': 'end',
            }, [document.createTextNode(String(value))]));
        }

        days.forEach((day, i) => {
            const h = day.chapters ? Math.max(2, innerH * day.chapters / max) : 0;
            const x = padL + i * step + (step - barW) / 2;
            const y = padT + innerH - h;
            const iso = day.date;
            if (h) {
                parts.push(el('rect', {
                    x, y, width: barW, height: h, rx: Math.min(3, barW / 2),
                    class: 'chart__bar',
                }, [title(`${iso}: ${day.chapters} ${plural(day.chapters, 'глава', 'главы', 'глав')}`)]));
            } else {
                // День без прослушивания тоже показываем — «пусто» это тоже
                // сведения, а провал в ряду читается лучше, чем пропуск
                parts.push(el('rect', {
                    x, y: padT + innerH - 2, width: barW, height: 2, rx: 1,
                    class: 'chart__bar chart__bar--empty',
                }, [title(`${iso}: ничего`)]));
            }
            // Подпись раз в неделю плюс у последнего дня. Последний ставим
            // только если он отошёл от предыдущей подписи: иначе «01.09» и
            // «02.09» налезают друг на друга и читаются как «01.0902.09».
            const weekly = i % 7 === 0;
            const lastOne = i === days.length - 1 && (days.length - 1) % 7 > 2;
            if (weekly || lastOne) {
                const [, m, d] = iso.split('-');
                parts.push(el('text', {
                    x: x + barW / 2, y: H - 8, class: 'chart__axis', 'text-anchor': 'middle',
                }, [document.createTextNode(`${d}.${m}`)]));
            }
        });

        return chart(W, H, parts);
    }

    /** Кольцо: сколько книг дочитано из начатых. */
    function booksDonut(stats) {
        const W = 260, H = 240, cx = W / 2, cy = 118, r = 78, stroke = 22;
        const started = Math.max(0, stats.books_started);
        const finished = Math.max(0, stats.books_finished);
        const share = started ? finished / started : 0;
        const circumference = 2 * Math.PI * r;

        const parts = [
            el('circle', {
                cx, cy, r, fill: 'none', 'stroke-width': stroke, class: 'chart__ring-bg',
            }),
            // При нуле кольцо не рисуем вовсе: скруглённый конец линии
            // нулевой длины всё равно оставляет на ободе точку, и она
            // читается как «что-то уже прочитано»
            share > 0 ? el('circle', {
                cx, cy, r, fill: 'none', 'stroke-width': stroke, class: 'chart__ring',
                'stroke-linecap': 'round',
                'stroke-dasharray': `${circumference * share} ${circumference}`,
                // Начинаем сверху, а не справа: так читается как индикатор
                transform: `rotate(-90 ${cx} ${cy})`,
            }, [title(`${finished} из ${started}`)]) : null,
            el('text', { x: cx, y: cy - 2, class: 'chart__big', 'text-anchor': 'middle' },
                [document.createTextNode(`${Math.round(share * 100)}%`)]),
            el('text', { x: cx, y: cy + 22, class: 'chart__axis', 'text-anchor': 'middle' },
                [document.createTextNode('дочитано')]),
            el('text', { x: cx, y: H - 14, class: 'chart__caption', 'text-anchor': 'middle' },
                [document.createTextNode(
                    `${finished} из ${started} ${plural(started, 'книги', 'книг', 'книг')}`)]),
        ];
        return chart(W, H, parts);
    }

    /** Семь столбиков: в какие дни недели слушают чаще. */
    function weekdayChart(weekday) {
        const W = 260, H = 240, padB = 28, padT = 14;
        const max = Math.max(1, ...weekday);
        const innerH = H - padB - padT;
        const step = W / 7;
        const barW = step * 0.5;

        const parts = [];
        weekday.forEach((value, i) => {
            const h = value ? Math.max(2, innerH * value / max) : 2;
            const x = i * step + (step - barW) / 2;
            const y = padT + innerH - h;
            parts.push(el('rect', {
                x, y, width: barW, height: h, rx: 4,
                class: value ? 'chart__bar chart__bar--alt' : 'chart__bar chart__bar--empty',
            }, [title(`${WEEKDAYS[i]}: ${value} ${plural(value, 'глава', 'главы', 'глав')}`)]));
            parts.push(el('text', {
                x: i * step + step / 2, y: H - 9, class: 'chart__axis', 'text-anchor': 'middle',
            }, [document.createTextNode(WEEKDAYS[i])]));
        });
        return chart(W, H, parts);
    }

    /** Горизонтальные полосы: книги по времени прослушивания. */
    function topBooksChart(books) {
        if (!books.length) return null;
        const rowH = 40, W = 720, H = books.length * rowH + 10;
        const max = Math.max(...books.map(b => b.seconds)) || 1;
        const labelW = 210, barX = labelW + 10, barMax = W - barX - 90;

        const parts = [];
        books.forEach((book, i) => {
            const y = i * rowH + 8;
            const w = Math.max(3, barMax * book.seconds / max);
            // Название режем по символам: SVG сам не переносит и не обрезает
            const name = book.title.length > 26 ? book.title.slice(0, 25) + '…' : book.title;
            parts.push(el('text', {
                x: 0, y: y + 20, class: 'chart__label',
            }, [document.createTextNode(name), title(book.title)]));
            parts.push(el('rect', {
                x: barX, y: y + 6, width: barMax, height: 18, rx: 9, class: 'chart__track',
            }));
            parts.push(el('rect', {
                x: barX, y: y + 6, width: w, height: 18, rx: 9, class: 'chart__bar',
            }, [title(`${book.title}: ${humanTime(book.seconds)}`)]));
            parts.push(el('text', {
                x: W, y: y + 20, class: 'chart__axis', 'text-anchor': 'end',
            }, [document.createTextNode(humanTime(book.seconds))]));
        });
        return chart(W, H, parts);
    }

    // --- Плитки ---

    function renderTiles(stats) {
        const tiles = [
            { icon: 'fa-headphones', label: 'Прослушано', value: humanTime(stats.seconds), accent: 'primary' },
            { icon: 'fa-list-check', label: 'Глав пройдено', value: stats.chapters, accent: 'secondary' },
            { icon: 'fa-book-open', label: 'Книг начато', value: stats.books_started, accent: 'primary' },
            { icon: 'fa-flag-checkered', label: 'Книг дочитано', value: stats.books_finished, accent: 'green' },
            {
                icon: 'fa-trophy', label: 'Достижений',
                value: `${stats.achievements} / ${stats.achievements_total}`, accent: 'secondary',
            },
            {
                icon: 'fa-fire', label: 'Дней подряд',
                value: stats.streak, accent: stats.streak ? 'green' : 'primary',
            },
        ];
        $('stat-tiles').innerHTML = tiles.map(t => `
            <div class="stat-tile stat-tile--${t.accent}">
                <i class="fas ${t.icon}"></i>
                <span class="stat-tile__value">${escapeHtml(t.value)}</span>
                <span class="stat-tile__label">${escapeHtml(t.label)}</span>
            </div>`).join('');
    }

    // --- Переключатель «чья статистика» ---

    function renderWho(data) {
        const chips = $('stats-who-chips');
        const box = $('stats-who');
        // Показываем переключатель, только когда есть на кого переключаться
        box.hidden = !(data.friends || []).length;
        chips.innerHTML = '';

        const add = (id, name, active) => {
            const chip = document.createElement('button');
            chip.type = 'button';
            chip.className = 'stats-chip' + (active ? ' is-active' : '');
            chip.textContent = name;
            chip.addEventListener('click', () => {
                if (viewing === id) return;
                viewing = id;
                load();
            });
            chips.appendChild(chip);
        };

        add(null, 'Я', viewing === null);
        (data.friends || []).forEach(f => add(f.id, f.nickname, viewing === f.id));
    }

    // --- Отрисовка ---

    function put(id, node) {
        const box = $(id);
        box.innerHTML = '';
        if (node) box.appendChild(node);
        else box.innerHTML = '<p class="table-empty">Пока нечего показать.</p>';
    }

    function render(data) {
        const stats = data.stats;
        renderWho(data);
        renderTiles(stats);

        const active = stats.days.filter(d => d.chapters).length;
        $('activity-note').textContent = active
            ? `${active} ${plural(active, 'активный день', 'активных дня', 'активных дней')}`
            : 'тишина';

        put('chart-activity', activityChart(stats.days));
        put('chart-books', booksDonut(stats));
        put('chart-weekday', weekdayChart(stats.weekday));
        put('chart-top', topBooksChart(stats.top_books));

        $('stats-body').hidden = false;
        $('stats-guest').hidden = true;
    }

    async function load() {
        const url = viewing === null ? '/api/stats' : `/api/stats?user=${viewing}`;
        try {
            const resp = await fetch(url, { credentials: 'same-origin' });
            if (resp.status === 401) {
                $('stats-guest').hidden = false;
                $('stats-body').hidden = true;
                $('stats-who').hidden = true;
                return;
            }
            const data = await resp.json();
            if (!resp.ok) throw new Error(data.error || 'Не получилось');
            render(data);
        } catch (e) {
            $('stats-guest').textContent = e.message;
            $('stats-guest').hidden = false;
            $('stats-body').hidden = true;
        }
    }

    window.CyberAuth.ready.then(load);
    // Вход и выход меняют, чью статистику вообще можно смотреть
    window.CyberAuth.onChange(() => { viewing = null; load(); });
})();
