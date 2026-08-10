document.addEventListener('DOMContentLoaded', () => {
    const $ = (id) => document.getElementById(id);

    const els = {
        warning: $('admin-warning'),
        stats: $('admin-stats'),
        users: document.querySelector('#users-table tbody'),
        progress: document.querySelector('#progress-table tbody'),
        albums: document.querySelector('#albums-table tbody'),
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
            alert(e.message);
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

            const del = iconButton('fa-trash', 'Удалить', 'row-btn--danger', () => {
                if (!confirm(`Удалить пользователя «${user.login}»? Его прогресс тоже будет стёрт.`)) return;
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

    function renderProgress(items) {
        els.progress.innerHTML = '';
        if (!items.length) {
            els.progress.innerHTML = '<tr><td colspan="7" class="table-empty">Записей пока нет.</td></tr>';
            return;
        }
        items.forEach(item => {
            const tr = document.createElement('tr');
            const actions = document.createElement('td');
            actions.className = 'row-actions';
            const del = iconButton('fa-trash', 'Удалить запись', 'row-btn--danger', () => {
                if (!confirm(`Сбросить прогресс «${item.title || item.path}» у ${item.login}?`)) return;
                guarded(del, () => send('DELETE', '/api/admin/progress',
                    { user_id: item.user_id, path: item.path }));
            });
            actions.appendChild(del);

            const remaining = item.remaining === null || item.remaining === undefined
                ? '—'
                : window.CyberAuth.formatDuration(item.remaining);

            tr.append(
                text(`${item.nickname} (${item.login})`),
                text(item.title || item.path),
                text(item.track_index + 1),
                text(window.CyberAuth.formatClock(item.position)),
                text(remaining),
                text(shortDate(item.updated_at)),
                actions
            );
            els.progress.appendChild(tr);
        });
    }

    function renderAlbums(albums) {
        els.albums.innerHTML = '';
        if (!albums.length) {
            els.albums.innerHTML = '<tr><td colspan="6" class="table-empty">Кэш пуст.</td></tr>';
            return;
        }
        albums.forEach(album => {
            const tr = document.createElement('tr');
            const exists = document.createElement('td');
            exists.innerHTML = album.exists
                ? '<span class="badge badge--ok">да</span>'
                : '<span class="badge badge--warn">нет</span>';

            const actions = document.createElement('td');
            actions.className = 'row-actions';
            const del = iconButton('fa-trash', 'Очистить кэш книги', 'row-btn--danger', () => {
                if (!confirm(`Удалить кэш длительностей для «${album.path}»?`)) return;
                guarded(del, () => send('DELETE', '/api/admin/albums', { path: album.path }));
            });
            actions.appendChild(del);

            tr.append(text(album.path), text(album.track_count),
                text(window.CyberAuth.formatDuration(album.total_duration)),
                text(shortDate(album.updated_at)), exists, actions);
            els.albums.appendChild(tr);
        });
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
            renderStats(data.stats);
            renderUsers(data.users);
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

    els.addBtn.addEventListener('click', () => openForm(null));
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
