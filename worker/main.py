#!/usr/bin/env python3
# main.py — рабочая лошадка CyberAudio Hub.
#
# Запускается на отдельном (обычно более мощном) компьютере и делает две
# работы, обе по очереди с сервера:
#
#   text  — распознаёт аудиокниги в текст (Whisper);
#   voice — озвучивает книги из документов, pdf/docx/txt/fb2/epub (синтез речи).
#
# Что именно брать, задаётся полем jobs в config.ini. Модель каждого вида
# скачивается при первом же задании такого рода — если озвучка выключена,
# синтезатор не скачается вовсе.
#
# Установка:
#   python -m venv .venv
#   .venv\Scripts\activate        (Windows)
#   source .venv/bin/activate     (Linux/macOS)
#   pip install -r requirements.txt
#   python main.py
#
# При первом запуске создаётся config.ini — впишите в него токен из админки.
import os
import shutil
import sys
import threading
import time
from datetime import datetime

import config as config_module
import documents
import speech
from client import Client, ServerError
from engine import WhisperEngine, check_ffmpeg


def log(message):
    print(f'[{datetime.now().strftime("%H:%M:%S")}] {message}', flush=True)


# Признаки того, что сломано окружение, а не конкретный файл: повторять
# такую главу бессмысленно — будет тот же обрыв на каждом круге.
FATAL_MARKERS = ('cannot be loaded', 'is not found', 'cublas', 'cudnn',
                 'cuda', 'out of memory', 'no kernel image')


def looks_fatal(message):
    low = str(message).lower()
    return any(mark in low for mark in FATAL_MARKERS)


def human_time(seconds):
    seconds = int(seconds)
    if seconds < 60:
        return f'{seconds} с'
    minutes, sec = divmod(seconds, 60)
    if minutes < 60:
        return f'{minutes} мин {sec:02d} с'
    hours, minutes = divmod(minutes, 60)
    return f'{hours} ч {minutes:02d} мин'


class Heartbeat:
    """
    Пока идёт долгая работа, продлевает бронь. Иначе сервер решит, что
    воркер умер, и отдаст задание другому.

    Что именно продлевать, решает переданная функция: у распознавания
    бронь на главу, у озвучки — на книгу целиком.
    """

    def __init__(self, beat, period=60):
        self.beat = beat
        self.period = period
        self._stop = threading.Event()
        self._thread = None

    def __enter__(self):
        self._thread = threading.Thread(target=self._loop, daemon=True)
        self._thread.start()
        return self

    def __exit__(self, *exc):
        self._stop.set()
        if self._thread:
            self._thread.join(timeout=2)
        return False

    def _loop(self):
        while not self._stop.wait(self.period):
            self.beat()


class Fatal(Exception):
    """Сломано окружение — крутиться дальше нет смысла."""


# ==========================================================================
# Распознавание: аудио -> текст
# ==========================================================================

def process_track(client, engine, cfg, book, track):
    """Одна глава: забронировать, скачать, распознать, отправить."""
    path, index, name = book['path'], track['index'], track['name']
    title = book.get('title') or path

    if not client.claim(path, index, cfg.name):
        log(f'   главу «{name}» уже взял другой распознаватель, пропускаю')
        return 'skip'

    os.makedirs(cfg.temp_dir, exist_ok=True)
    suffix = os.path.splitext(name)[1] or '.audio'
    local = os.path.join(cfg.temp_dir, f'track_{index}{suffix}')

    try:
        log(f'   скачиваю «{name}»')
        client.download_audio(path, index, local)
        size_mb = os.path.getsize(local) / (1024 * 1024)
        log(f'   распознаю ({size_mb:.1f} МБ), это может занять время...')

        started = time.time()
        with Heartbeat(lambda: client.heartbeat(path, index, cfg.name)):
            segments, language = engine.transcribe(local)
        spent = time.time() - started

        words = sum(len(s.get('w') or []) for s in segments)
        log(f'   готово за {human_time(spent)}: {len(segments)} реплик, {words} слов')

        client.send_result(path, index, segments, language, engine.name)
        log(f'   отправлено на сервер — «{title}», глава «{name}»')
        return 'ok'

    except ServerError as exc:
        log(f'   ОШИБКА сервера: {exc}')
        client.report_error(path, index, str(exc))
        return 'failed'
    except Exception as exc:  # распознавание могло упасть на битом файле
        log(f'   ОШИБКА распознавания: {exc}')
        client.report_error(path, index, f'{type(exc).__name__}: {exc}')
        return 'fatal' if looks_fatal(exc) else 'failed'
    finally:
        try:
            os.remove(local)
        except OSError:
            pass


