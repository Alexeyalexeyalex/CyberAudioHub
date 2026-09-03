# db.py — работа с локальной базой SQLite
import os
import json
import secrets
import sqlite3
from datetime import datetime, timezone

from flask import g

SCHEMA = """
PRAGMA journal_mode = WAL;

CREATE TABLE IF NOT EXISTS users (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    login         TEXT    NOT NULL UNIQUE COLLATE NOCASE,
    nickname      TEXT    NOT NULL,
    password_hash TEXT    NOT NULL,
    is_admin      INTEGER NOT NULL DEFAULT 0,
    created_at    TEXT    NOT NULL
);

-- Прогресс прослушивания: своя строка на каждую пару «пользователь + книга»
CREATE TABLE IF NOT EXISTS progress (
    user_id     INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    path        TEXT    NOT NULL,
    title       TEXT    NOT NULL DEFAULT '',
    cover       TEXT    NOT NULL DEFAULT '',
    track_index INTEGER NOT NULL DEFAULT 0,
    position    REAL    NOT NULL DEFAULT 0,
    finished    INTEGER NOT NULL DEFAULT 0,
    updated_at  TEXT    NOT NULL,
    PRIMARY KEY (user_id, path)
);

CREATE INDEX IF NOT EXISTS idx_progress_user ON progress(user_id, updated_at DESC);

-- Длительности треков одинаковы для всех пользователей, поэтому кэш общий.
-- Заполняется браузером после первого открытия книги (метаданные аудио),
-- чтобы не тянуть в проект зависимость для разбора тегов.
CREATE TABLE IF NOT EXISTS albums (
    path           TEXT PRIMARY KEY,
    track_count    INTEGER NOT NULL,
    total_duration REAL    NOT NULL,
    durations      TEXT    NOT NULL,
    updated_at     TEXT    NOT NULL
);

-- Текстовая версия книги. Строка на книгу — общий статус распознавания.
CREATE TABLE IF NOT EXISTS transcripts (
    path         TEXT PRIMARY KEY,
    status       TEXT    NOT NULL DEFAULT 'pending',  -- pending|running|done|error
    engine       TEXT    NOT NULL DEFAULT '',
    language     TEXT    NOT NULL DEFAULT '',
    done_tracks  INTEGER NOT NULL DEFAULT 0,
    total_tracks INTEGER NOT NULL DEFAULT 0,
    error        TEXT    NOT NULL DEFAULT '',
    created_at   TEXT    NOT NULL,
    updated_at   TEXT    NOT NULL
);

-- Текст отдельной главы. Слова со временем лежат в JSON: строк на каждое слово
-- было бы слишком много (у книги их сотни тысяч).
CREATE TABLE IF NOT EXISTS transcript_tracks (
    path        TEXT    NOT NULL,
    track_index INTEGER NOT NULL,
    name        TEXT    NOT NULL DEFAULT '',
    data        TEXT    NOT NULL,
    updated_at  TEXT    NOT NULL,
    PRIMARY KEY (path, track_index)
);

-- Дополнительные папки с музыкой: на других дисках или в других местах.
-- Основная папка (static/music) в таблице не хранится — она всегда первая
-- и её путь задаётся отдельной строкой в meta.
CREATE TABLE IF NOT EXISTS library_roots (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    name       TEXT    NOT NULL UNIQUE,
    path       TEXT    NOT NULL,
    created_at TEXT    NOT NULL
);

-- Бегущие строки внизу экрана. Показывается не больше трёх сразу:
-- иначе они закрыли бы половину страницы.
CREATE TABLE IF NOT EXISTS site_messages (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    text       TEXT    NOT NULL,
    color      TEXT    NOT NULL DEFAULT 'pink',
    enabled    INTEGER NOT NULL DEFAULT 1,
    created_at TEXT    NOT NULL
);

-- Дружба: одна строка на заявку. Кто позвал — user_id, кого — friend_id.
-- Принятая заявка означает дружбу в обе стороны.
CREATE TABLE IF NOT EXISTS friendships (
    user_id    INTEGER NOT NULL,
    friend_id  INTEGER NOT NULL,
    status     TEXT    NOT NULL DEFAULT 'pending',
    created_at TEXT    NOT NULL,
    PRIMARY KEY (user_id, friend_id)
);

-- Достижения: за что выдаются и кому уже выданы.
CREATE TABLE IF NOT EXISTS achievements (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    title        TEXT    NOT NULL,
    image        TEXT    NOT NULL DEFAULT '',
    description  TEXT    NOT NULL DEFAULT '',
    target_path  TEXT    NOT NULL DEFAULT '',
    target_track INTEGER,
    -- Номера глав через запятую; пусто — засчитывается любая глава книги
    target_tracks TEXT   NOT NULL DEFAULT '',
    rarity       TEXT    NOT NULL DEFAULT 'common',
    created_at   TEXT    NOT NULL
);

-- Какие главы человек дослушал: нужно для достижений сразу за несколько глав.
CREATE TABLE IF NOT EXISTS track_completions (
    user_id      INTEGER NOT NULL,
    path         TEXT    NOT NULL,
    track_index  INTEGER NOT NULL,
    completed_at TEXT    NOT NULL,
    PRIMARY KEY (user_id, path, track_index)
);

CREATE TABLE IF NOT EXISTS user_achievements (
    user_id        INTEGER NOT NULL,
    achievement_id INTEGER NOT NULL,
    earned_at      TEXT    NOT NULL,
    PRIMARY KEY (user_id, achievement_id)
);

-- Личные папки читателя: свои подборки книг, которые видит только он.
CREATE TABLE IF NOT EXISTS folders (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id    INTEGER NOT NULL,
    name       TEXT    NOT NULL,
    cover      TEXT    NOT NULL DEFAULT '',
    created_at TEXT    NOT NULL,
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS folder_items (
    folder_id  INTEGER NOT NULL,
    path       TEXT    NOT NULL,
    added_at   TEXT    NOT NULL,
    PRIMARY KEY (folder_id, path),
    FOREIGN KEY (folder_id) REFERENCES folders(id) ON DELETE CASCADE
);

-- Просьба читателя сделать текстовую версию книги. По записи на человека:
-- админ видит, кто именно просил, а повторно нажать кнопку уже нельзя.
CREATE TABLE IF NOT EXISTS text_requests (
    path       TEXT    NOT NULL,
    user_id    INTEGER NOT NULL,
    created_at TEXT    NOT NULL,
    PRIMARY KEY (path, user_id),
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- Глава, которую прямо сейчас распознаёт внешний компьютер. Заявка
-- протухает по времени: если воркер отключили, глава вернётся в очередь.
CREATE TABLE IF NOT EXISTS transcript_claims (
    path        TEXT    NOT NULL,
    track_index INTEGER NOT NULL,
    worker      TEXT    NOT NULL DEFAULT '',
    claimed_at  TEXT    NOT NULL,
    PRIMARY KEY (path, track_index)
);

-- Книга из документа (pdf/docx/txt/fb2/epub), поставленная в очередь на
-- озвучку. Строка на книгу — общий статус, как у текстовой версии.
CREATE TABLE IF NOT EXISTS voiceovers (
    path         TEXT PRIMARY KEY,
    status       TEXT    NOT NULL DEFAULT 'pending',  -- pending|running|done|error
    engine       TEXT    NOT NULL DEFAULT '',
    voice        TEXT    NOT NULL DEFAULT '',
    document     TEXT    NOT NULL DEFAULT '',
    done_tracks  INTEGER NOT NULL DEFAULT 0,
    total_tracks INTEGER NOT NULL DEFAULT 0,
    error        TEXT    NOT NULL DEFAULT '',
    created_at   TEXT    NOT NULL,
    updated_at   TEXT    NOT NULL
);

-- Какие файлы озвучка создала. Нужен именно список, а не поиск по маске:
-- при снятии галочки удаляются ровно наши файлы, а не всё, что похоже.
CREATE TABLE IF NOT EXISTS voiceover_tracks (
    path        TEXT    NOT NULL,
    track_index INTEGER NOT NULL,
    name        TEXT    NOT NULL DEFAULT '',
    filename    TEXT    NOT NULL,
    seconds     REAL    NOT NULL DEFAULT 0,
    updated_at  TEXT    NOT NULL,
    PRIMARY KEY (path, track_index)
);

-- Книга, которую прямо сейчас озвучивает воркер. В отличие от расшифровки
-- бронь берётся на книгу целиком: сколько в ней глав, выясняется только
-- после разбора документа, а разбирает его сам воркер.
CREATE TABLE IF NOT EXISTS voiceover_claims (
    path       TEXT PRIMARY KEY,
    worker     TEXT NOT NULL DEFAULT '',
    claimed_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS meta (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
"""


