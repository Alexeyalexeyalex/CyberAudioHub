# app.py
import os
from flask import Flask, jsonify, render_template, url_for, request, abort

# --- КОНФИГУРАЦИЯ ---
APP_PORT = 2077

app = Flask(__name__)
MUSIC_FOLDER_ROOT = os.path.join(app.static_folder, 'music')

def has_music_recursive(directory_path):
    """
    Рекурсивно проверяет, содержит ли папка (или ее подпапки) хотя бы один аудиофайл.
    """
    for root, dirs, files in os.walk(directory_path):
        for file in files:
            if file.lower().endswith(('.mp3', '.ogg', '.wav', '.m4a')):
                return True
    return False

# --- Маршруты страниц ---
@app.route('/')
def index():
    return render_template('index.html')

@app.route('/player')
def player():
    return render_template('player.html')

# --- API ---
@app.route('/api/browse')
def api_browse():
    """
    Главный API-эндпоинт. Принимает 'path' и возвращает содержимое.
    """
    relative_path = request.args.get('path', '').strip('/')
    current_path = os.path.join(MUSIC_FOLDER_ROOT, relative_path)
    
    if not os.path.abspath(current_path).startswith(os.path.abspath(MUSIC_FOLDER_ROOT)):
        abort(403, "Access denied")

    if not os.path.isdir(current_path):
        abort(404, "Directory not found")

    items = []
    tracks = []
    
    dir_content = sorted(os.listdir(current_path))

    for item_name in dir_content:
        item_path_on_disk = os.path.join(current_path, item_name)
        # Путь для навигации (относительно папки music)
        nav_path = os.path.join(relative_path, item_name).replace('\\', '/')
        
        if os.path.isdir(item_path_on_disk):
            if has_music_recursive(item_path_on_disk):
                cover_url = None
                # Ищем обложку для папки
                for cover_name in ['cover.jpg', 'cover.png']:
                    if os.path.exists(os.path.join(item_path_on_disk, cover_name)):
                        # ИСПРАВЛЕНО: Добавлен префикс 'music' для корректного URL
                        cover_url_path = os.path.join('music', nav_path, cover_name).replace('\\', '/')
                        cover_url = url_for('static', filename=cover_url_path)
                        break

                items.append({
                    "type": "directory",
                    "name": item_name.replace('_', ' '),
                    "path": nav_path,
                    "cover": cover_url
                })
        
        elif item_name.lower().endswith(('.mp3', '.ogg', '.wav', '.m4a')):
            # ИСПРАВЛЕНО: Добавлен префикс 'music' для корректного URL трека
            track_url_path = os.path.join('music', nav_path).replace('\\', '/')
            tracks.append({
                "name": item_name,
                "url": url_for('static', filename=track_url_path)
            })

    if tracks:
        # Это "альбом", так как в папке есть треки
        cover_url = url_for('static', filename='assets/default_cover.png')
        for cover_name in ['cover.jpg', 'cover.png']:
            if os.path.exists(os.path.join(current_path, cover_name)):
                cover_url_path = os.path.join('music', relative_path, cover_name).replace('\\', '/')
                cover_url = url_for('static', filename=cover_url_path)
                break
        
        return jsonify({
            "type": "album",
            "title": os.path.basename(relative_path).replace('_', ' ') or "Медиатека",
            "tracks": tracks,
            "cover": cover_url
        })
    else:
        # Это "директория", так как в ней только другие папки
        for item in items:
            if item['cover'] is None:
                item['cover'] = url_for('static', filename='assets/default_cover.png')

        return jsonify({
            "type": "directory",
            "items": items,
            "path": relative_path
        })


if __name__ == '__main__':
    print(f"CyberAudio Hub запущен. Добро пожаловать в Найт-Сити.")
    print(f"-> Откройте в браузере: http://localhost:{APP_PORT}")
    app.run(debug=True, port=APP_PORT, host='0.0.0.0')