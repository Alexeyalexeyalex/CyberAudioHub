#!/usr/bin/env python3
# import_achievements.py — загрузка достижений из папок с картинками.
#
# Раскладка, из которой читаем:
#
#   static/assets/Достижения/01. Ведьмак/1. Последнее желание/1Гость из Ривии.png
#                            ^^^^^^^^^^^  ^^^^^^^^^^^^^^^^^^^  ^^^^^^^^^^^^^^^^^
#                            серия        книга                номер главы + название
#
# Из имени файла берётся число (номер главы) и остаток (название достижения).
# Книга ищется в медиатеке по имени папки, редкость ставится обычная.
# Уже существующие записи пропускаются: скрипт можно запускать повторно.
#
# Запуск из папки проекта:
#   python import_achievements.py                 # посмотреть и загрузить
#   python import_achievements.py --dry-run       # только показать, что будет
#   python import_achievements.py --rarity rare   # другая редкость
import argparse
import os
import re
import sqlite3
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_DIR = os.path.join(HERE, 'static', 'assets', 'Достижения')
DEFAULT_DB = os.path.join(HERE, 'data', 'cyberaudio.db')
DEFAULT_MUSIC = os.path.join(HERE, 'static', 'music')

IMAGE_EXTENSIONS = ('.png', '.jpg', '.jpeg', '.webp', '.gif')
AUDIO_EXTENSIONS = ('.mp3', '.ogg', '.wav', '.m4a', '.flac', '.opus', '.aac')
RARITIES = ('common', 'rare', 'mythic', 'legendary')

# «1Гость из Ривии», «1. Гость из Ривии», «01 - Гость из Ривии»
NAME_PATTERN = re.compile(r'^\s*(\d+)\s*[.\-–—)_]*\s*(.+)$')


def normalize(name):
    """
    Приводит название к виду, по которому сравниваем папки с книгами:
    без ведущей нумерации, без подчёркиваний, без регистра.
    «01. Последнее желание» и «Последнее_желание» должны совпасть.
    """
    text = re.sub(r'^\s*\d+\s*[.\-–—)_]*\s*', '', name)
    text = text.replace('_', ' ').replace('ё', 'е')
    return ' '.join(text.split()).casefold()


def library_roots(conn):
    """Все папки медиатеки: основная плюс подключённые в админке."""
    roots = []
    try:
        row = conn.execute("SELECT value FROM meta WHERE key = 'main_root'").fetchone()
        roots.append(row[0] if row else DEFAULT_MUSIC)
    except sqlite3.Error:
        roots.append(DEFAULT_MUSIC)
    try:
        for row in conn.execute("SELECT path FROM library_roots"):
            roots.append(row[0])
    except sqlite3.Error:
        pass
    return [r for r in roots if r and os.path.isdir(r)]


def find_albums(conn):
    """{нормализованное имя книги: путь в медиатеке}."""
    albums = {}
    for root in library_roots(conn):
        for current, _dirs, files in os.walk(root):
            if not any(f.lower().endswith(AUDIO_EXTENSIONS) for f in files):
                continue
            rel = os.path.relpath(current, root).replace('\\', '/')
            if rel == '.':
                continue
            key = normalize(os.path.basename(rel))
            # Первое совпадение выигрывает: одинаковых имён быть не должно,
            # а если есть — предупредим ниже
            albums.setdefault(key, rel)
    return albums


def scan_images(base_dir):
    """Находит картинки достижений на любой глубине под base_dir."""
    found = []
    for current, _dirs, files in os.walk(base_dir):
        for name in sorted(files):
            if not name.lower().endswith(IMAGE_EXTENSIONS):
                continue
            found.append((current, name))
    return found


def parse_name(filename):
    """'1Гость из Ривии.png' -> (1, 'Гость из Ривии'). None, если не разобрали."""
    stem = os.path.splitext(filename)[0]
    match = NAME_PATTERN.match(stem)
    if not match:
        return None
    chapter = int(match.group(1))
    title = match.group(2).strip(' .-–—_')
    if not title or chapter < 1:
        return None
    return chapter, title


