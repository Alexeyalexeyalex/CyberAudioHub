package com.cyberaudio.hub;

import android.media.MediaDataSource;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Отдаёт MediaPlayer'у звук из контейнера .cah, расшифровывая на лету.
 *
 * Без этого пришлось бы перед каждым прослушиванием разворачивать книгу
 * во временный mp3 — то есть держать на телефоне вторую копию и оставлять
 * её открытой для любого файлового менеджера. Здесь расшифровывается ровно
 * тот кусок, который плеер прямо сейчас просит.
 *
 * MediaDataSource появился в Android 6 (API 23), а приложение требует 24 —
 * так что доступен всегда.
 */
public class ContainerDataSource extends MediaDataSource {

    private final RandomAccessFile file;
    private final long offset;      // где в файле кончается заголовок
    private final long length;      // длина звука
    private final byte[] key;

    public ContainerDataSource(File source) throws IOException {
        offset = Container.audioOffset(source);
        file = new RandomAccessFile(source, "r");
        length = file.length() - offset;
        key = Store.containerKey();
    }

    @Override
    public int readAt(long position, byte[] buffer, int bufferOffset, int size)
            throws IOException {
        if (position >= length) return -1;          // конец файла
        int want = (int) Math.min(size, length - position);
        if (want <= 0) return -1;

        synchronized (file) {
            file.seek(offset + position);
            int read = file.read(buffer, bufferOffset, want);
            if (read <= 0) return read;
            // Поток ключа привязан к позиции в звуке, а не в файле, — поэтому
            // перемотка в середину главы расшифровывается правильно
            byte[] chunk = new byte[read];
            System.arraycopy(buffer, bufferOffset, chunk, 0, read);
            Container.mask(chunk, read, key, position);
            System.arraycopy(chunk, 0, buffer, bufferOffset, read);
            return read;
        }
    }

    @Override
    public long getSize() {
        return length;
    }

    @Override
    public void close() throws IOException {
        file.close();
    }
}
