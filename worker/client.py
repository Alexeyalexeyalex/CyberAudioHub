# client.py — общение с сервером CyberAudio Hub.
#
# Намеренно на стандартной библиотеке: чтобы поставить распознаватель,
# должно хватать одного pip install faster-whisper.
import json
import os
import urllib.error
import urllib.parse
import urllib.request


class ServerError(Exception):
    """Сервер ответил ошибкой."""

    def __init__(self, message, status=None):
        super().__init__(message)
        self.status = status


class Client:
    def __init__(self, base_url, token, timeout=60):
        self.base_url = base_url.rstrip('/')
        self.token = token
        self.timeout = timeout

    # --- Низкий уровень ---

    def _request(self, method, path, payload=None, timeout=None):
        url = self.base_url + path
        data = json.dumps(payload).encode('utf-8') if payload is not None else None
        req = urllib.request.Request(url, data=data, method=method)
        req.add_header('X-Worker-Token', self.token)
        if data is not None:
            req.add_header('Content-Type', 'application/json')
        try:
            with urllib.request.urlopen(req, timeout=timeout or self.timeout) as resp:
                body = resp.read().decode('utf-8')
                return json.loads(body) if body else {}
        except urllib.error.HTTPError as exc:
            raw = exc.read().decode('utf-8', 'replace')
            try:
                message = json.loads(raw).get('error') or raw
            except ValueError:
                message = raw[:200]
            raise ServerError(message, exc.code)
        except urllib.error.URLError as exc:
            raise ServerError(f'нет связи с сервером: {exc.reason}')

    # --- Операции ---

    def queue(self):
        return self._request('GET', '/api/worker/queue').get('books', [])

    def claim(self, path, track, worker):
        """True — глава наша. False — её уже забрал кто-то другой."""
        try:
            self._request('POST', '/api/worker/claim',
                          {"path": path, "track": track, "worker": worker})
            return True
        except ServerError as exc:
            if exc.status == 409:
                return False
            raise

    def heartbeat(self, path, track, worker):
        try:
            self._request('POST', '/api/worker/heartbeat',
                          {"path": path, "track": track, "worker": worker}, timeout=15)
        except ServerError:
            pass  # продление не критично: пропустим до следующего раза

    def download_audio(self, path, track, target_path):
        query = urllib.parse.urlencode({"path": path, "track": track})
        url = f'{self.base_url}/api/worker/audio?{query}'
        req = urllib.request.Request(url)
        req.add_header('X-Worker-Token', self.token)
        try:
            with urllib.request.urlopen(req, timeout=600) as resp, \
                    open(target_path, 'wb') as out:
                while True:
                    chunk = resp.read(1 << 16)
                    if not chunk:
                        break
                    out.write(chunk)
        except urllib.error.HTTPError as exc:
            raise ServerError(f'не удалось скачать аудио: {exc.code}', exc.code)
        except urllib.error.URLError as exc:
            raise ServerError(f'нет связи с сервером: {exc.reason}')
        return target_path

    def send_result(self, path, track, segments, language, engine):
        return self._request('POST', '/api/worker/result', {
            "path": path, "track": track, "segments": segments,
            "language": language, "engine": engine,
        }, timeout=300)

    def report_error(self, path, track, message):
        try:
            self._request('POST', '/api/worker/error',
                          {"path": path, "track": track, "error": message})
        except ServerError:
            pass

    # --- Озвучка книг ---
    #
    # Здесь бронь берётся на книгу целиком, а не на главу: сколько в книге
    # глав, выясняется только после разбора документа, а разбираем его мы.

    def voice_queue(self):
        return self._request('GET', '/api/worker/voice/queue').get('books', [])

    def voice_claim(self, path, worker):
        """True — книга наша. False — её уже озвучивает кто-то другой."""
        try:
            self._request('POST', '/api/worker/voice/claim',
                          {"path": path, "worker": worker})
            return True
        except ServerError as exc:
            if exc.status == 409:
                return False
            raise

    def voice_heartbeat(self, path, worker):
        try:
            self._request('POST', '/api/worker/voice/heartbeat',
                          {"path": path, "worker": worker}, timeout=15)
        except ServerError:
            pass  # продление не критично: пропустим до следующего раза

    def download_document(self, path, target_path):
        query = urllib.parse.urlencode({"path": path})
        url = f'{self.base_url}/api/worker/voice/document?{query}'
        req = urllib.request.Request(url)
        req.add_header('X-Worker-Token', self.token)
        try:
            with urllib.request.urlopen(req, timeout=600) as resp, \
                    open(target_path, 'wb') as out:
                while True:
                    chunk = resp.read(1 << 16)
                    if not chunk:
                        break
                    out.write(chunk)
        except urllib.error.HTTPError as exc:
            raise ServerError(f'не удалось скачать документ: {exc.code}', exc.code)
        except urllib.error.URLError as exc:
            raise ServerError(f'нет связи с сервером: {exc.reason}')
        return target_path

    def voice_plan(self, path, total, engine, voice):
        return self._request('POST', '/api/worker/voice/plan',
                             {"path": path, "total": total,
                              "engine": engine, "voice": voice})

    def upload_voice_track(self, path, index, name, file_path,
                           seconds=0.0, ext='mp3'):
        """
        Отправляет готовую главу. Файл идёт телом запроса и читается
        порциями: держать в памяти десяток мегабайт незачем, а на длинных
        главах это были бы уже сотни.
        """
        query = urllib.parse.urlencode({
            "path": path, "index": index, "name": name,
            "seconds": round(float(seconds or 0), 2), "ext": ext,
        })
        url = f'{self.base_url}/api/worker/voice/track?{query}'
        size = os.path.getsize(file_path)
        with open(file_path, 'rb') as body:
            req = urllib.request.Request(url, data=body, method='POST')
            req.add_header('X-Worker-Token', self.token)
            req.add_header('Content-Type', 'application/octet-stream')
            # Без Content-Length urllib попытается собрать тело целиком
            # в памяти, чтобы посчитать длину самостоятельно
            req.add_header('Content-Length', str(size))
            try:
                with urllib.request.urlopen(req, timeout=1800) as resp:
                    raw = resp.read().decode('utf-8')
                    return json.loads(raw) if raw else {}
            except urllib.error.HTTPError as exc:
                text = exc.read().decode('utf-8', 'replace')
                try:
                    message = json.loads(text).get('error') or text
                except ValueError:
                    message = text[:200]
                raise ServerError(message, exc.code)
            except urllib.error.URLError as exc:
                raise ServerError(f'нет связи с сервером: {exc.reason}')

    def upload_voice_text(self, path, index, name, segments, engine):
        return self._request('POST', '/api/worker/voice/text', {
            "path": path, "track": index, "name": name,
            "segments": segments, "engine": engine,
        }, timeout=300)

    def voice_done(self, path):
        return self._request('POST', '/api/worker/voice/done', {"path": path})

    def voice_error(self, path, message):
        try:
            self._request('POST', '/api/worker/voice/error',
                          {"path": path, "error": message})
        except ServerError:
            pass
