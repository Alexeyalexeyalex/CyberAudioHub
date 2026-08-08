# app.py
import os
import re
import functools

from flask import (Flask, jsonify, render_template, url_for, request, abort,
                   session, g)
from werkzeug.security import generate_password_hash, check_password_hash

import db as database

# --- КОНФИГУРАЦИЯ ---
APP_PORT = 2077
AUDIO_EXTENSIONS = ('.mp3', '.ogg', '.wav', '.m4a', '.flac', '.opus', '.aac')
COVER_NAMES = ('cover.jpg', 'cover.jpeg', 'cover.png', 'cover.webp',
               'folder.jpg', 'folder.jpeg', 'folder.png', 'front.jpg')

LOGIN_RE = re.compile(r'^[A-Za-z0-9_.-]{3,32}$')
MIN_PASSWORD_LENGTH = 6
MAX_NICKNAME_LENGTH = 32

app = Flask(__name__)
MUSIC_FOLDER_ROOT = os.path.join(app.static_folder, 'music')
DB_PATH = os.path.join(app.root_path, 'data', 'cyberaudio.db')

# База и ключ подписи сессий создаются при первом запуске
database.init_db(DB_PATH)
app.config.update(
    SECRET_KEY=database.get_or_create_secret_key(DB_PATH),
    SESSION_COOKIE_HTTPONLY=True,
    SESSION_COOKIE_SAMESITE='Lax',
    PERMANENT_SESSION_LIFETIME=60 * 60 * 24 * 30,  # 30 дней
)
app.teardown_appcontext(database.close_db)


def get_db():
    return database.get_db(DB_PATH)


# --- Вспомогательные функции файловой системы ---

def is_audio(filename):
    return filename.lower().endswith(AUDIO_EXTENSIONS)


def natural_key(name):
    """
    Ключ для «человеческой» сортировки: Глава_2 идёт перед Глава_10.
    """
    return [int(part) if part.isdigit() else part.lower()
            for part in re.split(r'(\d+)', name)]


def has_direct_tracks(directory_path):
    """
    Есть ли аудиофайлы непосредственно в этой папке (без учёта подпапок).
    Такая папка считается «альбомом» / аудиокнигой.
    """
    try:
        with os.scandir(directory_path) as entries:
            for entry in entries:
                if entry.is_file() and is_audio(entry.name):
                    return True
    except OSError:
        return False
    return False


def has_music_recursive(directory_path):
    """
    Рекурсивно проверяет, содержит ли папка (или ее подпапки) хотя бы один аудиофайл.
    """
    for root, dirs, files in os.walk(directory_path):
        for file in files:
            if is_audio(file):
                return True
    return False


def find_cover(directory_path, relative_path):
    """
    Ищет файл обложки в папке и возвращает URL или None.
    """
    for cover_name in COVER_NAMES:
        if os.path.isfile(os.path.join(directory_path, cover_name)):
            cover_url_path = os.path.join('music', relative_path, cover_name).replace('\\', '/')
            return url_for('static', filename=cover_url_path)
    return None


def safe_join(root, relative_path):
    """
    Собирает абсолютный путь и проверяет, что он не выходит за пределы root.
    """
    root_abs = os.path.abspath(root)
    target_abs = os.path.abspath(os.path.join(root_abs, relative_path))
    # os.path.commonpath сравнивает пути посегментно, в отличие от startswith,
    # который пропустил бы, например, ".../music_secret"
    if os.path.commonpath([root_abs, target_abs]) != root_abs:
        abort(403, "Access denied")
    return target_abs


def album_exists(relative_path):
    """Проверяет, что путь указывает на существующую папку с треками."""
    if not relative_path:
        return False
    try:
        path = safe_join(MUSIC_FOLDER_ROOT, relative_path)
    except Exception:
        return False
    return os.path.isdir(path) and has_direct_tracks(path)


# --- Пользователи и сессии ---

def current_user():
    """Возвращает строку пользователя из БД или None."""
    if 'user' in g:
        return g.user
    user_id = session.get('user_id')
    g.user = database.find_user_by_id(get_db(), user_id) if user_id else None
    if user_id and g.user is None:
        session.clear()  # пользователя удалили — чистим протухшую сессию
    return g.user


def login_required(view):
    @functools.wraps(view)
    def wrapped(*args, **kwargs):
        if current_user() is None:
            return jsonify({"error": "Требуется авторизация"}), 401
        return view(*args, **kwargs)
    return wrapped


def user_public(user):
    return {"login": user['login'], "nickname": user['nickname']}


def sign_in(user_id):
    session.clear()
    session['user_id'] = user_id
    session.permanent = True


