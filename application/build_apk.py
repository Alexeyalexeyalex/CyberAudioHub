#!/usr/bin/env python3
# build_apk.py — сборка APK через Buildozer.
#
# Запускать из этой папки:
#   python3 build_apk.py            # обычная отладочная сборка
#   python3 build_apk.py --clean    # начисто, если прошлая сборка сломалась
#   python3 build_apk.py --release  # неподписанный релизный APK
#
# Что нужно на машине сборки:
#   - Linux (на Windows Buildozer не работает — используйте WSL2)
#   - Python 3.9+, Java 17, git, zip, unzip
#   - интернет: при первом запуске качается Android SDK/NDK, это долго
#
# Готовый файл появится в bin/ и будет скопирован сюда же,
# чтобы сервер отдавал его по кнопке «Скачать приложение».
import argparse
import os
import shutil
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))

APT_PACKAGES = ('git', 'zip', 'unzip', 'openjdk-17-jdk', 'python3-pip',
                'autoconf', 'libtool', 'pkg-config', 'zlib1g-dev',
                'libncurses-dev', 'libtinfo6', 'cmake', 'libffi-dev', 'libssl-dev')


def run(command, **kwargs):
    print(f'\n$ {" ".join(command)}\n', flush=True)
    return subprocess.call(command, cwd=HERE, **kwargs)


def have(tool):
    return shutil.which(tool) is not None


def check_environment():
    """Проверяет то, без чего сборка точно не пойдёт."""
    problems = []
    if sys.platform.startswith('win'):
        problems.append(
            'Buildozer не работает в Windows напрямую.\n'
            '    Поставьте WSL2 с Ubuntu и запустите скрипт оттуда.')
    if not have('java'):
        problems.append('Нет Java. Установите: sudo apt install openjdk-17-jdk')
    if not have('git'):
        problems.append('Нет git. Установите: sudo apt install git')
    if not have('unzip'):
        problems.append('Нет unzip. Установите: sudo apt install zip unzip')
    return problems


def ensure_buildozer():
    if have('buildozer'):
        return True
    print('Buildozer не найден, ставлю через pip...')
    code = subprocess.call([sys.executable, '-m', 'pip', 'install',
                            '--user', 'buildozer', 'cython'])
    if code != 0:
        print('Не удалось поставить Buildozer.')
        return False
    if not have('buildozer'):
        print('Buildozer установлен, но не виден в PATH.\n'
              '  Добавьте: export PATH="$HOME/.local/bin:$PATH"')
        return False
    return True


def collect_result():
    """Кладёт собранный APK рядом со скриптом — оттуда его отдаёт сервер."""
    bin_dir = os.path.join(HERE, 'bin')
    if not os.path.isdir(bin_dir):
        return None
    apks = [f for f in os.listdir(bin_dir) if f.endswith('.apk')]
    if not apks:
        return None
    apks.sort(key=lambda name: os.path.getmtime(os.path.join(bin_dir, name)))
    newest = apks[-1]
    target = os.path.join(HERE, 'CyberAudioHub.apk')
    shutil.copy2(os.path.join(bin_dir, newest), target)
    return target


def main():
    parser = argparse.ArgumentParser(description='Сборка APK приложения CyberAudio Hub.')
    parser.add_argument('--clean', action='store_true',
                        help='удалить прошлую сборку и собрать начисто')
    parser.add_argument('--release', action='store_true',
                        help='релизная сборка (APK останется неподписанным)')
    args = parser.parse_args()

    print('=' * 62)
    print(' CyberAudio Hub — сборка приложения для Android'.center(62))
    print('=' * 62)

    problems = check_environment()
    if problems:
        print('\nСборка невозможна:')
        for item in problems:
            print(f'  - {item}')
        print('\nОдной командой на Ubuntu:')
        print('  sudo apt install ' + ' '.join(APT_PACKAGES))
        return 1

    if not ensure_buildozer():
        return 1

    if args.clean:
        run(['buildozer', 'android', 'clean'])
        shutil.rmtree(os.path.join(HERE, '.buildozer'), ignore_errors=True)

    print('\nПервая сборка качает Android SDK и NDK — это десятки минут '
          'и несколько гигабайт.\n')
    code = run(['buildozer', '-v', 'android',
                'release' if args.release else 'debug'])
    if code != 0:
        print('\nСборка не удалась. Что обычно помогает:')
        print('  1) python3 build_apk.py --clean — начать начисто;')
        print('  2) проверить, что версия Java именно 17 (java -version);')
        print('  3) заглянуть в конец вывода выше: Buildozer пишет,')
        print('     какого пакета ему не хватило.')
        return code

    apk = collect_result()
    if apk:
        print(f'\nГотово: {apk}')
        print('Файл лежит в папке application — сервер отдаёт его по кнопке')
        print('«Скачать приложение на Android» в окне профиля.')
    else:
        print('\nСборка прошла, но APK в bin/ не найден — проверьте вывод выше.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
