# config.py — настройки распознавателя.
#
# Читаются из config.ini рядом с main.py. Если файла нет, он создаётся
# при первом запуске со значениями по умолчанию — останется вписать токен.
import configparser
import os

CONFIG_NAME = 'config.ini'
HERE = os.path.dirname(os.path.abspath(__file__))
CONFIG_PATH = os.path.join(HERE, CONFIG_NAME)

DEFAULTS = """; Настройки распознавателя CyberAudio Hub.
; Меняйте под себя и перезапускайте main.py.

[server]
; Адрес компьютера, на котором крутится CyberAudio Hub.
host = 192.168.31.4
port = 2077
; Токен из админки («Текстовые версии книг») или из консоли сервера.
token = ВПИШИТЕ_ТОКЕН

[worker]
; Как эта машина подписывается в очереди — видно в админке.
name = worker
; Пауза между опросами очереди, секунды.
poll_seconds = 10
; Куда складывать скачанное аудио на время работы.
temp_dir = temp
; Какие задания брать:
;   text  — распознавать аудио в текст (Whisper);
;   voice — озвучивать книги из документов (синтез речи).
; Можно оставить что-то одно: лишняя модель тогда не скачается
; и не займёт видеопамять.
jobs = text, voice

[whisper]
; tiny | base | small | medium | large-v3
; large-v3 — самая точная. На видеокарте она быстрее, чем small на процессоре,
; так что уменьшать размер есть смысл только при нехватке видеопамяти.
model = large-v3

; cuda — видеокарта NVIDIA, cpu — процессор.
device = cuda

; Тип вычислений:
;   float16 — для видеокарты, проверенный вариант;
;   int8    — на видеокарте НЕ работает с RTX 50-й серии (Blackwell):
;             CTranslate2 падает с CUBLAS_STATUS_NOT_SUPPORTED.
;             Зато это верный выбор, когда device = cpu.
compute_type = float16

; Код языка, например ru. Пусто — определять автоматически.
language = ru

; Куда качать модели. Пусто — стандартный кеш системы.
model_dir =

; --- Скорость ---
; Сколько кусков записи модель считает разом. Больше — быстрее и прожорливее
; по видеопамяти. 16 подходит картам с 16 ГБ; на 8 ГБ ставьте 8, на 24 — 24.
; 1 отключает пакетный режим.
batch_size = 16

; Ширина поиска. 5 — обычное качество, 1 заметно быстрее и чуть хуже.
beam_size = 5

; Сколько записей обрабатывать параллельно. Для одной видеокарты хватает 1.
num_workers = 1

; Потоков процессора; 0 — по числу ядер. Важно только при device = cpu.
cpu_threads = 0

[tts]
; Чем озвучивать книги.
;
;   f5ru   — F5-TTS, дообученный на русском: 5000 часов, из них 4000 —
;            русский датасет и 400 часов русских аудиокниг. Читает
;            по-русски без акцента, понимает ударения и повторяет голос
;            по образцу вместе с интонацией. Требует speaker_wav.
;   xtts   — XTTS-v2: голос красивый, но модель многоязычная, русского в
;            обучении мало — слышен акцент, ударения не расставляет.
;   silero — самый лёгкий: 50 МБ, идёт даже на процессоре, выговаривает
;            чисто, но ровно, почти без эмоций. Образец не нужен.
engine = f5ru

; Версия весов f5ru: v2 (рекомендуется автором), v4 (новее, но без
; описания), accent (полная разметка ударений), v1 (первая).
checkpoint = v2

; И f5ru, и xtts распространяются по некоммерческим лицензиям
; (CC BY-NC 4.0 и Coqui CPML): пользоваться можно бесплатно, продавать
; полученную озвучку — нельзя. Пока здесь no, воркер за озвучку
; не возьмётся и напишет об этом в консоль. Silero этого не требует.
accept_license = no

; cuda — видеокарта NVIDIA, cpu — процессор.
device = cuda

; Голос для xtts — имя из набора модели; пусто — возьмём первый доступный
; и напечатаем его в консоли вместе со списком остальных.
; Для silero: aidar, baya, kseniya, xenia, eugene.
; Для f5ru это поле не используется — там голос задаётся образцом.
speaker =

; Образец голоса: путь к чистой записи 6-15 секунд (wav или mp3).
; Для f5ru обязателен — своего голоса у модели нет, она повторяет тот,
; что ей дали, вместе с манерой чтения. Подойдёт и кусок любой аудиокниги
; из медиатеки: чей голос дадите, тот и будет читать. Чем живее прочитан
; образец, тем живее выйдет книга.
; Для xtts необязателен: если пусто, берётся голос из набора модели.
speaker_wav =

; Что именно говорят в образце. Пусто — расшифруем сами тем Whisper,
; который уже стоит у воркера. Заполнять вручную стоит, только если
; распознавание отключено (jobs = voice).
speaker_text =

; Ударения. Русский синтез спотыкается не на тембре, а на ударениях:
; зАмок или замОк понятно только по смыслу. Их расставляет RUAccent —
; отдельная небольшая модель. Работает только с f5ru.
accent = yes

; Модель расстановки: turbo3.1 (быстрая, по умолчанию) или big_poetry.
accent_model = turbo3.1

; Сколько шагов делает f5ru на каждый кусок. Больше — чище звук и
; медленнее. 32 — разумная середина, ниже 16 слышны артефакты.
nfe_step = 32

; Язык текста.
language = ru

; Темп речи: 1.0 — как есть, 1.1 чуть быстрее. Только для xtts.
speed = 1.0

; Куда качать модели. Пусто — стандартный кеш системы.
model_dir =

; Примерная длина главы в знаках, если в книге нет заголовков и её
; приходится резать по размеру. 9000 ≈ десять минут звучания.
chapter_chars = 9000

; Битрейт готовых mp3. Речь в моно 64k звучит чисто и весит немного.
bitrate = 64k
"""


