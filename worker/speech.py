# speech.py — синтез речи: превращаем текст главы в аудиодорожку.
#
# Модель скачивается при первом запуске и дальше берётся из кеша, как и
# Whisper: интернет нужен ровно один раз.
#
# Движков три.
#
#   f5ru   — F5-TTS, дообученный на русском (Misha24-10/F5-TTS_RUSSIAN):
#            5000 часов, из них 4000 — русский датасет и 400 часов русских
#            аудиокниг. Читает по-русски без акцента, понимает ударения и
#            повторяет голос по образцу вместе с его интонацией. Это то,
#            что слышно как живого диктора. Стоит по умолчанию.
#
#   xtts   — XTTS-v2 от Coqui. Многоязычная модель: голос красивый, но
#            русского в обучении было мало, поэтому слышен акцент, а
#            ударения она просто не знает. Оставлен как запасной.
#
#   silero — самый лёгкий: 50 МБ, идёт даже на процессоре, по-русски
#            выговаривает чисто, но ровно — эмоций почти нет.
#
# Движок выбирается в config.ini, разделом [tts].
#
# --- Про ударения ---
#
# Главная причина, по которой русский синтез звучит «неродным», — не тембр,
# а ударения: зАмок или замОк решается только по смыслу фразы. f5ru умеет
# принимать разметку (плюс перед ударной гласной), а расставляет её
# RUAccent — отдельная небольшая модель. Без неё f5ru всё равно лучше
# XTTS, с ней разница слышна сразу.
import os
import re
import shutil
import subprocess
import tempfile
import wave

# XTTS тянет за собой torch, а тот на Windows любит писать предупреждения
# про symlink и число потоков. К делу они отношения не имеют.
os.environ.setdefault('HF_HUB_DISABLE_SYMLINKS_WARNING', '1')

# Модель XTTS-v2 в каталоге Coqui
XTTS_MODEL = 'tts_models/multilingual/multi-dataset/xtts_v2'

# Русский F5-TTS. Веса лежат в одном репозитории несколькими вариантами;
# v2 — та, что рекомендует автор: дообученная и с фильтрацией артефактов.
F5RU_REPO = 'Misha24-10/F5-TTS_RUSSIAN'
F5RU_CHECKPOINTS = {
    'v1': 'F5TTS_v1_Base/model_240000_inference.safetensors',
    'accent': 'F5TTS_v1_Base_accent_tune/model_last_inference.safetensors',
    'v2': 'F5TTS_v1_Base_v2/model_last_inference.safetensors',
    'v4': 'F5TTS_v1_Base_v4_winter/model_212000.safetensors',
}
F5RU_VOCAB = 'F5TTS_v1_Base/vocab.txt'

# Сколько знаков отдаём модели за раз. XTTS заметно теряет интонацию на
# длинных кусках, Silero просто обрывает текст после тысячи символов,
# а F5 держит фразу целиком и звучит на ней ровнее.
MAX_CHARS = {'xtts': 230, 'silero': 800, 'f5ru': 300}

# Паузы между кусками, секунды. Без них речь звучит как один длинный
# выдох: модель не знает, что абзац кончился.
PAUSE_SENTENCE = 0.28
PAUSE_PARAGRAPH = 0.7


class SpeechError(Exception):
    """Синтез не удался."""


# Пакеты, которые ставятся не с PyPI, а с индекса NVIDIA, — вместе с torch
# и строго той же версии. Обычный pip install подтянул бы сборку без CUDA.
TORCH_INDEX = 'https://download.pytorch.org/whl/cu128'


def _import_help(package, root_module, exc):
    """
    Внятное объяснение, почему не поднялся импорт.

    Раньше здесь стояло короткое «не установлен coqui-tts», и оно врало:
    пакет мог стоять, а не хватать чего-то у него внутри. Поэтому сначала
    смотрим, какой именно модуль не нашёлся.
    """
    missing = (getattr(exc, 'name', '') or '').split('.')[0]

    if missing == root_module or not missing:
        return (f'не установлен {package} — выполните '
                'pip install -r requirements.txt')

    if missing in ('torch', 'torchaudio', 'torchvision'):
        return (f'{package} установлен, но ему не хватает «{missing}».\n'
                f'  Исходная ошибка: {exc}\n'
                '  Эти пакеты идут отдельно и строго одной версией друг с другом:\n'
                f'    pip install torch torchaudio --index-url {TORCH_INDEX}')

    return (f'{package} установлен, но не смог запуститься: '
            f'не найден модуль «{missing}».\n'
            f'  Исходная ошибка: {exc}\n'
            '  Попробуйте: pip install -r requirements.txt')