def run_text_once(client, engine, cfg, skip):
    """
    Один проход по очереди распознавания. skip — главы, на которых мы уже
    спотыкались: иначе воркер по кругу качал бы один и тот же файл и падал
    на нём же.
    """
    books = client.queue()
    free = [(b, t) for b in books for t in b['pending']
            if not t['taken_by'] and (b['path'], t['index']) not in skip]
    if not free:
        return 0

    total = sum(len(b['pending']) for b in books)
    log(f'В очереди на распознавание {total} глав(ы), свободных {len(free)}'
        + (f', отложено после ошибок {len(skip)}' if skip else ''))

    handled = 0
    for book, track in free:
        log(f'-> «{book.get("title") or book["path"]}», глава {track["index"] + 1}')
        result = process_track(client, engine, cfg, book, track)
        if result == 'ok':
            handled += 1
        elif result == 'fatal':
            raise Fatal(f'{book.get("title") or book["path"]}, '
                        f'глава {track["index"] + 1}')
        elif result == 'failed':
            # Битый файл не должен останавливать всю очередь: помечаем
            # и идём дальше, к следующей главе.
            skip.add((book['path'], track['index']))
    return handled


# ==========================================================================
# Озвучка: документ -> аудио
# ==========================================================================

def voice_chapter(client, voice, cfg, path, index, title, body, temp_dir):
    """Одна глава: синтезировать, сжать, отправить вместе с текстом."""
    wav_path = os.path.join(temp_dir, f'chapter_{index}.wav')
    mp3_path = os.path.join(temp_dir, f'chapter_{index}.mp3')

    def progress(done, total, seconds):
        # Каждый кусок — одно-два предложения, их в главе бывают сотни.
        # Сообщаем раз в полсотни, иначе консоль превратится в кашу.
        if done % 50 == 0:
            log(f'      {done} из {total} фрагментов, {human_time(seconds)} звука')

    try:
        started = time.time()
        seconds, segments = speech.render_chapter(voice, body, wav_path, progress)
        speech.to_mp3(wav_path, mp3_path, cfg.bitrate)
        size_mb = os.path.getsize(mp3_path) / (1024 * 1024)
        log(f'   «{title}»: {human_time(seconds)} звука за '
            f'{human_time(time.time() - started)}, {size_mb:.1f} МБ')

        client.upload_voice_track(path, index, title, mp3_path, seconds, 'mp3')
        # Текст отправляем следом: тайминги при синтезе известны точно,
        # поэтому у сгенерированной книги сразу работает режим чтения.
        client.upload_voice_text(path, index, title, segments, voice.name)
    finally:
        for temp in (wav_path, mp3_path):
            try:
                os.remove(temp)
            except OSError:
                pass


