package com.cyberaudio.hub;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Свой контейнер для скачанных книг — файлы .cah.
 *
 * Обычный плеер такой файл не откроет: заголовок не совпадает ни с одним
 * известным форматом, а содержимое перемешано потоком байтов от ключа.
 *
 * Честно о степени защиты: это заслон от случайного открытия, а не от
 * специалиста. Ключ лежит внутри приложения, и тот, кто захочет, достанет
 * исходный звук. Настоящая защита потребовала бы выдачи ключей сервером и
 * полноценного DRM, чего здесь нет и не планировалось.
 *
 * Формат совпадает байт в байт с реализацией на Python (application/backend.py),
 * поэтому книга, скачанная одной версией приложения, читается другой:
 *
 *     "CAH1" | длина заголовка, 4 байта big-endian | JSON | перемешанный звук
 */
public final class Container {

    private static final byte[] MAGIC = {'C', 'A', 'H', '1'};
    private static final int BLOCK = 32;               // размер выхода SHA-256

    private Container() {
    }

    /**
     * Поток байтов из ключа: SHA-256 по номеру блока.
     *
     * offset нужен, чтобы шифровать файл кусками, а не целиком в памяти:
     * глава на сорок минут в несжатом виде телефон бы не пережил.
     */
    private static byte[] keystream(byte[] key, int length, long offset)
            throws IOException {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("нет SHA-256", e);   // на Android не бывает
        }
        long block = offset / BLOCK;
        int skip = (int) (offset % BLOCK);
        byte[] out = new byte[length + skip];
        int filled = 0;
        while (filled < out.length) {
            sha.reset();
            sha.update(key);
            sha.update(ByteBuffer.allocate(8).putLong(block).array());
            byte[] digest = sha.digest();
            int take = Math.min(BLOCK, out.length - filled);
            System.arraycopy(digest, 0, out, filled, take);
            filled += take;
            block += 1;
        }
        byte[] result = new byte[length];
        System.arraycopy(out, skip, result, 0, length);
        return result;
    }

    /** Накладывает поток на данные. Операция симметричная: та же и туда, и обратно. */
    static void mask(byte[] data, int length, byte[] key, long offset)
            throws IOException {
        byte[] stream = keystream(key, length, offset);
        for (int i = 0; i < length; i++) {
            data[i] ^= stream[i];
        }
    }

    /**
     * Пишет скачанную главу в контейнер, переливая поток кусками.
     * Возвращает число байтов звука.
     */
    public static long pack(InputStream audio, File target, byte[] key, JSONObject meta)
            throws IOException {
        byte[] header = meta.toString().getBytes(StandardCharsets.UTF_8);
        long total = 0;
        try (OutputStream out = new FileOutputStream(target)) {
            out.write(MAGIC);
            out.write(ByteBuffer.allocate(4).putInt(header.length).array());
            out.write(header);

            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = audio.read(buffer)) > 0) {
                mask(buffer, read, key, total);
                out.write(buffer, 0, read);
                total += read;
            }
        }
        return total;
    }

    /** Сведения о главе из заголовка контейнера. */
    public static JSONObject readMeta(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            byte[] head = new byte[8];
            if (in.read(head) != 8 || head[0] != 'C' || head[1] != 'A'
                    || head[2] != 'H' || head[3] != '1') {
                throw new IOException("Это не файл CyberAudio Hub");
            }
            int size = ByteBuffer.wrap(head, 4, 4).getInt();
            if (size < 0 || size > 1 << 20) {
                throw new IOException("Повреждённый заголовок");
            }
            byte[] json = new byte[size];
            int got = 0;
            while (got < size) {
                int n = in.read(json, got, size - got);
                if (n < 0) throw new IOException("Файл обрывается на заголовке");
                got += n;
            }
            try {
                return new JSONObject(new String(json, StandardCharsets.UTF_8));
            } catch (Exception e) {
                throw new IOException("Не читается заголовок", e);
            }
        }
    }

    /** С какого байта в файле начинается звук. */
    public static long audioOffset(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            byte[] head = new byte[8];
            if (in.read(head) != 8) throw new IOException("Файл слишком короткий");
            return 8 + ByteBuffer.wrap(head, 4, 4).getInt();
        }
    }
}
