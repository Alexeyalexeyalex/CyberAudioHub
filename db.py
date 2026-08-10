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


def delete_progress(conn, user_id, path):
    conn.execute("DELETE FROM progress WHERE user_id = ? AND path = ?", (user_id, path))
    conn.commit()


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