def progress_payload(row, durations_cache):
    """
    Собирает ответ по одной книге: где остановились и сколько осталось.
    remaining/total будут null, пока браузер не сообщил длительности треков.
    """
    info = durations_cache.get(row['path'])
    elapsed = None
    total = None
    remaining = None
    percent = None

    if info and info['durations']:
        durations = info['durations']
        idx = max(0, min(row['track_index'], len(durations) - 1))
        elapsed = sum(durations[:idx]) + row['position']
        total = info['total_duration']
        if total > 0:
            elapsed = min(elapsed, total)
            remaining = max(0.0, total - elapsed)
            percent = round(elapsed / total * 100, 1)

    return {
        "path": row['path'],
        "title": row['title'],
        "cover": row['cover'],
        "track_index": row['track_index'],
        "position": row['position'],
        "finished": bool(row['finished']),
        "updated_at": row['updated_at'],
        "elapsed": elapsed,
        "total": total,
        "remaining": remaining,
        "percent": percent,
    }


# --- Маршруты страниц ---
@app.route('/')
def index():
    return render_template('index.html')


@app.route('/player')
def player():
    return render_template('player.html')


# --- API: авторизация ---

@app.route('/api/auth/register', methods=['POST'])
def api_register():
    data = request.get_json(silent=True) or {}
    login = (data.get('login') or '').strip()
    password = data.get('password') or ''

    if not LOGIN_RE.match(login):
        return jsonify({"error": "Логин: 3–32 символа, латиница, цифры, точка, дефис или подчёркивание"}), 400
    if len(password) < MIN_PASSWORD_LENGTH:
        return jsonify({"error": f"Пароль должен быть не короче {MIN_PASSWORD_LENGTH} символов"}), 400

    conn = get_db()
    if database.find_user_by_login(conn, login):
        return jsonify({"error": "Такой логин уже занят"}), 409

    # Никнейм по умолчанию совпадает с логином
    user_id = database.create_user(conn, login, login, generate_password_hash(password))
    sign_in(user_id)
    return jsonify({"user": user_public(database.find_user_by_id(conn, user_id))}), 201


@app.route('/api/auth/login', methods=['POST'])
def api_login():
    data = request.get_json(silent=True) or {}
    login = (data.get('login') or '').strip()
    password = data.get('password') or ''

    user = database.find_user_by_login(get_db(), login)
    # Одинаковый текст ошибки, чтобы нельзя было перебором узнать существующие логины
    if user is None or not check_password_hash(user['password_hash'], password):
        return jsonify({"error": "Неверный логин или пароль"}), 401

    sign_in(user['id'])
    return jsonify({"user": user_public(user)})


@app.route('/api/auth/logout', methods=['POST'])
def api_logout():
    session.clear()
    return jsonify({"ok": True})


@app.route('/api/me')
def api_me():
    user = current_user()
    return jsonify({"user": user_public(user) if user else None})


@app.route('/api/me', methods=['PATCH'])
@login_required
def api_update_me():
    user = current_user()
    data = request.get_json(silent=True) or {}
    conn = get_db()
    updates = {}

    if 'nickname' in data:
        nickname = (data.get('nickname') or '').strip()
        if not nickname:
            # Пустое поле возвращает никнейм к логину
            nickname = user['login']
        if len(nickname) > MAX_NICKNAME_LENGTH:
            return jsonify({"error": f"Никнейм не длиннее {MAX_NICKNAME_LENGTH} символов"}), 400
        updates['nickname'] = nickname

    if data.get('new_password'):
        if not check_password_hash(user['password_hash'], data.get('current_password') or ''):
            return jsonify({"error": "Текущий пароль указан неверно"}), 403
        if len(data['new_password']) < MIN_PASSWORD_LENGTH:
            return jsonify({"error": f"Пароль должен быть не короче {MIN_PASSWORD_LENGTH} символов"}), 400
        updates['password_hash'] = generate_password_hash(data['new_password'])

    if not updates:
        return jsonify({"error": "Нечего обновлять"}), 400

    database.update_user(conn, user['id'], **updates)
    return jsonify({"user": user_public(database.find_user_by_id(conn, user['id']))})


# --- API: прогресс прослушивания ---

@app.route('/api/progress')
@login_required
def api_list_progress():
    conn = get_db()
    user = current_user()
    cache = database.get_all_album_durations(conn)
    items = []
    for row in database.list_progress(conn, user['id']):
        # Книгу могли удалить с диска — не показываем «висящие» записи
        if not album_exists(row['path']):
            continue
        items.append(progress_payload(row, cache))
    return jsonify({"items": items})