def process_book(client, voice, cfg, book):
    """Одна книга целиком: забронировать, разобрать документ, озвучить."""
    path = book['path']
    title = book.get('title') or path

    if not client.voice_claim(path, cfg.name):
        log(f'   книгу «{title}» уже озвучивает другой воркер, пропускаю')
        return 'skip'

    temp_dir = os.path.join(cfg.temp_dir, 'voice')
    os.makedirs(temp_dir, exist_ok=True)
    document = book.get('document') or 'book.txt'
    local = os.path.join(temp_dir, 'source' + os.path.splitext(document)[1].lower())

    try:
        with Heartbeat(lambda: client.voice_heartbeat(path, cfg.name)):
            log(f'   скачиваю документ «{document}»')
            client.download_document(path, local)

            text = documents.read_document(local)
            chapters = documents.split_chapters(text, cfg.chapter_chars)
            log(f'   разобрано: {len(text):,} знаков, {len(chapters)} глав(ы)'
                .replace(',', ' '))
            client.voice_plan(path, len(chapters), voice.name, voice.speaker)

            # Модель поднимаем только теперь: если документ не прочитался,
            # незачем было ждать загрузку синтезатора
            voice.ensure_model(log=lambda m: log(f'   {m}'))

            done = set(book.get('done') or [])
            if done:
                log(f'   уже озвучено ранее: {len(done)} глав(ы), продолжаю')

            for index, (chapter_title, body) in enumerate(chapters):
                if index in done:
                    continue
                log(f'   глава {index + 1} из {len(chapters)}')
                voice_chapter(client, voice, cfg, path, index,
                              chapter_title, body, temp_dir)

            client.voice_done(path)
            log(f'   книга «{title}» озвучена целиком')
        return 'ok'

    except speech.SpeechError as exc:
        # Не про эту книгу, а про настройку машины: лицензия, модель,
        # видеопамять. Пробовать следующую книгу бессмысленно.
        log(f'   ОЗВУЧКА НЕ ЗАПУСТИЛАСЬ: {exc}')
        client.voice_error(path, str(exc))
        return 'stop'
    except documents.DocumentError as exc:
        log(f'   ОШИБКА разбора документа: {exc}')
        client.voice_error(path, str(exc))
        return 'failed'
    except ServerError as exc:
        log(f'   ОШИБКА сервера: {exc}')
        client.voice_error(path, str(exc))
        return 'failed'
    except Exception as exc:
        log(f'   ОШИБКА озвучки: {exc}')
        client.voice_error(path, f'{type(exc).__name__}: {exc}')
        return 'stop' if looks_fatal(exc) else 'failed'
    finally:
        shutil.rmtree(temp_dir, ignore_errors=True)


def run_voice_once(client, voice, cfg, skip):
    """
    Один проход по очереди озвучки. За круг берём одну книгу: она делается
    часами, и держать очередь занятой дольше нужного незачем.
    Возвращает (сколько сделано, продолжать ли озвучивать вообще).
    """
    books = [b for b in client.voice_queue()
             if not b['taken_by'] and b['path'] not in skip]
    if not books:
        return 0, True

    book = books[0]
    log(f'-> озвучиваю «{book.get("title") or book["path"]}» '
        f'({len(books)} в очереди)')
    result = process_book(client, voice, cfg, book)
    if result == 'ok':
        return 1, True
    if result == 'stop':
        return 0, False
    if result == 'failed':
        skip.add(book['path'])
    return 0, True


# ==========================================================================

