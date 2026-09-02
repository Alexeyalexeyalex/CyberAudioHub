# app.py
import os
import re
import sqlite3
import secrets
import functools
from datetime import datetime, timezone, timedelta

from flask import (Flask, jsonify, render_template, url_for, request, abort,
                   session, g, send_file)
from werkzeug.security import generate_password_hash, check_password_hash

import db as database
import transcribe

# --- КОНФИГУРАЦИЯ ---
APP_PORT = 2077
AUDIO_EXTENSIONS = ('.mp3', '.ogg', '.wav', '.m4a', '.flac', '.opus', '.aac')

# Документы, из которых воркер умеет доставать текст для озвучки. Папка
# с таким файлом и без аудио — «текстовая книга»: в медиатеке её не видно,
# потому что альбомом считается только папка с аудиофайлами.
DOCUMENT_EXTENSIONS = ('.txt', '.md', '.fb2', '.epub', '.pdf', '.docx')
COVER_NAMES = ('cover.jpg', 'cover.jpeg', 'cover.png', 'cover.webp',
               'folder.jpg', 'folder.jpeg', 'folder.png', 'front.jpg')

LOGIN_RE = re.compile(r'^[A-Za-z0-9_.-]{3,32}$')

# Учётная запись администратора создаётся при первом запуске.
# Логин и пароль можно переопределить переменными окружения — см. README.
ADMIN_LOGIN = os.environ.get('CYBERAUDIO_ADMIN_LOGIN', 'admin')
ADMIN_PASSWORD = os.environ.get('CYBERAUDIO_ADMIN_PASSWORD', '123Alex12')

# Режим отладки удобен при разработке, но даёт интерактивную консоль Python
# всякому, кто дотянется до порта. Выключается: CYBERAUDIO_DEBUG=0
DEBUG = os.environ.get('CYBERAUDIO_DEBUG', '1').strip().lower() not in ('0', 'false', 'no', '')
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


def ensure_admin_account():
    """
    Создаёт администратора при первом запуске. Если учётка с таким логином
    уже есть, пароль не трогаем — просто выдаём ей права, чтобы случайно
    не перезаписать чей-то существующий аккаунт.
    """
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    try:
        existing = database.find_user_by_login(conn, ADMIN_LOGIN)
        if existing is None:
            database.create_user(conn, ADMIN_LOGIN, ADMIN_LOGIN,
                                 generate_password_hash(ADMIN_PASSWORD), is_admin=1)
            return 'created'
        if not existing['is_admin']:
            database.update_user(conn, existing['id'], is_admin=1)
            return 'promoted'
        return 'exists'
    finally:
        conn.close()


ADMIN_STATE = ensure_admin_account()


def get_db():
    return database.get_db(DB_PATH)


# --- Вспомогательные функции файловой системы ---

def is_audio(filename):
    return filename.lower().endswith(AUDIO_EXTENSIONS)


def is_document(filename):
    return filename.lower().endswith(DOCUMENT_EXTENSIONS)


def has_direct_documents(directory_path):
    """Есть ли в самой папке (без подпапок) файл-документ."""
    try:
        with os.scandir(directory_path) as entries:
            for entry in entries:
                if entry.is_file() and is_document(entry.name):
                    return True
    except OSError:
        return False
    return False


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


@app.route('/media')
def media_file():
    """
    Отдаёт файл из медиатеки. Нужен потому, что дополнительные папки лежат
    вне static: встроенная раздача Flask до них не достаёт.
    """
    virtual = (request.args.get('path') or '').strip('/')
    disk = resolve_disk(virtual)
    if not os.path.isfile(disk):
        abort(404)
    return send_file(disk, conditional=True)


DOWNLOADS_KEY = 'downloads_enabled'


def downloads_enabled():
    """Разрешено ли читателям скачивать файлы. По умолчанию — нет."""
    try:
        return database.get_flag(get_db(), DOWNLOADS_KEY, False)
    except Exception:
        return False


def find_cover_file(directory_path):
    for cover_name in COVER_NAMES:
        candidate = os.path.join(directory_path, cover_name)
        if os.path.isfile(candidate):
            return candidate
    return None


@app.route('/api/download/track')
def api_download_track():
    """Один аудиофайл главы."""
    if not downloads_enabled():
        return jsonify({"error": "Скачивание выключено"}), 403

    path = (request.args.get('path') or '').strip('/')
    try:
        index = int(request.args.get('track'))
    except (TypeError, ValueError):
        return jsonify({"error": "Некорректный номер главы"}), 400

    tracks = album_tracks_on_disk(path)
    if not 0 <= index < len(tracks):
        return jsonify({"error": "Глава не найдена"}), 404
    name, disk = tracks[index]
    return send_file(disk, as_attachment=True, download_name=name)


@app.route('/api/download/book')
def api_download_book():
    """
    Вся книга одним архивом. Складываем без сжатия: аудио уже сжато, и
    попытка ужать его только съела бы время и процессор.
    """
    if not downloads_enabled():
        return jsonify({"error": "Скачивание выключено"}), 403

    path = (request.args.get('path') or '').strip('/')
    tracks = album_tracks_on_disk(path)
    if not tracks:
        return jsonify({"error": "Книга не найдена"}), 404

    import zipfile
    import tempfile

    title = os.path.basename(path).replace('_', ' ') or 'book'
    handle, temp_path = tempfile.mkstemp(suffix='.zip')
    os.close(handle)
    try:
        with zipfile.ZipFile(temp_path, 'w', zipfile.ZIP_STORED) as archive:
            for name, disk in tracks:
                archive.write(disk, arcname=name)
            cover = find_cover_file(resolve_disk(path))
            if cover:
                archive.write(cover, arcname=os.path.basename(cover))
    except OSError:
        os.remove(temp_path)
        return jsonify({"error": "Не удалось собрать архив"}), 500

    response = send_file(temp_path, as_attachment=True, download_name=f'{title}.zip')
    # Временный архив убираем, когда отдача закончится
    response.call_on_close(lambda: os.path.exists(temp_path) and os.remove(temp_path))
    return response


def media_url(virtual_path):
    return url_for('media_file', path=virtual_path)


def find_cover(directory_path, relative_path):
    """
    Ищет файл обложки в папке и возвращает URL или None.
    """
    for cover_name in COVER_NAMES:
        if os.path.isfile(os.path.join(directory_path, cover_name)):
            joined = f'{relative_path}/{cover_name}' if relative_path else cover_name
            return media_url(joined.replace('\\', '/'))
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


def main_root():
    """Основная папка медиатеки; её путь настраивается в админке."""
    try:
        return database.get_main_root(get_db(), MUSIC_FOLDER_ROOT)
    except Exception:
        return MUSIC_FOLDER_ROOT


def extra_roots():
    """Дополнительные папки: [(имя, путь на диске), ...]."""
    try:
        return [(r['name'], r['path']) for r in database.list_roots(get_db())]
    except Exception:
        return []


def resolve_root(virtual_path):
    """
    Куда на диске смотрит виртуальный путь.

    Основная папка отвечает за пути без префикса — так уже накопленные записи
    (прогресс, расшифровки, подборки) продолжают работать без переделки.
    Дополнительные папки живут под своим именем: «Внешний/Книги/...».
    """
    virtual = (virtual_path or '').strip('/')
    if virtual:
        head = virtual.split('/')[0]
        for name, disk in extra_roots():
            if head == name:
                return disk, virtual[len(head):].strip('/')
    return main_root(), virtual


def resolve_disk(virtual_path):
    """Абсолютный путь на диске с защитой от выхода за пределы папки."""
    root, rel = resolve_root(virtual_path)
    return safe_join(root, rel)


def all_album_paths():
    """Все папки медиатеки с аудиофайлами — по всем подключённым папкам."""
    found = []
    sources = [('', main_root())] + [(n, p) for n, p in extra_roots()]
    for prefix, root in sources:
        if not os.path.isdir(root):
            continue
        for current, dirs, files in os.walk(root):
            if any(is_audio(f) for f in files):
                rel = os.path.relpath(current, root).replace('\\', '/')
                rel = '' if rel == '.' else rel
                if prefix:
                    rel = f'{prefix}/{rel}' if rel else prefix
                found.append(rel)
    return sorted(set(found), key=natural_key)


def album_tracks_on_disk(relative_path):
    """[(имя файла, абсолютный путь), ...] в том же порядке, что и в плеере."""
    if not album_exists(relative_path):
        return []
    directory = resolve_disk(relative_path)
    names = sorted((n for n in os.listdir(directory) if is_audio(n)), key=natural_key)
    return [(n, os.path.join(directory, n)) for n in names]


def album_exists(relative_path):
    """Проверяет, что путь указывает на существующую папку с треками."""
    if not relative_path:
        return False
    try:
        path = resolve_disk(relative_path)
    except Exception:
        return False
    return os.path.isdir(path) and has_direct_tracks(path)


def all_text_book_paths():
    """
    Папки с документами — по всем подключённым корням. В медиатеку они не
    попадают: там альбомом считается папка с аудио. Видно их только в админке,
    в разделе «Текстовые книги».
    """
    found = []
    sources = [('', main_root())] + [(n, p) for n, p in extra_roots()]
    for prefix, root in sources:
        if not os.path.isdir(root):
            continue
        for current, _dirs, files in os.walk(root):
            if not any(is_document(f) for f in files):
                continue
            rel = os.path.relpath(current, root).replace('\\', '/')
            rel = '' if rel == '.' else rel
            if prefix:
                rel = f'{prefix}/{rel}' if rel else prefix
            found.append(rel)
    return sorted(set(p for p in found if p), key=natural_key)


def book_documents_on_disk(relative_path):
    """[(имя файла, абсолютный путь, размер), ...] — документы книги."""
    if not relative_path:
        return []
    try:
        directory = resolve_disk(relative_path)
    except Exception:
        return []
    if not os.path.isdir(directory):
        return []
    names = sorted((n for n in os.listdir(directory) if is_document(n)),
                   key=natural_key)
    out = []
    for name in names:
        disk = os.path.join(directory, name)
        try:
            size = os.path.getsize(disk)
        except OSError:
            size = 0
        out.append((name, disk, size))
    return out


def text_book_exists(relative_path):
    return bool(book_documents_on_disk(relative_path))


def safe_track_name(title):
    """
    Имя главы, пригодное для файла. secure_filename из werkzeug здесь не
    годится: он выбрасывает кириллицу целиком, и от «Глава 1» не остаётся
    ничего. Поэтому убираем только то, что действительно нельзя.
    """
    cleaned = re.sub(r'[\\/:*?"<>|\r\n\t]', ' ', str(title or ''))
    cleaned = re.sub(r'\s+', ' ', cleaned).strip(' .')
    return cleaned[:80]


