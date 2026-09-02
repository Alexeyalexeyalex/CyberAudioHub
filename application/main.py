# main.py — приложение CyberAudio Hub для Android.
#
# Три экрана: вход, полка с книгами и плеер. Оформление повторяет сайт:
# тёмный фиолетовый фон, неоновая бирюза и малиновый.
#
# ВАЖНО: этот код ни разу не запускался — собрать и проверить APK в среде,
# где он писался, было нечем. Считайте его рабочим черновиком: возможны
# правки при первой сборке. Подробности в README.md рядом.
import os
import threading

from kivy.app import App
from kivy.clock import Clock, mainthread
from kivy.core.window import Window
from kivy.metrics import dp
from kivy.uix.boxlayout import BoxLayout
from kivy.uix.button import Button
from kivy.uix.label import Label
from kivy.uix.screenmanager import ScreenManager, Screen, SlideTransition
from kivy.uix.scrollview import ScrollView
from kivy.uix.textinput import TextInput
from kivy.uix.gridlayout import GridLayout
from kivy.graphics import Color, Rectangle, Line
from kivy.storage.jsonstore import JsonStore

import backend

# --- Цвета сайта ---
BG = (0.05, 0.02, 0.10, 1)
PANEL = (0.09, 0.03, 0.16, 1)
NEON = (0.0, 0.94, 1.0, 1)
PINK = (1.0, 0.17, 0.82, 1)
TEXT = (0.92, 0.92, 0.96, 1)
MUTED = (0.62, 0.60, 0.70, 1)

# Ключ контейнера .cah. Лежит в приложении — см. оговорку в backend.py.
CONTAINER_KEY = 'cyberaudio-hub-offline-v1'


def painted(widget, color):
    """Заливка фона виджета: у Kivy её нет из коробки."""
    with widget.canvas.before:
        Color(*color)
        rect = Rectangle(pos=widget.pos, size=widget.size)

    def resize(*_args):
        rect.pos = widget.pos
        rect.size = widget.size

    widget.bind(pos=resize, size=resize)
    return widget


def bordered(widget, color):
    with widget.canvas.after:
        Color(*color)
        line = Line(rectangle=(*widget.pos, *widget.size), width=1.1)

    def resize(*_args):
        line.rectangle = (*widget.pos, *widget.size)

    widget.bind(pos=resize, size=resize)
    return widget


class NeonButton(Button):
    def __init__(self, text='', accent=NEON, **kwargs):
        super().__init__(text=text, **kwargs)
        self.background_normal = ''
        self.background_color = (0, 0, 0, 0)
        self.color = accent
        self.size_hint_y = None
        self.height = dp(48)
        self.halign = 'center'
        painted(self, PANEL)
        bordered(self, accent)


class Field(TextInput):
    def __init__(self, hint='', **kwargs):
        super().__init__(hint_text=hint, **kwargs)
        self.multiline = False
        self.size_hint_y = None
        self.height = dp(46)
        self.background_normal = ''
        self.background_active = ''
        self.background_color = PANEL
        self.foreground_color = TEXT
        self.cursor_color = NEON
        self.padding = [dp(12), dp(12)]


class Title(Label):
    def __init__(self, text='', size=22, color=NEON, **kwargs):
        super().__init__(text=text, **kwargs)
        self.font_size = f'{size}sp'
        self.color = color
        self.size_hint_y = None
        self.height = dp(size * 2)
        self.bold = True


# --- Экран входа ---

