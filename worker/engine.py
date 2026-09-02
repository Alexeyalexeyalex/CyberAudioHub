# engine.py — распознавание речи через faster-whisper.
#
# Модель скачивается при первом запуске и складывается в кеш. Дальше
# берётся оттуда: интернет нужен ровно один раз на каждую модель.
import os
import sys

# Windows без «режима разработчика» не умеет символические ссылки, и
# huggingface_hub каждый раз честно об этом предупреждает. На работу это не
# влияет — гасим сообщение, чтобы не пугало. Ставить нужно до импорта
# huggingface_hub, поэтому строка стоит здесь, а не внутри функции.
os.environ.setdefault('HF_HUB_DISABLE_SYMLINKS_WARNING', '1')

# Примерный вес моделей — чтобы предупредить перед первой загрузкой.
MODEL_SIZES = {
    'tiny': '~75 МБ',
    'base': '~145 МБ',
    'small': '~480 МБ',
    'medium': '~1.5 ГБ',
    'large-v2': '~3 ГБ',
    'large-v3': '~3 ГБ',
    'large-v3-turbo': '~1.6 ГБ',
}


# Ссылки на добавленные папки DLL держим живыми: если объект соберёт
# сборщик мусора, Windows уберёт папку из поиска.
_dll_dirs = []


def find_cuda_lib_dirs():
    """Папки с библиотеками CUDA, поставленными через pip."""
    try:
        import nvidia
    except ImportError:
        return []

    # nvidia — namespace-пакет без __init__.py, поэтому __file__ у него None,
    # а список папок лежит в __path__.
    roots = [str(x) for x in (getattr(nvidia, '__path__', None) or [])]
    if not roots and getattr(nvidia, '__file__', None):
        roots = [os.path.dirname(nvidia.__file__)]

    found = []
    for root in roots:
        if not os.path.isdir(root):
            continue
        # Перебираем все вложенные пакеты (cublas, cudnn, cuda_runtime и прочие):
        # состав зависит от версии, поэтому не гадаем с именами.
        for sub in sorted(os.listdir(root)):
            for folder in ('bin', 'lib'):
                path = os.path.join(root, sub, folder)
                if os.path.isdir(path) and path not in found:
                    found.append(path)
    return found


def _add_lib_dir(path):
    if os.name == 'nt':
        # add_dll_directory действует только на загрузку через Python, а
        # CTranslate2 подтягивает cublas64_12.dll изнутри нативного кода —
        # обычным поиском по PATH. Поэтому нужно и то, и другое.
        os.environ['PATH'] = path + os.pathsep + os.environ.get('PATH', '')
        try:
            _dll_dirs.append(os.add_dll_directory(path))
        except OSError:
            pass
        return
    # На Linux LD_LIBRARY_PATH задаётся до старта процесса,
    # поэтому подгружаем файлы напрямую.
    import ctypes
    for name in sorted(os.listdir(path)):
        if '.so' in name:
            try:
                ctypes.CDLL(os.path.join(path, name), mode=ctypes.RTLD_GLOBAL)
            except OSError:
                pass


def _libraries_seen(dirs):
    """Какие из нужных библиотек реально лежат в найденных папках."""
    marks = {'cublas': ('cublas64_', 'libcublas'),
             'cudnn': ('cudnn64_', 'libcudnn', 'cudnn_')}
    seen = set()
    for path in dirs:
        try:
            names = os.listdir(path)
        except OSError:
            continue
        for name in names:
            low = name.lower()
            for key, prefixes in marks.items():
                if any(low.startswith(pre) for pre in prefixes):
                    seen.add(key)
    return seen


def prepare_cuda_libs(log=None):
    """
    Делает библиотеки CUDA из виртуального окружения видимыми для CTranslate2.
    Возвращает список подключённых папок.
    """
    dirs = find_cuda_lib_dirs()
    for path in dirs:
        _add_lib_dir(path)

    if log:
        if not dirs:
            log('Библиотеки CUDA из окружения не найдены — '
                'рассчитываю на установленные в системе.')
        else:
            seen = _libraries_seen(dirs)
            missing = [n for n in ('cublas', 'cudnn') if n not in seen]
            log(f'Подключено папок с библиотеками CUDA: {len(dirs)}')
            if missing:
                log('Внимание: среди них нет ' + ' и '.join(missing) + '. '
                    'Если распознавание сорвётся на поиске DLL, выполните:')
                log('  pip install -r requirements.txt')
    return dirs


