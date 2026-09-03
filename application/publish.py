#!/usr/bin/env python3
"""
Выкладывает собранное приложение так, чтобы сайт начал его раздавать,
а установленные копии предложили обновиться.

Кладёт рядом два файла:

    CyberAudioHub.apk  — сам APK, его отдаёт кнопка на сайте;
    version.txt        — «код имя», например «2 1.1».

version.txt нужен серверу: по нему он сообщает приложению, какая версия
лежит на раздаче. Без этого файла сервер честно отвечает «версия
неизвестна», и обновление не предлагается — лучше промолчать, чем звать
обновиться неизвестно на что.

Версия читается из build.gradle, руками её дублировать не нужно:
    python publish.py
"""
import os
import re
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
GRADLE = os.path.join(HERE, 'android', 'app', 'build.gradle')
BUILT = os.path.join(HERE, 'android', 'app', 'build', 'outputs',
                     'apk', 'debug', 'app-debug.apk')
TARGET = os.path.join(HERE, 'CyberAudioHub.apk')
MARKER = os.path.join(HERE, 'version.txt')


def version_from_gradle():
    """Код и имя версии из build.gradle — единственного места, где они есть."""
    with open(GRADLE, encoding='utf-8') as f:
        text = f.read()
    code = re.search(r'versionCode\s+(\d+)', text)
    name = re.search(r'versionName\s+"([^"]+)"', text)
    if not code or not name:
        raise SystemExit('в build.gradle не нашлись versionCode и versionName')
    return int(code.group(1)), name.group(1)


def main():
    if not os.path.isfile(BUILT):
        raise SystemExit(
            'APK не собран. Сначала выполните в папке android:\n'
            '    gradle assembleDebug')

    code, name = version_from_gradle()
    shutil.copy2(BUILT, TARGET)
    with open(MARKER, 'w', encoding='utf-8') as f:
        f.write(f'{code} {name}\n')

    size = os.path.getsize(TARGET) / 1048576
    print(f'выложено: {os.path.basename(TARGET)}, {size:.1f} МБ')
    print(f'версия:   {code} ({name})')
    print()
    print('Сайт отдаёт его по кнопке «Скачать приложение на Android»,')
    print('а установленные копии предложат обновиться при следующем запуске.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