class Accentuator:
    """
    Расстановка ударений через RUAccent.

    Нужна только f5ru: модель обучена на размеченном тексте и без разметки
    угадывает ударение сама, а значит иногда мимо. Если пакета нет или он
    не поднялся, молча работаем без разметки — это хуже, но не смертельно.
    """

    def __init__(self, enabled=True, model='turbo3.1'):
        self.enabled = bool(enabled)
        self.model = model or 'turbo3.1'
        self._engine = None
        self._broken = False

    def prepare(self, log=print):
        if not self.enabled or self._engine is not None or self._broken:
            return self._engine
        try:
            from ruaccent import RUAccent
        except ImportError:
            self._broken = True
            log('RUAccent не установлен — ударения расставляться не будут. '
                'Поставить: pip install ruaccent')
            return None
        try:
            engine = RUAccent()
            engine.load(omograph_model_size=self.model, use_dictionary=True)
        except Exception as exc:
            self._broken = True
            log(f'RUAccent не запустился ({exc}) — работаю без разметки ударений.')
            return None
        self._engine = engine
        log(f'Ударения расставляет RUAccent, модель {self.model}.')
        return engine

    def apply(self, text):
        if self._engine is None:
            return text
        try:
            return self._engine.process_all(text)
        except Exception:
            # Одна споткнувшаяся фраза не повод ронять всю книгу
            return text


# --- Чтение звука в обход torchcodec --------------------------------------
#
# torchaudio, начиная с версии 2.9, декодирует звук только через torchcodec,
# а тому нужны РАЗДЕЛЯЕМЫЕ библиотеки FFmpeg мажорных версий 4-7. Обычная
# установка ffmpeg на Windows — статическая сборка одним exe-файлом, и
# свежая: FFmpeg 9 в неё уже не входит по версии. В итоге torchcodec не
# поднимается, и F5 не может прочитать даже собственный образец голоса.
#
# Чинить это установкой второго FFmpeg ради одной короткой записи —
# несоразмерно. Читаем через soundfile: libsndfile понимает wav, flac,
# ogg и mp3 и никакого FFmpeg не требует.

_audio_loader_patched = False


def _soundfile_load(uri, frame_offset=0, num_frames=-1, normalize=True,
                    channels_first=True, format=None, buffer_size=4096,
                    backend=None):
    """Замена torchaudio.load с той же сигнатурой и порядком осей."""
    import soundfile as sf
    import torch
    data, rate = sf.read(uri, dtype='float32', always_2d=True,
                         start=int(frame_offset or 0),
                         frames=-1 if not num_frames or num_frames < 0
                         else int(num_frames))
    tensor = torch.from_numpy(data.T.copy() if channels_first else data.copy())
    return tensor, rate


def ensure_audio_loader(probe_path, log=print):
    """
    Проверяет, читает ли torchaudio звук, и при неудаче подменяет загрузчик.
    Подмена глобальная и на весь процесс — иначе её пришлось бы протаскивать
    внутрь чужого кода, который зовёт torchaudio.load без параметров.
    """
    global _audio_loader_patched
    if _audio_loader_patched:
        return True
    try:
        import torchaudio
    except ImportError as exc:
        raise SpeechError(_import_help('torchaudio', 'torchaudio', exc))

    try:
        torchaudio.load(probe_path)
        return False  # всё работает штатно, ничего не трогаем
    except Exception as exc:
        if 'torchcodec' not in str(exc).lower():
            raise
    torchaudio.load = _soundfile_load
    _audio_loader_patched = True
    log('torchcodec не поднялся (ему нужны библиотеки FFmpeg 4-7) — '
        'читаю звук через soundfile.')
    return True


