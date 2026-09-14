package com.cyberaudio.hub;

import org.json.JSONArray;
import org.junit.Test;
import static org.junit.Assert.*;

public class TranscriptsTest {
    private JSONArray part() throws Exception {
        return new JSONArray("[{\"s\":0,\"e\":3,\"t\":\"Привет, мир!\",\"w\":[[\"Привет,\",0,1],[\"мир!\",1,3]]}]");
    }
    @Test public void highlightsWordAndDimsPast() throws Exception {
        Transcripts.Timeline t = Transcripts.build(new JSONArray[]{part()}, new String[]{""}, 0);
        int[] word = t.wordAt(0, 2);
        assertNotNull(word);
        assertEquals("мир!", t.text.substring(word[0], word[1]));
        assertTrue(t.spokenUntil(0, 2) >= 7);
    }
    @Test public void headingsAndChapterOffsetsRemainCorrect() throws Exception {
        Transcripts.Timeline t = Transcripts.build(new JSONArray[]{part(), part()}, new String[]{"Глава 1", "Глава 2"}, 0);
        assertEquals(2, t.headings());
        assertEquals(1, t.heading(1)[2]);
        assertTrue(t.wordAt(1, 2)[0] > t.heading(1)[0]);
        assertEquals(1, (int) t.timeAt(t.wordAt(1, 2)[0])[0]);
    }
    @Test public void missingChapterIsSafe() {
        Transcripts.Timeline t = Transcripts.build(new JSONArray[]{null}, new String[]{"Глава 1"}, 0);
        assertTrue(t.isEmpty());
        assertNull(t.wordAt(0, 2));
    }

    @Test(timeout = 5000) public void largeBookUsesBoundedRowsAndFastLookups() {
        int count = 300000;
        StringBuilder text = new StringBuilder(count * 5);
        int[] from = new int[count], to = new int[count], tracks = new int[count];
        double[] start = new double[count], end = new double[count];
        for (int i = 0; i < count; i++) {
            from[i] = text.length(); text.append("word"); to[i] = text.length();
            text.append(i % 50 == 49 ? "\n\n" : " ");
            tracks[i] = i / 1000; start[i] = (i % 1000) * 2; end[i] = start[i] + 1;
        }
        Transcripts.Timeline t = new Transcripts.Timeline(text.toString(), from, to, start, end, tracks, new int[0][]);
        for (int[] row : t.rows) assertTrue(row[1] - row[0] <= 800);
        for (int i = 0; i < count; i += 7) {
            assertArrayEquals(new int[]{from[i], to[i]}, t.wordAt(tracks[i], start[i] + .5));
            assertNull(t.wordAt(tracks[i], start[i] + 1.5));
            assertEquals(to[i], t.spokenUntil(tracks[i], end[i]));
            assertEquals(tracks[i], (int)t.timeAt(from[i])[0]);
            int row = t.rowAt(from[i]);
            assertTrue(t.rows[row][0] <= from[i] && t.rows[row][1] >= to[i]);
        }
    }

    @Test public void longParagraphIsSplitWithoutLosingWords() {
        String text = String.join(" ", java.util.Collections.nCopies(1000, "слово"));
        Transcripts.Timeline t = new Transcripts.Timeline(text, new int[0], new int[0], new double[0], new double[0], new int[0], new int[0][]);
        assertTrue(t.rows.length > 1);
        StringBuilder rebuilt = new StringBuilder();
        for (int[] row : t.rows) {
            assertTrue(row[1] - row[0] <= 800);
            if (rebuilt.length() > 0) rebuilt.append(' ');
            rebuilt.append(text, row[0], row[1]);
        }
        assertEquals(text, rebuilt.toString());
    }
}