def cuda_devices():
    """Сколько видеокарт видит CTranslate2. -1 — проверить не удалось."""
    try:
        import ctranslate2
        return ctranslate2.get_cuda_device_count()
    except Exception:
        return -1


def check_device(device, compute_type, log=print):
    """
    Проверяет связку «устройство + тип вычислений» до начала работы.
    Возвращает исправленный compute_type. Останавливает запуск, если
    выбрана видеокарта, а её нет: молча уехать на процессор нельзя —
    large-v3 там работает медленнее реального времени.
    """
    if device != 'cuda':
        return compute_type

    prepare_cuda_libs(log)
    count = cuda_devices()
    if count == 0:
        raise SystemExit(
            'Указано device = cuda, но CTranslate2 не видит ни одной видеокарты.\n'
            '  Проверьте:\n'
            '   - установлен свежий драйвер NVIDIA;\n'
            '   - в окружении стоят nvidia-cublas-cu12 и nvidia-cudnn-cu12\n'
            '     (они есть в requirements.txt);\n'
            '   - команда nvidia-smi показывает карту.\n'
            '  Либо поставьте в config.ini device = cpu.')
    if count > 0:
        log(f'Видеокарт найдено: {count}')

    if 'int8' in compute_type:
        # Известная несовместимость: на RTX 50-й серии (архитектура Blackwell,
        # sm_120) int8 в CTranslate2 падает с CUBLAS_STATUS_NOT_SUPPORTED.
        log(f'compute_type = {compute_type} на видеокарте ненадёжен '
            '(на RTX 50-й серии срывается в CUBLAS_STATUS_NOT_SUPPORTED).')
        log('Беру float16 — он проверен и по скорости почти не уступает.')
        return 'float16'
    return compute_type


def _segment(start, end, text, words):
    """Формат, который ждёт сервер: короткие ключи ради размера JSON."""
    return {"s": round(float(start), 3), "e": round(float(end), 3),
            "t": text.strip(), "w": words}


def _word(text, start, end):
    return [text, round(float(start), 3), round(float(end), 3)]


def _spread_words(text, start, end):
    """
    Запасной вариант, когда модель не вернула тайминги отдельных слов:
    раскладываем слова равномерно по длине реплики. Подсветка будет
    приблизительной, но текст не потеряется.
    """
    parts = [w for w in text.split() if w]
    if not parts:
        return []
    span = max(0.05, (float(end) - float(start)) / len(parts))
    out = []
    for i, part in enumerate(parts):
        ws = float(start) + i * span
        out.append(_word(part, ws, ws + span * 0.9))
    return out


