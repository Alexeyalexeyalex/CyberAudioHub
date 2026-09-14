"""Local API checks use a temporary database, never the user's library history."""
import importlib
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


class RecommendationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='cah-tests-')
        with patch.dict(os.environ, {'CYBERAUDIO_DB_PATH': str(Path(cls.temp.name) / 'test.db')}):
            cls.server = importlib.import_module('app')
        cls.client = cls.server.app.test_client()

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def recommend(self, paths, user=None, history=()):
        with patch.object(self.server, 'all_album_paths', return_value=paths), \
                patch.object(self.server, 'current_user', return_value=user), \
                patch.object(self.server, 'ready_text_paths', return_value=set()), \
                patch.object(self.server.database, 'list_progress', return_value=history), \
                patch.object(self.server, 'album_card', side_effect=lambda path, _: {'path': path, 'name': path}):
            result = self.client.get('/api/recommendation')
            self.assertEqual(result.status_code, 200)
            return result.json['book']

    def test_empty_catalog(self):
        self.assertIsNone(self.recommend([]))

    def test_guest_gets_available_book(self):
        self.assertEqual(self.recommend(['', 'Новая история'])['path'], 'Новая история')

    def test_unseen_has_priority(self):
        rows = [{'path': 'Old', 'finished': 0}, {'path': 'Done', 'finished': 1}]
        self.assertEqual(self.recommend(['Old', 'New', 'Done'], {'id': 7}, rows)['path'], 'New')

    def test_unfinished_fallback(self):
        rows = [{'path': 'Old', 'finished': 0}, {'path': 'Done', 'finished': 1}]
        self.assertEqual(self.recommend(['Old', 'Done'], {'id': 7}, rows)['path'], 'Old')

    def test_finished_fallback(self):
        self.assertEqual(self.recommend(['Done'], {'id': 7}, [{'path': 'Done', 'finished': 1}])['path'], 'Done')

    def test_homepage_has_accessible_record(self):
        response = self.client.get('/?_view=1')
        self.assertEqual(response.status_code, 200)
        self.assertIn(b'id="recommendation-link"', response.data)
        self.assertIn(b'id="recommendation-pause"', response.data)

    def test_shell_keeps_audio_outside_navigation(self):
        response = self.client.get('/')
        self.assertIn(b'id="site-frame"', response.data)
        self.assertIn(b'id="persistent-audio"', response.data)
        self.assertIn(b'id="global-close"', response.data)

    def test_assets_are_served(self):
        for asset in ('favicon.svg', 'favicon.png', 'default_cover.png'):
            with self.subTest(asset=asset):
                response = self.client.get('/static/assets/' + asset)
                self.assertEqual(response.status_code, 200)
                response.close()

    def test_admin_stays_protected(self):
        self.assertIn(self.client.get('/admin').status_code, (302, 401, 403, 404))


if __name__ == '__main__':
    unittest.main()
