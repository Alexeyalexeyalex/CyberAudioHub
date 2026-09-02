# backend.py — работа с сервером и локальным хранилищем.
#
# Здесь нет ничего от интерфейса: только запросы к CyberAudio Hub,
# скачивание книг и упаковка их в собственный контейнер .cah.
import hashlib
import json
import os
import urllib.error
import urllib.parse
import urllib.request

APP_NAME = 'CyberAudio Hub'
CONTAINER_EXT = '.cah'
CONTAINER_MAGIC = b'CAH1'


class ApiError(Exception):
    """Сервер ответил ошибкой или не ответил вовсе."""


class Api:
    """
    Клиент сервера. Сессия держится на печенье, как в браузере:
    отдельного протокола для приложения на сервере нет.
    """

    def __init__(self, base_url=''):
        self.base_url = (base_url or '').rstrip('/')
        self.jar = urllib.request.HTTPCookieProcessor()
        self.opener = urllib.request.build_opener(self.jar)
        self.user = None

    # --- Низкий уровень ---

    def _request(self, method, path, payload=None, timeout=30):
        if not self.base_url:
            raise ApiError('Не указан адрес сервера')
        data = json.dumps(payload).encode('utf-8') if payload is not None else None
        req = urllib.request.Request(self.base_url + path, data=data, method=method)
        if data is not None:
            req.add_header('Content-Type', 'application/json')
        try:
            with self.opener.open(req, timeout=timeout) as resp:
                body = resp.read().decode('utf-8')
                return json.loads(body) if body else {}
        except urllib.error.HTTPError as exc:
            raw = exc.read().decode('utf-8', 'replace')
            try:
                message = json.loads(raw).get('error') or f'Ошибка {exc.code}'
            except ValueError:
                message = f'Ошибка {exc.code}'
            raise ApiError(message)
        except urllib.error.URLError as exc:
            raise ApiError(f'Нет связи с сервером: {exc.reason}')
        except (TimeoutError, OSError) as exc:
            raise ApiError(f'Сервер не ответил: {exc}')

    # --- Учётная запись ---

    def login(self, login, password):
        data = self._request('POST', '/api/auth/login',
                             {'login': login, 'password': password})
        self.user = data.get('user')
        return self.user

    def register(self, login, password):
        data = self._request('POST', '/api/auth/register',
                             {'login': login, 'password': password})
        self.user = data.get('user')
        return self.user

    def me(self):
        data = self._request('GET', '/api/me')
        self.user = data.get('user')
        return self.user

    def logout(self):
        try:
            self._request('POST', '/api/auth/logout')
        except ApiError:
            pass
        self.user = None

    # --- Медиатека ---

    def browse(self, path=''):
        query = f'?path={urllib.parse.quote(path)}' if path else ''
        return self._request('GET', '/api/browse' + query)

    def media_url(self, virtual_path):
        return f'{self.base_url}/media?path={urllib.parse.quote(virtual_path)}'

    def fetch_bytes(self, url, on_progress=None, timeout=600):
        """Качает файл кусками, чтобы показывать ход и не съедать память."""
        req = urllib.request.Request(url)
        chunks = []
        try:
            with self.opener.open(req, timeout=timeout) as resp:
                total = int(resp.headers.get('Content-Length') or 0)
                done = 0
                while True:
                    piece = resp.read(256 * 1024)
                    if not piece:
                        break
                    chunks.append(piece)
                    done += len(piece)
                    if on_progress and total:
                        on_progress(done / total)
        except (urllib.error.URLError, OSError) as exc:
            raise ApiError(f'Не удалось скачать: {exc}')
        return b''.join(chunks)


# --- Свой контейнер для скачанных книг ---
#
# Файл .cah не открывается обычным плеером: заголовок не совпадает ни с одним
# известным форматом, а содержимое перемешано потоком байтов от ключа.
#
# Честно: это защита от случайного открытия, а не от специалиста. Ключ лежит
# внутри приложения, и тот, кто захочет, извлечёт исходный звук. Настоящая
# защита потребовала бы серверной выдачи ключей и DRM, чего здесь нет.

def _keystream(key, length, offset=0):
    """Поток байтов из ключа: SHA-256 по счётчику блоков."""
    out = bytearray()
    block = offset // 32
    while len(out) < length + (offset % 32):
        digest = hashlib.sha256(key + block.to_bytes(8, 'big')).digest()
        out.extend(digest)
        block += 1
    start = offset % 32
    return bytes(out[start:start + length])


def _mask(data, key, offset=0):
    stream = _keystream(key, len(data), offset)
    return bytes(a ^ b for a, b in zip(data, stream))


def pack(raw, key, meta):
    """Собирает контейнер: метка формата, сведения о главе, перемешанный звук."""
    header = json.dumps(meta, ensure_ascii=False).encode('utf-8')
    return (CONTAINER_MAGIC
            + len(header).to_bytes(4, 'big')
            + header
            + _mask(raw, key))


def unpack(blob, key):
    """Возвращает (сведения, звук). Бросает ValueError на чужом файле."""
    if not blob.startswith(CONTAINER_MAGIC):
        raise ValueError('Это не файл CyberAudio Hub')
    size = int.from_bytes(blob[4:8], 'big')
    meta = json.loads(blob[8:8 + size].decode('utf-8'))
    return meta, _mask(blob[8 + size:], key)


class Library:
    """Скачанные книги на телефоне."""

    def __init__(self, root, key):
        self.root = root
        self.key = key.encode('utf-8') if isinstance(key, str) else key
        os.makedirs(self.root, exist_ok=True)

    def _folder(self, book_path):
        safe = hashlib.sha1(book_path.encode('utf-8')).hexdigest()[:16]
        return os.path.join(self.root, safe)

    def has_book(self, book_path):
        return os.path.isfile(os.path.join(self._folder(book_path), 'book.json'))

    def save_book(self, book_path, title, tracks_bytes, cover=b''):
        """tracks_bytes: [(имя главы, содержимое)]"""
        folder = self._folder(book_path)
        os.makedirs(folder, exist_ok=True)
        names = []
        for index, (name, raw) in enumerate(tracks_bytes):
            target = os.path.join(folder, f'{index:03d}{CONTAINER_EXT}')
            with open(target, 'wb') as f:
                f.write(pack(raw, self.key, {'track': name, 'index': index,
                                             'book': book_path}))
            names.append(name)
        if cover:
            with open(os.path.join(folder, 'cover.bin'), 'wb') as f:
                f.write(_mask(cover, self.key))
        with open(os.path.join(folder, 'book.json'), 'w', encoding='utf-8') as f:
            json.dump({'path': book_path, 'title': title, 'tracks': names},
                      f, ensure_ascii=False)
        return folder

    def list_books(self):
        books = []
        for entry in sorted(os.listdir(self.root)):
            info = os.path.join(self.root, entry, 'book.json')
            if not os.path.isfile(info):
                continue
            try:
                with open(info, encoding='utf-8') as f:
                    data = json.load(f)
            except (OSError, ValueError):
                continue
            data['folder'] = os.path.join(self.root, entry)
            books.append(data)
        return books

    def track_bytes(self, folder, index):
        """Распаковывает главу во временный звук для проигрывания."""
        target = os.path.join(folder, f'{index:03d}{CONTAINER_EXT}')
        with open(target, 'rb') as f:
            _meta, raw = unpack(f.read(), self.key)
        return raw

    def delete_book(self, folder):
        for name in os.listdir(folder):
            try:
                os.remove(os.path.join(folder, name))
            except OSError:
                pass
        try:
            os.rmdir(folder)
        except OSError:
            pass
