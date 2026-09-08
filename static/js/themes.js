// themes.js — праздничное оформление сайта.
//
// Включается галочками в админке. Скрипт вешает на страницу класс темы
// и собирает украшения. Всё — CSS-анимации и элементы без картинок:
// так дешевле для слабых машин и не тянет за собой ассеты.
//
// Общая идея всех четырёх оформлений одна: цветная дымка поверх фона
// плюс дрейфующие светящиеся точки. Эмодзи, сыплющиеся сверху, читались
// как гирлянда из нулевых, поэтому от них не осталось ничего.
(() => {
    const REDUCED = window.matchMedia
        && window.matchMedia('(prefers-reduced-motion: reduce)').matches;

    const layer = () => {
        let box = document.getElementById('theme-layer');
        if (!box) {
            box = document.createElement('div');
            box.id = 'theme-layer';
            box.className = 'theme-layer';
            box.setAttribute('aria-hidden', 'true');
            document.body.appendChild(box);
        }
        return box;
    };

    const random = (min, max) => min + Math.random() * (max - min);

    // Плотность оформления задаётся в админке и множит число частиц
    let intensity = 1;
    let screamerVideo = '';

    /** Элемент-одиночка: несколько включённых тем не должны его дублировать. */
    const once = (className) => {
        const box = layer();
        if (box.querySelector('.' + className)) return null;
        const el = document.createElement('span');
        el.className = className;
        box.appendChild(el);
        return el;
    };

    /**
     * Дрейфующие искры. kind задаёт цвет и размер через CSS, dir — куда
     * летят: 'rise' снизу вверх, 'fall' сверху вниз.
     *
     * Каждой частице выдаём свой набор переменных, а сама анимация лежит
     * в CSS одна на всех: сотня отдельных keyframes стоила бы дороже,
     * чем всё остальное оформление вместе взятое.
     */
    const motes = (kind, baseCount, dir) => {
        const box = layer();
        const count = Math.round(baseCount * intensity);
        for (let i = 0; i < count; i += 1) {
            const dot = document.createElement('span');
            dot.className = `theme-mote theme-mote--${kind} theme-mote--${dir}`;
            dot.style.setProperty('--x', `${random(0, 100).toFixed(2)}vw`);
            dot.style.setProperty('--size', `${random(2, 6).toFixed(1)}px`);
            // Снос вбок: без него столб частиц выглядит как дождь по линейке
            dot.style.setProperty('--sway', `${random(-14, 14).toFixed(1)}vw`);
            dot.style.setProperty('--peak', random(0.35, 0.95).toFixed(2));
            dot.style.animationDuration = `${random(14, 34).toFixed(1)}s`;
            // Отрицательная задержка — часть частиц уже в пути,
            // иначе первые полминуты экран пустой
            dot.style.animationDelay = `${-random(0, 34).toFixed(1)}s`;
            box.appendChild(dot);
        }
    };

    /** Цветная дымка поверх фона — она и задаёт настроение темы. */
    const veil = () => once('theme-veil');

    /** Хэллоуин: помехи, будто на странице сбоит развёртка. */
    const glitch = () => once('theme-glitch');

    /** День влюблённых: края экрана бьются в такт пульсу. */
    const pulse = () => once('theme-pulse');

    /* Хэллоуинские щупальца не являются частью кнопки по смыслу: это
       отдельный aria-hidden слой и pointer-events в CSS. Поэтому эффект
       остаётся заметным, но ни одна кнопка не теряет область нажатия. */
    const TENTACLE_TARGETS = [
        'button:not(.theme-monster)',
        'a.back-button',
        'a.panel-btn',
        'a.hero-link',
        'a.navigation-link'
    ].join(', ');

    const TENTACLE_MARKUP = `
        <span class="halloween-button-tentacles" aria-hidden="true">
            <svg viewBox="0 0 200 72" preserveAspectRatio="none" focusable="false">
                <path class="halloween-tentacle halloween-tentacle--rear"
                    d="M4 20 C26 0, 42 8, 51 26 S71 58, 95 48" />
                <path class="halloween-tentacle halloween-tentacle--front"
                    d="M195 53 C169 72, 149 62, 143 42 S123 8, 99 21" />
                <path class="halloween-tentacle halloween-tentacle--curl"
                    d="M31 60 C10 52, 17 31, 34 38 C49 45, 42 61, 27 54" />
                <g class="halloween-suckers">
                    <circle cx="22" cy="11" r="2.2" /><circle cx="33" cy="14" r="2" />
                    <circle cx="46" cy="25" r="2.2" /><circle cx="57" cy="41" r="1.9" />
                    <circle cx="178" cy="61" r="2.1" /><circle cx="164" cy="58" r="2" />
                    <circle cx="151" cy="48" r="2.2" /><circle cx="140" cy="31" r="1.9" />
                </g>
            </svg>
        </span>`;

    let tentacleObserver = null;

    const entwineButtons = (root = document) => {
        const candidates = [];
        if (root.nodeType === Node.ELEMENT_NODE && root.matches(TENTACLE_TARGETS)) {
            candidates.push(root);
        }
        if (root.querySelectorAll) candidates.push(...root.querySelectorAll(TENTACLE_TARGETS));

        candidates.forEach((control) => {
            if (control.closest('.theme-screamer')) return;
            if (control.dataset.halloweenEntwined !== '1') {
                control.dataset.halloweenEntwined = '1';
                control.classList.add('theme-halloween-entwined');
                if (window.getComputedStyle(control).position === 'static') {
                    control.classList.add('theme-halloween-entwined--positioned');
                }
            }
            // Некоторые кнопки обновляют подпись через textContent. В таком
            // случае браузер удаляет дочернее украшение, но не саму кнопку.
            if (!control.querySelector('.halloween-button-tentacles')) {
                control.insertAdjacentHTML('beforeend', TENTACLE_MARKUP);
            }
        });
    };

    /** Украшаем также динамические элементы: список глав и окна создаются
       уже после загрузки оформления. */
    const tentacles = () => {
        entwineButtons();
        if (tentacleObserver) return;
        tentacleObserver = new MutationObserver((records) => {
            records.forEach((record) => {
                if (record.target.nodeType === Node.ELEMENT_NODE) entwineButtons(record.target);
                record.addedNodes.forEach((node) => {
                    if (node.nodeType === Node.ELEMENT_NODE) entwineButtons(node);
                });
            });
        });
        tentacleObserver.observe(document.body, { childList: true, subtree: true });
    };

    /** Ролик во весь экран по клику на глаз. */
    const playScreamer = () => {
        if (!screamerVideo) return;
        const overlay = document.createElement('div');
        overlay.className = 'theme-screamer';
        const video = document.createElement('video');
        video.src = screamerVideo;
        video.autoplay = true;
        video.playsInline = true;
        // Клик по монстру — жест пользователя, поэтому звук разрешён
        video.muted = false;
        video.controls = false;
        overlay.appendChild(video);

        const close = () => overlay.remove();
        video.addEventListener('ended', close);
        overlay.addEventListener('click', close);
        document.body.appendChild(overlay);
        // Если браузер всё же не пустил ролик — просто убираем затемнение
        const started = video.play();
        if (started && started.catch) started.catch(close);
    };

    /**
     * Из-за края экрана время от времени выглядывает глаз. Он же —
     * кнопка скримера, поэтому остаётся кликабельным поверх всего слоя.
     * Редко и ненадолго: постоянный жилец быстро надоел бы.
     */
    const watcher = () => {
        const box = layer();
        if (box.querySelector('.theme-monster')) return;

        const eye = document.createElement('button');
        eye.type = 'button';
        eye.className = 'theme-monster';
        eye.setAttribute('aria-label', 'Кто-то смотрит');
        eye.innerHTML = `
            <span class="theme-monster__socket" aria-hidden="true">
                <span class="theme-monster__eye">
                    <span class="theme-monster__iris"><span class="theme-monster__pupil"></span></span>
                    <span class="theme-monster__glint"></span>
                    <span class="theme-monster__lid"></span>
                </span>
            </span>`;
        eye.addEventListener('click', playScreamer);
        box.appendChild(eye);

        const peek = () => {
            const fromLeft = Math.random() < 0.5;
            eye.classList.toggle('theme-monster--left', fromLeft);
            eye.style.top = `${random(20, 70)}vh`;
            eye.style.setProperty('--gaze-x', `${random(-9, 9).toFixed(1)}px`);
            eye.style.setProperty('--gaze-y', `${random(-5, 5).toFixed(1)}px`);
            eye.classList.add('is-peeking');
            setTimeout(() => eye.classList.remove('is-peeking'), 3500);
            setTimeout(peek, random(18000, 40000));
        };
        setTimeout(peek, random(4000, 9000));
    };

    const DECOR = {
        // Холод: редкие тяжёлые искры вниз плюс иней по краям обложек
        newyear: () => { veil(); motes('frost', 40, 'fall'); },
        // Тепло: пыльца поднимается вверх
        spring: () => { veil(); motes('pollen', 34, 'rise'); },
        // Пульс: искры вверх и толчки по краям экрана
        valentine: () => { veil(); motes('spark', 30, 'rise'); pulse(); },
        // Сбой: помехи, наблюдатель за краем и живые щупальца на управлении.
        halloween: () => { veil(); glitch(); watcher(); tentacles(); },
    };

    const apply = (themes) => {
        (themes || []).forEach((name) => {
            document.body.classList.add(`theme-${name}`);
            const decorate = DECOR[name];
            // При отключённой анимации оставляем только цвета темы.
            // Исключение — Хэллоуин: без наблюдателя пропал бы скример,
            // а он никуда не летит и не мигает.
            if (!decorate) return;
            if (!REDUCED) decorate();
            else if (name === 'halloween') watcher();
        });
    };

    const load = async () => {
        try {
            const resp = await fetch('/api/theme');
            const data = await resp.json();
            intensity = typeof data.intensity === 'number' ? data.intensity : 1;
            screamerVideo = data.video || '';
            document.documentElement.style.setProperty('--theme-intensity', intensity);
            apply(data.themes);
        } catch (e) {
            /* без оформления сайт работает как обычно */
        }
    };

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', load);
    } else {
        load();
    }
})();
