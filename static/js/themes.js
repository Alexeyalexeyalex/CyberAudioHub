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
        eye.addEventListener('click', playScreamer);
        box.appendChild(eye);

        const peek = () => {
            const fromLeft = Math.random() < 0.5;
            eye.classList.toggle('theme-monster--left', fromLeft);
            eye.style.top = `${random(20, 70)}vh`;
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
        // Сбой: помехи и наблюдатель за краем
        halloween: () => { veil(); glitch(); watcher(); },
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
