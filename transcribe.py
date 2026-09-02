# transcribe.py — хранение текстовой версии книг.
#
# Сам сервер речь НЕ распознаёт и не требует для этого никаких библиотек:
# он лишь ведёт очередь и складывает готовый текст. Распознаванием занимается
# отдельная программа из папки worker/, запущенная на другом компьютере —
# обычно более мощном. Она забирает очередь по сети, скачивает аудио,
# прогоняет через Whisper и присылает результат обратно.
#
# Здесь остаётся только работа с файлами текста, которые лежат рядом
# с аудиофайлами книги, в подпапке text/.
import os
import json


# Имя папки, в которую кладём текст рядом с аудиофайлами книги.
TEXT_FOLDER_NAME = os.environ.get('CYBERAUDIO_TEXT_DIR', 'text')


def text_folder(track_disk_path):
    """Папка с текстом рядом с аудиофайлами этой книги."""
    return os.path.join(os.path.dirname(track_disk_path), TEXT_FOLDER_NAME)


def text_files_for(track_disk_path):
    """(читаемый .txt, служебный .json) для конкретной аудиодорожки."""
    base = os.path.splitext(os.path.basename(track_disk_path))[0]
    folder = text_folder(track_disk_path)
    return os.path.join(folder, base + '.txt'), os.path.join(folder, base + '.json')


def plain_text(segments):
    """Сплошной читаемый текст главы: по абзацу на реплику."""
    lines = []
    for seg in segments:
        # Сегменты хранятся с короткими ключами: t — текст реплики
        line = (seg.get('t') or '').strip()
        if line:
            lines.append(line)
    return '\n\n'.join(lines) + ('\n' if lines else '')


def write_track_files(track_disk_path, name, segments, language='', engine=''):
    """
    Сохраняет главу на диск рядом с аудио: .txt для чтения человеком,
    .json с таймингами слов — из него подсветка восстанавливается,
    даже если база потеряна или книгу перенесли на другой компьютер.
    """
    txt_path, json_path = text_files_for(track_disk_path)
    try:
        os.makedirs(os.path.dirname(txt_path), exist_ok=True)
        with open(txt_path, 'w', encoding='utf-8') as f:
            f.write(plain_text(segments))
        payload = {"track": name, "language": language or '',
                   "engine": engine or '', "segments": segments}
        with open(json_path, 'w', encoding='utf-8') as f:
            json.dump(payload, f, ensure_ascii=False)
        return True
    except OSError as exc:
        # Медиатека может лежать на диске только для чтения — это не повод
        # ронять распознавание: в базе текст всё равно сохранится.
        print(f'[transcribe] не удалось записать текст рядом с аудио: {exc}')
        return False


def read_track_file(track_disk_path):
    """Читает ранее сохранённый .json главы. None, если его нет или он битый."""
    _, json_path = text_files_for(track_disk_path)
    try:
        with open(json_path, encoding='utf-8') as f:
            payload = json.load(f)
    except (OSError, ValueError):
        return None
    segments = payload.get('segments')
    if not isinstance(segments, list):
        return None
    return segments, payload.get('language') or ''


def remove_track_files(tracks):
    """Убирает текст книги с диска, когда админ снимает галочку."""
    folders = set()
    for _name, disk_path in tracks:
        for file_path in text_files_for(disk_path):
            try:
                os.remove(file_path)
            except OSError:
                pass
        folders.add(text_folder(disk_path))
    for folder in folders:
        try:
            os.rmdir(folder)  # только если папка опустела
        except OSError:
            pass