def main():
    print('=' * 62)
    print(' CyberAudio Hub — распознавание и озвучка'.center(62))
    print('=' * 62)

    cfg, created = config_module.load()
    if created:
        log(f'Создан файл настроек: {config_module.CONFIG_PATH}')
        print()
        print('Впишите в него адрес сервера и токен, затем запустите main.py снова.')
        print('Токен показан в админке сервера, раздел «Текстовые версии книг»,')
        print('а также печатается в консоли сервера при запуске.')
        return 1

    problems = cfg.problems()
    if problems:
        for item in problems:
            log(f'НАСТРОЙКА: {item}')
        return 1

    check_ffmpeg()

    log(f'Сервер: {cfg.base_url}')
    log(f'Имя воркера: {cfg.name}')
    log('Задания: ' + ', '.join(sorted(cfg.jobs)))
    if cfg.does_text:
        log(f'Распознавание: {cfg.model} на {cfg.device}')
    if cfg.does_voice:
        log(f'Озвучка: {cfg.tts_engine} на {cfg.tts_device}')

    engine = WhisperEngine(cfg.model, cfg.device, cfg.compute_type,
                           cfg.language, cfg.model_dir,
                           batch_size=cfg.batch_size, beam_size=cfg.beam_size,
                           num_workers=cfg.num_workers,
                           cpu_threads=cfg.cpu_threads)
    # Образец голоса для f5ru нужно расшифровать. Whisper для этого уже
    # стоит — но только если воркер и распознаванием занят: качать три
    # гигабайта ради одной короткой записи было бы расточительно.
    def transcribe_sample(path):
        segments, _language = engine.transcribe(path)
        return ' '.join((s.get('t') or '') for s in segments)

    voice = speech.VoiceEngine(
        cfg.tts_engine, cfg.tts_device, cfg.tts_speaker, cfg.tts_speaker_wav,
        cfg.tts_language, cfg.tts_speed, cfg.tts_model_dir, cfg.tts_accept,
        checkpoint=cfg.tts_checkpoint, speaker_text=cfg.tts_speaker_text,
        accent=cfg.tts_accent, accent_model=cfg.tts_accent_model,
        nfe_step=cfg.nfe_step,
        transcribe=transcribe_sample if cfg.does_text else None)
    client = Client(cfg.base_url, cfg.token)

    # Проверяем связь до загрузки моделей: незачем ждать скачивание,
    # если адрес или токен указаны неверно.
    try:
        client.queue()
        log('Связь с сервером есть.')
    except ServerError as exc:
        log(f'НЕ УДАЛОСЬ подключиться: {exc}')
        if getattr(exc, 'status', None) == 403:
            log('Токен не подошёл. Сверьте его с админкой сервера.')
        else:
            log(f'Проверьте, что сервер запущен и доступен по адресу {cfg.base_url}')
        return 1

    # Whisper поднимаем сразу: он нужен на каждой главе, и лучше подождать
    # скачивание один раз на старте. Синтезатор — наоборот, лениво: у него
    # своя лицензия и своя видеопамять, а книг на озвучку может не быть вовсе.
    if cfg.does_text:
        engine.ensure_model(log=lambda m: log(m))

    log('Жду задания. Остановить — Ctrl+C.')
    idle_notified = False
    skip_tracks = set()
    skip_books = set()
    voicing = cfg.does_voice
    try:
        while True:
            try:
                handled = 0
                if cfg.does_text:
                    handled += run_text_once(client, engine, cfg, skip_tracks)
                if voicing:
                    done, keep = run_voice_once(client, voice, cfg, skip_books)
                    handled += done
                    if not keep:
                        voicing = False
                        log('Озвучка отключена до перезапуска — '
                            'разберитесь с сообщением выше.')
                        if not cfg.does_text:
                            return 1

                if handled:
                    idle_notified = False
                    continue  # сразу проверяем, не появилось ли ещё
                if not idle_notified:
                    waiting = len(skip_tracks) + len(skip_books)
                    log('Очередь пуста, жду новых книг...' if not waiting else
                        f'Свободных заданий нет; отложено после ошибок: {waiting}.')
                    idle_notified = True
            except ServerError as exc:
                log(f'Сервер недоступен ({exc}), повтор через {cfg.poll_seconds} с')
            time.sleep(cfg.poll_seconds)
    except Fatal as exc:
        print()
        log(f'ОСТАНОВЛЕНО: распознавание сорвалось на «{exc}».')
        log('Похоже, дело не в файле, а в настройке видеокарты.')
        log('Что попробовать по порядку:')
        log('  1) pip install -r requirements.txt — доставит cuBLAS и cuDNN;')
        log('  2) обновить драйвер NVIDIA (нужны CUDA 12 и cuDNN 9);')
        log('  3) при нехватке видеопамяти уменьшить batch_size в config.ini;')
        log('  4) как временный выход — device = cpu в config.ini.')
        return 1
    except KeyboardInterrupt:
        print()
        log('Остановлено.')
    finally:
        shutil.rmtree(cfg.temp_dir, ignore_errors=True)
    return 0


if __name__ == '__main__':
    sys.exit(main())
