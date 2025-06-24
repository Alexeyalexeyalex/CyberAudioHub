# app.py
import os
import json
from flask import Flask, jsonify, render_template, url_for

# Инициализируем Flask приложение
app = Flask(__name__)

# Путь к папке с музыкой внутри 'static'
MUSIC_FOLDER = os.path.join(app.static_folder, 'music')

# Главная страница, которая показывает все альбомы
@app.route('/')
def index():
    return render_template('index.html')

# Страница плеера
@app.route('/player')
def player():
    return render_template('player.html')


# API эндпоинт, который сканирует папки и возвращает данные в формате JSON
@app.route('/api/music-data')
def get_music_data():
    albums = []
    if not os.path.exists(MUSIC_FOLDER):
        return jsonify({"error": "Music directory not found"}), 404

    # Сканируем папки альбомов
    for album_name in sorted(os.listdir(MUSIC_FOLDER)):
        album_path = os.path.join(MUSIC_FOLDER, album_name)
        if os.path.isdir(album_path):
            
            # Формируем базовую структуру данных для альбома
            album_data = {
                "id": album_name.lower().replace(' ', '-').replace('_', '-'),
                "title": album_name.replace('_', ' '),
                "tracks": []
            }

            # Ищем обложку и треки внутри папки альбома
            found_cover = None
            track_files = []

            for filename in sorted(os.listdir(album_path)):
                # Формируем часть пути для URL
                url_path_part = os.path.join('music', album_name, filename)
                # ИСПРАВЛЕНИЕ: Заменяем системные разделители на веб-разделители
                web_path = url_path_part.replace('\\', '/')

                # Ищем аудиофайлы
                if filename.lower().endswith(('.mp3', '.ogg', '.wav', '.m4a')):
                    track_url = url_for('static', filename=web_path)
                    track_files.append({"name": filename, "url": track_url})
                
                # Ищем обложку
                if filename.lower().startswith('cover') and filename.lower().endswith(('.jpg', '.jpeg', '.png')):
                    found_cover = url_for('static', filename=web_path)

            # Устанавливаем обложку (найденную или по умолчанию)
            default_cover_path = os.path.join('assets', 'default_cover.png').replace('\\', '/')
            album_data["cover"] = found_cover if found_cover else url_for('static', filename=default_cover_path)
            
            # Добавляем отсортированные треки
            album_data["tracks"] = sorted(track_files, key=lambda x: x['name'])

            # Добавляем альбом в список, только если в нем есть треки
            if album_data["tracks"]:
                albums.append(album_data)
    
    return jsonify(albums)


# Запуск сервера
if __name__ == '__main__':
    app.run(debug=True, port=5000, host='0.0.0.0')