def now_iso():
    return datetime.now(timezone.utc).isoformat(timespec='seconds')


def get_db(db_path=None):
    """Соединение живёт в контексте запроса и закрывается автоматически."""
    if 'db' not in g:
        path = db_path or g.get('db_path')
        conn = sqlite3.connect(path)
        conn.row_factory = sqlite3.Row
        conn.execute('PRAGMA foreign_keys = ON')
        g.db = conn
    return g.db


def close_db(exc=None):
    conn = g.pop('db', None)
    if conn is not None:
        conn.close()


def init_db(db_path):
    """Создаёт файл БД и таблицы, если их ещё нет, и доводит схему до актуальной."""
    os.makedirs(os.path.dirname(db_path), exist_ok=True)
    conn = sqlite3.connect(db_path)
    try:
        conn.executescript(SCHEMA)
        migrate(conn)
        conn.commit()
    finally:
        conn.close()


def migrate(conn):
    """
    Догоняет схему на базах, созданных прошлыми версиями:
    CREATE TABLE IF NOT EXISTS не добавляет новые колонки в существующую таблицу.
    """
    columns = {row[1] for row in conn.execute("PRAGMA table_info(users)")}
    if 'is_admin' not in columns:
        conn.execute("ALTER TABLE users ADD COLUMN is_admin INTEGER NOT NULL DEFAULT 0")

    # Картинка личной папки появилась позже самих папок
    ach_columns = {row[1] for row in conn.execute("PRAGMA table_info(achievements)")}
    if ach_columns and 'target_tracks' not in ach_columns:
        conn.execute("ALTER TABLE achievements ADD COLUMN target_tracks TEXT NOT NULL DEFAULT ''")

    folder_columns = {row[1] for row in conn.execute("PRAGMA table_info(folders)")}
    if folder_columns and 'cover' not in folder_columns:
        conn.execute("ALTER TABLE folders ADD COLUMN cover TEXT NOT NULL DEFAULT ''")