def generated_track_filename(index, title, total, ext='mp3'):
    """
    Имя файла озвученной главы. Номер с ведущими нулями обязателен: порядок
    глав в плеере задаётся сортировкой имён, и «10» не должна обгонять «2».
    """
    width = max(2, len(str(max(int(total or 0), 1))))
    stem = safe_track_name(title) or f'Глава {index + 1}'
    ext = re.sub(r'[^a-z0-9]', '', str(ext or 'mp3').lower()) or 'mp3'
    return f'{index + 1:0{width}d}. {stem}.{ext}'


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


def admin_required(view):
    @functools.wraps(view)
    def wrapped(*args, **kwargs):
        user = current_user()
        if user is None:
            return jsonify({"error": "Требуется авторизация"}), 401
        if not user['is_admin']:
            return jsonify({"error": "Недостаточно прав"}), 403
        return view(*args, **kwargs)
    return wrapped


def user_public(user):
    return {
        "login": user['login'],
        "nickname": user['nickname'],
        "is_admin": bool(user['is_admin']),
    }


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


# Распознаванием занимается внешний компьютер (папка worker/). Сервер лишь
# ведёт очередь: галочка в админке ставит книгу в неё, воркер разбирает.
WORKER_TOKEN = database.get_or_create_worker_token(
    DB_PATH, os.environ.get('CYBERAUDIO_WORKER_TOKEN', ''))

# Если воркер взял главу и замолчал — через столько минут она вернётся
# в очередь. Длинная глава распознаётся долго, поэтому воркер продлевает
# заявку в процессе, а не берёт её «навсегда».
CLAIM_TIMEOUT_MINUTES = int(os.environ.get('CYBERAUDIO_CLAIM_TIMEOUT', '15'))


def stale_before():
    """Момент, раньше которого заявки считаются брошенными."""
    return (datetime.now(timezone.utc)
            - timedelta(minutes=CLAIM_TIMEOUT_MINUTES)).isoformat(timespec='seconds')


def adopt_existing_text(conn, path):
    """
    Забирает в базу текст, который уже лежит на диске рядом с аудио.
    Работает и когда книгу принесли с готовой папкой text/, и когда базу
    сбросили: распознавать заново такие главы незачем.
    """
    adopted = 0
    language = ''
    for index, (name, disk_path) in enumerate(album_tracks_on_disk(path)):
        if database.get_transcript_track(conn, path, index) is not None:
            continue
        saved = transcribe.read_track_file(disk_path)
        if not saved:
            continue
        segments, lang = saved
        database.save_transcript_track(conn, path, index, name, segments)
        language = language or lang
        adopted += 1
    return adopted, language


def refresh_transcript_status(conn, path):
    """Пересчитывает статус книги по числу готовых глав."""
    row = database.get_transcript(conn, path)
    if row is None:
        return None
    done = database.transcript_track_indexes(conn, path)
    total = row['total_tracks'] or len(album_tracks_on_disk(path))
    if total and len(done) >= total:
        database.release_book_claims(conn, path)
        database.update_transcript(conn, path, status='done',
                                   done_tracks=len(done), error='')
    else:
        busy = database.active_claims(conn, stale_before())
        running = any(p == path for p, _ in busy)
        database.update_transcript(conn, path,
                                   status='running' if running else 'pending',
                                   done_tracks=len(done))
    return database.get_transcript(conn, path)


def queue_snapshot(conn):
    """Что осталось распознать — общий вид очереди для админки и воркера."""
    busy = database.active_claims(conn, stale_before())
    books = []
    for row in database.list_transcripts(conn):
        path = row['path']
        tracks = album_tracks_on_disk(path)
        done = set(database.transcript_track_indexes(conn, path))
        pending = []
        for index, (name, _disk) in enumerate(tracks):
            if index in done:
                continue
            claim = busy.get((path, index))
            pending.append({"index": index, "name": name,
                            "taken_by": claim['worker'] if claim else None})
        books.append({
            "path": path,
            "title": os.path.basename(path).replace('_', ' '),
            "status": row['status'],
            "total_tracks": len(tracks),
            "done_tracks": len(done),
            "pending": pending,
        })
    return books


# --- Озвучка книг из документов ---
#
# Зеркало очереди расшифровки, но в другую сторону: там из аудио делали
# текст, здесь из документа делают аудио. Сервер снова ничего не считает
# сам — он ведёт очередь и складывает присланные воркером файлы.
#
# Одно отличие в механике: бронь берётся на книгу целиком, а не на главу.
# Сколько в книге глав, выясняется только после разбора документа, а
# разбирает его воркер — сервер про содержимое pdf ничего не знает.

def voiceover_state(conn, path):
    """Статус озвучки книги. None — книга в очередь не ставилась."""
    row = database.get_voiceover(conn, path)
    if row is None:
        return None
    busy = database.active_book_claims(conn, stale_before())
    claim = busy.get(path)
    return {
        "path": path,
        "status": row['status'],
        "engine": row['engine'],
        "voice": row['voice'],
        "document": row['document'],
        "done_tracks": row['done_tracks'],
        "total_tracks": row['total_tracks'],
        "error": row['error'],
        "worker": claim['worker'] if claim else '',
        "updated_at": row['updated_at'],
    }


def refresh_voiceover_status(conn, path):
    """Пересчитывает статус книги по числу готовых глав."""
    row = database.get_voiceover(conn, path)
    if row is None:
        return None
    done = len(database.voiceover_track_indexes(conn, path))
    total = row['total_tracks'] or 0
    if total and done >= total:
        database.release_book_claim(conn, path)
        database.update_voiceover(conn, path, status='done',
                                  done_tracks=done, error='')
    else:
        busy = database.active_book_claims(conn, stale_before())
        database.update_voiceover(conn, path,
                                  status='running' if path in busy else 'pending',
                                  done_tracks=done)
    return database.get_voiceover(conn, path)


def text_books_payload(conn):
    """
    Список текстовых книг для админки: где лежит, чем открывается,
    есть ли уже аудио и что с очередью озвучки.
    """
    books = []
    for path in all_text_book_paths():
        docs = book_documents_on_disk(path)
        if not docs:
            continue
        books.append({
            "path": path,
            "title": os.path.basename(path).replace('_', ' '),
            "documents": [{"name": n, "size": size} for n, _disk, size in docs],
            "has_audio": album_exists(path),
            "voice": voiceover_state(conn, path),
        })
    return books


def voice_queue_snapshot(conn):
    """Что осталось озвучить — общий вид очереди для админки и воркера."""
    busy = database.active_book_claims(conn, stale_before())
    out = []
    for row in database.list_voiceovers(conn):
        path = row['path']
        if row['status'] == 'done':
            continue
        docs = book_documents_on_disk(path)
        if not docs:
            continue  # документ унесли с диска — озвучивать нечего
        claim = busy.get(path)
        out.append({
            "path": path,
            "title": os.path.basename(path).replace('_', ' '),
            "document": row['document'] or docs[0][0],
            "status": row['status'],
            "total_tracks": row['total_tracks'],
            # Уже готовые главы, чтобы после обрыва воркер продолжил,
            # а не начинал книгу заново
            "done": database.voiceover_track_indexes(conn, path),
            "taken_by": claim['worker'] if claim else None,
        })
    return out


def voiceover_document(conn, path):
    """Файл, который озвучиваем. Записан при постановке в очередь."""
    docs = book_documents_on_disk(path)
    if not docs:
        return None
    row = database.get_voiceover(conn, path)
    wanted = row['document'] if row is not None else ''
    for name, disk, _size in docs:
        if name == wanted:
            return name, disk
    return docs[0][0], docs[0][1]


def remove_generated_audio(conn, path):
    """
    Убирает всё, что озвучка создала: аудиофайлы, текст рядом с ними и
    записи в базе. Удаляем строго по списку из voiceover_tracks — маской
    по папке можно было бы снести и то, что положили руками.
    """
    rows = database.voiceover_tracks(conn, path)
    try:
        directory = resolve_disk(path)
    except Exception:
        return 0

    tracks = []
    for row in rows:
        disk = os.path.join(directory, row['filename'])
        if os.path.isfile(disk):
            tracks.append((row['filename'], disk))

    # Текст удаляем до аудио: имена текстовых файлов выводятся из имён
    # аудиодорожек, после удаления мп3 их уже не найти.
    transcribe.remove_track_files(tracks)
    removed = 0
    for _name, disk in tracks:
        try:
            os.remove(disk)
            removed += 1
        except OSError:
            pass
    return removed


def worker_required(view):
    """Пускает только по общему токену — он же настраивается в воркере."""
    @functools.wraps(view)
    def wrapped(*args, **kwargs):
        token = (request.headers.get('X-Worker-Token')
                 or request.args.get('token') or '')
        if not secrets.compare_digest(token, WORKER_TOKEN):
            return jsonify({"error": "Неверный токен распознавателя"}), 403
        return view(*args, **kwargs)
    return wrapped


# --- Маршруты страниц ---
@app.route('/')
def index():
    return render_template('index.html')


@app.route('/player')
def player():
    return render_template('player.html')


@app.route('/friends')
def friends_page():
    return render_template('friends.html')


@app.route('/achievements')
def achievements_page():
    return render_template('achievements.html')


@app.route('/admin')
def admin_page():
    user = current_user()
    if user is None or not user['is_admin']:
        # Обычному пользователю страницы просто не существует
        abort(404)
    return render_template('admin.html', admin_login=ADMIN_LOGIN)


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
    if user is None:
        return jsonify({"user": None, "downloads": downloads_enabled()})
    conn = get_db()
    payload = {
        "user": user_public(user),
        "downloads": downloads_enabled(),
        # Книги, которые этот человек уже просил: кнопка на них гаснет
        "requested_paths": database.user_requested_paths(conn, user['id']),
        "friend_requests": database.count_incoming_requests(conn, user['id']),
    }
    if user['is_admin']:
        # Значок «запрошены текстовые книги» виден на любой странице
        payload["text_requests"] = database.count_text_requests(conn)
    return jsonify(payload)


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




# --- API: текстовая версия ---

def _requested_by_me(conn, path):
    """Просил ли текущий читатель текст для этой книги."""
    user = current_user()
    if user is None:
        return False
    return database.has_text_request(conn, path, user['id'])


def transcript_state(conn, path, track_count=None):
    """Краткий статус текста книги — для плеера и карточек."""
    row = database.get_transcript(conn, path)
    if row is None:
        return {"status": "none", "ready": False, "engine": "",
                "requested": _requested_by_me(conn, path)}
    ready_tracks = database.transcript_track_indexes(conn, path)
    return {
        "status": row['status'],
        # Читать можно уже с первой распознанной главы, не дожидаясь всей книги
        "ready": bool(ready_tracks),
        "ready_tracks": ready_tracks,
        "done_tracks": row['done_tracks'],
        "total_tracks": row['total_tracks'] or (track_count or 0),
        "engine": row['engine'],
        "requested": _requested_by_me(conn, path),
        "language": row['language'],
        "error": row['error'],
    }