def to_wav(source, target, seconds=15, rate=24000):
    """
    Приводит образец голоса к простому моно-WAV и подрезает по длине.
    Формат после этого предсказуем, а F5 всё равно просит короткий отрывок:
    на длинном образце он начинает копировать не голос, а саму запись.
    """
    if not shutil.which('ffmpeg'):
        raise SpeechError('ffmpeg не найден в PATH — без него не подготовить образец')
    command = ['ffmpeg', '-hide_banner', '-loglevel', 'error', '-y',
               '-t', str(seconds), '-i', source,
               '-ac', '1', '-ar', str(rate), target]
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode != 0 or not os.path.isfile(target):
        raise SpeechError('не удалось подготовить образец голоса: '
                          + result.stderr.strip()[:300])
    return target


# --- Разбиение текста -----------------------------------------------------

SENTENCE_END = re.compile(r'(?<=[.!?…])\s+')


def _sentences(paragraph):
    parts = [p.strip() for p in SENTENCE_END.split(paragraph) if p.strip()]
    return parts or ([paragraph.strip()] if paragraph.strip() else [])


def _cut_long(sentence, limit):
    """
    Предложение длиннее лимита режем по запятым и тире, а если и это не
    помогло — по словам. Лучше лишняя пауза, чем оборванная фраза.
    """
    if len(sentence) <= limit:
        return [sentence]
    pieces, current = [], ''
    for part in re.split(r'(?<=[,;:—–])\s+', sentence):
        if len(current) + len(part) + 1 <= limit:
            current = f'{current} {part}'.strip()
            continue
        if current:
            pieces.append(current)
        current = part if len(part) <= limit else ''
        if not current:
            words, line = part.split(), ''
            for word in words:
                if len(line) + len(word) + 1 > limit:
                    pieces.append(line)
                    line = word
                else:
                    line = f'{line} {word}'.strip()
            current = line
    if current:
        pieces.append(current)
    return pieces


def plan_chunks(text, limit):
    """
    [(текст, пауза после), ...] — что именно отдаём модели по очереди.
    Границы абзацев сохраняем: по ним ставится пауза подлиннее.
    """
    chunks = []
    paragraphs = [p.strip() for p in text.split('\n\n') if p.strip()]
    for para_index, paragraph in enumerate(paragraphs):
        flat = ' '.join(paragraph.split())
        sentences = []
        for sentence in _sentences(flat):
            sentences.extend(_cut_long(sentence, limit))

        # Короткие предложения склеиваем: отдельный вызов модели на «Да.»
        # стоит столько же, сколько на целый абзац
        merged = []
        for sentence in sentences:
            if merged and len(merged[-1]) + len(sentence) + 1 <= limit:
                merged[-1] = f'{merged[-1]} {sentence}'
            else:
                merged.append(sentence)

        last_para = para_index == len(paragraphs) - 1
        for i, sentence in enumerate(merged):
            last = i == len(merged) - 1
            pause = (0.0 if last and last_para
                     else PAUSE_PARAGRAPH if last else PAUSE_SENTENCE)
            chunks.append((sentence, pause))
    return chunks


# --- Движки ---------------------------------------------------------------