class LoginScreen(Screen):
    def __init__(self, app, **kwargs):
        super().__init__(**kwargs)
        self.app = app
        root = BoxLayout(orientation='vertical', padding=dp(20), spacing=dp(10))
        painted(root, BG)

        root.add_widget(Title('CYBER_AUDIO_HUB', size=24))
        root.add_widget(Label(text='Ваша коллекция из будущего', color=PINK,
                              size_hint_y=None, height=dp(28)))

        self.server = Field('Адрес сервера, например 192.168.31.4:2077')
        self.login = Field('Логин')
        self.password = Field('Пароль')
        self.password.password = True
        for widget in (self.server, self.login, self.password):
            root.add_widget(widget)

        self.message = Label(text='', color=PINK, size_hint_y=None, height=dp(40))
        root.add_widget(self.message)

        enter = NeonButton('ВОЙТИ')
        enter.bind(on_release=lambda *_: self.submit('login'))
        root.add_widget(enter)

        signup = NeonButton('ЗАРЕГИСТРИРОВАТЬСЯ', accent=PINK)
        signup.bind(on_release=lambda *_: self.submit('register'))
        root.add_widget(signup)

        offline = NeonButton('ТОЛЬКО СКАЧАННОЕ', accent=MUTED)
        offline.bind(on_release=lambda *_: self.app.open_offline())
        root.add_widget(offline)

        root.add_widget(BoxLayout())
        self.add_widget(root)

    def submit(self, mode):
        host = self.server.text.strip()
        if host and not host.startswith('http'):
            host = 'http://' + host
        self.message.text = 'Соединяюсь...'
        self.app.api.base_url = host

        def work():
            try:
                if mode == 'register':
                    self.app.api.register(self.login.text.strip(), self.password.text)
                else:
                    self.app.api.login(self.login.text.strip(), self.password.text)
                self.app.remember_server(host)
                self.done('')
            except backend.ApiError as exc:
                self.done(str(exc))

        threading.Thread(target=work, daemon=True).start()

    @mainthread
    def done(self, error):
        self.message.text = error
        if not error:
            self.app.open_library()


# --- Полка с книгами ---

class ShelfScreen(Screen):
    def __init__(self, app, **kwargs):
        super().__init__(**kwargs)
        self.app = app
        self.path = ''
        self.offline = False

        root = BoxLayout(orientation='vertical', padding=dp(12), spacing=dp(8))
        painted(root, BG)

        head = BoxLayout(size_hint_y=None, height=dp(46), spacing=dp(8))
        self.back = NeonButton('< НАЗАД', accent=PINK, size_hint_x=None, width=dp(120))
        self.back.bind(on_release=lambda *_: self.go_up())
        head.add_widget(self.back)
        self.crumbs = Label(text='Медиатека', color=TEXT, halign='left', valign='middle')
        self.crumbs.bind(size=lambda w, *_: setattr(w, 'text_size', w.size))
        head.add_widget(self.crumbs)
        root.add_widget(head)

        self.status = Label(text='', color=MUTED, size_hint_y=None, height=dp(24))
        root.add_widget(self.status)

        scroll = ScrollView()
        self.grid = GridLayout(cols=1, size_hint_y=None, spacing=dp(8), padding=[0, dp(4)])
        self.grid.bind(minimum_height=self.grid.setter('height'))
        scroll.add_widget(self.grid)
        root.add_widget(scroll)

        tools = BoxLayout(size_hint_y=None, height=dp(46), spacing=dp(8))
        saved = NeonButton('СКАЧАННОЕ')
        saved.bind(on_release=lambda *_: self.show_offline())
        tools.add_widget(saved)
        online = NeonButton('МЕДИАТЕКА')
        online.bind(on_release=lambda *_: self.open_path(''))
        tools.add_widget(online)
        root.add_widget(tools)

        self.add_widget(root)

    # --- Наполнение ---

    def open_path(self, path):
        self.offline = False
        self.path = path
        self.crumbs.text = 'Медиатека' + (f' > {path.replace("/", " > ")}' if path else '')
        self.status.text = 'Загрузка...'
        self.grid.clear_widgets()

        def work():
            try:
                data = self.app.api.browse(path)
                self.fill(data)
            except backend.ApiError as exc:
                self.fail(str(exc))

        threading.Thread(target=work, daemon=True).start()

    @mainthread
    def fail(self, message):
        self.status.text = message

    @mainthread
    def fill(self, data):
        self.status.text = ''
        self.grid.clear_widgets()

        if data.get('type') == 'album':
            self.app.open_player(data, offline=False)
            return

        for item in data.get('items', []):
            title = item.get('name', '')
            mark = '📁' if item.get('type') == 'directory' else '📖'
            row = NeonButton(f'{mark}  {title}')
            row.halign = 'left'
            row.text_size = (Window.width - dp(50), None)
            row.bind(on_release=lambda _b, p=item['path']: self.open_path(p))
            self.grid.add_widget(row)

        if not data.get('items'):
            self.status.text = 'Здесь пусто.'

    def show_offline(self):
        self.offline = True
        self.crumbs.text = 'Скачанные книги'
        self.status.text = ''
        self.grid.clear_widgets()
        books = self.app.library.list_books()
        if not books:
            self.status.text = 'Скачанных книг пока нет.'
            return
        for book in books:
            row = NeonButton(f'💾  {book["title"]}')
            row.halign = 'left'
            row.text_size = (Window.width - dp(50), None)
            row.bind(on_release=lambda _b, b=book: self.app.open_saved(b))
            self.grid.add_widget(row)

    def go_up(self):
        if self.offline:
            self.open_path('')
            return
        if not self.path:
            self.app.open_login()
            return
        self.open_path(self.path.rsplit('/', 1)[0] if '/' in self.path else '')