@app.route('/api/progress', methods=['PUT'])
@login_required
def api_save_progress():
    data = request.get_json(silent=True) or {}
    path = (data.get('path') or '').strip('/')
    if not album_exists(path):
        return jsonify({"error": "Книга не найдена"}), 404

    try:
        track_index = max(0, int(data.get('track_index', 0)))
        position = max(0.0, float(data.get('position', 0)))
    except (TypeError, ValueError):
        return jsonify({"error": "Некорректные данные прогресса"}), 400

    conn = get_db()
    database.save_progress(
        conn, current_user()['id'], path,
        track_index=track_index,
        position=position,
        title=(data.get('title') or '')[:200],
        cover=(data.get('cover') or '')[:500],
        finished=1 if data.get('finished') else 0,
    )
    row = database.get_progress(conn, current_user()['id'], path)
    return jsonify({"progress": progress_payload(row, database.get_all_album_durations(conn))})


@app.route('/api/progress', methods=['DELETE'])
@login_required
def api_delete_progress():
    data = request.get_json(silent=True) or {}
    path = (data.get('path') or request.args.get('path') or '').strip('/')
    if not path:
        return jsonify({"error": "Не указан путь"}), 400
    database.delete_progress(get_db(), current_user()['id'], path)
    return jsonify({"ok": True})


@app.route('/api/album-durations', methods=['PUT'])
def api_save_album_durations():
    """
    Браузер сообщает длительности треков после чтения метаданных.
    Кэш общий для всех пользователей, поэтому авторизация не требуется.
    """
    data = request.get_json(silent=True) or {}
    path = (data.get('path') or '').strip('/')
    durations = data.get('durations')

    if not album_exists(path):
        return jsonify({"error": "Книга не найдена"}), 404
    if not isinstance(durations, list) or not durations:
        return jsonify({"error": "Нужен непустой список длительностей"}), 400

    try:
        clean = [max(0.0, float(d)) for d in durations]
    except (TypeError, ValueError):
        return jsonify({"error": "Некорректные длительности"}), 400
    if any(d <= 0 for d in clean):
        return jsonify({"error": "Длительности ещё не определены"}), 400

    database.save_album_durations(get_db(), path, clean)
    return jsonify({"ok": True, "total_duration": sum(clean)})


# --- API: каталог ---
@app.route('/api/browse')
def api_browse():
    """
    Главный API-эндпоинт. Принимает 'path' и возвращает содержимое.
    """
    relative_path = request.args.get('path', '').strip('/')
    current_path = safe_join(MUSIC_FOLDER_ROOT, relative_path)

    if not os.path.isdir(current_path):
        abort(404, "Directory not found")

    items = []
    tracks = []

    dir_content = sorted(os.listdir(current_path), key=natural_key)
    default_cover = url_for('static', filename='assets/default_cover.png')

    for item_name in dir_content:
        item_path_on_disk = os.path.join(current_path, item_name)
        # Путь для навигации (относительно папки music)
        nav_path = os.path.join(relative_path, item_name).replace('\\', '/')

        if os.path.isdir(item_path_on_disk):
            if has_music_recursive(item_path_on_disk):
                items.append({
                    # Папка, в которой треки лежат напрямую, — это альбом/аудиокнига.
                    # Её открываем сразу в плеере, без промежуточного запроса.
                    "type": "album" if has_direct_tracks(item_path_on_disk) else "directory",
                    "name": item_name.replace('_', ' '),
                    "path": nav_path,
                    "cover": find_cover(item_path_on_disk, nav_path) or default_cover
                })

        elif is_audio(item_name):
            track_url_path = os.path.join('music', nav_path).replace('\\', '/')
            tracks.append({
                "name": item_name,
                "url": url_for('static', filename=track_url_path)
            })

    if tracks:
        # Это "альбом", так как в папке есть треки
        cached = database.get_album_durations(get_db(), relative_path)
        return jsonify({
            "type": "album",
            "title": os.path.basename(relative_path).replace('_', ' ') or "Медиатека",
            "path": relative_path,
            # Путь к родительской папке — нужен кнопке «Назад» в плеере
            "parent": os.path.dirname(relative_path).replace('\\', '/'),
            "tracks": tracks,
            "cover": find_cover(current_path, relative_path) or default_cover,
            # Длительности из кэша: если их число совпало с числом треков,
            # браузеру не нужно заново вычитывать метаданные всех файлов
            "durations": cached['durations'] if cached and cached['track_count'] == len(tracks) else None
        })
    else:
        # Это "директория", так как в ней только другие папки
        return jsonify({
            "type": "directory",
            "items": items,
            "path": relative_path,
            "parent": os.path.dirname(relative_path).replace('\\', '/')
        })


if __name__ == '__main__':
    print("CyberAudio Hub запущен. Добро пожаловать в Найт-Сити.")
    print(f"-> База данных: {DB_PATH}")
    print(f"-> Откройте в браузере: http://localhost:{APP_PORT}")
    app.run(debug=True, port=APP_PORT, host='0.0.0.0')