@app.route('/api/transcript')
def api_transcript():
    """Текст одной главы вместе с пословной разметкой времени."""
    path = (request.args.get('path') or '').strip('/')
    if not album_exists(path):
        return jsonify({"error": "Книга не найдена"}), 404

    conn = get_db()
    state = transcript_state(conn, path)

    if request.args.get('all'):
        # Вся книга разом — для режима сплошного чтения. Главы без текста
        # пропускаем: показывать пустоту между готовыми незачем.
        chapters = []
        for index, (name, _disk) in enumerate(album_tracks_on_disk(path)):
            track = database.get_transcript_track(conn, path, index)
            if track is None:
                continue
            chapters.append({"index": index, "name": track['name'] or name,
                             "segments": track['segments']})
        return jsonify({"state": state, "chapters": chapters})

    track_param = request.args.get('track')
    if track_param is None:
        return jsonify({"state": state, "track": None})

    try:
        track_index = max(0, int(track_param))
    except (TypeError, ValueError):
        return jsonify({"error": "Некорректный номер главы"}), 400

    track = database.get_transcript_track(conn, path, track_index)
    if track is None:
        return jsonify({"state": state, "track": None})

    return jsonify({
        "state": state,
        "track": {"index": track_index, "name": track['name'], "segments": track['segments']}
    })


@app.route('/api/transcript/request', methods=['POST'])
@login_required
def api_request_transcript():
    """
    Кнопка «запросить текстовый формат» у книги без текста.
    Повторно нажать нельзя: запись одна на человека и книгу.
    """
    data = request.get_json(silent=True) or {}
    path = (data.get('path') or '').strip('/')
    if not album_exists(path):
        return jsonify({"error": "Книга не найдена"}), 404

    conn = get_db()
    if database.get_transcript(conn, path) is not None:
        return jsonify({"error": "Текст для этой книги уже делается"}), 409

    user = current_user()
    added = database.add_text_request(conn, path, user['id'])
    return jsonify({"ok": True, "added": added,
                    "state": transcript_state(conn, path)})


def catalog_exists(path):
    """Есть ли по пути книга или папка с книгами внутри."""
    if album_exists(path):
        return True
    disk = resolve_disk(path)
    return os.path.isdir(disk) and has_music_recursive(disk)


def album_card(path, ready_text=None):
    """Карточка каталога: и книга, и папка с книгами выглядят одинаково."""
    disk = resolve_disk(path)
    default_cover = url_for('static', filename='assets/default_cover.png')
    is_album = os.path.isdir(disk) and has_direct_tracks(disk)
    return {
        "type": "album" if is_album else "directory",
        "name": os.path.basename(path).replace('_', ' ') or path,
        "path": path,
        "cover": find_cover(disk, path) or default_cover,
        "has_text": path in ready_text if ready_text is not None else False,
    }


def ready_text_paths(conn):
    return {row['path'] for row in database.list_transcripts(conn)
            if row['status'] in ('done', 'running')}


@app.route('/api/search')
def api_search():
    """
    Поиск книги по всей медиатеке, а не только в открытой папке:
    иначе пришлось бы помнить, в какой подпапке она лежит.
    """
    query = (request.args.get('q') or '').strip().lower()
    if len(query) < 2:
        return jsonify({"items": [], "query": query})

    conn = get_db()
    ready = ready_text_paths(conn)
    items = []
    for path in all_album_paths():
        if not path:
            continue
        title = os.path.basename(path).replace('_', ' ')
        # Ищем и по названию книги, и по пути: «Ведьмак» найдётся и как папка
        if query in title.lower() or query in path.lower().replace('_', ' '):
            items.append(album_card(path, ready))
        if len(items) >= 200:
            break
    return jsonify({"items": items, "query": query})


# --- API: личные папки читателя ---

@app.route('/api/folders')
@login_required
def api_folders():
    """Папки текущего читателя. Чужие не отдаём никогда."""
    conn = get_db()
    user = current_user()
    ready = ready_text_paths(conn)
    folders = []
    for row in database.list_folders(conn, user['id']):
        paths = database.folder_paths(conn, row['id'])
        folders.append({
            "id": row['id'],
            "name": row['name'],
            "cover": folder_cover_url(row['cover']),
            "created_at": row['created_at'],
            # Книгу могли удалить с диска — такие в подборке не показываем
            "items": [album_card(p, ready) for p in paths if catalog_exists(p)],
            "missing": [p for p in paths if not catalog_exists(p)],
        })
    return jsonify({"folders": folders})


# Картинки личных папок: лежат отдельно от медиатеки, чтобы не путаться
# с обложками книг и не попадать в обход каталога.
FOLDER_COVERS = os.path.join(app.root_path, 'data', 'folder_covers')
COVER_EXTENSIONS = {'.jpg', '.jpeg', '.png', '.webp', '.gif'}
MAX_COVER_BYTES = 4 * 1024 * 1024


def folder_cover_url(filename):
    return url_for('folder_cover_file', filename=filename) if filename else None


@app.route('/folder-cover/<path:filename>')
def folder_cover_file(filename):
    """Отдаёт загруженную картинку папки."""
    safe = os.path.basename(filename)          # никаких переходов по каталогам
    disk = os.path.join(FOLDER_COVERS, safe)
    if not os.path.isfile(disk):
        abort(404)
    return send_file(disk, conditional=True)


def drop_cover_file(filename):
    if not filename:
        return
    try:
        os.remove(os.path.join(FOLDER_COVERS, os.path.basename(filename)))
    except OSError:
        pass


@app.route('/api/folders/<int:folder_id>/cover', methods=['POST', 'DELETE'])
@login_required
def api_folder_cover(folder_id):
    conn = get_db()
    user = current_user()
    if not database.owns_folder(conn, folder_id, user['id']):
        return jsonify({"error": "Папка не найдена"}), 404

    if request.method == 'DELETE':
        previous = database.set_folder_cover(conn, folder_id, user['id'], '')
        drop_cover_file(previous)
        return jsonify({"ok": True, "cover": None})

    upload = request.files.get('image')
    if upload is None or not upload.filename:
        return jsonify({"error": "Файл не выбран"}), 400
    ext = os.path.splitext(upload.filename)[1].lower()
    if ext not in COVER_EXTENSIONS:
        return jsonify({"error": "Подойдёт JPG, PNG, WEBP или GIF"}), 400

    blob = upload.read(MAX_COVER_BYTES + 1)
    if len(blob) > MAX_COVER_BYTES:
        return jsonify({"error": "Картинка тяжелее 4 МБ"}), 400

    os.makedirs(FOLDER_COVERS, exist_ok=True)
    name = f'{folder_id}_{secrets.token_hex(8)}{ext}'
    with open(os.path.join(FOLDER_COVERS, name), 'wb') as f:
        f.write(blob)

    # Прежнюю картинку удаляем только после того, как новая легла на диск
    previous = database.set_folder_cover(conn, folder_id, user['id'], name)
    if previous != name:
        drop_cover_file(previous)
    return jsonify({"ok": True, "cover": folder_cover_url(name)})


@app.route('/api/folders', methods=['POST'])
@login_required
def api_create_folder():
    data = request.get_json(silent=True) or {}
    name = (data.get('name') or '').strip()[:64]
    if not name:
        return jsonify({"error": "Название не может быть пустым"}), 400
    conn = get_db()
    user = current_user()
    if len(database.list_folders(conn, user['id'])) >= 50:
        return jsonify({"error": "Слишком много папок"}), 400
    folder_id = database.create_folder(conn, user['id'], name)
    return jsonify({"ok": True, "id": folder_id})


@app.route('/api/folders/<int:folder_id>', methods=['PATCH'])
@login_required
def api_rename_folder(folder_id):
    data = request.get_json(silent=True) or {}
    name = (data.get('name') or '').strip()[:64]
    conn = get_db()
    user = current_user()
    if not database.owns_folder(conn, folder_id, user['id']):
        return jsonify({"error": "Папка не найдена"}), 404
    if not name:
        return jsonify({"error": "Название не может быть пустым"}), 400
    database.rename_folder(conn, folder_id, user['id'], name)
    return jsonify({"ok": True})


@app.route('/api/folders/<int:folder_id>', methods=['DELETE'])
@login_required
def api_delete_folder(folder_id):
    conn = get_db()
    cover = database.get_folder_cover(conn, folder_id)
    if not database.delete_folder(conn, folder_id, current_user()['id']):
        return jsonify({"error": "Папка не найдена"}), 404
    drop_cover_file(cover)
    return jsonify({"ok": True})


@app.route('/api/folders/<int:folder_id>/items', methods=['POST', 'DELETE'])
@login_required
def api_folder_items(folder_id):
    data = request.get_json(silent=True) or {}
    path = (data.get('path') or '').strip('/')
    conn = get_db()
    user = current_user()
    if not database.owns_folder(conn, folder_id, user['id']):
        return jsonify({"error": "Папка не найдена"}), 404
    if request.method == 'DELETE':
        database.remove_from_folder(conn, folder_id, path)
        return jsonify({"ok": True})
    if not catalog_exists(path):
        return jsonify({"error": "Книга или папка не найдена"}), 404
    database.add_to_folder(conn, folder_id, path)
    return jsonify({"ok": True})


@app.route('/api/folders/for-book')
@login_required
def api_folders_for_book():
    """В каких папках уже лежит книга — для галочек в меню «в папку»."""
    path = (request.args.get('path') or '').strip('/')
    conn = get_db()
    user = current_user()
    return jsonify({
        "folders": [{"id": r['id'], "name": r['name']}
                    for r in database.list_folders(conn, user['id'])],
        "selected": database.folders_with_path(conn, user['id'], path),
    })


MESSAGES_KEY = 'messages_enabled'
MESSAGE_COLORS = ('pink', 'green', 'yellow', 'red')
RARITIES = ('common', 'rare', 'mythic', 'legendary')
MAX_ACTIVE_MESSAGES = 3
# За сколько секунд до конца главы засчитываем прослушивание
ACHIEVEMENT_TAIL_SECONDS = 10


def message_row(row):
    return {"id": row['id'], "text": row['text'], "color": row['color'],
            "enabled": bool(row['enabled']), "created_at": row['created_at']}


