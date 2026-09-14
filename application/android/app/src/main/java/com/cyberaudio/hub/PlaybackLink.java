package com.cyberaudio.hub;

import android.content.*;
import android.widget.Toast;
import org.json.*;

/** The centre record opens the current book, or the last played book after restart. */
final class PlaybackLink {
    private static final String KEY = "last_played_book";
    static void save(Context context, String path, String title, String cover, String[] sources, String[] names) {
        try {
            JSONObject book = new JSONObject().put("path", path).put("title", title).put("cover", cover)
                    .put("urls", new JSONArray(remoteSources(path, sources, names))).put("names", new JSONArray(names));
            context.getSharedPreferences("cyberaudio", Context.MODE_PRIVATE).edit().putString(KEY, book.toString()).apply();
        } catch (JSONException ignored) { }
    }
    static void open(Context context) {
        PlaybackService service = PlaybackService.get();
        if (service != null && !service.path().isEmpty()) {
            context.startActivity(service.playerIntent());
            return;
        }
        try {
            JSONObject book = new JSONObject(context.getSharedPreferences("cyberaudio", Context.MODE_PRIVATE).getString(KEY, "{}"));
            String path = book.optString("path");
            if (path.isEmpty()) throw new JSONException("empty");
            context.startActivity(new Intent(context, PlayerActivity.class)
                    .putExtra(PlayerActivity.EXTRA_PATH, path)
                    .putExtra(PlayerActivity.EXTRA_TITLE, book.optString("title"))
                    .putExtra(PlayerActivity.EXTRA_COVER, book.optString("cover"))
                    .putExtra(PlayerActivity.EXTRA_URLS, strings(book.optJSONArray("urls")))
                    .putExtra(PlayerActivity.EXTRA_NAMES, strings(book.optJSONArray("names")))
                    .putExtra(PlayerActivity.EXTRA_OFFLINE, new Store(context).isDownloaded(path)));
        } catch (JSONException e) {
            Toast.makeText(context, "Сначала выберите книгу в медиатеке", Toast.LENGTH_SHORT).show();
        }
    }
    private static String[] strings(JSONArray array) {
        String[] values = new String[array == null ? 0 : array.length()];
        for (int i = 0; i < values.length; i++) values[i] = array.optString(i);
        return values;
    }

    // A bookmark must remain usable after the downloaded copy is removed.
    static String[] remoteSources(String path, String[] sources, String[] names) {
        String[] result = sources.clone();
        for (int i = 0; i < result.length && i < names.length; i++) {
            if (result[i].startsWith("/data/")) {
                try { result[i] = "/media?path=" + java.net.URLEncoder.encode(path + "/" + names[i], "UTF-8"); }
                catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
            }
        }
        return result;
    }
}
