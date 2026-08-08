/**
 * auth.js — общий модуль авторизации и прогресса.
 * Подключается на обеих страницах ДО app.js / player.js и отдаёт им
 * глобальный объект CyberAuth.
 */
(function () {
    'use strict';

    const LOCAL_KEY = 'cyberAudioProgress';

    let user = null;                 // {login, nickname} или null
    let progressCache = null;        // Map: path -> запись прогресса
    const listeners = [];

    /**
     * Прогресс гостя — словарь «путь книги -> позиция» в localStorage.
     * Живёт здесь, а не в player.js, чтобы сброс истории из панели профиля
     * мог убрать и локальную копию тоже.
     */
    const localProgress = {
        readAll() {
            try {
                const raw = JSON.parse(localStorage.getItem(LOCAL_KEY));
                if (raw && typeof raw === 'object') return raw;
            } catch (e) { /* повреждённые данные игнорируем */ }

            // Миграция со старого формата (одна книга в ключе cyberAudioLastPlayed)
            try {
                const old = JSON.parse(localStorage.getItem('cyberAudioLastPlayed'));
                if (old && old.path) {
                    const migrated = { [old.path]: { trackIndex: old.trackIndex, position: old.currentTime } };
                    localStorage.setItem(LOCAL_KEY, JSON.stringify(migrated));
                    localStorage.removeItem('cyberAudioLastPlayed');
                    return migrated;
                }
            } catch (e) { /* ничего страшного */ }
            return {};
        },
        get(path) { return this.readAll()[path] || null; },
        set(path, entry) {
            this._write((all) => { all[path] = entry; });
        },
        remove(path) {
            this._write((all) => { delete all[path]; });
        },
        _write(mutate) {
            try {
                const all = this.readAll();
                mutate(all);
                localStorage.setItem(LOCAL_KEY, JSON.stringify(all));
            } catch (e) { /* приватный режим / переполнение */ }
        }
    };

    // --- Сеть ---

    async function api(url, options = {}) {
        const response = await fetch(url, {
            credentials: 'same-origin',
            headers: options.body ? { 'Content-Type': 'application/json' } : {},
            ...options
        });
        let data = null;
        try { data = await response.json(); } catch (e) { /* пустой ответ */ }
        if (!response.ok) {
            const err = new Error((data && data.error) || `Ошибка ${response.status}`);
            err.status = response.status;
            throw err;
        }
        return data;
    }

    const json = (method, url, body) => api(url, { method, body: JSON.stringify(body || {}) });

    // --- Форматирование времени ---

    function formatDuration(seconds) {
        if (seconds === null || seconds === undefined || isNaN(seconds)) return '';
        const total = Math.max(0, Math.round(seconds));
        if (total < 60) return 'меньше минуты';
        const hours = Math.floor(total / 3600);
        const minutes = Math.round((total % 3600) / 60);
        if (hours === 0) return `${minutes} мин`;
        if (minutes === 0) return `${hours} ч`;
        return `${hours} ч ${minutes} мин`;
    }

    /**
     * Считать книгу дослушанной. Порог относительный: у 10-часовой книги
     * хвост в полминуты — это конец, а у 20-минутной сказки — ещё нет.
     */
    function isFinished(item) {
        if (!item || item.remaining === null || item.remaining === undefined || !item.total) return false;
        return item.remaining < Math.min(30, item.total * 0.02);
    }

    function formatClock(seconds) {
        if (isNaN(seconds) || !isFinite(seconds)) return '0:00';
        const total = Math.floor(seconds);
        const h = Math.floor(total / 3600);
        const m = Math.floor((total % 3600) / 60);
        const s = total % 60;
        const pad = (n) => n.toString().padStart(2, '0');
        return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${m}:${pad(s)}`;
    }

    // --- Состояние пользователя ---

    function notify() {
        listeners.forEach(cb => {
            try { cb(user); } catch (e) { console.error(e); }
        });
    }

    function setUser(next) {
        user = next;
        progressCache = null;   // прогресс всегда принадлежит конкретному аккаунту
        renderUserArea();
        notify();
    }

    async function loadProgress(force = false) {
        if (!user) return new Map();
        if (progressCache && !force) return progressCache;
        try {
            const data = await api('/api/progress');
            progressCache = new Map(data.items.map(item => [item.path, item]));
        } catch (e) {
            console.warn('Не удалось загрузить прогресс:', e);
            progressCache = new Map();
        }
        return progressCache;
    }

    async function saveProgress(entry) {
        if (!user) return null;
        try {
            const data = await json('PUT', '/api/progress', entry);
            if (progressCache && data && data.progress) {
                progressCache.set(data.progress.path, data.progress);
            }
            return data ? data.progress : null;
        } catch (e) {
            console.warn('Не удалось сохранить прогресс:', e);
            return null;
        }
    }

    async function resetProgress(path) {
        if (!user) return;
        await json('DELETE', '/api/progress', { path });
        if (progressCache) progressCache.delete(path);
        // Без этого локальная копия при следующем открытии книги вернула бы
        // позицию обратно, и книга снова оказалась бы в истории
        localProgress.remove(path);
        window.dispatchEvent(new CustomEvent('cyberaudio:progress-reset', { detail: { path } }));
    }

    async function reportDurations(path, durations) {
        try {
            await json('PUT', '/api/album-durations', { path, durations });
        } catch (e) {
            console.warn('Не удалось сохранить длительности:', e);
        }
    }

    // --- Интерфейс ---

    const $ = (id) => document.getElementById(id);
    let els = {};

    function renderUserArea() {
        if (!els.userChip) return;
        if (user) {
            els.userChip.hidden = false;
            els.guestChip.hidden = true;
            els.chipName.textContent = user.nickname;
            els.panelNickname.textContent = user.nickname;
            els.panelLogin.textContent = '@' + user.login;
            els.nicknameInput.value = user.nickname;
        } else {
            els.userChip.hidden = true;
            els.guestChip.hidden = false;
            closePanel();
        }
    }

    function panelMessage(text, isError) {
        if (!els.panelMessage) return;
        els.panelMessage.textContent = text || '';
        els.panelMessage.classList.toggle('is-error', Boolean(isError));
        if (text) {
            clearTimeout(panelMessage._timer);
            panelMessage._timer = setTimeout(() => {
                els.panelMessage.textContent = '';
                els.panelMessage.classList.remove('is-error');
            }, 4000);
        }
    }

    async function renderProgressList() {
        const list = els.progressList;
        if (!list) return;
        list.innerHTML = '<li class="progress-list__empty">Загрузка…</li>';
        const map = await loadProgress(true);
        const items = Array.from(map.values());

        if (items.length === 0) {
            list.innerHTML = '<li class="progress-list__empty">Пока ничего не прослушано.</li>';
            return;
        }

        list.innerHTML = '';
        items.forEach(item => {
            const li = document.createElement('li');
            li.className = 'progress-list__item';

            const link = document.createElement('a');
            link.className = 'progress-list__link';
            link.href = `/player?path=${encodeURIComponent(item.path)}`;
            link.textContent = item.title || item.path.split('/').pop().replace(/_/g, ' ');

            const meta = document.createElement('span');
            meta.className = 'progress-list__meta';
            if (item.remaining !== null && item.remaining !== undefined) {
                meta.textContent = isFinished(item)
                    ? 'Прослушано полностью'
                    : `Осталось ${formatDuration(item.remaining)}`;
            } else {
                meta.textContent = `Глава ${item.track_index + 1}, ${formatClock(item.position)}`;
            }

            const bar = document.createElement('div');
            bar.className = 'progress-list__bar';
            const fill = document.createElement('div');
            fill.className = 'progress-list__fill';
            fill.style.width = `${item.percent || 0}%`;
            bar.appendChild(fill);

            const reset = document.createElement('button');
            reset.type = 'button';
            reset.className = 'progress-list__reset';
            reset.title = 'Сбросить прогресс';
            reset.innerHTML = '<i class="fas fa-rotate-left"></i>';
            reset.addEventListener('click', async () => {
                reset.disabled = true;
                try {
                    await resetProgress(item.path);
                    panelMessage('Прогресс сброшен');
                    renderProgressList();
                } catch (e) {
                    panelMessage(e.message, true);
                    reset.disabled = false;
                }
            });

            const row = document.createElement('div');
            row.className = 'progress-list__row';
            row.append(link, reset);
            li.append(row, bar, meta);
            list.appendChild(li);
        });
    }

    function openPanel() {
        if (!user || !els.panel) return;
        els.panel.hidden = false;
        els.userChip.setAttribute('aria-expanded', 'true');
        renderProgressList();
    }

    function closePanel() {
        if (!els.panel) return;
        els.panel.hidden = true;
        if (els.userChip) els.userChip.setAttribute('aria-expanded', 'false');
    }

    function togglePanel() {
        els.panel.hidden ? openPanel() : closePanel();
    }

    // --- Модалка входа/регистрации ---

    let authMode = 'login';

    function setAuthMode(mode) {
        authMode = mode;
        const isRegister = mode === 'register';
        els.authTitle.textContent = isRegister ? 'СОЗДАНИЕ ПРОФИЛЯ' : 'ВХОД В СИСТЕМУ';
        els.authSubmit.textContent = isRegister ? 'ЗАРЕГИСТРИРОВАТЬСЯ' : 'ВОЙТИ';
        els.authHint.hidden = !isRegister;
        els.authPassword.setAttribute('autocomplete', isRegister ? 'new-password' : 'current-password');
        els.tabLogin.classList.toggle('is-active', !isRegister);
        els.tabRegister.classList.toggle('is-active', isRegister);
        els.authError.textContent = '';
    }

    function openAuth(mode = 'login') {
        setAuthMode(mode);
        els.authOverlay.hidden = false;
        setTimeout(() => els.authLogin.focus(), 50);
    }

    function closeAuth() {
        els.authOverlay.hidden = true;
        els.authForm.reset();
        els.authError.textContent = '';
    }

    async function submitAuth(event) {
        event.preventDefault();
        const login = els.authLogin.value.trim();
        const password = els.authPassword.value;
        els.authError.textContent = '';
        els.authSubmit.disabled = true;

        try {
            const url = authMode === 'register' ? '/api/auth/register' : '/api/auth/login';
            const data = await json('POST', url, { login, password });
            setUser(data.user);
            closeAuth();
        } catch (e) {
            els.authError.textContent = e.message;
        } finally {
            els.authSubmit.disabled = false;
        }
    }

    // --- Инициализация ---

    function bindUi() {
        els = {
            userChip: $('user-chip'), guestChip: $('guest-chip'), chipName: $('user-chip-name'),
            panel: $('user-panel'), panelClose: $('panel-close'), panelNickname: $('panel-nickname'),
            panelLogin: $('panel-login'), panelMessage: $('panel-message'),
            nicknameInput: $('nickname-input'), nicknameSave: $('nickname-save'),
            currentPassword: $('current-password'), newPassword: $('new-password'),
            passwordSave: $('password-save'), progressList: $('progress-list'), logout: $('logout-btn'),
            authOverlay: $('auth-overlay'),
            authModal: document.querySelector('#auth-overlay .auth-modal'),
            authForm: $('auth-form'),
            authClose: $('auth-close'), authTitle: $('auth-title'), authSubmit: $('auth-submit'),
            authHint: $('auth-hint'), authError: $('auth-error'), authLogin: $('auth-login'),
            authPassword: $('auth-password'), tabLogin: $('tab-login'), tabRegister: $('tab-register')
        };
        if (!els.userChip) return;

        els.userChip.addEventListener('click', togglePanel);
        els.panelClose.addEventListener('click', closePanel);
        els.guestChip.addEventListener('click', () => openAuth('login'));

        els.tabLogin.addEventListener('click', () => setAuthMode('login'));
        els.tabRegister.addEventListener('click', () => setAuthMode('register'));
        els.authClose.addEventListener('click', closeAuth);
        els.authForm.addEventListener('submit', submitAuth);
        // Клик по затемнению НЕ закрывает окно: промах мимо поля стирал бы
        // наполовину заполненную форму. Закрыть можно только крестиком.
        els.authOverlay.addEventListener('click', (e) => {
            if (e.target !== els.authOverlay) return;
            // Короткая подсветка рамки — подсказка, где находится закрытие
            els.authModal.classList.remove('is-nudged');
            void els.authModal.offsetWidth;   // перезапуск CSS-анимации
            els.authModal.classList.add('is-nudged');
        });

        els.nicknameSave.addEventListener('click', async () => {
            els.nicknameSave.disabled = true;
            try {
                const data = await json('PATCH', '/api/me', { nickname: els.nicknameInput.value });
                user = data.user;
                renderUserArea();
                notify();
                panelMessage('Никнейм обновлён');
            } catch (e) {
                panelMessage(e.message, true);
            } finally {
                els.nicknameSave.disabled = false;
            }
        });

        els.nicknameInput.addEventListener('keydown', (e) => {
            if (e.key === 'Enter') { e.preventDefault(); els.nicknameSave.click(); }
        });

        els.passwordSave.addEventListener('click', async () => {
            els.passwordSave.disabled = true;
            try {
                await json('PATCH', '/api/me', {
                    current_password: els.currentPassword.value,
                    new_password: els.newPassword.value
                });
                els.currentPassword.value = '';
                els.newPassword.value = '';
                panelMessage('Пароль обновлён');
            } catch (e) {
                panelMessage(e.message, true);
            } finally {
                els.passwordSave.disabled = false;
            }
        });

        els.logout.addEventListener('click', async () => {
            try { await json('POST', '/api/auth/logout'); } catch (e) { /* всё равно разлогиниваем */ }
            setUser(null);
        });

        // Панель профиля — выпадающее меню, её клик мимо и Escape закрывают
        document.addEventListener('click', (e) => {
            if (els.panel.hidden) return;
            if (!e.target.closest('#user-area')) closePanel();
        });
        document.addEventListener('keydown', (e) => {
            if (e.key !== 'Escape') return;
            // Пока открыто окно входа, Escape тоже ничего не закрывает
            if (!els.authOverlay.hidden) return;
            if (!els.panel.hidden) closePanel();
        });
    }

    const ready = new Promise((resolve) => {
        const start = async () => {
            bindUi();
            try {
                const data = await api('/api/me');
                user = data.user;
            } catch (e) {
                user = null;
            }
            renderUserArea();
            notify();
            resolve(user);
        };
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', start);
        } else {
            start();
        }
    });

    window.CyberAuth = {
        ready,
        get user() { return user; },
        isLoggedIn: () => Boolean(user),
        onChange: (cb) => { listeners.push(cb); },
        loadProgress,
        saveProgress,
        resetProgress,
        localProgress,
        reportDurations,
        openAuth,
        formatDuration,
        formatClock,
        isFinished
    };
})();