@app.route('/api/messages')
def api_messages():
    """Бегущие строки для всех страниц. Пусто, если показ выключен."""
    conn = get_db()
    if not database.get_flag(conn, MESSAGES_KEY, False):
        return jsonify({"messages": []})
    rows = database.list_messages(conn, only_enabled=True)[:MAX_ACTIVE_MESSAGES]
    return jsonify({"messages": [message_row(r) for r in rows]})


def parse_tracks(raw):
    """Строка «0,2,5» -> [0, 2, 5]. Пусто — засчитывается любая глава."""
    out = []
    for piece in (raw or '').split(','):
        piece = piece.strip()
        if piece.isdigit():
            out.append(int(piece))
    return sorted(set(out))


def achievement_row(row, earned_at=None):
    keys = row.keys()
    tracks = parse_tracks(row['target_tracks'] if 'target_tracks' in keys else '')
    if not tracks and row['target_track'] is not None:
        tracks = [int(row['target_track'])]   # записи прежнего формата
    return {
        "id": row['id'],
        "title": row['title'],
        "image": row['image'],
        "description": row['description'],
        "target_path": row['target_path'],
        "target_tracks": tracks,
        "rarity": row['rarity'],
        "earned_at": earned_at if earned_at is not None
                     else (row['earned_at'] if 'earned_at' in keys else None),
    }


def achievement_earned(conn, row, user_id, path):
    """
    Заслужено ли достижение.

    За книгу с отмеченными главами — нужны все отмеченные.
    За книгу без отметок — засчитывается любая её глава.
    За папку — нужны все книги внутри целиком: иначе достижение за раздел
    выдавалось бы после одной книги, что и было ошибкой.
    """
    target = (row['target_path'] or '').strip('/')
    if not target:
        return False

    keys = row.keys()
    needed = parse_tracks(row['target_tracks'] if 'target_tracks' in keys else '')
    if not needed and row['target_track'] is not None:
        needed = [int(row['target_track'])]

    if album_exists(target):
        if path != target:
            return False
        if not needed:
            return True
        done = database.done_tracks_for(conn, user_id, target)
        return all(index in done for index in needed)

    # Цель — папка: слушаемая книга должна лежать внутри неё
    if not path.startswith(target + '/'):
        return False

    albums = albums_under(target)
    if not albums:
        return False
    for album in albums:
        total = len(album_tracks_on_disk(album))
        if not total:
            continue
        done = database.done_tracks_for(conn, user_id, album)
        if len(done) < total:
            return False
    return True


@app.route('/api/achievements')
@login_required
def api_my_achievements():
    """Страница достижений: что уже получено и что ещё можно получить."""
    conn = get_db()
    user = current_user()
    mine = {r['id']: r['earned_at'] for r in database.user_achievement_rows(conn, user['id'])}
    items = []
    for row in database.list_achievements(conn):
        item = achievement_row(row, mine.get(row['id']))
        target = item['target_path']
        item['book'] = os.path.basename(target).replace('_', ' ') if target else 'Без книги'
        item['book_path'] = target
        items.append(item)
    return jsonify({"achievements": items, "earned": len(mine)})


@app.route('/api/achievements/claim', methods=['POST'])
@login_required
def api_claim_achievement():
    """
    Вызывается плеером на последних секундах главы. Сервер сам решает,
    что засчитать: полагаться на присланный список было бы наивно.
    """
    data = request.get_json(silent=True) or {}
    path = (data.get('path') or '').strip('/')
    try:
        track_index = int(data.get('track'))
    except (TypeError, ValueError):
        return jsonify({"error": "Некорректный номер главы"}), 400
    if not album_exists(path):
        return jsonify({"error": "Книга не найдена"}), 404

    conn = get_db()
    user = current_user()
    database.mark_track_done(conn, user['id'], path, track_index)

    granted = []
    for row in database.list_achievements(conn):
        if not achievement_earned(conn, row, user['id'], path):
            continue
        if database.grant_achievement(conn, user['id'], row['id']):
            granted.append(achievement_row(row, database.now_iso()))
    return jsonify({"granted": granted})


def person(row):
    return {"id": row['id'], "login": row['login'],
            "nickname": row['nickname'] or row['login']}


@app.route('/api/friends')
@login_required
def api_friends():
    conn = get_db()
    me = current_user()['id']
    return jsonify({
        "friends": [person(r) for r in database.list_friends(conn, me)],
        "incoming": [person(r) for r in database.list_incoming_requests(conn, me)],
        "outgoing": [person(r) for r in database.list_outgoing_requests(conn, me)],
    })


@app.route('/api/friends/search')
@login_required
def api_friends_search():
    """
    Людей показываем только по запросу: общего списка пользователей
    в приложении нет, и выкладывать его не стоит.
    """
    query = (request.args.get('q') or '').strip()
    if len(query) < 2:
        return jsonify({"results": [], "query": query})
    conn = get_db()
    me = current_user()['id']
    results = []
    for row in database.search_users(conn, query, me):
        item = person(row)
        item["state"] = database.friend_state(conn, me, row['id'])
        results.append(item)
    return jsonify({"results": results, "query": query})


@app.route('/api/friends', methods=['POST'])
@login_required
def api_friends_request():
    data = request.get_json(silent=True) or {}
    conn = get_db()
    me = current_user()['id']
    try:
        other = int(data.get('id'))
    except (TypeError, ValueError):
        return jsonify({"error": "Некорректный запрос"}), 400
    if other == me:
        return jsonify({"error": "Себя добавить нельзя"}), 400
    if database.find_user_by_id(conn, other) is None:
        return jsonify({"error": "Пользователь не найден"}), 404

    action = data.get('action') or 'request'
    if action == 'accept':
        if not database.accept_friend_request(conn, me, other):
            return jsonify({"error": "Заявки нет"}), 404
    elif not database.add_friend_request(conn, me, other):
        return jsonify({"error": "Заявка уже отправлена"}), 400
    return jsonify({"ok": True, "state": database.friend_state(conn, me, other)})


@app.route('/api/friends', methods=['DELETE'])
@login_required
def api_friends_drop():
    """Одной кнопкой: отменить свою заявку, отклонить чужую, удалить друга."""
    data = request.get_json(silent=True) or {}
    try:
        other = int(data.get('id'))
    except (TypeError, ValueError):
        return jsonify({"error": "Некорректный запрос"}), 400
    database.drop_friendship(get_db(), current_user()['id'], other)
    return jsonify({"ok": True})


@app.route('/api/friends/<int:friend_id>/achievements')
@login_required
def api_compare_achievements(friend_id):
    """Сравнение достижений: моё против друга, книгами."""
    conn = get_db()
    me = current_user()
    if database.friend_state(conn, me['id'], friend_id) != 'friends':
        return jsonify({"error": "Это не ваш друг"}), 403
    other = database.find_user_by_id(conn, friend_id)
    if other is None:
        return jsonify({"error": "Пользователь не найден"}), 404

    mine = {r['id']: r['earned_at'] for r in database.user_achievement_rows(conn, me['id'])}
    theirs = {r['id']: r['earned_at']
              for r in database.user_achievement_rows(conn, friend_id)}

    items = []
    for row in database.list_achievements(conn):
        item = achievement_row(row, mine.get(row['id']))
        target = item['target_path']
        item['book'] = os.path.basename(target).replace('_', ' ') if target else 'Без книги'
        item['book_path'] = target
        item['mine'] = mine.get(row['id'])
        item['theirs'] = theirs.get(row['id'])
        items.append(item)

    return jsonify({
        "me": person(me),
        "friend": person(other),
        "mine_total": len(mine),
        "their_total": len(theirs),
        "achievements": items,
    })


# Праздничные оформления. Ключ в meta -> класс на странице.
THEMES = (
    {"id": "newyear", "name": "Новый год", "hint": "холодный свет, искры и иней на обложках"},
    {"id": "halloween", "name": "Хэллоуин", "hint": "помехи и наблюдатель за краем экрана"},
    {"id": "valentine", "name": "День всех влюблённых", "hint": "розовое свечение в такт пульсу"},
    {"id": "spring", "name": "Весенний праздник", "hint": "тёплый свет сверху и пыльца"},
)
THEME_IDS = tuple(t["id"] for t in THEMES)


def active_themes(conn):
    return [t["id"] for t in THEMES
            if database.get_flag(conn, f'theme_{t["id"]}', False)]


APP_FOLDER = os.path.join(app.root_path, 'application')


def built_apk():
    """Свежий собранный APK, если он есть."""
    if not os.path.isdir(APP_FOLDER):
        return None
    files = [f for f in os.listdir(APP_FOLDER) if f.lower().endswith('.apk')]
    if not files:
        return None
    files.sort(key=lambda name: os.path.getmtime(os.path.join(APP_FOLDER, name)))
    return os.path.join(APP_FOLDER, files[-1])


@app.route('/api/app')
def api_app_info():
    """Есть ли собранное приложение — по этому кнопка решает, показываться ли."""
    apk = built_apk()
    if not apk:
        return jsonify({"ready": False})
    return jsonify({"ready": True,
                    "size": os.path.getsize(apk),
                    "name": os.path.basename(apk)})


@app.route('/download/app')
def download_app():
    apk = built_apk()
    if not apk:
        return jsonify({"error": "Приложение ещё не собрано"}), 404
    return send_file(apk, as_attachment=True,
                     download_name='CyberAudioHub.apk',
                     mimetype='application/vnd.android.package-archive')


INTENSITY_KEY = 'theme_intensity'
SCREAMER_KEY = 'halloween_video'


def theme_settings(conn):
    raw = database.get_setting(conn, INTENSITY_KEY, '1')
    try:
        intensity = max(0.0, min(3.0, float(raw)))
    except (TypeError, ValueError):
        intensity = 1.0
    return {
        "intensity": intensity,
        "video": database.get_setting(conn, SCREAMER_KEY, ''),
    }


@app.route('/api/theme')
def api_theme():
    """Какие оформления включены. Спрашивается на каждой странице."""
    conn = get_db()
    settings = theme_settings(conn)
    return jsonify(dict(settings, themes=active_themes(conn)))


# --- API: администрирование ---

