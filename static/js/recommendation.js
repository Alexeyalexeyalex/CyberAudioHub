/* Одна рекомендация на открытие страницы. Первые пять секунд — обычный диск. */
document.addEventListener('DOMContentLoaded', () => {
    const orbit = document.getElementById('book-recommendation');
    if (!orbit) return;
    const link = document.getElementById('recommendation-link');
    const cover = document.getElementById('recommendation-cover');
    const caption = document.getElementById('recommendation-caption');
    const started = performance.now();
    let timer;
    const reveal = (book) => {
        link.href = '/player?path=' + encodeURIComponent(book.path);
        link.setAttribute('aria-label', 'Открыть книгу «' + book.name + '»');
        link.title = book.name;
        cover.hidden = false;
        caption.textContent = book.name;
        orbit.classList.add('has-recommendation');
    };
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
    window.addEventListener('pagehide', event => { if (!event.persisted) clearTimeout(timer); });
});