# --- Плеер ---

class PlayerScreen(Screen):
    def __init__(self, app, **kwargs):
        super().__init__(**kwargs)
        self.app = app
        self.album = None
        self.offline = False
        self.folder = None
        self.index = 0
        self.sound = None

        root = BoxLayout(orientation='vertical', padding=dp(12), spacing=dp(8))
        painted(root, BG)

        back = NeonButton('< К ПОЛКЕ', accent=PINK, size_hint_y=None, height=dp(44))
        back.bind(on_release=lambda *_: self.app.open_library())
        root.add_widget(back)

        self.title = Title('', size=20)
        root.add_widget(self.title)
        self.track = Label(text='', color=MUTED, size_hint_y=None, height=dp(30))
        root.add_widget(self.track)

        controls = BoxLayout(size_hint_y=None, height=dp(56), spacing=dp(8))
        for label, handler in (('|<', self.previous), ('||>', self.toggle), ('>|', self.next_track)):
            btn = NeonButton(label)
            btn.bind(on_release=lambda _b, h=handler: h())
            controls.add_widget(btn)
        root.add_widget(controls)

        self.save_btn = NeonButton('СКАЧАТЬ НА ТЕЛЕФОН', accent=PINK)
        self.save_btn.bind(on_release=lambda *_: self.download())
        root.add_widget(self.save_btn)

        self.status = Label(text='', color=MUTED, size_hint_y=None, height=dp(28))
        root.add_widget(self.status)

        scroll = ScrollView()
        self.list = GridLayout(cols=1, size_hint_y=None, spacing=dp(6))
        self.list.bind(minimum_height=self.list.setter('height'))
        scroll.add_widget(self.list)
        root.add_widget(scroll)

        self.add_widget(root)

    def load(self, album, offline=False, folder=None):
        self.album = album
        self.offline = offline
        self.folder = folder
        self.index = 0
        self.title.text = album.get('title', '')
        self.save_btn.disabled = offline
        self.save_btn.text = 'УЖЕ НА ТЕЛЕФОНЕ' if offline else 'СКАЧАТЬ НА ТЕЛЕФОН'

        self.list.clear_widgets()
        names = ([t['name'] for t in album.get('tracks', [])] if not offline
                 else album.get('tracks', []))
        for i, name in enumerate(names):
            row = NeonButton(f'{i + 1}. {name}')
            row.halign = 'left'
            row.text_size = (Window.width - dp(50), None)
            row.bind(on_release=lambda _b, n=i: self.play(n))
            self.list.add_widget(row)
        if names:
            self.play(0, autostart=False)

    # --- Воспроизведение ---

    def play(self, index, autostart=True):
        from kivy.core.audio import SoundLoader
        self.stop()
        self.index = index
        try:
            if self.offline:
                raw = self.app.library.track_bytes(self.folder, index)
                temp = os.path.join(self.app.cache_dir, 'now.mp3')
                with open(temp, 'wb') as f:
                    f.write(raw)
                source = temp
                name = self.album['tracks'][index]
            else:
                track = self.album['tracks'][index]
                source = self.app.api.base_url + track['url'] \
                    if track['url'].startswith('/') else track['url']
                name = track['name']
        except Exception as exc:                      # noqa: BLE001
            self.status.text = f'Не удалось открыть главу: {exc}'
            return

        self.track.text = name
        self.sound = SoundLoader.load(source)
        if self.sound and autostart:
            self.sound.play()
            self.app.show_notification(self.album.get('title', ''), name)

    def toggle(self):
        if not self.sound:
            return
        if self.sound.state == 'play':
            self.sound.stop()
        else:
            self.sound.play()

    def stop(self):
        if self.sound:
            self.sound.stop()
            self.sound = None

    def next_track(self):
        total = len(self.album.get('tracks', []))
        if total:
            self.play((self.index + 1) % total)

    def previous(self):
        total = len(self.album.get('tracks', []))
        if total:
            self.play((self.index - 1) % total)

    # --- Скачивание ---

    def download(self):
        if self.offline or not self.album:
            return
        self.status.text = 'Скачиваю...'
        album = self.album

        def work():
            try:
                pieces = []
                total = len(album['tracks'])
                for i, track in enumerate(album['tracks']):
                    url = track['url']
                    if url.startswith('/'):
                        url = self.app.api.base_url + url
                    raw = self.app.api.fetch_bytes(url)
                    pieces.append((track['name'], raw))
                    self.progress(f'Скачано глав: {i + 1} из {total}')
                self.app.library.save_book(album['path'], album.get('title', ''), pieces)
                self.progress('Книга сохранена на телефоне.')
            except Exception as exc:                  # noqa: BLE001
                self.progress(f'Не получилось: {exc}')

        threading.Thread(target=work, daemon=True).start()

    @mainthread
    def progress(self, message):
        self.status.text = message