def get_or_create_secret_key(db_path):
    """
    Ключ подписи сессий хранится в БД: иначе после каждого перезапуска
    сервера все пользователи разлогинивались бы.
    """
    conn = sqlite3.connect(db_path)
    try:
        row = conn.execute("SELECT value FROM meta WHERE key = 'secret_key'").fetchone()
        if row:
            return row[0]
        key = secrets.token_hex(32)
        conn.execute("INSERT INTO meta (key, value) VALUES ('secret_key', ?)", (key,))
        conn.commit()
        return key
    finally:
        conn.close()


def get_or_create_worker_token(db_path, override=''):
    """
    Общий пароль для внешних распознавателей. Хранится в БД, чтобы не менялся
    при перезапуске: иначе после каждого рестарта воркер пришлось бы
    перенастраивать.
    """
    conn = sqlite3.connect(db_path)
    try:
        if override:
            conn.execute("INSERT OR REPLACE INTO meta (key, value) VALUES ('worker_token', ?)",
                         (override,))
            conn.commit()
            return override
        row = conn.execute("SELECT value FROM meta WHERE key = 'worker_token'").fetchone()
        if row:
            return row[0]
        token = secrets.token_urlsafe(24)
        conn.execute("INSERT INTO meta (key, value) VALUES ('worker_token', ?)", (token,))
        conn.commit()
        return token
    finally:
        conn.close()


# --- Бегущие строки ---

def list_messages(conn, only_enabled=False):
    sql = "SELECT id, text, color, enabled, created_at FROM site_messages"
    if only_enabled:
        sql += " WHERE enabled = 1"
    sql += " ORDER BY id"
    return conn.execute(sql).fetchall()


def create_message(conn, text, color):
    cur = conn.execute("INSERT INTO site_messages (text, color, enabled, created_at) "
                       "VALUES (?, ?, 1, ?)", (text, color, now_iso()))
    conn.commit()
    return cur.lastrowid


def update_message(conn, message_id, text, color, enabled):
    conn.execute("UPDATE site_messages SET text = ?, color = ?, enabled = ? WHERE id = ?",
                 (text, color, 1 if enabled else 0, message_id))
    conn.commit()


def delete_message(conn, message_id):
    conn.execute("DELETE FROM site_messages WHERE id = ?", (message_id,))
    conn.commit()


def count_enabled_messages(conn, exclude_id=None):
    sql = "SELECT COUNT(*) AS n FROM site_messages WHERE enabled = 1"
    args = []
    if exclude_id is not None:
        sql += " AND id <> ?"
        args.append(exclude_id)
    return conn.execute(sql, args).fetchone()['n']


# --- Друзья ---

def friend_state(conn, me, other):
    """none | outgoing | incoming | friends"""
    row = conn.execute("SELECT user_id, status FROM friendships "
                       "WHERE (user_id = ? AND friend_id = ?) OR (user_id = ? AND friend_id = ?)",
                       (me, other, other, me)).fetchone()
    if row is None:
        return 'none'
    if row['status'] == 'accepted':
        return 'friends'
    return 'outgoing' if row['user_id'] == me else 'incoming'


def add_friend_request(conn, me, other):
    if friend_state(conn, me, other) != 'none':
        return False
    conn.execute("INSERT INTO friendships (user_id, friend_id, status, created_at) "
                 "VALUES (?, ?, 'pending', ?)", (me, other, now_iso()))
    conn.commit()
    return True


def accept_friend_request(conn, me, other):
    cur = conn.execute("UPDATE friendships SET status = 'accepted' "
                       "WHERE user_id = ? AND friend_id = ? AND status = 'pending'",
                       (other, me))
    conn.commit()
    return cur.rowcount > 0


def drop_friendship(conn, me, other):
    """Убирает и дружбу, и неотвеченную заявку в любую сторону."""
    conn.execute("DELETE FROM friendships WHERE (user_id = ? AND friend_id = ?) "
                 "OR (user_id = ? AND friend_id = ?)", (me, other, other, me))
    conn.commit()


def list_friends(conn, user_id):
    return conn.execute("""
        SELECT u.id, u.login, u.nickname FROM friendships f
        JOIN users u ON u.id = CASE WHEN f.user_id = ? THEN f.friend_id ELSE f.user_id END
        WHERE f.status = 'accepted' AND (f.user_id = ? OR f.friend_id = ?)
        ORDER BY u.nickname COLLATE NOCASE
    """, (user_id, user_id, user_id)).fetchall()