class Config:
    def __init__(self, parser):
        self.host = parser.get('server', 'host', fallback='192.168.31.4').strip()
        self.port = parser.getint('server', 'port', fallback=2077)
        self.token = parser.get('server', 'token', fallback='').strip()

        self.name = parser.get('worker', 'name', fallback='worker').strip() or 'worker'
        self.poll_seconds = max(2, parser.getint('worker', 'poll_seconds', fallback=10))
        temp = parser.get('worker', 'temp_dir', fallback='temp').strip() or 'temp'
        self.temp_dir = temp if os.path.isabs(temp) else os.path.join(HERE, temp)
        jobs = parser.get('worker', 'jobs', fallback='text, voice')
        self.jobs = {j.strip().lower() for j in jobs.split(',') if j.strip()}

        self.model = parser.get('whisper', 'model', fallback='large-v3').strip() or 'large-v3'
        self.device = parser.get('whisper', 'device', fallback='cuda').strip() or 'cuda'
        self.compute_type = parser.get('whisper', 'compute_type',
                                       fallback='float16').strip() or 'float16'
        self.language = parser.get('whisper', 'language', fallback='').strip() or None
        model_dir = parser.get('whisper', 'model_dir', fallback='').strip()
        self.model_dir = model_dir or None
        self.batch_size = parser.getint('whisper', 'batch_size', fallback=16)
        self.beam_size = parser.getint('whisper', 'beam_size', fallback=5)
        self.num_workers = parser.getint('whisper', 'num_workers', fallback=1)
        self.cpu_threads = parser.getint('whisper', 'cpu_threads', fallback=0)

        # --- Озвучка ---
        self.tts_engine = parser.get('tts', 'engine', fallback='f5ru').strip().lower() or 'f5ru'
        self.tts_accept = parser.getboolean('tts', 'accept_license', fallback=False)
        self.tts_device = parser.get('tts', 'device', fallback='cuda').strip() or 'cuda'
        self.tts_speaker = parser.get('tts', 'speaker', fallback='').strip()
        self.tts_speaker_wav = parser.get('tts', 'speaker_wav', fallback='').strip()
        self.tts_speaker_text = parser.get('tts', 'speaker_text', fallback='').strip()
        self.tts_checkpoint = parser.get('tts', 'checkpoint', fallback='v2').strip().lower() or 'v2'
        self.tts_accent = parser.getboolean('tts', 'accent', fallback=True)
        self.tts_accent_model = parser.get('tts', 'accent_model',
                                           fallback='turbo3.1').strip() or 'turbo3.1'
        self.nfe_step = parser.getint('tts', 'nfe_step', fallback=32)
        self.tts_language = parser.get('tts', 'language', fallback='ru').strip() or 'ru'
        self.tts_speed = parser.getfloat('tts', 'speed', fallback=1.0)
        self.tts_model_dir = parser.get('tts', 'model_dir', fallback='').strip()
        self.chapter_chars = max(1500, parser.getint('tts', 'chapter_chars',
                                                     fallback=9000))
        self.bitrate = parser.get('tts', 'bitrate', fallback='64k').strip() or '64k'

    @property
    def does_text(self):
        return 'text' in self.jobs

    @property
    def does_voice(self):
        return 'voice' in self.jobs

    @property
    def base_url(self):
        return f'http://{self.host}:{self.port}'

    def problems(self):
        """Список того, что мешает начать работу."""
        issues = []
        if not self.token or self.token == 'ВПИШИТЕ_ТОКЕН':
            issues.append(
                f'не указан токен. Откройте {CONFIG_NAME}, раздел [server], '
                'и впишите токен из админки сервера (раздел «Текстовые версии книг»).')
        if not self.host:
            issues.append('не указан адрес сервера в разделе [server].')
        if not self.jobs:
            issues.append('в разделе [worker] пустое поле jobs — '
                          'укажите text, voice или оба через запятую.')
        unknown = self.jobs - {'text', 'voice'}
        if unknown:
            issues.append('в разделе [worker] непонятные задания: '
                          + ', '.join(sorted(unknown)))
        if self.does_voice and self.tts_engine not in ('f5ru', 'xtts', 'silero'):
            issues.append(f'в разделе [tts] неизвестный движок «{self.tts_engine}». '
                          'Доступны: f5ru, xtts, silero.')
        return issues


def load():
    """Читает config.ini, создавая его при первом запуске."""
    created = False
    if not os.path.exists(CONFIG_PATH):
        with open(CONFIG_PATH, 'w', encoding='utf-8') as f:
            f.write(DEFAULTS)
        created = True

    parser = configparser.ConfigParser()
    parser.read(CONFIG_PATH, encoding='utf-8')
    return Config(parser), created