def library_tree(conn):
    """
    Медиатека деревом: папки с вложенностью, книги — листья со статусом текста.
    Нужно админке, чтобы включать текст на любом уровне, а не по книге за раз.
    """
    states = {}
    for row in database.list_transcripts(conn):
        states[row['path']] = transcript_state(conn, row['path'])

    root = {"name": "Медиатека", "path": "", "type": "directory", "children": []}
    index = {"": root}

    for path in all_album_paths():
        if not path:
            continue
        # Создаём недостающие узлы-папки по дороге к книге
        parts = path.split('/')
        parent = root
        for depth in range(len(parts) - 1):
            branch = '/'.join(parts[:depth + 1])
            node = index.get(branch)
            if node is None:
                node = {"name": parts[depth].replace('_', ' '), "path": branch,
                        "type": "directory", "children": []}
                index[branch] = node
                parent["children"].append(node)
            parent = node

        state = states.get(path) or {"status": "none", "done_tracks": 0,
                                     "total_tracks": 0, "engine": ""}
        leaf = {
            "name": parts[-1].replace('_', ' '),
            "path": path,
            "type": "album",
            "status": state.get('status', 'none'),
            "done_tracks": state.get('done_tracks', 0),
            "total_tracks": state.get('total_tracks', 0),
            "engine": state.get('engine', ''),
        }
        index[path] = leaf
        parent["children"].append(leaf)

    return root["children"]


def albums_under(path):
    """Все книги внутри ветки. Для пустого пути — вся медиатека."""
    prefix = (path or '').strip('/')
    result = []
    for album in all_album_paths():
        if not album:
            continue
        if not prefix or album == prefix or album.startswith(prefix + '/'):
            result.append(album)
    return result



def text_requests_payload(conn):
    """Просьбы, сгруппированные по книгам: кто просил и когда."""
    grouped = {}
    for row in database.list_text_requests(conn):
        book = grouped.setdefault(row['path'], {
            "path": row['path'],
            "title": os.path.basename(row['path']).replace('_', ' '),
            "users": [],
            "first_at": row['created_at'],
            "exists": album_exists(row['path']),
        })
        book["users"].append({
            "id": row['user_id'],
            "login": row['login'],
            "nickname": row['nickname'] or row['login'],
            "created_at": row['created_at'],
        })
    return sorted(grouped.values(), key=lambda b: (-len(b['users']), b['title']))


def admin_user_row(row):
    return {
        "id": row['id'],
        "login": row['login'],
        "nickname": row['nickname'],
        "is_admin": bool(row['is_admin']),
        "created_at": row['created_at'],
        "books": row['books'] if 'books' in row.keys() else 0,
        # Пароли хранятся только в виде хеша и в открытом виде недоступны
        # даже администратору — показываем лишь алгоритм.
        "password_algo": (row['password_hash'] or '').split('$')[0] or '—',
    }


@app.route('/api/admin/overview')
@admin_required
def api_admin_overview():
    """Всё содержимое базы для админской страницы."""
    conn = get_db()
    cache = database.get_all_album_durations(conn)

    progress = []
    for row in database.list_all_progress(conn):
        item = progress_payload(row, cache)
        item.update({"user_id": row['user_id'], "login": row['login'], "nickname": row['nickname']})
        progress.append(item)

    albums = [{
        "path": row['path'],
        "track_count": row['track_count'],
        "total_duration": row['total_duration'],
        "updated_at": row['updated_at'],
        "exists": album_exists(row['path']),
    } for row in database.list_albums(conn)]

    # Подсказка на странице: пароль администратора всё ещё тот, что лежит в исходниках
    admin_row = database.find_user_by_login(conn, ADMIN_LOGIN)
    default_password = bool(admin_row and check_password_hash(admin_row['password_hash'], ADMIN_PASSWORD))

    # Все книги медиатеки с состоянием текстовой версии
    books = []
    for book_path in all_album_paths():
        state = transcript_state(conn, book_path)
        state['path'] = book_path
        state['title'] = os.path.basename(book_path).replace('_', ' ')
        books.append(state)

    return jsonify({
        "books": books,
        "queue": queue_snapshot(conn),
        "worker_token": WORKER_TOKEN,
        "worker_port": APP_PORT,
        "users": [admin_user_row(r) for r in database.list_users(conn)],
        "progress": progress,
        "albums": albums,
        "requests": text_requests_payload(conn),
        "tree": library_tree(conn),
        "roots": roots_payload(conn),
        "downloads": database.get_flag(conn, DOWNLOADS_KEY, False),
        "current_user_id": current_user()['id'],
        "default_password": default_password,
        "stats": {
            "users": len(database.list_users(conn)),
            "progress": len(progress),
            "albums": len(albums),
            "admins": database.count_admins(conn),
            "requests": database.count_text_requests(conn),
        }
    })


def roots_payload(conn):
    """Список папок для админки: с пометкой, доступна ли папка сейчас."""
    def count_books(root, prefix):
        found = 0
        if os.path.isdir(root):
            for current, _dirs, files in os.walk(root):
                if any(is_audio(f) for f in files):
                    found += 1
        return found

    main = database.get_main_root(conn, MUSIC_FOLDER_ROOT)
    out = [{
        "id": None, "name": "Основная", "path": main, "main": True,
        "exists": os.path.isdir(main), "books": count_books(main, ''),
    }]
    for row in database.list_roots(conn):
        out.append({"id": row['id'], "name": row['name'], "path": row['path'],
                    "main": False, "exists": os.path.isdir(row['path']),
                    "books": count_books(row['path'], row['name'])})
    return out


def check_root_path(path):
    """Проверяет путь, который админ вписал руками."""
    path = (path or '').strip().strip('"')
    if not path:
        return None, "Укажите путь к папке"
    if not os.path.isabs(path):
        return None, "Нужен полный путь, например D:\\Книги или /mnt/books"
    if not os.path.isdir(path):
        return None, "Такой папки нет или она недоступна"
    return os.path.abspath(path), None


@app.route('/api/admin/settings', methods=['PUT'])
@admin_required
def api_admin_settings():
    data = request.get_json(silent=True) or {}
    conn = get_db()
    if 'downloads' in data:
        database.set_flag(conn, DOWNLOADS_KEY, bool(data['downloads']))
    return jsonify({"ok": True, "downloads": database.get_flag(conn, DOWNLOADS_KEY, False)})


@app.route('/api/admin/themes', methods=['GET'])
@admin_required
def api_admin_themes():
    conn = get_db()
    return jsonify(dict(theme_settings(conn), themes=[
        dict(theme, enabled=database.get_flag(conn, f'theme_{theme["id"]}', False))
        for theme in THEMES]))


@app.route('/api/admin/theme-settings', methods=['PUT'])
@admin_required
def api_admin_theme_settings():
    """Плотность летящих элементов и ролик для монстра на Хэллоуин."""
    data = request.get_json(silent=True) or {}
    conn = get_db()
    if 'intensity' in data:
        try:
            value = max(0.0, min(3.0, float(data['intensity'])))
        except (TypeError, ValueError):
            return jsonify({"error": "Некорректная плотность"}), 400
        database.set_setting(conn, INTENSITY_KEY, str(value))
    if 'video' in data:
        database.set_setting(conn, SCREAMER_KEY, (data.get('video') or '').strip()[:300])
    return jsonify(dict({"ok": True}, **theme_settings(conn)))


@app.route('/api/admin/themes', methods=['PUT'])
@admin_required
def api_admin_theme_set():
    data = request.get_json(silent=True) or {}
    theme_id = data.get('id')
    if theme_id not in THEME_IDS:
        return jsonify({"error": "Неизвестное оформление"}), 400
    conn = get_db()
    database.set_flag(conn, f'theme_{theme_id}', bool(data.get('enabled')))
    return jsonify({"ok": True, "active": active_themes(conn)})


@app.route('/api/admin/messages', methods=['GET'])
@admin_required
def api_admin_messages():
    conn = get_db()
    return jsonify({
        "enabled": database.get_flag(conn, MESSAGES_KEY, False),
        "messages": [message_row(r) for r in database.list_messages(conn)],
        "limit": MAX_ACTIVE_MESSAGES,
    })


@app.route('/api/admin/messages', methods=['PUT'])
@admin_required
def api_admin_messages_flag():
    data = request.get_json(silent=True) or {}
    conn = get_db()
    database.set_flag(conn, MESSAGES_KEY, bool(data.get('enabled')))
    return jsonify({"ok": True, "enabled": database.get_flag(conn, MESSAGES_KEY, False)})


@app.route('/api/admin/messages', methods=['POST', 'PATCH', 'DELETE'])
@admin_required
def api_admin_message_edit():
    data = request.get_json(silent=True) or {}
    conn = get_db()

    if request.method == 'DELETE':
        database.delete_message(conn, int(data.get('id') or 0))
        return jsonify({"ok": True, "messages": [message_row(r)
                                                 for r in database.list_messages(conn)]})

    text = (data.get('text') or '').strip()[:300]
    color = data.get('color') if data.get('color') in MESSAGE_COLORS else 'pink'
    if not text:
        return jsonify({"error": "Текст сообщения пуст"}), 400

    if request.method == 'POST':
        if database.count_enabled_messages(conn) >= MAX_ACTIVE_MESSAGES:
            return jsonify({"error": f"Одновременно показываем не больше "
                                     f"{MAX_ACTIVE_MESSAGES} сообщений. "
                                     "Выключите лишние"}), 400
        database.create_message(conn, text, color)
    else:
        message_id = int(data.get('id') or 0)
        enabled = bool(data.get('enabled'))
        if enabled and database.count_enabled_messages(conn, message_id) >= MAX_ACTIVE_MESSAGES:
            return jsonify({"error": f"Уже показывается {MAX_ACTIVE_MESSAGES} сообщения"}), 400
        database.update_message(conn, message_id, text, color, enabled)

    return jsonify({"ok": True, "messages": [message_row(r)
                                             for r in database.list_messages(conn)]})


@app.route('/api/admin/achievements', methods=['GET'])
@admin_required
def api_admin_achievements():
    conn = get_db()
    # Список целей: книги и папки, чтобы админ выбирал из готового
    targets = []
    folders = set()
    for album in all_album_paths():
        if not album:
            continue
        targets.append({"path": album, "type": "album",
                        "name": os.path.basename(album).replace('_', ' '),
                        "tracks": [t[0] for t in album_tracks_on_disk(album)]})
        # Все папки по пути к книге: достижение можно выдавать за раздел целиком
        parts = album.split('/')
        for depth in range(len(parts) - 1):
            folders.add('/'.join(parts[:depth + 1]))
    for folder in sorted(folders):
        targets.append({"path": folder, "type": "directory",
                        "name": os.path.basename(folder).replace('_', ' '),
                        "tracks": []})
    return jsonify({
        "achievements": [achievement_row(r) for r in database.list_achievements(conn)],
        "targets": targets,
        "rarities": list(RARITIES),
    })