def list_incoming_requests(conn, user_id):
    return conn.execute("""
        SELECT u.id, u.login, u.nickname, f.created_at FROM friendships f
        JOIN users u ON u.id = f.user_id
        WHERE f.friend_id = ? AND f.status = 'pending'
        ORDER BY f.created_at
    """, (user_id,)).fetchall()


def list_outgoing_requests(conn, user_id):
    return conn.execute("""
        SELECT u.id, u.login, u.nickname FROM friendships f
        JOIN users u ON u.id = f.friend_id
        WHERE f.user_id = ? AND f.status = 'pending'
        ORDER BY u.nickname COLLATE NOCASE
    """, (user_id,)).fetchall()


def count_incoming_requests(conn, user_id):
    row = conn.execute("SELECT COUNT(*) AS n FROM friendships "
                       "WHERE friend_id = ? AND status = 'pending'", (user_id,)).fetchone()
    return row['n'] if row else 0


def search_users(conn, query, exclude_id, limit=20):
    """Поиск по логину и никнейму. Показываем только по запросу — списка всех нет."""
    like = f'%{query}%'
    return conn.execute(
        "SELECT id, login, nickname FROM users "
        "WHERE id <> ? AND (login LIKE ? COLLATE NOCASE OR nickname LIKE ? COLLATE NOCASE) "
        "ORDER BY nickname COLLATE NOCASE LIMIT ?",
        (exclude_id, like, like, limit)).fetchall()


# --- Достижения ---

def list_achievements(conn):
    return conn.execute(
        "SELECT id, title, image, description, target_path, target_track, "
        "target_tracks, rarity, created_at "
        "FROM achievements ORDER BY id").fetchall()


def get_achievement(conn, achievement_id):
    return conn.execute("SELECT * FROM achievements WHERE id = ?",
                        (achievement_id,)).fetchone()


