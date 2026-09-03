package com.cyberaudio.hub;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Текст книги с привязкой ко времени — чтобы следить за чтением глазами.
 *
 * Сегмент — это реплика: у неё есть начало, конец, текст и разбивка по
 * словам с их временем. Такой же формат отдаёт сайт, поэтому подсветка
 * в приложении совпадает с подсветкой в браузере до слова.
 *
 * Текст лежит рядом со скачанной главой, обычным json: он весит килобайты
 * против десятков мегабайт звука, поэтому качаем его всегда вместе с
 * книгой — без сети слушать с текстом иначе не выйдет.
 */
public final class Transcripts {

    /**
     * Готовый к показу текст главы: сплошная строка и карта слов в ней.
     *
     * Карта строится один раз при загрузке. Позиции слов ищутся по тексту,
     * а не складываются из их длин: распознавание не всегда собирает
     * реплику из слов дословно — где-то съест пробел, где-то приклеит
     * запятую, — и арифметика поехала бы к середине главы.
     */
    public static class Timeline {
        public final String text;
        /** Начало слова в тексте. */
        final int[] from;
        /** Конец слова в тексте. */
        final int[] to;
        /** Когда слово звучит, в секундах. */
        final double[] since;
        final double[] until;
        /** Из какой главы слово. В режиме одной главы везде одно и то же. */
        final int[] track;

        Timeline(String text, int[] from, int[] to,
                 double[] since, double[] until, int[] track) {
            this.text = text;
            this.from = from;
            this.to = to;
            this.since = since;
            this.until = until;
            this.track = track;
        }

        public boolean isEmpty() {
            return from.length == 0;
        }

        /**
         * Границы слова, которое звучит в этот момент, или null в паузе.
         *
         * Перебор линейный: слов в главе тысячи, но проверка — два
         * сравнения, и на каждом тике это доли миллисекунды. Двоичный
         * поиск здесь усложнил бы код без заметной пользы.
         */
        public int[] wordAt(int chapter, double seconds) {
            for (int i = 0; i < from.length; i++) {
                if (track[i] != chapter) continue;
                if (seconds >= since[i] && seconds <= until[i]) {
                    return new int[]{from[i], to[i]};
                }
                // Внутри главы слова идут по возрастанию времени
                if (track[i] == chapter && since[i] > seconds) break;
            }
            return null;
        }

        /**
         * Где заканчивается уже прочитанное. Всё до этой границы гасим:
         * так глаз сразу находит место, на котором идёт чтение, и не
         * перечитывает то, что уже прозвучало.
         */
        public int spokenUntil(int chapter, double seconds) {
            int edge = 0;
            for (int i = 0; i < from.length; i++) {
                boolean past = track[i] < chapter
                        || (track[i] == chapter && until[i] <= seconds);
                if (!past) break;
                edge = to[i];
            }
            return edge;
        }

        /** Куда перематывать, если ткнуть в это место текста. */
        public double[] timeAt(int offset) {
            for (int i = 0; i < from.length; i++) {
                if (offset >= from[i] && offset <= to[i]) {
                    return new double[]{track[i], since[i]};
                }
            }
            return null;
        }
    }

    /** Собирает текст одной главы. */
    public static Timeline build(JSONArray segments) {
        return build(new JSONArray[]{segments}, new String[]{""}, 0);
    }

    /**
     * Собирает сплошной текст и карту слов.
     *
     * chapters — сегменты по главам, titles — их названия (пусто, если
     * показываем одну главу). first — номер первой главы в наборе: в
     * режиме одной главы это её номер, в режиме всей книги — ноль.
     */
    public static Timeline build(JSONArray[] chapters, String[] titles, int first) {
        StringBuilder text = new StringBuilder();
        java.util.List<int[]> spans = new java.util.ArrayList<>();
        java.util.List<double[]> times = new java.util.ArrayList<>();
        java.util.List<Integer> tracks = new java.util.ArrayList<>();

        for (int c = 0; c < chapters.length; c++) {
            JSONArray segments = chapters[c];
            int chapter = first + c;
            if (titles.length > c && !titles[c].isEmpty()) {
                text.append(titles[c]).append("\n\n");
            }
            for (int i = 0; segments != null && i < segments.length(); i++) {
                JSONObject segment = segments.optJSONObject(i);
                if (segment == null) continue;
                String line = segment.optString("t", "").trim();
                if (line.isEmpty()) continue;

                int base = text.length();
                text.append(line).append("\n\n");

                JSONArray words = segment.optJSONArray("w");
                if (words == null) continue;
                int cursor = base;
                for (int w = 0; w < words.length(); w++) {
                    JSONArray word = words.optJSONArray(w);
                    if (word == null) continue;
                    String piece = word.optString(0, "").trim();
                    if (piece.isEmpty()) continue;
                    int at = text.indexOf(piece, cursor);
                    // Слово не нашлось (распознавание переписало его иначе) —
                    // пропускаем: подсветка на нём просто не задержится
                    if (at < 0 || at >= base + line.length()) continue;
                    spans.add(new int[]{at, at + piece.length()});
                    times.add(new double[]{word.optDouble(1, 0), word.optDouble(2, 0)});
                    tracks.add(chapter);
                    cursor = at + piece.length();
                }
            }
        }

        int[] from = new int[spans.size()];
        int[] to = new int[spans.size()];
        double[] since = new double[spans.size()];
        double[] until = new double[spans.size()];
        int[] track = new int[spans.size()];
        for (int i = 0; i < spans.size(); i++) {
            from[i] = spans.get(i)[0];
            to[i] = spans.get(i)[1];
            since[i] = times.get(i)[0];
            until[i] = times.get(i)[1];
            track[i] = tracks.get(i);
        }
        return new Timeline(text.toString().trim(), from, to, since, until, track);
    }

    private Transcripts() {
    }

    /** Где лежит текст главы на телефоне. */
    public static File file(Store store, String path, int index) {
        return new File(store.bookDir(path),
                String.format(java.util.Locale.ROOT, "%03d.text.json", index + 1));
    }

    public static boolean isSaved(Store store, String path, int index) {
        return file(store, path, index).isFile();
    }

    /**
     * Сегменты главы с телефона. Сервер тут не спрашиваем вовсе: текст
     * скачивается вместе с книгой, а следить за ним можно только по
     * скачанной — тогда и в дороге без сети всё на месте.
     *
     * null — текста для этой главы нет.
     */
    public static JSONArray load(Store store, String path, int index) {
        File local = file(store, path, index);
        if (!local.isFile()) return null;
        try {
            return new JSONArray(read(local));
        } catch (Exception e) {
            return null;   // битый файл: перекачается вместе с книгой
        }
    }

    /** Скачивает текст главы и кладёт рядом со звуком. Тихо: текст — не звук. */
    public static void save(Api api, Store store, String path, int index) {
        try {
            JSONObject data = api.transcript(path, index);
            JSONObject track = data.optJSONObject("track");
            if (track == null) return;
            JSONArray segments = track.optJSONArray("segments");
            if (segments == null) return;
            File target = file(store, path, index);
            File dir = target.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) return;
            try (FileOutputStream out = new FileOutputStream(target)) {
                out.write(segments.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            // У книги может не быть текста — это не ошибка скачивания
        }
    }

    private static String read(File file) throws Exception {
        try (InputStream in = new java.io.FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int got;
            while ((got = in.read(buffer)) > 0) out.write(buffer, 0, got);
            return out.toString("UTF-8");
        }
    }
}
