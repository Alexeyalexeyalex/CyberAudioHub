# documents.py — достаём текст книги из файла и режем его на главы.
#
# Работает на стороне воркера, а не сервера: разбор pdf и docx тянет за собой
# библиотеки, а сервер по замыслу остаётся без единой зависимости сверх Flask.
#
# txt, md, fb2 и epub читаются стандартной библиотекой. Для pdf нужен pypdf,
# для docx — python-docx; обе стоят в requirements.txt.
import os
import re
import zipfile


class DocumentError(Exception):
    """Файл не удалось прочитать."""


# --- Чтение ---------------------------------------------------------------

# Порядок важен: utf-8-sig снимает BOM, cp1251 — самая частая кодировка
# у русских txt, взятых из старых источников.
ENCODINGS = ('utf-8-sig', 'utf-8', 'cp1251', 'koi8-r', 'cp866')


def _decode(raw):
    for encoding in ENCODINGS:
        try:
            return raw.decode(encoding)
        except UnicodeDecodeError:
            continue
    # Последняя попытка: не терять книгу целиком из-за пары битых байтов
    return raw.decode('utf-8', 'replace')


def _strip_tags(html):
    """Грубое снятие разметки: нам нужен текст, а не структура документа."""
    html = re.sub(r'(?is)<(script|style)[^>]*>.*?</\1>', ' ', html)
    # Блочные теги превращаем в перевод строки, иначе абзацы слипнутся
    html = re.sub(r'(?i)</(p|div|h[1-6]|li|br|tr)\s*>', '\n', html)
    html = re.sub(r'(?i)<br\s*/?>', '\n', html)
    html = re.sub(r'(?s)<[^>]+>', ' ', html)
    html = (html.replace('&nbsp;', ' ').replace('&mdash;', '—')
            .replace('&ndash;', '–').replace('&laquo;', '«')
            .replace('&raquo;', '»').replace('&quot;', '"')
            .replace('&amp;', '&').replace('&lt;', '<').replace('&gt;', '>'))
    return html


def read_txt(path):
    with open(path, 'rb') as f:
        return _decode(f.read())


def read_fb2(path):
    """FB2 — обычный XML. Заголовки помечаем, чтобы по ним резать главы."""
    import xml.etree.ElementTree as ET
    try:
        tree = ET.parse(path)
    except ET.ParseError as exc:
        raise DocumentError(f'битый fb2: {exc}')

    lines = []
    # Заголовок в fb2 — это <title> с вложенными <p>. Без этого множества
    # каждый такой абзац попадал бы в текст дважды: один раз в составе
    # заголовка и второй раз сам по себе.
    consumed = set()
    for element in tree.iter():
        if id(element) in consumed:
            continue
        tag = element.tag.rsplit('}', 1)[-1]
        if tag not in ('p', 'title', 'subtitle', 'v'):
            continue
        text = ' '.join(element.itertext()).strip()
        if tag in ('title', 'subtitle'):
            for child in element.iter():
                consumed.add(id(child))
        if not text:
            continue
        # Заголовок выносим отдельной строкой: разбиение на главы
        # ищет именно такие строки
        lines.append(text if tag in ('p', 'v') else f'\n{text}\n')
    return '\n'.join(lines)


def read_epub(path):
    """EPUB — zip с html внутри. Порядок глав берём из content.opf."""
    try:
        book = zipfile.ZipFile(path)
    except (zipfile.BadZipFile, OSError) as exc:
        raise DocumentError(f'битый epub: {exc}')

    with book:
        names = book.namelist()
        opf = next((n for n in names if n.lower().endswith('.opf')), None)
        order = []
        if opf:
            manifest = _decode(book.read(opf))
            base = os.path.dirname(opf)
            # id -> href из манифеста, затем порядок из spine
            hrefs = dict(re.findall(r'<item[^>]+id="([^"]+)"[^>]+href="([^"]+)"',
                                    manifest))
            hrefs.update({i: h for h, i in re.findall(
                r'<item[^>]+href="([^"]+)"[^>]+id="([^"]+)"', manifest)})
            for ref in re.findall(r'<itemref[^>]+idref="([^"]+)"', manifest):
                href = hrefs.get(ref)
                if not href:
                    continue
                full = os.path.normpath(os.path.join(base, href)).replace('\\', '/')
                if full in names:
                    order.append(full)
        if not order:
            order = [n for n in names
                     if n.lower().endswith(('.xhtml', '.html', '.htm'))]

        parts = []
        for name in order:
            try:
                parts.append(_strip_tags(_decode(book.read(name))))
            except KeyError:
                continue
    return '\n\n'.join(parts)


def read_pdf(path):
    try:
        from pypdf import PdfReader
    except ImportError:
        raise DocumentError(
            'для pdf нужен pypdf — выполните pip install -r requirements.txt')
    try:
        reader = PdfReader(path)
    except Exception as exc:
        raise DocumentError(f'не удалось открыть pdf: {exc}')

    pages = []
    for page in reader.pages:
        try:
            pages.append(page.extract_text() or '')
        except Exception:
            pages.append('')
    text = '\n'.join(pages)
    if not text.strip():
        raise DocumentError(
            'в pdf нет текстового слоя — похоже, это скан. '
            'Прогоните его через распознавание (OCR) и попробуйте снова.')
    return text


