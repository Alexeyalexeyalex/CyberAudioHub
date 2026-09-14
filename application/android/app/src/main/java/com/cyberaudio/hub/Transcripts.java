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
        /** Заголовки глав: {начало, конец, номер главы}. Пусто вне режима книги. */
        final int[][] heads;
        public final int[][] rows;

        Timeline(String text, int[] from, int[] to,
                 double[] since, double[] until, int[] track, int[][] heads) {
            this.text = text;
            this.from = from;
            this.to = to;
            this.since = since;
            this.until = until;
            this.track = track;
            this.heads = heads;
            java.util.List<int[]> paragraphs = new java.util.ArrayList<>();
            for (int start = 0; start < text.length();) {
                int end = text.indexOf("\n\n", start);
                if (end < 0) end = text.length();
                while (end - start > 800) {
                    int cut = text.lastIndexOf(' ', start + 800);
                    if (cut <= start) cut = start + 800;
                    paragraphs.add(new int[]{start, cut}); start = cut;
                    while (start < end && text.charAt(start) == ' ') start++;
                }
                if (end > start) paragraphs.add(new int[]{start, end});
                start = end + 2;
            }
            rows = paragraphs.toArray(new int[0][]);
        }

        public int headings() {
            return heads.length;
        }

        /** Границы заголовка и номер его главы: {начало, конец, глава}. */
        public int[] heading(int i) {
            return heads[i];
        }

        public boolean isEmpty() {
            return from.length == 0;
        }

        /**
         * Границы слова, которое звучит в этот момент, или null в паузе.
         *
         * Двоичный поиск по главе и времени: стоимость тика не растёт
         * линейно с размером книги, даже если в ней сотни тысяч слов.
         */
        public int[] wordAt(int chapter, double seconds) {
            int lo = 0, hi = from.length;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (track[mid] < chapter || (track[mid] == chapter && until[mid] < seconds)) lo = mid + 1;
                else hi = mid;
            }
            if (lo < from.length && track[lo] == chapter && since[lo] <= seconds)
                return new int[]{from[lo], to[lo]};
            return null;
        }

        /**
         * Где заканчивается уже прочитанное. Всё до этой границы гасим:
         * так глаз сразу находит место, на котором идёт чтение, и не
         * перечитывает то, что уже прозвучало.
         */
        public int spokenUntil(int chapter, double seconds) {
            int lo = 0, hi = from.length;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (track[mid] < chapter || (track[mid] == chapter && until[mid] <= seconds)) lo = mid + 1;
                else hi = mid;
            }
            return lo == 0 ? 0 : to[lo - 1];
        }

        /** Куда перематывать, если ткнуть в это место текста. */
        public double[] timeAt(int offset) {
            int lo = 0, hi = from.length;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (from[mid] <= offset) lo = mid + 1; else hi = mid;
            }
            int i = lo - 1;
            if (i >= 0 && offset <= to[i]) return new double[]{track[i], since[i]};
            return null;
        }

        public int rowAt(int offset) {
            int lo = 0, hi = rows.length;
            while (lo < hi) {
                int mid = (lo + hi) >>> 1;
                if (rows[mid][0] <= offset) lo = mid + 1; else hi = mid;
            }
            return Math.max(0, lo - 1);
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
        java.util.List<int[]> heads = new java.util.ArrayList<>();

        for (int c = 0; c < chapters.length; c++) {
            JSONArray segments = chapters[c];
            int chapter = first + c;
            if (titles.length > c && !titles[c].isEmpty()) {
                // Запоминаем, где стоит заголовок: по нему экран покажет,
                // какая глава звучит сейчас, а какие — соседние
                int head = text.length();
                text.append(titles[c]);
                heads.add(new int[]{head, text.length(), chapter});
                text.append("\n\n");
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
        return new Timeline(text.toString().trim(), from, to, since, until,
                track, heads.toArray(new int[0][]));
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