class VoiceEngine:
    """
    Обёртка над моделью синтеза с ленивой загрузкой: модель поднимается
    в память при первой книге, а не при старте воркера. На машине, которая
    занята только распознаванием, она не займёт ни видеопамяти, ни времени.
    """

    def __init__(self, engine='f5ru', device='cuda', speaker='', speaker_wav='',
                 language='ru', speed=1.0, model_dir='', accept_license=False,
                 sample_rate=0, checkpoint='v2', speaker_text='', accent=True,
                 accent_model='turbo3.1', nfe_step=32, transcribe=None):
        self.engine = (engine or 'f5ru').strip().lower()
        self.device = (device or 'cuda').strip().lower()
        self.speaker = (speaker or '').strip()
        self.speaker_wav = (speaker_wav or '').strip()
        self.speaker_text = (speaker_text or '').strip()
        self.language = (language or 'ru').strip() or 'ru'
        self.speed = float(speed or 1.0)
        self.model_dir = (model_dir or '').strip()
        self.accept_license = bool(accept_license)
        self.checkpoint = (checkpoint or 'v2').strip().lower()
        self.nfe_step = max(8, int(nfe_step or 32))
        self.accentuator = Accentuator(accent, accent_model)
        # Расшифровать образец голоса умеет Whisper, который у воркера уже
        # есть. Передаётся снаружи, чтобы этот модуль не знал про engine.py.
        self.transcribe = transcribe
        self._model = None
        self._sample_rate = int(sample_rate or 0)

    @property
    def name(self):
        if self.engine == 'f5ru':
            voice = os.path.basename(self.speaker_wav) or 'образец не задан'
            return f'f5ru-{self.checkpoint}/{voice}'
        voice = self.speaker or os.path.basename(self.speaker_wav) or 'default'
        return f'{self.engine}/{voice}'

    @property
    def max_chars(self):
        return MAX_CHARS.get(self.engine, 230)

    @property
    def sample_rate(self):
        if self._sample_rate:
            return self._sample_rate
        return 48000 if self.engine == 'silero' else 24000

    # --- Загрузка ---

    def ensure_model(self, log=print):
        if self._model is not None:
            return self._model
        if self.engine == 'silero':
            self._model = self._load_silero(log)
        elif self.engine == 'xtts':
            self._model = self._load_xtts(log)
        elif self.engine == 'f5ru':
            self._model = self._load_f5ru(log)
        else:
            raise SpeechError(f'неизвестный движок озвучки: {self.engine}')
        return self._model

    # --- F5-TTS, дообученный на русском ---

    def _load_f5ru(self, log):
        if not self.accept_license:
            raise SpeechError(
                'F5-TTS_RUSSIAN распространяется по лицензии CC BY-NC 4.0.\n'
                '  Пользоваться можно бесплатно, продавать полученную озвучку — нет.\n'
                '  Если это подходит, откройте config.ini, раздел [tts],\n'
                '  и поставьте accept_license = yes.')

        if not self.speaker_wav:
            raise SpeechError(
                'для f5ru нужен образец голоса: модель не имеет собственного\n'
                '  и повторяет тот, что ей дали, вместе с его интонацией.\n'
                '  Укажите в config.ini, раздел [tts], поле speaker_wav —\n'
                '  путь к чистой записи 6-15 секунд. Подойдёт и кусок любой\n'
                '  аудиокниги из вашей медиатеки: чей голос дадите, тот и будет\n'
                '  читать. Чем живее прочитан образец, тем живее выйдет книга.')
        if not os.path.isfile(self.speaker_wav):
            raise SpeechError(f'файл образца голоса не найден: {self.speaker_wav}')

        self._torch(log)
        try:
            from f5_tts.api import F5TTS
            from huggingface_hub import hf_hub_download
        except ImportError as exc:
            raise SpeechError(_import_help('f5-tts', 'f5_tts', exc))

        # Образец приводим к простому WAV своим ffmpeg: так формат заведомо
        # читается дальше, а заодно отрезается лишняя длина.
        prepared = os.path.join(tempfile.gettempdir(), 'cyberaudio_voice_ref.wav')
        to_wav(self.speaker_wav, prepared)
        ensure_audio_loader(prepared, log)
        self.speaker_wav = prepared

        name = F5RU_CHECKPOINTS.get(self.checkpoint)
        if name is None:
            raise SpeechError(
                f'неизвестная версия модели: {self.checkpoint}. '
                'Доступны: ' + ', '.join(sorted(F5RU_CHECKPOINTS)))

        log(f'Загружаю F5-TTS_RUSSIAN, версия {self.checkpoint} '
            '(около 1.4 ГБ при первом запуске)...')
        try:
            cache = self.model_dir or None
            ckpt = hf_hub_download(F5RU_REPO, name, cache_dir=cache)
            vocab = hf_hub_download(F5RU_REPO, F5RU_VOCAB, cache_dir=cache)
        except Exception as exc:
            raise SpeechError(
                f'не удалось скачать модель: {exc}\n'
                '  Проверьте интернет на этом компьютере. Модель нужна\n'
                '  только при первом запуске, дальше работа идёт офлайн.')

        try:
            model = F5TTS(model='F5TTS_v1_Base', ckpt_file=ckpt,
                          vocab_file=vocab, device=self.device)
        except Exception as exc:
            raise SpeechError(f'F5-TTS не запустился: {exc}')

        self._sample_rate = 24000
        self.accentuator.prepare(log)

        if not self.speaker_text:
            self.speaker_text = self._describe_reference(log)
        log(f'Образец голоса: {os.path.basename(self.speaker_wav)}')
        log(f'F5-TTS готов, устройство: {self.device}.')
        return model

    def _describe_reference(self, log):
        """
        Текст образца. F5 сверяет его с записью, чтобы понять голос; если
        не дать, библиотека скачает ради этого отдельный Whisper. У воркера
        Whisper уже есть, поэтому просим расшифровать его.
        """
        if self.transcribe is None:
            log('Текст образца не задан — F5 расшифрует его сам '
                '(и скачает для этого свою модель).')
            return ''
        try:
            log('Расшифровываю образец голоса своим Whisper...')
            text = self.transcribe(self.speaker_wav)
        except Exception as exc:
            log(f'Расшифровать не вышло ({exc}) — оставляю F5 разбираться самому.')
            return ''
        text = ' '.join((text or '').split())
        if not text:
            return ''
        log(f'Текст образца: {text[:90]}' + ('...' if len(text) > 90 else ''))
        return text

    def _torch(self, log):
        try:
            import torch
        except ImportError as exc:
            raise SpeechError(_import_help('torch', 'torch', exc))
        if self.device.startswith('cuda') and not torch.cuda.is_available():
            log('Видеокарта недоступна, озвучка пойдёт на процессоре '
                '(это в разы медленнее).')
            self.device = 'cpu'
        return torch

    def _load_xtts(self, log):
        if not self.accept_license:
            raise SpeechError(
                'XTTS-v2 распространяется по некоммерческой лицензии Coqui (CPML).\n'
                '  Модель бесплатна для личного использования, но продавать\n'
                '  озвучку, сделанную ею, нельзя.\n'
                '  Если это подходит, откройте config.ini, раздел [tts],\n'
                '  и поставьте accept_license = yes.\n'
                '  Не подходит — там же смените engine на silero.')

        torch = self._torch(log)
        try:
            from TTS.api import TTS
        except ImportError as exc:
            raise SpeechError(_import_help('coqui-tts', 'TTS', exc))

        # Пакет спрашивает согласие с лицензией через консоль. Воркер часто
        # крутится без человека за клавиатурой, поэтому отвечаем заранее —
        # согласие уже дано в config.ini.
        os.environ['COQUI_TOS_AGREED'] = '1'
        if self.model_dir:
            os.environ.setdefault('TTS_HOME', self.model_dir)

        log('Загружаю XTTS-v2 (около 2 ГБ при первом запуске)...')
        try:
            model = TTS(XTTS_MODEL, progress_bar=True).to(self.device)
        except Exception as exc:
            raise SpeechError(f'не удалось поднять XTTS: {exc}')

        available = self._xtts_speakers(model)
        if available:
            if self.speaker not in available:
                if self.speaker:
                    log(f'Голос «{self.speaker}» модель не знает.')
                if not self.speaker_wav:
                    self.speaker = available[0]
                    log(f'Беру голос «{self.speaker}». Список доступных: '
                        + ', '.join(available[:12])
                        + (' и другие' if len(available) > 12 else ''))
            else:
                log(f'Голос: {self.speaker}')
        if self.speaker_wav:
            if not os.path.isfile(self.speaker_wav):
                raise SpeechError(f'файл образца голоса не найден: {self.speaker_wav}')
            log(f'Голос клонируется с образца: {os.path.basename(self.speaker_wav)}')

        rate = getattr(getattr(model, 'synthesizer', None), 'output_sample_rate', 0)
        if rate:
            self._sample_rate = int(rate)
        log(f'XTTS готов, устройство: {self.device}, частота {self.sample_rate} Гц.')
        return model

    @staticmethod
    def _xtts_speakers(model):
        try:
            manager = model.synthesizer.tts_model.speaker_manager
            return sorted(manager.speakers.keys())
        except Exception:
            return []

    def _load_silero(self, log):
        torch = self._torch(log)
        log('Загружаю Silero TTS (около 50 МБ при первом запуске)...')
        try:
            model, _example = torch.hub.load(
                repo_or_dir='snakers4/silero-models', model='silero_tts',
                language='ru', speaker='v4_ru', trust_repo=True)
            model.to(torch.device(self.device))
        except Exception as exc:
            raise SpeechError(f'не удалось поднять Silero: {exc}')

        known = list(getattr(model, 'speakers', []) or
                     ['aidar', 'baya', 'kseniya', 'xenia', 'eugene'])
        if self.speaker not in known:
            self.speaker = 'xenia' if 'xenia' in known else known[0]
            log(f'Беру голос «{self.speaker}». Доступны: ' + ', '.join(known))
        self._sample_rate = 48000
        log(f'Silero готов, устройство: {self.device}.')
        return model

    # --- Синтез ---

    def speak(self, text):
        """Один кусок текста -> массив float32 в диапазоне [-1, 1]."""
        import numpy as np
        model = self.ensure_model()

        if self.engine == 'f5ru':
            # Ударения ставим перед самой генерацией: в сегменты для режима
            # чтения должен уйти обычный текст, без служебных плюсов.
            marked = self.accentuator.apply(text)
            wav, _sr, _spec = model.infer(
                ref_file=self.speaker_wav, ref_text=self.speaker_text,
                gen_text=marked, nfe_step=self.nfe_step, speed=self.speed,
                remove_silence=False,
                show_info=lambda *a, **k: None)
            return np.asarray(wav, dtype='float32')

        if self.engine == 'silero':
            audio = model.apply_tts(text=text, speaker=self.speaker,
                                    sample_rate=self.sample_rate,
                                    put_accent=True, put_yo=True)
            return audio.detach().cpu().numpy().astype('float32')

        options = dict(text=text, language=self.language)
        if self.speaker_wav:
            options['speaker_wav'] = self.speaker_wav
        elif self.speaker:
            options['speaker'] = self.speaker
        if abs(self.speed - 1.0) > 0.01:
            options['speed'] = self.speed
        wav = model.tts(**options)
        return np.asarray(wav, dtype='float32')


