"""Isolated UI review. Run locally; never reuse the user's database or port."""
import os
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
QA = ROOT / 'application/android/app/build/qa'
QA.mkdir(parents=True, exist_ok=True)
os.environ['CYBERAUDIO_DB_PATH'] = str(QA / 'preview.db')
os.environ['CYBERAUDIO_ADMIN_LOGIN'] = 'preview_admin'
os.environ['CYBERAUDIO_ADMIN_PASSWORD'] = 'preview_local_only'

import app as server
from flask import request
import sqlite3

with sqlite3.connect(server.DB_PATH) as conn:
    conn.row_factory = sqlite3.Row
    owner = server.database.find_user_by_login(conn, 'preview_admin')['id']
    if server.database.find_user_by_login(conn, 'preview_friend') is None:
        friend = server.database.create_user(conn, 'preview_friend', 'Александр', server.generate_password_hash('preview_local_only'))
        server.database.add_friend_request(conn, owner, friend)
        server.database.accept_friend_request(conn, friend, owner)
        incoming = server.database.create_user(conn, 'preview_reader', 'Читатель', server.generate_password_hash('preview_local_only'))
        server.database.add_friend_request(conn, incoming, owner)
        for title, rarity in [('Первая история', 'common'), ('На своей волне', 'rare'), ('Хранитель миров', 'legendary')]:
            badge = server.database.create_achievement(conn, title, '/static/assets/favicon.png', 'Каждая прочитанная история открывает новый мир.', '', '[]', rarity)
            if rarity != 'legendary': server.database.grant_achievement(conn, owner, badge)
            server.database.grant_achievement(conn, friend, badge)

    # Synthetic reading material belongs only to the preview database.
    # Audio/catalogue files are read-only; no worker job is scheduled.
    demo_path = 'мир для лекаря'
    if server.album_exists(demo_path):
        tracks = server.album_tracks_on_disk(demo_path)
        if tracks:
            server.database.create_transcript(conn, demo_path, len(tracks))
            for index, (name, _disk) in enumerate(tracks):
                segments = []
                for paragraph in range(32):
                    words = 'Это тестовый текст для проверки чтения. История продолжается, и новая глава открывает ещё один мир.'.split()
                    start = paragraph * 8.0
                    segments.append({'s': start, 'e': start + 8,
                        't': ' '.join(words), 'w': [
                            [word, start + w * .45, start + (w + 1) * .45]
                            for w, word in enumerate(words)]})
                server.database.save_transcript_track(conn, demo_path, index, name, segments)
            server.database.update_transcript(conn, demo_path, status='ready', done_tracks=len(tracks))


@server.app.after_request
def preview_theme(response):
    # Only modifies HTML served by this explicit development entry point.
    if response.mimetype == 'text/html' and request.path != '/static':
        theme = request.args.get('preview_theme')
        if theme in ('newyear', 'spring', 'valentine'):
            text = response.get_data(as_text=True)
            script = '<script>const originalFetch=window.fetch;window.fetch=(u,o)=>u==="/api/theme"?Promise.resolve(new Response(JSON.stringify({themes:["' + theme + '"],intensity:1}))):originalFetch(u,o);</script>'
            response.set_data(text.replace('<head>', '<head>' + script, 1))
    return response


if __name__ == '__main__':
    server.app.run(host='127.0.0.1', port=2078, debug=False, use_reloader=False)