# --- Приложение ---

class CyberAudioApp(App):
    title = APP_TITLE = 'CyberAudio Hub'

    def build(self):
        Window.clearcolor = BG
        self.api = backend.Api()
        self.cache_dir = self.user_data_dir
        self.library = backend.Library(os.path.join(self.user_data_dir, 'books'),
                                       CONTAINER_KEY)
        self.store = JsonStore(os.path.join(self.user_data_dir, 'settings.json'))

        self.manager = ScreenManager(transition=SlideTransition(duration=0.2))
        self.login_screen = LoginScreen(self, name='login')
        self.shelf = ShelfScreen(self, name='shelf')
        self.player = PlayerScreen(self, name='player')
        for screen in (self.login_screen, self.shelf, self.player):
            self.manager.add_widget(screen)

        if self.store.exists('server'):
            self.login_screen.server.text = self.store.get('server')['url']
        return self.manager

    # --- Переходы ---

    def remember_server(self, url):
        self.store.put('server', url=url)

    def open_login(self):
        self.manager.current = 'login'

    def open_library(self):
        self.manager.current = 'shelf'
        self.shelf.open_path('')

    def open_offline(self):
        self.manager.current = 'shelf'
        self.shelf.show_offline()

    def open_player(self, album, offline=False, folder=None):
        self.manager.current = 'player'
        self.player.load(album, offline=offline, folder=folder)

    def open_saved(self, book):
        self.open_player({'title': book['title'], 'tracks': book['tracks'],
                          'path': book['path']},
                         offline=True, folder=book['folder'])

    # --- Уведомление с управлением ---

    def show_notification(self, book, track):
        """
        Постоянное уведомление в шторке и на экране блокировки.
        Работает только на Android; на компьютере молча пропускается.
        """
        try:
            from jnius import autoclass
        except ImportError:
            return
        try:
            PythonActivity = autoclass('org.kivy.android.PythonActivity')
            Context = autoclass('android.content.Context')
            Builder = autoclass('androidx.core.app.NotificationCompat$Builder')
            Channel = autoclass('android.app.NotificationChannel')
            Manager = autoclass('android.app.NotificationManager')

            activity = PythonActivity.mActivity
            service = activity.getSystemService(Context.NOTIFICATION_SERVICE)
            channel = Channel('cah_play', 'Воспроизведение', Manager.IMPORTANCE_LOW)
            service.createNotificationChannel(channel)

            builder = Builder(activity, 'cah_play')
            builder.setContentTitle(book)
            builder.setContentText(track)
            builder.setOngoing(True)
            builder.setVisibility(1)  # VISIBILITY_PUBLIC — видно на блокировке
            builder.setSmallIcon(activity.getApplicationInfo().icon)
            service.notify(1, builder.build())
        except Exception:                             # noqa: BLE001
            # Уведомление — приятное дополнение, из-за него падать нельзя
            pass

    def on_pause(self):
        return True


if __name__ == '__main__':
    CyberAudioApp().run()