def create_achievement(conn, title, image, description, target_path, tracks, rarity):
    cur = conn.execute(
        "INSERT INTO achievements (title, image, description, target_path, "
        "target_track, target_tracks, rarity, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        (title, image, description, target_path, None, tracks, rarity, now_iso()))
    conn.commit()
    return cur.lastrowid


def update_achievement(conn, achievement_id, title, image, description,
                       target_path, tracks, rarity):
    conn.execute(
        "UPDATE achievements SET title = ?, image = ?, description = ?, "
        "target_path = ?, target_tracks = ?, rarity = ? WHERE id = ?",
        (title, image, description, target_path, tracks, rarity, achievement_id))
    conn.commit()


def mark_track_done(conn, user_id, path, track_index):
    try:
        conn.execute("INSERT INTO track_completions (user_id, path, track_index, completed_at) "
                     "VALUES (?, ?, ?, ?)", (user_id, path, track_index, now_iso()))
        conn.commit()
    except sqlite3.IntegrityError:
        pass


def done_tracks_for(conn, user_id, path):
    rows = conn.execute("SELECT track_index FROM track_completions "
                        "WHERE user_id = ? AND path = ?", (user_id, path)).fetchall()
    return {r['track_index'] for r in rows}


def delete_achievement(conn, achievement_id):
    conn.execute("DELETE FROM user_achievements WHERE achievement_id = ?", (achievement_id,))
    conn.execute("DELETE FROM achievements WHERE id = ?", (achievement_id,))
    conn.commit()


def grant_achievement(conn, user_id, achievement_id):
    """True — выдали впервые, False — уже было."""
    try:
        conn.execute("INSERT INTO user_achievements (user_id, achievement_id, earned_at) "
                     "VALUES (?, ?, ?)", (user_id, achievement_id, now_iso()))
    except sqlite3.IntegrityError:
        return False
    conn.commit()
    return True


def user_achievement_rows(conn, user_id):
    return conn.execute(
        "SELECT a.*, u.earned_at FROM user_achievements u "
        "JOIN achievements a ON a.id = u.achievement_id "
        "WHERE u.user_id = ? ORDER BY u.earned_at DESC", (user_id,)).fetchall()


def count_user_achievements(conn, user_id):
    row = conn.execute("SELECT COUNT(*) AS n FROM user_achievements WHERE user_id = ?",
                       (user_id,)).fetchone()
    return row['n'] if row else 0


# --- Папки медиатеки на диске ---

def list_roots(conn):
    return conn.execute("SELECT id, name, path, created_at FROM library_roots "
                        "ORDER BY name COLLATE NOCASE").fetchall()


def add_root(conn, name, path):
    try:
        cur = conn.execute("INSERT INTO library_roots (name, path, created_at) "
                           "VALUES (?, ?, ?)", (name, path, now_iso()))
    except sqlite3.IntegrityError:
        return None
    conn.commit()
    return cur.lastrowid


def update_root(conn, root_id, name, path):
    try:
        conn.execute("UPDATE library_roots SET name = ?, path = ? WHERE id = ?",
                     (name, path, root_id))
    except sqlite3.IntegrityError:
        return False
    conn.commit()
    return True


def delete_root(conn, root_id):
    conn.execute("DELETE FROM library_roots WHERE id = ?", (root_id,))
    conn.commit()


def get_setting(conn, key, default=''):
    row = conn.execute("SELECT value FROM meta WHERE key = ?", (key,)).fetchone()
    return row['value'] if row else default


def set_setting(conn, key, value):
    conn.execute("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)",
                 (key, str(value)))
    conn.commit()


def get_flag(conn, key, default=False):
    """Настройка-переключатель из таблицы meta."""
    row = conn.execute("SELECT value FROM meta WHERE key = ?", (key,)).fetchone()
    return row['value'] == '1' if row else default


def set_flag(conn, key, value):
    conn.execute("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)",
                 (key, '1' if value else '0'))
    conn.commit()


def get_main_root(conn, fallback):
    """Путь основной папки: его тоже можно поменять из админки."""
    row = conn.execute("SELECT value FROM meta WHERE key = 'main_root'").fetchone()
    return row['value'] if row else fallback


def set_main_root(conn, path):
    conn.execute("INSERT OR REPLACE INTO meta (key, value) VALUES ('main_root', ?)",
                 (path,))
    conn.commit()


# --- Личные папки читателя ---

def create_folder(conn, user_id, name):
    cur = conn.execute("INSERT INTO folders (user_id, name, created_at) VALUES (?, ?, ?)",
                       (user_id, name, now_iso()))
    conn.commit()
    return cur.lastrowid


def rename_folder(conn, folder_id, user_id, name):
    conn.execute("UPDATE folders SET name = ? WHERE id = ? AND user_id = ?",
                 (name, folder_id, user_id))
    conn.commit()


def delete_folder(conn, folder_id, user_id):
    """Удаляет папку вместе с содержимым — но только если она чужой не является."""
    own = conn.execute("SELECT 1 FROM folders WHERE id = ? AND user_id = ?",
                       (folder_id, user_id)).fetchone()
    if not own:
        return False
    conn.execute("DELETE FROM folder_items WHERE folder_id = ?", (folder_id,))
    conn.execute("DELETE FROM folders WHERE id = ?", (folder_id,))
    conn.commit()
    return True


def owns_folder(conn, folder_id, user_id):
    row = conn.execute("SELECT 1 FROM folders WHERE id = ? AND user_id = ?",
                       (folder_id, user_id)).fetchone()
    return row is not None


def set_folder_cover(conn, folder_id, user_id, filename):
    """Возвращает прежнюю картинку, чтобы вызывающий удалил файл с диска."""
    row = conn.execute("SELECT cover FROM folders WHERE id = ? AND user_id = ?",
                       (folder_id, user_id)).fetchone()
    if row is None:
        return None
    conn.execute("UPDATE folders SET cover = ? WHERE id = ?", (filename, folder_id))
    conn.commit()
    return row['cover']


def get_folder_cover(conn, folder_id):
    row = conn.execute("SELECT cover FROM folders WHERE id = ?", (folder_id,)).fetchone()
    return row['cover'] if row else ''


def list_folders(conn, user_id):
    return conn.execute(
        "SELECT f.id, f.name, f.cover, f.created_at, COUNT(i.path) AS books "
        "FROM folders f LEFT JOIN folder_items i ON i.folder_id = f.id "
        "WHERE f.user_id = ? GROUP BY f.id ORDER BY f.name COLLATE NOCASE",
        (user_id,)).fetchall()


def folder_paths(conn, folder_id):
    rows = conn.execute("SELECT path FROM folder_items WHERE folder_id = ? "
                        "ORDER BY added_at", (folder_id,)).fetchall()
    return [r['path'] for r in rows]


def add_to_folder(conn, folder_id, path):
    try:
        conn.execute("INSERT INTO folder_items (folder_id, path, added_at) VALUES (?, ?, ?)",
                     (folder_id, path, now_iso()))
    except sqlite3.IntegrityError:
        return False
    conn.commit()
    return True


def remove_from_folder(conn, folder_id, path):
    conn.execute("DELETE FROM folder_items WHERE folder_id = ? AND path = ?",
                 (folder_id, path))
    conn.commit()


def folders_with_path(conn, user_id, path):
    """В каких своих папках лежит эта книга — чтобы отметить их в меню."""
    rows = conn.execute(
        "SELECT f.id FROM folders f JOIN folder_items i ON i.folder_id = f.id "
        "WHERE f.user_id = ? AND i.path = ?", (user_id, path)).fetchall()
    return [r['id'] for r in rows]


# --- Просьбы читателей о текстовой версии ---

def add_text_request(conn, path, user_id):
    """True — просьба записана, False — этот человек уже просил."""
    try:
        conn.execute("INSERT INTO text_requests (path, user_id, created_at) "
                     "VALUES (?, ?, ?)", (path, user_id, now_iso()))
    except sqlite3.IntegrityError:
        return False
    conn.commit()
    return True


def has_text_request(conn, path, user_id):
    row = conn.execute("SELECT 1 FROM text_requests WHERE path = ? AND user_id = ?",
                       (path, user_id)).fetchone()
    return row is not None


def user_requested_paths(conn, user_id):
    """Книги, которые этот человек уже просил, — чтобы погасить кнопки разом."""
    rows = conn.execute("SELECT path FROM text_requests WHERE user_id = ?",
                        (user_id,)).fetchall()
    return [r['path'] for r in rows]


def list_text_requests(conn):
    """Просьбы вместе с теми, кто их оставил, сгруппированные по книгам."""
    rows = conn.execute("""
        SELECT r.path, r.created_at, u.id AS user_id, u.login, u.nickname
        FROM text_requests r
        JOIN users u ON u.id = r.user_id
        ORDER BY r.path, r.created_at
    """).fetchall()
    return rows


def count_text_requests(conn):
    """Сколько книг ждут решения — для значка у администратора."""
    row = conn.execute("SELECT COUNT(DISTINCT path) AS n FROM text_requests").fetchone()
    return row['n'] if row else 0


def delete_text_requests(conn, path, user_id=None):
    if user_id is None:
        conn.execute("DELETE FROM text_requests WHERE path = ?", (path,))
    else:
        conn.execute("DELETE FROM text_requests WHERE path = ? AND user_id = ?",
                     (path, user_id))
    conn.commit()


# --- Заявки внешних распознавателей ---

def claim_track(conn, path, track_index, worker, stale_before):
    """
    Пытается забронировать главу за воркером. False — её уже взял другой.
    Заявки старше stale_before считаются брошенными и перехватываются.
    """
    conn.execute("DELETE FROM transcript_claims WHERE claimed_at < ?", (stale_before,))
    try:
        conn.execute(
            "INSERT INTO transcript_claims (path, track_index, worker, claimed_at) "
            "VALUES (?, ?, ?, ?)", (path, track_index, worker, now_iso()))
    except sqlite3.IntegrityError:
        return False
    conn.commit()
    return True


def touch_claim(conn, path, track_index, worker):
    """Продлевает заявку: длинная глава не должна протухнуть на полпути."""
    conn.execute("UPDATE transcript_claims SET claimed_at = ? "
                 "WHERE path = ? AND track_index = ? AND worker = ?",
                 (now_iso(), path, track_index, worker))
    conn.commit()


def release_claim(conn, path, track_index):
    conn.execute("DELETE FROM transcript_claims WHERE path = ? AND track_index = ?",
                 (path, track_index))
    conn.commit()


def release_book_claims(conn, path):
    conn.execute("DELETE FROM transcript_claims WHERE path = ?", (path,))
    conn.commit()


def active_claims(conn, stale_before):
    """{(path, index): worker} по живым заявкам."""
    conn.execute("DELETE FROM transcript_claims WHERE claimed_at < ?", (stale_before,))
    conn.commit()
    rows = conn.execute("SELECT path, track_index, worker, claimed_at "
                        "FROM transcript_claims").fetchall()
    return {(r['path'], r['track_index']): dict(r) for r in rows}


# --- Пользователи ---

def create_user(conn, login, nickname, password_hash, is_admin=0):
    cur = conn.execute(
        "INSERT INTO users (login, nickname, password_hash, is_admin, created_at) VALUES (?, ?, ?, ?, ?)",
        (login, nickname, password_hash, 1 if is_admin else 0, now_iso())
    )
    conn.commit()
    return cur.lastrowid


def find_user_by_login(conn, login):
    return conn.execute("SELECT * FROM users WHERE login = ? COLLATE NOCASE", (login,)).fetchone()


def find_user_by_id(conn, user_id):
    return conn.execute("SELECT * FROM users WHERE id = ?", (user_id,)).fetchone()


def update_user(conn, user_id, **fields):
    allowed = {'login', 'nickname', 'password_hash', 'is_admin'}
    fields = {k: v for k, v in fields.items() if k in allowed}
    if not fields:
        return
    assignments = ', '.join(f'{k} = ?' for k in fields)
    conn.execute(f"UPDATE users SET {assignments} WHERE id = ?", (*fields.values(), user_id))
    conn.commit()


def delete_user(conn, user_id):
    conn.execute("DELETE FROM progress WHERE user_id = ?", (user_id,))
    conn.execute("DELETE FROM text_requests WHERE user_id = ?", (user_id,))
    conn.execute("DELETE FROM user_achievements WHERE user_id = ?", (user_id,))
    conn.execute("DELETE FROM track_completions WHERE user_id = ?", (user_id,))
    conn.execute("DELETE FROM friendships WHERE user_id = ? OR friend_id = ?",
                 (user_id, user_id))
    conn.execute("DELETE FROM folder_items WHERE folder_id IN "
                 "(SELECT id FROM folders WHERE user_id = ?)", (user_id,))
    conn.execute("DELETE FROM folders WHERE user_id = ?", (user_id,))
    conn.execute("DELETE FROM users WHERE id = ?", (user_id,))
    conn.commit()


def list_users(conn):
    """Все учётные записи вместе с числом начатых книг."""
    return conn.execute(
        """
        SELECT u.*, (SELECT COUNT(*) FROM progress p WHERE p.user_id = u.id) AS books
        FROM users u
        ORDER BY u.is_admin DESC, u.login COLLATE NOCASE
        """
    ).fetchall()


def count_admins(conn):
    return conn.execute("SELECT COUNT(*) FROM users WHERE is_admin = 1").fetchone()[0]


def list_all_progress(conn):
    """Прогресс всех пользователей — для админской страницы."""
    return conn.execute(
        """
        SELECT p.*, u.login, u.nickname
        FROM progress p JOIN users u ON u.id = p.user_id
        ORDER BY p.updated_at DESC
        """
    ).fetchall()


def list_albums(conn):
    return conn.execute(
        "SELECT path, track_count, total_duration, updated_at FROM albums ORDER BY path"
    ).fetchall()


def delete_album(conn, path):
    conn.execute("DELETE FROM albums WHERE path = ?", (path,))
    conn.commit()


# --- Прогресс ---

def save_progress(conn, user_id, path, track_index, position, title='', cover='', finished=0):
    conn.execute(
        """
        INSERT INTO progress (user_id, path, title, cover, track_index, position, finished, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(user_id, path) DO UPDATE SET
            track_index = excluded.track_index,
            position    = excluded.position,
            finished    = excluded.finished,
            title       = CASE WHEN excluded.title <> '' THEN excluded.title ELSE progress.title END,
            cover       = CASE WHEN excluded.cover <> '' THEN excluded.cover ELSE progress.cover END,
            updated_at  = excluded.updated_at
        """,
        (user_id, path, title, cover, track_index, position, finished, now_iso())
    )
    conn.commit()


def get_progress(conn, user_id, path):
    return conn.execute(
        "SELECT * FROM progress WHERE user_id = ? AND path = ?", (user_id, path)
    ).fetchone()


def list_progress(conn, user_id):
    return conn.execute(
        "SELECT * FROM progress WHERE user_id = ? ORDER BY updated_at DESC", (user_id,)
    ).fetchall()


def user_completions(conn, user_id):
    """Все дослушанные главы человека — основа статистики прослушивания."""
    return conn.execute(
        "SELECT path, track_index, completed_at FROM track_completions "
        "WHERE user_id = ? ORDER BY completed_at", (user_id,)
    ).fetchall()


def delete_progress(conn, user_id, path):
    conn.execute("DELETE FROM progress WHERE user_id = ? AND path = ?", (user_id, path))
    conn.commit()


# --- Текстовая версия ---

def create_transcript(conn, path, total_tracks):
    conn.execute(
        """
        INSERT INTO transcripts (path, status, total_tracks, created_at, updated_at)
        VALUES (?, 'pending', ?, ?, ?)
        ON CONFLICT(path) DO UPDATE SET
            status       = 'pending',
            total_tracks = excluded.total_tracks,
            done_tracks  = 0,
            error        = '',
            updated_at   = excluded.updated_at
        """,
        (path, total_tracks, now_iso(), now_iso())
    )
    conn.commit()


def update_transcript(conn, path, **fields):
    allowed = {'status', 'engine', 'language', 'done_tracks', 'total_tracks', 'error'}
    fields = {k: v for k, v in fields.items() if k in allowed}
    if not fields:
        return
    assignments = ', '.join(f'{k} = ?' for k in fields)
    conn.execute(f"UPDATE transcripts SET {assignments}, updated_at = ? WHERE path = ?",
                 (*fields.values(), now_iso(), path))
    conn.commit()


def get_transcript(conn, path):
    return conn.execute("SELECT * FROM transcripts WHERE path = ?", (path,)).fetchone()


def list_transcripts(conn):
    return conn.execute("SELECT * FROM transcripts ORDER BY path").fetchall()


def delete_transcript(conn, path):
    conn.execute("DELETE FROM transcript_claims WHERE path = ?", (path,))
    conn.execute("DELETE FROM transcript_tracks WHERE path = ?", (path,))
    conn.execute("DELETE FROM transcripts WHERE path = ?", (path,))
    conn.commit()


def save_transcript_track(conn, path, track_index, name, segments):
    conn.execute(
        """
        INSERT INTO transcript_tracks (path, track_index, name, data, updated_at)
        VALUES (?, ?, ?, ?, ?)
        ON CONFLICT(path, track_index) DO UPDATE SET
            name       = excluded.name,
            data       = excluded.data,
            updated_at = excluded.updated_at
        """,
        (path, track_index, name, json.dumps(segments, ensure_ascii=False), now_iso())
    )
    conn.commit()


def get_transcript_track(conn, path, track_index):
    row = conn.execute(
        "SELECT * FROM transcript_tracks WHERE path = ? AND track_index = ?",
        (path, track_index)
    ).fetchone()
    if not row:
        return None
    try:
        return {"name": row['name'], "segments": json.loads(row['data'])}
    except (ValueError, TypeError):
        return None


def transcript_track_indexes(conn, path):
    rows = conn.execute(
        "SELECT track_index FROM transcript_tracks WHERE path = ? ORDER BY track_index", (path,)
    ).fetchall()
    return [r['track_index'] for r in rows]


# --- Озвучка книг из документов ---

def create_voiceover(conn, path, document):
    conn.execute(
        """
        INSERT INTO voiceovers (path, status, document, created_at, updated_at)
        VALUES (?, 'pending', ?, ?, ?)
        ON CONFLICT(path) DO UPDATE SET
            status      = 'pending',
            document    = excluded.document,
            done_tracks = 0,
            error       = '',
            updated_at  = excluded.updated_at
        """,
        (path, document, now_iso(), now_iso())
    )
    conn.commit()


def update_voiceover(conn, path, **fields):
    allowed = {'status', 'engine', 'voice', 'document',
               'done_tracks', 'total_tracks', 'error'}
    fields = {k: v for k, v in fields.items() if k in allowed}
    if not fields:
        return
    assignments = ', '.join(f'{k} = ?' for k in fields)
    conn.execute(f"UPDATE voiceovers SET {assignments}, updated_at = ? WHERE path = ?",
                 (*fields.values(), now_iso(), path))
    conn.commit()


def get_voiceover(conn, path):
    return conn.execute("SELECT * FROM voiceovers WHERE path = ?", (path,)).fetchone()


def list_voiceovers(conn):
    return conn.execute("SELECT * FROM voiceovers ORDER BY path").fetchall()


def delete_voiceover(conn, path):
    conn.execute("DELETE FROM voiceover_claims WHERE path = ?", (path,))
    conn.execute("DELETE FROM voiceover_tracks WHERE path = ?", (path,))
    conn.execute("DELETE FROM voiceovers WHERE path = ?", (path,))
    conn.commit()


def save_voiceover_track(conn, path, track_index, name, filename, seconds=0.0):
    conn.execute(
        """
        INSERT INTO voiceover_tracks (path, track_index, name, filename, seconds, updated_at)
        VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT(path, track_index) DO UPDATE SET
            name       = excluded.name,
            filename   = excluded.filename,
            seconds    = excluded.seconds,
            updated_at = excluded.updated_at
        """,
        (path, track_index, name, filename, float(seconds or 0), now_iso())
    )
    conn.commit()


def voiceover_tracks(conn, path):
    return conn.execute(
        "SELECT * FROM voiceover_tracks WHERE path = ? ORDER BY track_index",
        (path,)).fetchall()


def voiceover_track_indexes(conn, path):
    rows = conn.execute(
        "SELECT track_index FROM voiceover_tracks WHERE path = ? ORDER BY track_index",
        (path,)).fetchall()
    return [r['track_index'] for r in rows]


def claim_book(conn, path, worker, stale_before):
    """Бронь на всю книгу. False — её уже озвучивает кто-то другой."""
    conn.execute("DELETE FROM voiceover_claims WHERE claimed_at < ?", (stale_before,))
    try:
        conn.execute("INSERT INTO voiceover_claims (path, worker, claimed_at) "
                     "VALUES (?, ?, ?)", (path, worker, now_iso()))
    except sqlite3.IntegrityError:
        return False
    conn.commit()
    return True


def touch_book_claim(conn, path, worker):
    conn.execute("UPDATE voiceover_claims SET claimed_at = ? WHERE path = ? AND worker = ?",
                 (now_iso(), path, worker))
    conn.commit()


def release_book_claim(conn, path):
    conn.execute("DELETE FROM voiceover_claims WHERE path = ?", (path,))
    conn.commit()


def active_book_claims(conn, stale_before):
    """{path: заявка} по живым броням на озвучку."""
    conn.execute("DELETE FROM voiceover_claims WHERE claimed_at < ?", (stale_before,))
    conn.commit()
    rows = conn.execute("SELECT path, worker, claimed_at FROM voiceover_claims").fetchall()
    return {r['path']: dict(r) for r in rows}


# --- Кэш длительностей ---

def save_album_durations(conn, path, durations):
    total = float(sum(durations))
    conn.execute(
        """
        INSERT INTO albums (path, track_count, total_duration, durations, updated_at)
        VALUES (?, ?, ?, ?, ?)
        ON CONFLICT(path) DO UPDATE SET
            track_count    = excluded.track_count,
            total_duration = excluded.total_duration,
            durations      = excluded.durations,
            updated_at     = excluded.updated_at
        """,
        (path, len(durations), total, json.dumps(durations), now_iso())
    )
    conn.commit()


def get_album_durations(conn, path):
    row = conn.execute("SELECT * FROM albums WHERE path = ?", (path,)).fetchone()
    if not row:
        return None
    try:
        durations = json.loads(row['durations'])
    except (ValueError, TypeError):
        return None
    return {'track_count': row['track_count'], 'total_duration': row['total_duration'], 'durations': durations}


def get_all_album_durations(conn):
    rows = conn.execute("SELECT path, track_count, total_duration, durations FROM albums").fetchall()
    result = {}
    for row in rows:
        try:
            result[row['path']] = {
                'track_count': row['track_count'],
                'total_duration': row['total_duration'],
                'durations': json.loads(row['durations'])
            }
        except (ValueError, TypeError):
            continue
    return result
