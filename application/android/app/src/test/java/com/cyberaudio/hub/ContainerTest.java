package com.cyberaudio.hub;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Проверка контейнера .cah на обычной JVM — без телефона и эмулятора.
 *
 * Эталонные значения посчитаны реализацией на Python (application/backend.py):
 * книга, скачанная одной версией приложения, должна читаться другой, поэтому
 * поток шифрования обязан совпадать байт в байт.
 */
public class ContainerTest {

    /** Тот же ключ, что отдаёт Store.containerKey(). */
    private static byte[] key() throws Exception {
        return MessageDigest.getInstance("SHA-256")
                .digest("CyberAudioHub/v1".getBytes(StandardCharsets.UTF_8));
    }

    /** Предсказуемые данные: 0..255 три раза подряд. */
    private static byte[] sample() {
        byte[] out = new byte[768];
        for (int i = 0; i < out.length; i++) out[i] = (byte) (i % 256);
        return out;
    }

    private static String hex(byte[] data, int from, int length) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < from + length; i++) {
            sb.append(String.format("%02x", data[i]));
        }
        return sb.toString();
    }

    @Test
    public void streamMatchesPython() throws Exception {
        byte[] data = sample();
        Container.mask(data, data.length, key(), 0);
        assertEquals("поток шифрования разошёлся с реализацией на Python",
                "32be1237179f596106e9019b48d6bbfd3b5518fe49be1456c58f15045dacb235",
                hex(data, 0, 32));
    }

    /**
     * Перемотка: кусок с середины должен шифроваться тем же потоком, что и
     * при обработке файла целиком. Если это сломается, звук после перемотки
     * превратится в шум — а поймать это на слух трудно.
     */
    @Test
    public void offsetMatchesPython() throws Exception {
        byte[] chunk = new byte[32];
        System.arraycopy(sample(), 500, chunk, 0, 32);
        Container.mask(chunk, chunk.length, key(), 500);
        assertEquals("шифрование со смещением разошлось с Python",
                "7a15656a2773810b5a63adc8f2ff2162158d6c6872d075a8ed4cd45f76d94bd4",
                hex(chunk, 0, 32));
    }

    /** Наложить поток дважды — получить исходные данные. */
    @Test
    public void maskIsReversible() throws Exception {
        byte[] original = sample();
        byte[] data = original.clone();
        Container.mask(data, data.length, key(), 0);
        assertTrue("после первого наложения данные не изменились",
                !java.util.Arrays.equals(original, data));
        Container.mask(data, data.length, key(), 0);
        assertArrayEquals("повторное наложение не вернуло исходные данные",
                original, data);
    }

    @Test
    public void packAndReadBack() throws Exception {
        File target = File.createTempFile("book", ".cah");
        target.deleteOnExit();

        JSONObject meta = new JSONObject()
                .put("book", "Тест").put("track", "Глава 1").put("index", 0);
        long written = Container.pack(
                new ByteArrayInputStream(sample()), target, key(), meta);

        assertEquals("записалось не столько байт звука", 768, written);

        JSONObject back = Container.readMeta(target);
        assertEquals("Тест", back.getString("book"));
        assertEquals("Глава 1", back.getString("track"));

        long offset = Container.audioOffset(target);
        assertEquals("смещение звука не сходится с длиной заголовка",
                target.length() - 768, offset);
    }

    /** Чужой файл должен быть отвергнут, а не проигран как шум. */
    @Test(expected = IOException.class)
    public void rejectsForeignFile() throws Exception {
        File mp3 = File.createTempFile("foreign", ".mp3");
        mp3.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(mp3)) {
            out.write(new byte[]{'I', 'D', '3', 4, 0, 0, 0, 0});
        }
        Container.readMeta(mp3);
    }

    /**
     * Файл, собранный питоном, должен читаться приложением. Собираем его
     * здесь по тому же описанию формата, что и на сервере.
     */
    @Test
    public void readsPythonContainer() throws Exception {
        byte[] header = "{\"book\": \"Тест\", \"index\": 0}"
                .getBytes(StandardCharsets.UTF_8);
        byte[] audio = sample();
        byte[] masked = audio.clone();
        Container.mask(masked, masked.length, key(), 0);

        File file = File.createTempFile("frompython", ".cah");
        file.deleteOnExit();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(new byte[]{'C', 'A', 'H', '1'});
            out.write(ByteBuffer.allocate(4).putInt(header.length).array());
            out.write(header);
            out.write(masked);
        }

        assertEquals("Тест", Container.readMeta(file).getString("book"));
        assertEquals(8 + header.length, Container.audioOffset(file));
    }
}
