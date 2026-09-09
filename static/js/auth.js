/**
 * auth.js — общий модуль авторизации и прогресса.
 * Подключается на обеих страницах ДО app.js / player.js и отдаёт им
 * глобальный объект CyberAuth.
 */
(function () {
    'use strict';

    const LOCAL_KEY = 'cyberAudioProgress';

    let user = null;
    let downloads = false;                 // {login, nickname} или null
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
        refreshAdminAlert();    // у нового пользователя свой счёт просьб
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
        const sidebarAdmin = document.getElementById('sidebar-admin-link');
        if (sidebarAdmin) {
            sidebarAdmin.hidden = !(user && user.is_admin);
            sidebarAdmin.parentElement.classList.toggle('has-admin', !sidebarAdmin.hidden);
        }
        if (!els.userChip) return;
        if (user) {
            els.userChip.hidden = false;
            els.guestChip.hidden = true;
            els.chipName.textContent = user.nickname;
            els.panelNickname.textContent = user.nickname;
            els.panelLogin.textContent = '@' + user.login;
            els.nicknameInput.value = user.nickname;
            if (els.adminLink) els.adminLink.hidden = !user.is_admin;
        } else {
            els.userChip.hidden = true;
            els.guestChip.hidden = false;
            closePanel();
        }
    }

    /**
     * Сообщение администратору о просьбах читателей. Живёт рядом с профилем,
     * поэтому попадается на глаза на любой странице, а не только в панели.
     */
    /** Значок с числом заявок в друзья — рядом с ником и в панели. */
    function renderFriendBadge(count) {
        const badge = document.getElementById('friends-badge');
        if (badge) {
            badge.hidden = !count;
            badge.textContent = count || '';
        }
        const chipBadge = document.getElementById('chip-badge');
        if (chipBadge) {
            chipBadge.hidden = !count;
            chipBadge.textContent = count || '';
        }
    }

    /**
     * Кнопку скачивания показываем, только если APK действительно собран:
     * иначе она вела бы в никуда.
     */
    async function checkApp() {
        const link = document.getElementById('app-download');
        if (!link) return;
        try {
            const data = await api('/api/app');
            link.hidden = !data.ready;
            const size = document.getElementById('app-size');
            if (size && data.ready) {
                size.textContent = ` ${(data.size / (1024 * 1024)).toFixed(1)} МБ`;
            }
        } catch (e) {
            link.hidden = true;
        }
    }

    function renderAdminAlert(count) {
        const box = document.getElementById('admin-alert');
        if (!box) return;
        const show = Boolean(user && user.is_admin && count > 0);
        box.hidden = !show;
        const badge = document.getElementById('admin-alert-count');
        if (badge) badge.textContent = show && count > 1 ? ` (${count})` : '';
    }

    async function refreshAdminAlert() {
        if (!user) {
            renderAdminAlert(0);
            renderFriendBadge(0);
            return;
        }
        try {
            const data = await api('/api/me');
            renderAdminAlert(data.text_requests || 0);
            renderFriendBadge(data.friend_requests || 0);
        } catch (e) {
            renderAdminAlert(0);
            renderFriendBadge(0);
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
            // Администратора сразу отправляем в его панель
            if (data.user && data.user.is_admin && window.location.pathname !== '/admin') {
                window.location.href = '/admin';
            }
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
            adminLink: $('admin-link'),
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
                downloads = Boolean(data.downloads);
                renderAdminAlert(data.text_requests || 0);
                renderFriendBadge(data.friend_requests || 0);
            } catch (e) {
                user = null;
            }
            renderUserArea();
            notify();
            messages.load();
            checkApp();
            resolve(user);
        };
        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', start);
        } else {
            start();
        }
    });

    /**
     * Окна сайта вместо браузерных alert/confirm/prompt. Возвращают промис:
     * confirm/prompt — значение или null при отказе, alert — undefined.
     */
    const dialog = (() => {
        const $$ = (id) => document.getElementById(id);
        let resolver = null;

        const close = (value) => {
            const overlay = $$('ui-dialog-overlay');
            if (overlay) overlay.hidden = true;
            document.body.classList.remove('is-dialog-open');
            const done = resolver;
            resolver = null;
            if (done) done(value);
        };

        const open = (params) => new Promise((resolve) => {
            const { title, text, mode, value, okText, options } = params;
            const overlay = $$('ui-dialog-overlay');
            if (!overlay) {   // страница без общего фрагмента — не зависаем
                resolve(mode === 'confirm' ? window.confirm(text) : null);
                return;
            }
            resolver = resolve;
            $$('ui-dialog-title').textContent = title || 'Подтверждение';
            $$('ui-dialog-text').textContent = text || '';
            $$('ui-dialog-text').hidden = !text;
            $$('ui-dialog-error').hidden = true;
            const field = $$('ui-dialog-field');
            const input = $$('ui-dialog-input');
            field.hidden = mode !== 'prompt';

            const list = $$('ui-dialog-choices');
            list.innerHTML = '';
            list.hidden = !['choice', 'multi', 'tree'].includes(mode);
            if (mode === 'tree') {
                // Папки свёрнуты: в большой медиатеке раскрытое дерево
                // пришлось бы листать целую страницу
                const addNode = (parent, node, depth) => {
                    const row = document.createElement('button');
                    row.type = 'button';
                    row.className = 'ui-dialog__choice ui-dialog__choice--tree';
                    row.style.paddingLeft = `${0.85 + depth * 1.1}rem`;
                    const folder = Boolean(node.children && node.children.length);

                    if (folder) {
                        row.innerHTML = `<i class="fas fa-chevron-right"></i> ${node.label}`;
                        const branch = document.createElement('div');
                        branch.className = 'ui-dialog__branch';
                        branch.hidden = true;

                        // У папки может быть свой выбор «вся папка целиком»
                        if (node.value) {
                            const whole = document.createElement('button');
                            whole.type = 'button';
                            whole.className = 'ui-dialog__choice ui-dialog__choice--tree';
                            whole.style.paddingLeft = `${0.85 + (depth + 1) * 1.1}rem`;
                            whole.innerHTML =
                                `<i class="fas fa-layer-group"></i> Вся папка «${node.label}»`;
                            whole.addEventListener('click', () => close(node.value));
                            branch.appendChild(whole);
                        }
                        node.children.forEach(child => addNode(branch, child, depth + 1));

                        row.addEventListener('click', () => {
                            branch.hidden = !branch.hidden;
                            row.querySelector('i').className = branch.hidden
                                ? 'fas fa-chevron-right' : 'fas fa-chevron-down';
                        });
                        parent.append(row, branch);
                        return;
                    }

                    row.innerHTML = `<i class="fas fa-book"></i> ${node.label}`;
                    row.addEventListener('click', () => close(node.value));
                    parent.appendChild(row);
                };

                (options || []).forEach(node => addNode(list, node, 0));
            }
            if (mode === 'multi') {
                const preset = new Set((params.picked || []).map(String));
                (options || []).forEach(opt => {
                    const btn = document.createElement('button');
                    btn.type = 'button';
                    btn.className = 'ui-dialog__choice ui-dialog__choice--multi';
                    btn.dataset.value = opt.value;
                    const on = preset.has(String(opt.value));
                    if (on) btn.classList.add('is-picked');
                    btn.innerHTML =
                        `<i class="${on ? 'fas fa-square-check' : 'far fa-square'}"></i> ${opt.label}`;
                    btn.addEventListener('click', () => {
                        const on = btn.classList.toggle('is-picked');
                        btn.querySelector('i').className = on
                            ? 'fas fa-square-check' : 'far fa-square';
                    });
                    list.appendChild(btn);
                });
            } else if (mode === 'choice') {
                (options || []).forEach(opt => {
                    const btn = document.createElement('button');
                    btn.type = 'button';
                    btn.className = 'ui-dialog__choice';
                    btn.innerHTML = opt.icon
                        ? `<i class="fas ${opt.icon}"></i> ${opt.label}`
                        : opt.label;
                    if (opt.hint) {
                        const hint = document.createElement('span');
                        hint.className = 'ui-dialog__choice-hint';
                        hint.textContent = opt.hint;
                        btn.appendChild(hint);
                    }
                    btn.addEventListener('click', () => close(opt.value));
                    list.appendChild(btn);
                });
            }
            input.value = value || '';
            $$('ui-dialog-ok').textContent = okText || 'ОК';
            $$('ui-dialog-ok').hidden = mode === 'choice' || mode === 'tree';
            $$('ui-dialog-cancel').hidden = mode === 'alert';
            overlay.hidden = false;
            document.body.classList.add('is-dialog-open');
            setTimeout(() => (mode === 'prompt' ? input.focus() : $$('ui-dialog-ok').focus()), 50);
        });

        const bind = () => {
            const overlay = $$('ui-dialog-overlay');
            if (!overlay || overlay.dataset.bound) return;
            overlay.dataset.bound = '1';
            const input = $$('ui-dialog-input');
            const submit = () => {
                const list = $$('ui-dialog-choices');
                if (!list.hidden && list.querySelector('.ui-dialog__choice--multi')) {
                    close([...list.querySelectorAll('.is-picked')]
                        .map(b => b.dataset.value));
                    return;
                }
                const mode = $$('ui-dialog-field').hidden ? 'confirm' : 'prompt';
                if (mode === 'prompt') {
                    const text = input.value.trim();
                    if (!text) {
                        const err = $$('ui-dialog-error');
                        err.textContent = 'Введите значение';
                        err.hidden = false;
                        return;
                    }
                    close(text);
                } else {
                    close(true);
                }
            };
            $$('ui-dialog-ok').addEventListener('click', submit);
            $$('ui-dialog-cancel').addEventListener('click', () => close(null));
            $$('ui-dialog-close').addEventListener('click', () => close(null));
            // Клик по затемнению намеренно ничего не делает: заполненную
            // форму обидно терять промахом мимо окна
            input.addEventListener('keydown', (e) => {
                if (e.key === 'Enter') submit();
            });
            document.addEventListener('keydown', (e) => {
                if (e.key === 'Escape' && !overlay.hidden) close(null);
            });
        };

        if (document.readyState === 'loading') {
            document.addEventListener('DOMContentLoaded', bind);
        } else {
            bind();
        }

        return {
            alert: (text, title) => open({ title: title || 'Сообщение', text, mode: 'alert' }),
            confirm: (text, title, okText) =>
                open({ title: title || 'Подтверждение', text, mode: 'confirm', okText })
                    .then(v => Boolean(v)),
            prompt: (text, value, title, okText) =>
                open({ title: title || 'Ввод', text, mode: 'prompt', value, okText }),
            choose: (text, options, title) =>
                open({ title: title || 'Выбор', text, mode: 'choice', options }),
            chooseMany: (text, options, title, okText, picked) =>
                open({ title: title || 'Выбор', text, mode: 'multi', options,
                       okText: okText || 'Готово', picked }),
            chooseTree: (text, options, title) =>
                open({ title: title || 'Выбор', text, mode: 'tree', options }),
        };
    })();

    /**
     * Бегущие строки внизу экрана. Закрытые не возвращаются до перезагрузки:
     * помним их только в памяти вкладки, ничего не сохраняя.
     */
    const messages = (() => {
        const closed = new Set();

        const render = (list) => {
            const box = document.getElementById('site-messages');
            if (!box) return;
            const visible = (list || []).filter(m => !closed.has(m.id));
            box.hidden = visible.length === 0;
            box.innerHTML = '';

            visible.forEach((item) => {
                const line = document.createElement('div');
                line.className = `site-message site-message--${item.color}`;

                // Строка выезжает от правого края, проходит всю ширину и
                // уходит за левый, затем начинает заново — отсюда отступ
                // слева на всю ширину окна просмотра.
                const viewport = document.createElement('div');
                viewport.className = 'site-message__viewport';
                const track = document.createElement('div');
                track.className = 'site-message__track';
                const text = document.createElement('span');
                text.className = 'site-message__text';
                text.textContent = item.text;
                track.appendChild(text);
                viewport.appendChild(track);

                const close = document.createElement('button');
                close.type = 'button';
                close.className = 'site-message__close';
                close.setAttribute('aria-label', 'Закрыть сообщение');
                // Свой крестик: у шрифтового значка слишком толстые линии
                close.innerHTML = '<svg viewBox="0 0 16 16" aria-hidden="true">' +
                    '<path d="M4 4 L12 12 M12 4 L4 12" stroke="currentColor" ' +
                    'stroke-width="1.2" stroke-linecap="round" fill="none"/></svg>';
                close.addEventListener('click', () => {
                    closed.add(item.id);
                    render(list);
                });

                line.append(viewport, close);
                // Скорость одинаковая для любых строк: считаем по пути,
                // который предстоит пройти, а не по числу букв
                requestAnimationFrame(() => {
                    const distance = viewport.clientWidth + text.offsetWidth;
                    track.style.animationDuration = `${Math.max(10, distance / 90)}s`;
                });
                box.appendChild(line);
            });
        };

        const load = async () => {
            try {
                const resp = await fetch('/api/messages');
                const data = await resp.json();
                render(data.messages || []);
            } catch (e) {
                /* без сообщений сайт работает так же */
            }
        };

        return { load };
    })();

    /** Короткое всплывающее уведомление о новом достижении. */
    function showAchievement(item) {
        const box = document.getElementById('achievement-toasts');
        if (!box) return;
        // Уведомление — ссылка: по клику открывается нужный раздел достижений
        const toast = document.createElement('a');
        const book = (item.target_path || '').split('/').pop().replace(/_/g, ' ');
        toast.href = book
            ? `/achievements?book=${encodeURIComponent(book)}`
            : '/achievements';
        toast.className = `achievement-toast achievement-toast--${item.rarity}`;
        const picture = item.image
            ? `<img src="${item.image}" alt="" loading="lazy" decoding="async" onerror="this.remove();">`
            : '<i class="fas fa-trophy"></i>';
        toast.innerHTML = `
            <div class="achievement-toast__pic">${picture}</div>
            <div>
                <p class="achievement-toast__label">Достижение получено</p>
                <p class="achievement-toast__title"></p>
            </div>`;
        toast.querySelector('.achievement-toast__title').textContent = item.title;
        box.appendChild(toast);
        setTimeout(() => toast.classList.add('is-leaving'), 5000);
        setTimeout(() => toast.remove(), 5600);
    }

    window.CyberUI = dialog;
    window.CyberMessages = messages;
    window.CyberAchievements = { show: showAchievement };

    window.CyberAuth = {
        ready,
        get user() { return user; },
        isLoggedIn: () => Boolean(user),
        downloadsEnabled: () => downloads,
        onChange: (cb) => { listeners.push(cb); },
        loadProgress,
        saveProgress,
        resetProgress,
        localProgress,
        reportDurations,
        openAuth,
        refreshAdminAlert,
        formatDuration,
        formatClock,
        isFinished
    };
})();
