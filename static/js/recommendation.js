/* Одна рекомендация на открытие страницы. Первые пять секунд — обычный диск. */
document.addEventListener('DOMContentLoaded', () => {
    const orbit = document.getElementById('book-recommendation');
    if (!orbit) return;
    const link = document.getElementById('recommendation-link');
    const cover = document.getElementById('recommendation-cover');
    const caption = document.getElementById('recommendation-caption');
    const pauseButton = document.getElementById('recommendation-pause');
    const reduced = matchMedia('(prefers-reduced-motion: reduce)');
    const started = performance.now();
    let timer;
    let motorTimer;
    let frame;
    let spin;
    let paused = false;

    function startMotor() {
        if (reduced.matches || !link.animate) return;
        // Меняется скорость одной анимации, а не сама transform: нет скачка
        // угла при переходе от разгона к постоянному вращению.
        spin = link.animate([{transform: 'rotate(-16deg)'}, {transform: 'rotate(344deg)'}],
            {duration: 22000, iterations: Infinity});
        spin.playbackRate = 0;
        const begin = performance.now();
        const accelerate = now => {
            const t = Math.min(1, (now - begin) / 1800);
            spin.playbackRate = t * t * (3 - 2 * t);
            if (t < 1) frame = requestAnimationFrame(accelerate);
        };
        if (paused || document.hidden) spin.pause();
        frame = requestAnimationFrame(accelerate);
    }
    const reveal = (book) => {
        link.href = '/player?path=' + encodeURIComponent(book.path);
        link.setAttribute('aria-label', 'Открыть книгу «' + book.name + '»');
        link.title = book.name;
        cover.hidden = false;
        caption.textContent = book.name;
        orbit.classList.add('has-recommendation');
        if (!reduced.matches) {
            pauseButton.hidden = false;
            motorTimer = setTimeout(startMotor, 2000);
        }
    };
    pauseButton.addEventListener('click', () => {
        paused = !paused;
        pauseButton.setAttribute('aria-pressed', String(paused));
        pauseButton.setAttribute('aria-label', paused ? 'Продолжить анимацию пластинки' : 'Остановить анимацию пластинки');
        pauseButton.firstElementChild.className = paused ? 'fas fa-play' : 'fas fa-pause';
        if (spin) paused ? spin.pause() : spin.play();
    });
    document.addEventListener('visibilitychange', () => {
        if (spin) document.hidden || paused ? spin.pause() : spin.play();
    });
    reduced.addEventListener('change', () => {
        if (reduced.matches) {
            clearTimeout(motorTimer); cancelAnimationFrame(frame);
            if (spin) spin.cancel();
            pauseButton.hidden = true;
        }
    });
    fetch('/api/recommendation', {credentials: 'same-origin'})
        .then(response => response.ok ? response.json() : Promise.reject())
        .then(data => {
            if (!data.book || !data.book.path) return;
            const book = data.book;
            cover.onload = () => {
                timer = setTimeout(() => reveal(book), Math.max(0, 5000 - (performance.now() - started)));
            };
            cover.onerror = () => {
                cover.onerror = null;
                cover.src = '/static/assets/default_cover.png';
            };
            cover.src = book.cover || '/static/assets/default_cover.png';
        })
        .catch(() => { /* Пустой каталог и недоступная сеть оставляют обычный диск. */ });
    window.addEventListener('pagehide', event => {
        if (!event.persisted) {
            clearTimeout(timer); clearTimeout(motorTimer); cancelAnimationFrame(frame);
            if (spin) spin.cancel();
        }
    });
});