@app.route('/api/admin/achievements', methods=['POST', 'PATCH', 'DELETE'])
@admin_required
def api_admin_achievement_edit():
    data = request.get_json(silent=True) or {}
    conn = get_db()

    if request.method == 'DELETE':
        database.delete_achievement(conn, int(data.get('id') or 0))
        return jsonify({"ok": True})

    title = (data.get('title') or '').strip()[:120]
    if not title:
        return jsonify({"error": "Укажите название достижения"}), 400
    image = (data.get('image') or '').strip()[:300]
    description = (data.get('description') or '').strip()[:600]
    target_path = (data.get('target_path') or '').strip('/')
    rarity = data.get('rarity') if data.get('rarity') in RARITIES else 'common'

    # Глав может быть несколько: «дослушать 1 и 2» — одно достижение
    raw = data.get('target_tracks')
    if isinstance(raw, list):
        chosen = [str(x) for x in raw if str(x).isdigit()]
    else:
        chosen = [x for x in str(raw or '').split(',') if x.strip().isdigit()]
    tracks = ','.join(sorted(set(chosen), key=int))

    if request.method == 'POST':
        database.create_achievement(conn, title, image, description,
                                    target_path, tracks, rarity)
    else:
        database.update_achievement(conn, int(data.get('id') or 0), title, image,
                                    description, target_path, tracks, rarity)
    return jsonify({"ok": True})


@app.route('/api/admin/user-achievements', methods=['GET'])
@admin_required
def api_admin_user_achievements():
    """Кто что получил — сгруппировано по людям и книгам."""
    conn = get_db()
    people = []
    for user in database.list_users(conn):
        rows = database.user_achievement_rows(conn, user['id'])
        if not rows:
            continue
        books = {}
        for row in rows:
            item = achievement_row(row, row['earned_at'])
            target = item['target_path']
            book = os.path.basename(target).replace('_', ' ') if target else 'Без книги'
            books.setdefault(book, []).append(item)
        people.append({
            "id": user['id'],
            "login": user['login'],
            "nickname": user['nickname'] or user['login'],
            "total": len(rows),
            "books": [{"book": name, "items": items} for name, items in sorted(books.items())],
        })
    return jsonify({"users": people})


@app.route('/api/admin/user-achievements', methods=['DELETE'])
@admin_required
def api_admin_revoke_achievement():
    """Снять достижение у человека."""
    data = request.get_json(silent=True) or {}
    try:
        user_id = int(data.get('user_id'))
        achievement_id = int(data.get('achievement_id'))
    except (TypeError, ValueError):
        return jsonify({"error": "Некорректный запрос"}), 400
    conn = get_db()
    conn.execute("DELETE FROM user_achievements WHERE user_id = ? AND achievement_id = ?",
                 (user_id, achievement_id))
    conn.commit()
    return jsonify({"ok": True})


@app.route('/api/admin/roots', methods=['GET'])
@admin_required
def api_admin_roots():
    return jsonify({"roots": roots_payload(get_db())})


@app.route('/api/admin/roots', methods=['POST'])
@admin_required
def api_admin_add_root():
    data = request.get_json(silent=True) or {}
    name = (data.get('name') or '').strip()[:48]
    path, error = check_root_path(data.get('path'))
    if error:
        return jsonify({"error": error}), 400
    if not name:
        return jsonify({"error": "Укажите название папки"}), 400
    if '/' in name or '\\' in name:
        return jsonify({"error": "В названии не должно быть косых черт"}), 400

    conn = get_db()
    known = [database.get_main_root(conn, MUSIC_FOLDER_ROOT)] + \
            [r['path'] for r in database.list_roots(conn)]
    for other in known:
        try:
            common = os.path.commonpath([os.path.abspath(other), path])
        except ValueError:
            continue   # разные диски — пересечения быть не может
        if common in (os.path.abspath(other), path):
            return jsonify({"error": "Эта папка уже входит в медиатеку "
                                     f"(через «{other}»). Книги в ней задвоились бы"}), 400

    # Имя становится первым сегментом пути, поэтому оно не должно совпадать
    # с папкой внутри основной медиатеки — иначе они перекроют друг друга.
    main = database.get_main_root(conn, MUSIC_FOLDER_ROOT)
    if os.path.isdir(os.path.join(main, name)):
        return jsonify({"error": f"В основной папке уже есть раздел «{name}». "
                                 "Выберите другое название"}), 400
    if database.add_root(conn, name, path) is None:
        return jsonify({"error": "Папка с таким названием уже добавлена"}), 400
    return jsonify({"ok": True, "roots": roots_payload(conn)})


@app.route('/api/admin/roots', methods=['PATCH'])
@admin_required
def api_admin_update_root():
    """Меняет путь основной папки или название и путь дополнительной."""
    data = request.get_json(silent=True) or {}
    path, error = check_root_path(data.get('path'))
    if error:
        return jsonify({"error": error}), 400

    conn = get_db()
    root_id = data.get('id')
    if root_id is None:
        database.set_main_root(conn, path)
        return jsonify({"ok": True, "roots": roots_payload(conn)})

    name = (data.get('name') or '').strip()[:48]
    if not name or '/' in name or '\\' in name:
        return jsonify({"error": "Некорректное название"}), 400
    if not database.update_root(conn, int(root_id), name, path):
        return jsonify({"error": "Папка с таким названием уже есть"}), 400
    return jsonify({"ok": True, "roots": roots_payload(conn)})


@app.route('/api/admin/roots', methods=['DELETE'])
@admin_required
def api_admin_delete_root():
    data = request.get_json(silent=True) or {}
    root_id = data.get('id')
    if root_id is None:
        return jsonify({"error": "Основную папку убрать нельзя"}), 400
    conn = get_db()
    database.delete_root(conn, int(root_id))
    return jsonify({"ok": True, "roots": roots_payload(conn)})


@app.route('/api/admin/requests', methods=['GET'])
@admin_required
def api_admin_requests():
    return jsonify({"requests": text_requests_payload(get_db())})


@app.route('/api/admin/requests', methods=['DELETE'])
@admin_required
def api_admin_delete_request():
    """Убрать просьбу: целиком по книге или от одного человека."""
    data = request.get_json(silent=True) or {}
    path = (data.get('path') or '').strip('/')
    user_id = data.get('user_id')
    if not path:
        return jsonify({"error": "Не указана книга"}), 400
    conn = get_db()
    database.delete_text_requests(conn, path,
                                  int(user_id) if user_id is not None else None)
    return jsonify({"ok": True, "requests": text_requests_payload(conn)})


@app.route('/api/admin/users', methods=['POST'])
@admin_required
def api_admin_create_user():
    data = request.get_json(silent=True) or {}
    login = (data.get('login') or '').strip()
    password = data.get('password') or ''
    nickname = (data.get('nickname') or '').strip() or login

    if not LOGIN_RE.match(login):
        return jsonify({"error": "Логин: 3–32 символа, латиница, цифры, точка, дефис или подчёркивание"}), 400
    if len(password) < MIN_PASSWORD_LENGTH:
        return jsonify({"error": f"Пароль должен быть не короче {MIN_PASSWORD_LENGTH} символов"}), 400
    if len(nickname) > MAX_NICKNAME_LENGTH:
        return jsonify({"error": f"Никнейм не длиннее {MAX_NICKNAME_LENGTH} символов"}), 400

    conn = get_db()
    if database.find_user_by_login(conn, login):
        return jsonify({"error": "Такой логин уже занят"}), 409

    user_id = database.create_user(conn, login, nickname, generate_password_hash(password),
                                   is_admin=1 if data.get('is_admin') else 0)
    return jsonify({"id": user_id}), 201


@app.route('/api/admin/users/<int:user_id>', methods=['PATCH'])
@admin_required
def api_admin_update_user(user_id):
    data = request.get_json(silent=True) or {}
    conn = get_db()
    target = database.find_user_by_id(conn, user_id)
    if target is None:
        return jsonify({"error": "Пользователь не найден"}), 404

    updates = {}

    if 'login' in data:
        login = (data.get('login') or '').strip()
        if not LOGIN_RE.match(login):
            return jsonify({"error": "Логин: 3–32 символа, латиница, цифры, точка, дефис или подчёркивание"}), 400
        clash = database.find_user_by_login(conn, login)
        if clash and clash['id'] != user_id:
            return jsonify({"error": "Такой логин уже занят"}), 409
        updates['login'] = login

    if 'nickname' in data:
        nickname = (data.get('nickname') or '').strip() or updates.get('login', target['login'])
        if len(nickname) > MAX_NICKNAME_LENGTH:
            return jsonify({"error": f"Никнейм не длиннее {MAX_NICKNAME_LENGTH} символов"}), 400
        updates['nickname'] = nickname

    if data.get('new_password'):
        if len(data['new_password']) < MIN_PASSWORD_LENGTH:
            return jsonify({"error": f"Пароль должен быть не короче {MIN_PASSWORD_LENGTH} символов"}), 400
        updates['password_hash'] = generate_password_hash(data['new_password'])

    if 'is_admin' in data:
        is_admin = 1 if data.get('is_admin') else 0
        # Нельзя снять с себя права и нельзя убрать последнего администратора —
        # иначе в панель больше никто не войдёт
        if not is_admin and target['is_admin']:
            if target['id'] == current_user()['id']:
                return jsonify({"error": "Нельзя снять права администратора с самого себя"}), 400
            if database.count_admins(conn) <= 1:
                return jsonify({"error": "Это последний администратор — права снять нельзя"}), 400
        updates['is_admin'] = is_admin

    if not updates:
        return jsonify({"error": "Нечего обновлять"}), 400

    database.update_user(conn, user_id, **updates)
    return jsonify({"ok": True})


@app.route('/api/admin/users/<int:user_id>', methods=['DELETE'])
@admin_required
def api_admin_delete_user(user_id):
    conn = get_db()
    target = database.find_user_by_id(conn, user_id)
    if target is None:
        return jsonify({"error": "Пользователь не найден"}), 404
    if target['id'] == current_user()['id']:
        return jsonify({"error": "Нельзя удалить учётную запись, под которой вы вошли"}), 400
    if target['is_admin'] and database.count_admins(conn) <= 1:
        return jsonify({"error": "Это последний администратор — удалить нельзя"}), 400

    database.delete_user(conn, user_id)
    return jsonify({"ok": True})


@app.route('/api/admin/progress', methods=['DELETE'])
@admin_required
def api_admin_delete_progress():
    data = request.get_json(silent=True) or {}
    try:
        user_id = int(data.get('user_id'))
    except (TypeError, ValueError):
        return jsonify({"error": "Не указан пользователь"}), 400
    path = (data.get('path') or '').strip('/')
    if not path:
        return jsonify({"error": "Не указан путь"}), 400
    database.delete_progress(get_db(), user_id, path)
    return jsonify({"ok": True})


@app.route('/api/admin/albums', methods=['DELETE'])
@admin_required
def api_admin_delete_album():
    data = request.get_json(silent=True) or {}
    path = (data.get('path') or '').strip('/')
    if not path:
        return jsonify({"error": "Не указан путь"}), 400
    database.delete_album(get_db(), path)
    return jsonify({"ok": True})



