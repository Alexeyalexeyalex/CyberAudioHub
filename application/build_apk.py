#!/usr/bin/env python3
"""Build the native app, test it, and publish only after successful checks."""
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

HERE = Path(__file__).resolve().parent
PROJECT = HERE / 'android'

def find_gradle(explicit=None):
    if explicit:
        return Path(explicit)
    installed = shutil.which('gradle')
    if installed:
        return Path(installed)
    executable = 'gradle.bat' if os.name == 'nt' else 'gradle'
    cache = Path(os.environ.get('GRADLE_USER_HOME', str(Path.home() / '.gradle')))
    candidates = list((cache / 'wrapper/dists').glob(f'gradle-8.*-bin/*/gradle-8.*/bin/{executable}'))
    def version(path):
        return tuple(int(n) for n in re.findall(r'\d+', path.parent.parent.name))
    return max(candidates, key=version) if candidates else None

def main():
    parser = argparse.ArgumentParser(description='Сборка CyberAudio Hub (Java / Gradle, без Kivy и WSL)')
    parser.add_argument('--gradle', help='Путь к Gradle 8.9+ (ветка 8.x)')
    parser.add_argument('--java-home', help='Папка JDK 17 или 21')
    parser.add_argument('--offline', action='store_true', help='Не скачивать зависимости')
    parser.add_argument('--no-publish', action='store_true', help='Не заменять APK на локальном сайте')
    args = parser.parse_args()
    gradle = find_gradle(args.gradle)
    if not gradle or not gradle.is_file():
        parser.error('Gradle не найден. Установите Gradle 8.9+ или укажите --gradle путь/gradle.bat')
    env = os.environ.copy()
    if args.java_home:
        env['JAVA_HOME'] = args.java_home
    elif not env.get('JAVA_HOME') and os.name == 'nt':
        jdks = sorted(Path('C:/Program Files/Android/openjdk').glob('jdk-21*'))
        if jdks:
            env['JAVA_HOME'] = str(jdks[-1])
    if not env.get('JAVA_HOME') and not shutil.which('java'):
        parser.error('Нужен JDK 17/21. Укажите --java-home.')
    if not (PROJECT / 'local.properties').exists() and not env.get('ANDROID_HOME'):
        parser.error('Укажите sdk.dir в android/local.properties или переменную ANDROID_HOME.')
    command = [str(gradle), ':app:assembleDebug', ':app:testDebugUnitTest', ':app:lintDebug', '--console=plain']
    if args.offline:
        command.append('--offline')
    subprocess.run(command, cwd=PROJECT, env=env, check=True)
    if not args.no_publish:
        subprocess.run([sys.executable, str(HERE / 'publish.py')], cwd=HERE, check=True)
    print('Готово: ' + str(PROJECT / 'app/build/outputs/apk/debug/app-debug.apk'))
    return 0

if __name__ == '__main__':
    try:
        sys.exit(main())
    except subprocess.CalledProcessError as error:
        sys.exit(error.returncode)