def read_docx(path):
    try:
        import docx
    except ImportError:
        raise DocumentError(
            'для docx нужен python-docx — выполните pip install -r requirements.txt')
    try:
        document = docx.Document(path)
    except Exception as exc:
        raise DocumentError(f'не удалось открыть docx: {exc}')

    lines = []
    for para in document.paragraphs:
        text = (para.text or '').strip()
        if not text:
            lines.append('')
            continue
        # Заголовки стилями Word отделяем пустыми строками — по ним
        # дальше находятся границы глав
        style = (para.style.name or '').lower() if para.style is not None else ''
        lines.append(f'\n{text}\n' if 'heading' in style or 'заголовок' in style
                     else text)
    return '\n'.join(lines)


READERS = {
    '.txt': read_txt, '.md': read_txt,
    '.fb2': read_fb2, '.epub': read_epub,
    '.pdf': read_pdf, '.docx': read_docx,
}


def read_document(path):
    """Текст книги из файла любого поддерживаемого вида."""
    ext = os.path.splitext(path)[1].lower()
    reader = READERS.get(ext)
    if reader is None:
        raise DocumentError(f'не умею читать {ext or "файл без расширения"}')
    text = reader(path)
    if not (text or '').strip():
        raise DocumentError('документ пуст')
    return text


# --- Очистка --------------------------------------------------------------

# Строка из одних цифр — почти всегда номер страницы из pdf. В речи он
# звучал бы как случайное число посреди абзаца.
PAGE_NUMBER = re.compile(r'^\s*[-—–]?\s*\d{1,4}\s*[-—–]?\s*$')

# Перенос по слогам: «сло-\nво». В pdf встречается на каждой странице.
HYPHEN_BREAK = re.compile(r'(\w)[-­]\s*\n\s*(\w)')


def clean(text):
    text = text.replace('\r\n', '\n').replace('\r', '\n')
    text = text.replace(' ', ' ').replace('﻿', '')
    text = HYPHEN_BREAK.sub(r'\1\2', text)

    lines = []
    for line in text.split('\n'):
        line = re.sub(r'[ \t]+', ' ', line).strip()
        if PAGE_NUMBER.match(line):
            continue
        lines.append(line)

    text = '\n'.join(lines)
    # Больше двух переводов строки подряд ничего не добавляют
    return re.sub(r'\n{3,}', '\n\n', text).strip()


# --- Разбиение на главы ---------------------------------------------------

# Заголовок ищем по ключевому слову. Голая строка из римских цифр или числа
# сюда намеренно не входит: в pdf это чаще колонтитул, чем название главы,
# и книга разлеталась бы на сотни «глав» по одной строке.
HEADING = re.compile(
    r'^\s*(?:глава|часть|книга|том|раздел|пролог|эпилог|предисловие|'
    r'послесловие|вступление|заключение|chapter|part|prologue|epilogue)'
    r'\b[^\n]{0,80}$',
    re.IGNORECASE)

MIN_CHAPTER_CHARS = 400


def _by_headings(text):
    """Границы глав по строкам-заголовкам. [] — заголовков не нашлось."""
    lines = text.split('\n')
    marks = [i for i, line in enumerate(lines)
             if line.strip() and HEADING.match(line)]
    if len(marks) < 2:
        return []

    chapters = []
    # Текст до первого заголовка — аннотация или оглавление; берём его
    # отдельной главой, только если он заметного размера
    if marks[0] > 0:
        head = '\n'.join(lines[:marks[0]]).strip()
        if len(head) >= MIN_CHAPTER_CHARS:
            chapters.append(('Начало', head))

    for pos, start in enumerate(marks):
        end = marks[pos + 1] if pos + 1 < len(marks) else len(lines)
        title = lines[start].strip()
        body = '\n'.join(lines[start:end]).strip()
        if len(body) < MIN_CHAPTER_CHARS and chapters:
            # Слишком короткий кусок — это подзаголовок, а не глава:
            # приклеиваем к предыдущей, чтобы не плодить дорожки на 5 секунд
            prev_title, prev_body = chapters[-1]
            chapters[-1] = (prev_title, prev_body + '\n\n' + body)
            continue
        chapters.append((title, body))
    return chapters


def _by_size(text, target_chars):
    """
    Запасной способ: режем по абзацам на куски примерно равного размера.
    Нужен для книг без внятных заголовков — иначе вся книга стала бы
    одной дорожкой на десять часов, которую неудобно ни слушать, ни грузить.
    """
    paragraphs = [p.strip() for p in text.split('\n\n') if p.strip()]
    chapters, current, size = [], [], 0
    for para in paragraphs:
        current.append(para)
        size += len(para)
        if size >= target_chars:
            chapters.append(('\n\n'.join(current)))
            current, size = [], 0
    if current:
        tail = '\n\n'.join(current)
        # Хвост короче половины куска приклеиваем к предыдущему
        if chapters and len(tail) < target_chars / 2:
            chapters[-1] += '\n\n' + tail
        else:
            chapters.append(tail)
    return [(f'Часть {i + 1}', body) for i, body in enumerate(chapters)]


def split_chapters(text, target_chars=9000):
    """
    [(название, текст), ...]. Сначала пробуем настоящие заголовки, и только
    если их нет — режем по размеру. target_chars ≈ 10 минут речи.
    """
    text = clean(text)
    chapters = _by_headings(text)
    if not chapters:
        chapters = _by_size(text, target_chars)

    # Слишком длинную главу всё равно делим: файл на три часа неудобен
    # и при перемотке, и при передаче по сети.
    limit = target_chars * 3
    result = []
    for title, body in chapters:
        if len(body) <= limit:
            result.append((title, body))
            continue
        for part, (_sub, chunk) in enumerate(_by_size(body, target_chars)):
            result.append((f'{title} ({part + 1})' if part else title, chunk))

    if not result:
        result = [('Книга', text)]
    return result