# --- Сборка главы ---------------------------------------------------------

def _word_times(text, start, end):
    """
    Слова раскладываем ровно по длине куска. Точных таймингов синтез не
    отдаёт, но кусок — это одно-два предложения, так что подсветка при
    чтении попадает куда надо.
    """
    parts = [w for w in text.split() if w]
    if not parts:
        return []
    span = max(0.05, (end - start) / len(parts))
    return [[part, round(start + i * span, 3), round(start + (i + 1) * span * 0.95, 3)]
            for i, part in enumerate(parts)]


def render_chapter(engine, text, wav_path, on_progress=None):
    """
    Озвучивает главу в WAV и попутно собирает тайминги.

    Пишем сразу в файл, кусок за куском: держать в памяти десять минут
    несжатого звука незачем, а у книги таких глав бывает сотня.

    Возвращает (длительность в секундах, сегменты для текстовой версии).
    """
    import numpy as np

    chunks = plan_chunks(text, engine.max_chars)
    if not chunks:
        raise SpeechError('в главе нет текста')

    rate = engine.sample_rate
    segments = []
    cursor = 0.0

    with wave.open(wav_path, 'wb') as out:
        out.setnchannels(1)
        out.setsampwidth(2)
        out.setframerate(rate)

        for index, (chunk, pause) in enumerate(chunks):
            audio = engine.speak(chunk)
            if audio is None or not len(audio):
                continue
            # Защита от перегрузки: у синтеза иногда проскакивают выбросы,
            # и без ограничения они превратились бы в щелчки
            peak = float(np.max(np.abs(audio))) or 1.0
            if peak > 1.0:
                audio = audio / peak
            out.writeframes((audio * 32767).astype('<i2').tobytes())

            start, cursor = cursor, cursor + len(audio) / rate
            segments.append({"s": round(start, 3), "e": round(cursor, 3),
                             "t": chunk, "w": _word_times(chunk, start, cursor)})

            if pause > 0:
                out.writeframes(np.zeros(int(rate * pause), dtype='<i2').tobytes())
                cursor += pause

            if on_progress:
                on_progress(index + 1, len(chunks), cursor)

    return cursor, segments


def to_mp3(wav_path, mp3_path, bitrate='64k'):
    """
    Перекодируем в mp3 перед отправкой: несжатая глава на десять минут
    весит под сотню мегабайт, и гонять её по сети незачем. ffmpeg на этой
    машине уже нужен для Whisper.
    """
    if not shutil.which('ffmpeg'):
        raise SpeechError('ffmpeg не найден в PATH — без него не собрать mp3')
    command = ['ffmpeg', '-hide_banner', '-loglevel', 'error', '-y',
               '-i', wav_path, '-c:a', 'libmp3lame', '-b:a', bitrate,
               '-ac', '1', mp3_path]
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode != 0 or not os.path.isfile(mp3_path):
        raise SpeechError(f'ffmpeg не смог собрать mp3: {result.stderr.strip()[:300]}')
    return mp3_path