def web_path(disk_path):
    """Путь на диске -> адрес вида /static/assets/Достижения/..."""
    rel = os.path.relpath(disk_path, HERE).replace('\\', '/')
    return '/' + rel.lstrip('/')


def existing_keys(conn):
    """Что уже есть в базе: по картинке и по паре «название + книга»."""
    images, pairs = set(), set()
    for row in conn.execute("SELECT title, image, target_path FROM achievements"):
        if row[1]:
            images.add(row[1])
        pairs.add((row[0].strip().casefold(), (row[2] or '').strip()))
    return images, pairs


def main():
    parser = argparse.ArgumentParser(
        description='Загружает достижения из папок с картинками в базу CyberAudio Hub.')
    parser.add_argument('--dir', default=DEFAULT_DIR, help='папка с картинками достижений')
    parser.add_argument('--db', default=DEFAULT_DB, help='файл базы данных')
    parser.add_argument('--rarity', default='common', choices=RARITIES,
                        help='редкость для новых записей')
    parser.add_argument('--dry-run', action='store_true',
                        help='показать, что будет добавлено, и ничего не менять')
    parser.add_argument('--allow-missing', action='store_true',
                        help='добавлять и те, для которых книга не нашлась в медиатеке')
    args = parser.parse_args()

    if not os.path.isdir(args.dir):
        print(f'Нет папки с картинками: {args.dir}')
        return 1
    if not os.path.isfile(args.db):
        print(f'Нет базы: {args.db}\n'
              '  Запустите приложение хотя бы раз, чтобы она появилась.')
        return 1

    conn = sqlite3.connect(args.db)
    try:
        albums = find_albums(conn)
        images, pairs = existing_keys(conn)

        added = skipped = unmatched = broken = 0
        misses = []

        for folder, filename in scan_images(args.dir):
            parsed = parse_name(filename)
            if not parsed:
                print(f'  ? не разобрал имя: {filename}')
                broken += 1
                continue
            chapter, title = parsed

            book_folder = os.path.basename(folder)
            target_path = albums.get(normalize(book_folder), '')
            if not target_path and not args.allow_missing:
                misses.append(f'{book_folder} / {filename}')
                unmatched += 1
                continue

            image = web_path(os.path.join(folder, filename))
            description = f'Прослушать {chapter} главу'
            key = (title.strip().casefold(), target_path)

            if image in images or key in pairs:
                skipped += 1
                continue

            print(f'  + {title} — {description} — {target_path or "книга не найдена"}')
            if not args.dry_run:
                conn.execute(
                    "INSERT INTO achievements (title, image, description, target_path, "
                    "target_track, target_tracks, rarity, created_at) "
                    "VALUES (?, ?, ?, ?, NULL, ?, ?, datetime('now'))",
                    (title, image, description, target_path, str(chapter - 1), args.rarity))
            images.add(image)
            pairs.add(key)
            added += 1

        if not args.dry_run:
            conn.commit()

        print()
        print(f'Добавлено: {added}')
        print(f'Уже было, пропущено: {skipped}')
        if broken:
            print(f'Не разобрано имён: {broken}')
        if unmatched:
            print(f'Книга не найдена в медиатеке: {unmatched}')
            for line in misses[:10]:
                print(f'   {line}')
            if len(misses) > 10:
                print(f'   ...и ещё {len(misses) - 10}')
            print('  Проверьте, что папка достижения называется как книга в медиатеке,')
            print('  либо запустите с --allow-missing, чтобы добавить их без привязки.')
        if args.dry_run:
            print('\nЭто был пробный запуск, база не изменялась.')
        return 0
    finally:
        conn.close()


if __name__ == '__main__':
    sys.exit(main())
