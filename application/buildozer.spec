[app]
title = CyberAudio Hub
package.name = cyberaudiohub
package.domain = corp.cyberaudio
source.dir = .
source.include_exts = py,png,jpg,ttf,json
version = 0.1.0

# Kivy тянет за собой всё нужное; сторонних библиотек приложение не требует.
requirements = python3,kivy,android,pyjnius

orientation = portrait
fullscreen = 0
icon.filename = %(source.dir)s/icon.png

# INTERNET — связь с сервером, FOREGROUND_SERVICE и POST_NOTIFICATIONS —
# уведомление с управлением в шторке и на экране блокировки.
android.permissions = INTERNET,FOREGROUND_SERVICE,POST_NOTIFICATIONS,WAKE_LOCK
android.api = 34
android.minapi = 24
android.archs = arm64-v8a,armeabi-v7a
android.allow_backup = True

# Нужно для уведомления через NotificationCompat
# androidx.media даёт MediaSessionCompat и стиль MediaStyle —
# без них Android не рисует виджет плеера на экране блокировки
android.gradle_dependencies = androidx.core:core:1.12.0,androidx.media:media:1.7.0
android.enable_androidx = True

[buildozer]
log_level = 2
warn_on_root = 1