@app.route('/api/admin/transcripts', methods=['PUT'])
@admin_required
def api_admin_toggle_transcript():
    """Галочка «создать текст» в админке: ставит книгу в очередь или снимает."""
    data = request.get_json(silent=True) or {}
    path = (data.get('path') or '').strip('/')
    enabled = bool(data.get('enabled'))

    # Путь может указывать и на книгу, и на целую папку: переключатель есть
    # на каждом уровне дерева, чтобы не протыкивать книги по одной.
    targets = [path] if album_exists(path) else albums_under(path)
    if not targets:
        return jsonify({"error": "Книга не найдена"}), 404

    conn = get_db()
    for target in targets:
        if not enabled:
            database.release_book_claims(conn, target)
            database.delete_transcript(conn, target)
            # Текст удаляем и с диска, иначе при повторном включении галочки
            # книга «мгновенно распозналась» бы из старых файлов.
            transcribe.remove_track_files(album_tracks_on_disk(target))
            continue

        if database.get_transcript(conn, target) is not None:
            continue  # уже в работе — второй раз в очередь не ставим

        database.create_transcript(conn, target, len(album_tracks_on_disk(target)))
        # Просьбы по этой книге исполнены — снимаем их
        database.delete_text_requests(conn, target)
        _adopted, language = adopt_existing_text(conn, target)
        if language:
            database.update_transcript(conn, target, language=language)
        refresh_transcript_status(conn, target)

    return jsonify({"ok": True, "affected": len(targets),
                    "tree": library_tree(conn)})


@app.route('/api/admin/transcripts')
@admin_required
def api_admin_transcripts():
    """Статусы для опроса из админки, пока идёт распознавание."""
    conn = get_db()
    items = []
    for row in database.list_transcripts(conn):
        state = transcript_state(conn, row['path'])
        state['path'] = row['path']
        items.append(state)
    return jsonify({"items": items, "queue": queue_snapshot(conn),
                    "tree": library_tree(conn),
                    "requests": text_requests_payload(conn)})




@app.route('/api/admin/textbooks')
@admin_required
def api_admin_textbooks():
    """Текстовые книги и очередь озвучки — для опроса из админки."""
    conn = get_db()
    return jsonify({"books": text_books_payload(conn),
                    "queue": voice_queue_snapshot(conn)})


@app.route('/api/admin/voiceovers', methods=['PUT'])
@admin_required
def api_admin_toggle_voiceover():
    """Галочка «создать аудио»: ставит книгу в очередь на озвучку или снимает."""
    data = request.get_json(silent=True) or {}
    path = (data.get('path') or '').strip('/')
    enabled = bool(data.get('enabled'))

    docs = book_documents_on_disk(path)
    if not docs:
        return jsonify({"error": "В этой папке нет документа для озвучки"}), 404

    conn = get_db()
    if not enabled:
        database.release_book_claim(conn, path)
        removed = remove_generated_audio(conn, path)
        database.delete_voiceover(conn, path)
        # Расшифровку тоже снимаем: она была сгенерирована вместе с аудио
        # и без него превратилась бы в текст неизвестно к чему.
        database.delete_transcript(conn, path)
        return jsonify({"ok": True, "removed": removed,
                        "books": text_books_payload(conn),
                        "queue": voice_queue_snapshot(conn)})

    if database.get_voiceover(conn, path) is None and album_exists(path):
        # Свои файлы озвучка кладёт в ту же папку и нумерует с единицы.
        # Если там уже лежит чужое аудио, порядок глав перемешается.
        return jsonify({
            "error": "В папке уже есть аудио. Уберите его или создайте "
                     "отдельную папку для озвучки."}), 409

    wanted = (data.get('document') or '').strip()
    names = [n for n, _disk, _size in docs]
    document = wanted if wanted in names else names[0]

    if database.get_voiceover(conn, path) is None:
        database.create_voiceover(conn, path, document)
    else:
        database.update_voiceover(conn, path, document=document)
    refresh_voiceover_status(conn, path)

    return jsonify({"ok": True,
                    "books": text_books_payload(conn),
                    "queue": voice_queue_snapshot(conn)})


# --- API: внешний распознаватель ---
#
# Эти маршруты вызывает программа из папки worker/, запущенная на другом
# компьютере. Доступ — по общему токену, пароль пользователя ей не нужен.

@app.route('/api/worker/queue')
@worker_required
def api_worker_queue():
    """Что осталось распознать. Воркер опрашивает этот адрес по кругу."""
    conn = get_db()
    books = [b for b in queue_snapshot(conn) if b['pending']]
    return jsonify({"books": books, "claim_timeout_minutes": CLAIM_TIMEOUT_MINUTES})


@app.route('/api/worker/claim', methods=['POST'])
@worker_required
def api_worker_claim():
    """
    Бронирует главу за воркером, чтобы двое не делали одну и ту же работу.
    409 — главу уже взял кто-то другой, воркеру следует перейти к следующей.
    """
    data = request.get_json(silent=True) or {}
    path = (data.get('path') or '').strip('/')
    worker = (data.get('worker') or 'worker')[:64]
    try:
        index = int(data.get('track'))
    except (TypeError, ValueError):
        return jsonify({"error": "Некорректный номер главы"}), 400

    if not album_exists(path):
        return jsonify({"error": "Книга не найдена"}), 404

    conn = get_db()
    if database.get_transcript(conn, path) is None:
        return jsonify({"error": "Книга не в очереди"}), 404
    if database.get_transcript_track(conn, path, index) is not None:
        return jsonify({"error": "Глава уже распознана"}), 409
    if not database.claim_track(conn, path, index, worker, stale_before()):
        return jsonify({"error": "Главу уже взял другой распознаватель"}), 409

    database.update_transcript(conn, path, status='running')
    return jsonify({"ok": True})


@app.route('/api/worker/heartbeat', methods=['POST'])
@worker_required
def api_worker_heartbeat():
    """Продлевает бронь: длинная глава распознаётся дольше таймаута."""
    data = request.get_json(silent=True) or {}
    path = (data.get('path') or '').strip('/')
    worker = (data.get('worker') or 'worker')[:64]
    try:
        index = int(data.get('track'))
    except (TypeError, ValueError):
        return jsonify({"error": "Некорректный номер главы"}), 400
    database.touch_claim(get_db(), path, index, worker)
    return jsonify({"ok": True})


@app.route('/api/worker/audio')
@worker_required
def api_worker_audio():
    """Отдаёт аудиофайл главы, чтобы воркер мог его скачать."""
    path = (request.args.get('path') or '').strip('/')
    try:
        index = int(request.args.get('track'))
    except (TypeError, ValueError):
        return jsonify({"error": "Некорректный номер главы"}), 400

    tracks = album_tracks_on_disk(path)
    if not 0 <= index < len(tracks):
        return jsonify({"error": "Глава не найдена"}), 404
    name, disk_path = tracks[index]
    return send_file(disk_path, as_attachment=True, download_name=name)


@app.route('/api/worker/result', methods=['POST'])
@worker_required
def api_worker_result():
    """Принимает готовый текст главы и раскладывает его по базе и файлам."""
    data = request.get_json(silent=True) or {}
    path = (data.get('path') or '').strip('/')
    segments = data.get('segments')
    try:
        index = int(data.get('track'))
    except (TypeError, ValueError):
        return jsonify({"error": "Некорректный номер главы"}), 400
    if not isinstance(segments, list):
        return jsonify({"error": "Ожидался список сегментов"}), 400

    tracks = album_tracks_on_disk(path)
    if not 0 <= index < len(tracks):
        return jsonify({"error": "Глава не найдена"}), 404

    name, disk_path = tracks[index]
    language = (data.get('language') or '')[:16]
    engine = (data.get('engine') or '')[:64]

    conn = get_db()
    database.save_transcript_track(conn, path, index, name, segments)
    transcribe.write_track_files(disk_path, name, segments, language, engine)
    database.release_claim(conn, path, index)
    database.update_transcript(conn, path, engine=engine, language=language, error='')
    refresh_transcript_status(conn, path)
    return jsonify({"ok": True, "state": transcript_state(conn, path)})


@app.route('/api/worker/error', methods=['POST'])
@worker_required
def api_worker_error():
    """Воркер сообщает, что глава не поддалась: снимаем бронь и пишем причину."""
    data = request.get_json(silent=True) or {}
    path = (data.get('path') or '').strip('/')
    message = (data.get('error') or 'Ошибка распознавания')[:500]
    try:
        index = int(data.get('track'))
    except (TypeError, ValueError):
        index = None

    conn = get_db()
    if index is not None:
        database.release_claim(conn, path, index)
    database.update_transcript(conn, path, error=message)
    refresh_transcript_status(conn, path)
    return jsonify({"ok": True})


# --- API: озвучка книг ---
#
# Вторая половина работы воркера. Сервер отдаёт документ, принимает готовые
# главы и складывает их в папку книги — после этого книга появляется
# в медиатеке сама, как любая другая папка с аудио.

@app.route('/api/worker/voice/queue')
@worker_required
def api_worker_voice_queue():
    """Что осталось озвучить. Воркер опрашивает этот адрес по кругу."""
    conn = get_db()
    return jsonify({"books": voice_queue_snapshot(conn),
                    "claim_timeout_minutes": CLAIM_TIMEOUT_MINUTES})


@app.route('/api/worker/voice/claim', methods=['POST'])
@worker_required
def api_worker_voice_claim():
    """Бронирует книгу целиком. 409 — её уже взял другой воркер."""
    data = request.get_json(silent=True) or {}
    path = (data.get('path') or '').strip('/')
    worker = (data.get('worker') or 'worker')[:64]

    conn = get_db()
    if database.get_voiceover(conn, path) is None:
        return jsonify({"error": "Книга не в очереди на озвучку"}), 404
    if not book_documents_on_disk(path):
        return jsonify({"error": "Документ не найден"}), 404
    if not database.claim_book(conn, path, worker, stale_before()):
        return jsonify({"error": "Книгу уже взял другой воркер"}), 409

    database.update_voiceover(conn, path, status='running', error='')
    return jsonify({"ok": True})


@app.route('/api/worker/voice/heartbeat', methods=['POST'])
@worker_required
def api_worker_voice_heartbeat():
    """Продлевает бронь: озвучка книги идёт часами, а не минутами."""
    data = request.get_json(silent=True) or {}
    path = (data.get('path') or '').strip('/')
    worker = (data.get('worker') or 'worker')[:64]
    database.touch_book_claim(get_db(), path, worker)
    return jsonify({"ok": True})