class WhisperEngine:
    """Обёртка над faster-whisper с ленивой загрузкой модели."""

    def __init__(self, model_name, device='cpu', compute_type='int8',
                 language=None, model_dir=None, batch_size=16, beam_size=5,
                 num_workers=1, cpu_threads=0):
        self.model_name = model_name
        self.device = device
        self.compute_type = compute_type
        self.language = language
        self.model_dir = model_dir
        # Пакетная обработка: модель считает несколько кусков записи разом
        # и заметно полнее загружает видеокарту.
        self.batch_size = max(1, int(batch_size or 1))
        self.beam_size = max(1, int(beam_size or 1))
        self.num_workers = max(1, int(num_workers or 1))
        self.cpu_threads = max(0, int(cpu_threads or 0))
        self._model = None
        self._pipeline = None

    @property
    def name(self):
        return f'faster-whisper/{self.model_name}'

    def is_model_cached(self):
        """
        Есть ли модель уже на диске. Точного API у библиотеки нет, поэтому
        смотрим в кеш huggingface — там модели лежат папками по имени.
        """
        roots = []
        if self.model_dir:
            roots.append(self.model_dir)
        roots.append(os.path.join(
            os.path.expanduser('~'), '.cache', 'huggingface', 'hub'))
        needle = self.model_name.replace('.', '').lower()
        for root in roots:
            if not os.path.isdir(root):
                continue
            for entry in os.listdir(root):
                low = entry.lower()
                if 'whisper' in low and needle in low.replace('.', ''):
                    return True
                # model_dir может содержать саму модель без папки-обёртки
                if entry == 'model.bin':
                    return True
        return False

    def ensure_model(self, log=print):
        """Загружает модель в память, при необходимости скачав её."""
        if self._model is not None:
            return self._model

        try:
            from faster_whisper import WhisperModel
        except ImportError:
            raise SystemExit(
                'Не установлен faster-whisper.\n'
                '  Активируйте виртуальное окружение и выполните:\n'
                '    pip install -r requirements.txt')

        self.compute_type = check_device(self.device, self.compute_type, log)

        cached = self.is_model_cached()
        if cached:
            log(f'Модель {self.model_name} уже загружена, беру с диска.')
        else:
            size = MODEL_SIZES.get(self.model_name, 'размер неизвестен')
            log(f'Модель {self.model_name} ещё не скачана ({size}).')
            log('Качаю — при первом запуске это занимает несколько минут...')

        try:
            self._model = WhisperModel(
                self.model_name, device=self.device,
                compute_type=self.compute_type, download_root=self.model_dir,
                num_workers=self.num_workers, cpu_threads=self.cpu_threads)
        except Exception as exc:
            text = str(exc).lower()
            if 'cudnn' in text or 'cublas' in text or 'cuda' in text:
                raise SystemExit(
                    f'Видеокарта не запустилась: {exc}\n'
                    '  Чаще всего помогает:\n'
                    '   - pip install -r requirements.txt (ставит cuBLAS и cuDNN);\n'
                    '   - обновить драйвер NVIDIA — нужны CUDA 12 и cuDNN 9;\n'
                    '   - compute_type = float16 в config.ini.\n'
                    '  Либо переключитесь на device = cpu.')
            if not cached:
                raise SystemExit(
                    f'Не удалось скачать модель {self.model_name}: {exc}\n'
                    '  Проверьте интернет на этом компьютере. Модель нужна\n'
                    '  только при первом запуске, дальше работа идёт офлайн.')
            raise

        if not cached:
            log('Модель загружена. Следующие запуски будут быстрыми.')

        self._pipeline = self._build_pipeline(self._model, log)
        return self._model

    def _build_pipeline(self, model, log=print):
        """Пакетный режим faster-whisper; None — если версия его не умеет."""
        if self.batch_size <= 1:
            return None
        try:
            from faster_whisper import BatchedInferencePipeline
            pipeline = BatchedInferencePipeline(model=model)
            log(f'Пакетная обработка включена, размер пакета {self.batch_size}.')
            return pipeline
        except Exception:
            log('Пакетная обработка недоступна в этой версии faster-whisper — '
                'работаю обычным способом.')
            return None

    def transcribe(self, audio_path, on_progress=None):
        """[(сегменты, язык)] — текст файла с привязкой слов ко времени."""
        model = self.ensure_model()
        options = dict(language=self.language, word_timestamps=True,
                       vad_filter=True, beam_size=self.beam_size)

        if self._pipeline is not None:
            try:
                segments, info = self._pipeline.transcribe(
                    audio_path, batch_size=self.batch_size, **options)
            except TypeError:
                # Набор параметров у пакетного режима меняется между версиями:
                # если не подошёл, откатываемся на обычный вызов.
                self._pipeline = None
                segments, info = model.transcribe(audio_path, **options)
        else:
            segments, info = model.transcribe(audio_path, **options)

        out = []
        for seg in segments:
            words = []
            for w in (getattr(seg, 'words', None) or []):
                token = (w.word or '').strip()
                if token:
                    words.append(_word(token, w.start, w.end))
            text = (seg.text or '').strip()
            if not text:
                continue
            if not words:
                words = _spread_words(text, seg.start, seg.end)
            out.append(_segment(seg.start, seg.end, text, words))
            if on_progress:
                on_progress(float(seg.end))

        return out, (getattr(info, 'language', '') or self.language or '')


def check_ffmpeg():
    """faster-whisper читает mp3/m4a через ffmpeg — предупреждаем заранее."""
    from shutil import which
    if which('ffmpeg'):
        return True
    print('ВНИМАНИЕ: ffmpeg не найден в PATH.', file=sys.stderr)
    print('  Без него не читаются mp3, m4a и другие сжатые форматы.',
          file=sys.stderr)
    print('  Windows: winget install Gyan.FFmpeg', file=sys.stderr)
    print('  Linux:   sudo apt install ffmpeg', file=sys.stderr)
    print('  macOS:   brew install ffmpeg', file=sys.stderr)
    return False