@app.route('/api/worker/voice/document')
@worker_required
def api_worker_voice_document():
    """Отдаёт сам документ, чтобы воркер достал из него текст."""
    path = (request.args.get('path') or '').strip('/')
    found = voiceover_document(get_db(), path)
    if not found:
        return jsonify({"error": "Документ не найден"}), 404
    name, disk = found
    return send_file(disk, as_attachment=True, download_name=name)


@app.route('/api/worker/voice/plan', methods=['POST'])
@worker_required
def api_worker_voice_plan():
    """Воркер разобрал документ и сообщает, сколько получилось глав."""
    data = request.get_json(silent=True) or {}
    path = (data.get('path') or '').strip('/')
    try:
        total = max(0, int(data.get('total')))
    except (TypeError, ValueError):
        return jsonify({"error": "Некорректное число глав"}), 400

    conn = get_db()
    if database.get_voiceover(conn, path) is None:
        return jsonify({"error": "Книга не в очереди на озвучку"}), 404
    database.update_voiceover(conn, path, total_tracks=total,
                              engine=(data.get('engine') or '')[:64],
                              voice=(data.get('voice') or '')[:64])
    refresh_voiceover_status(conn, path)
    return jsonify({"ok": True})


@app.route('/api/worker/voice/track', methods=['POST'])
@worker_required
def api_worker_voice_track():
    """
    Принимает готовую главу. Аудио идёт телом запроса, а не формой: воркер
    собран на одной стандартной библиотеке, и multipart там пришлось бы
    склеивать руками. Всё остальное — в строке запроса.
    """
    path = (request.args.get('path') or '').strip('/')
    name = (request.args.get('name') or '').strip()
    ext = (request.args.get('ext') or 'mp3').strip()
    try:
        index = int(request.args.get('index'))
    except (TypeError, ValueError):
        return jsonify({"error": "Некорректный номер главы"}), 400
    try:
        seconds = float(request.args.get('seconds') or 0)
    except ValueError:
        seconds = 0.0

    audio = request.get_data(cache=False)
    if not audio:
        return jsonify({"error": "Пустое тело запроса"}), 400

    conn = get_db()
    row = database.get_voiceover(conn, path)
    if row is None:
        return jsonify({"error": "Книга не в очереди на озвучку"}), 404

    try:
        directory = resolve_disk(path)
    except Exception:
        return jsonify({"error": "Книга не найдена"}), 404
    if not os.path.isdir(directory):
        return jsonify({"error": "Папка книги не найдена"}), 404

    filename = generated_track_filename(index, name, row['total_tracks'], ext)
    try:
        with open(os.path.join(directory, filename), 'wb') as f:
            f.write(audio)
    except OSError as exc:
        return jsonify({"error": f"Не удалось записать файл: {exc}"}), 500

    database.save_voiceover_track(conn, path, index, name, filename, seconds)
    refresh_voiceover_status(conn, path)
    return jsonify({"ok": True, "filename": filename})


@app.route('/api/worker/voice/text', methods=['POST'])
@worker_required
def api_worker_voice_text():
    """
    Текст главы с таймингами. При озвучке они не угадываются, а известны
    точно: воркер синтезирует речь кусками и знает длительность каждого.
    Поэтому у сгенерированной книги сразу работает режим чтения.
    """
    data = request.get_json(silent=True) or {}
    path = (data.get('path') or '').strip('/')
    segments = data.get('segments')
    try:
        index = int(data.get('track'))
    except (TypeError, ValueError):
        return jsonify({"error": "Некорректный номер главы"}), 400
    if not isinstance(segments, list):
        return jsonify({"error": "Ожидался список сегментов"}), 400

    conn = get_db()
    row = database.get_voiceover(conn, path)
    if row is None:
        return jsonify({"error": "Книга не в очереди на озвучку"}), 404

    track = database.voiceover_tracks(conn, path)
    filename = next((r['filename'] for r in track if r['track_index'] == index), '')
    if not filename:
        return jsonify({"error": "Сначала пришлите аудио главы"}), 409

    name = (data.get('name') or filename)[:200]
    engine = (data.get('engine') or '')[:64]

    # Текстовая версия книги ведётся той же таблицей, что и у распознавания:
    # для читателя разницы нет, откуда взялся текст.
    if database.get_transcript(conn, path) is None:
        database.create_transcript(conn, path, row['total_tracks'])
    database.save_transcript_track(conn, path, index, name, segments)
    try:
        disk = os.path.join(resolve_disk(path), filename)
        transcribe.write_track_files(disk, name, segments, 'ru', engine)
    except Exception:
        pass  # текст уже в базе; файл рядом с аудио — приятное дополнение
    return jsonify({"ok": True})


@app.route('/api/worker/voice/done', methods=['POST'])
@worker_required
def api_worker_voice_done():
    """Книга озвучена целиком: снимаем бронь и закрываем очередь."""
    data = request.get_json(silent=True) or {}
    path = (data.get('path') or '').strip('/')
    conn = get_db()
    database.release_book_claim(conn, path)
    refresh_voiceover_status(conn, path)
    if database.get_transcript(conn, path) is not None:
        database.update_transcript(conn, path,
                                   total_tracks=len(album_tracks_on_disk(path)))
        refresh_transcript_status(conn, path)
    return jsonify({"ok": True, "state": voiceover_state(conn, path)})


@app.route('/api/worker/voice/error', methods=['POST'])
@worker_required
def api_worker_voice_error():
    """Воркер сообщает, что книга не поддалась: снимаем бронь, пишем причину."""
    data = request.get_json(silent=True) or {}
    path = (data.get('path') or '').strip('/')
    message = (data.get('error') or 'Ошибка озвучки')[:500]
    conn = get_db()
    database.release_book_claim(conn, path)
    database.update_voiceover(conn, path, status='error', error=message)
    return jsonify({"ok": True})


# --- API: каталог ---
@app.route('/api/browse')
def api_browse():
    """
    Главный API-эндпоинт. Принимает 'path' и возвращает содержимое.
    """
    relative_path = request.args.get('path', '').strip('/')
    current_path = resolve_disk(relative_path)

    if not os.path.isdir(current_path):
        abort(404, "Directory not found")

    items = []
    tracks = []

    # Какие книги уже имеют текст — одним запросом, а не по книге на карточку
    ready_text = {row['path'] for row in database.list_transcripts(get_db())
                  if row['status'] in ('done', 'running')}

    dir_content = sorted(os.listdir(current_path), key=natural_key)
    default_cover = url_for('static', filename='assets/default_cover.png')

    for item_name in dir_content:
        item_path_on_disk = os.path.join(current_path, item_name)
        # Путь для навигации (относительно папки music)
        nav_path = os.path.join(relative_path, item_name).replace('\\', '/')

        if os.path.isdir(item_path_on_disk):
            if item_name == transcribe.TEXT_FOLDER_NAME:
                continue  # служебная папка с расшифровками, не показываем
            if has_music_recursive(item_path_on_disk):
                items.append({
                    # Папка, в которой треки лежат напрямую, — это альбом/аудиокнига.
                    # Её открываем сразу в плеере, без промежуточного запроса.
                    "type": "album" if has_direct_tracks(item_path_on_disk) else "directory",
                    "name": item_name.replace('_', ' '),
                    "path": nav_path,
                    "cover": find_cover(item_path_on_disk, nav_path) or default_cover,
                    # Значок на карточке: у книги есть текстовая версия
                    "has_text": nav_path in ready_text,
                })

        elif is_audio(item_name):
            tracks.append({
                "name": item_name,
                "url": media_url(nav_path.replace('\\', '/'))
            })

    if not relative_path:
        # Содержимое подключённых папок раскладываем на верхнем уровне —
        # так же, как содержимое основной. Сама папка отдельной карточкой
        # не показывается: она лишь способ подключить диск.
        for name, disk in extra_roots():
            if not os.path.isdir(disk):
                continue
            if has_direct_tracks(disk):
                # В самой папке лежат треки — это одна книга
                items.append({
                    "type": "album", "name": name, "path": name,
                    "cover": find_cover(disk, name) or default_cover,
                    "has_text": name in ready_text,
                })
                continue
            for child in sorted(os.listdir(disk), key=natural_key):
                child_disk = os.path.join(disk, child)
                if not os.path.isdir(child_disk) or child == transcribe.TEXT_FOLDER_NAME:
                    continue
                if not has_music_recursive(child_disk):
                    continue
                child_path = f'{name}/{child}'
                items.append({
                    "type": "album" if has_direct_tracks(child_disk) else "directory",
                    "name": child.replace('_', ' '),
                    "path": child_path,
                    "cover": find_cover(child_disk, child_path) or default_cover,
                    "has_text": child_path in ready_text,
                })

    if tracks:
        # Это "альбом", так как в папке есть треки
        cached = database.get_album_durations(get_db(), relative_path)
        return jsonify({
            "type": "album",
            "downloads": downloads_enabled(),
            "title": os.path.basename(relative_path).replace('_', ' ') or "Медиатека",
            "path": relative_path,
            # Путь к родительской папке — нужен кнопке «Назад» в плеере
            "parent": os.path.dirname(relative_path).replace('\\', '/'),
            "tracks": tracks,
            "cover": find_cover(current_path, relative_path) or default_cover,
            # Длительности из кэша: если их число совпало с числом треков,
            # браузеру не нужно заново вычитывать метаданные всех файлов
            "durations": cached['durations'] if cached and cached['track_count'] == len(tracks) else None,
            "transcript": transcript_state(get_db(), relative_path, len(tracks))
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
    # В режиме отладки Flask поднимает два процесса: сторожевой и рабочий.
    # Приветствие печатает только тот, который обслуживает запросы.
    is_serving_process = os.environ.get('WERKZEUG_RUN_MAIN') == 'true' or not DEBUG

    if is_serving_process:
        print("CyberAudio Hub запущен. Добро пожаловать в Найт-Сити.")
        print(f"-> База данных: {DB_PATH}")
        if ADMIN_STATE == 'created':
            print(f"-> Создан администратор: логин '{ADMIN_LOGIN}', пароль из настроек.")
            print("   ВНИМАНИЕ: пароль по умолчанию известен всем, у кого есть исходники.")
            print("   Смените его в профиле или задайте CYBERAUDIO_ADMIN_PASSWORD.")
        elif ADMIN_STATE == 'promoted':
            print(f"-> Учётной записи '{ADMIN_LOGIN}' выданы права администратора.")
        print(f"-> Токен для распознавателя: {WORKER_TOKEN}")
        print("   Впишите его в worker/config.ini на том компьютере,")
        print("   который будет создавать текст из аудио.")
        print(f"-> Откройте в браузере: http://localhost:{APP_PORT}")

    app.run(debug=DEBUG, port=APP_PORT, host='0.0.0.0')
