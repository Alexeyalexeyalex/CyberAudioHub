package com.cyberaudio.hub;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ContextThemeWrapper;
import org.json.JSONObject;

/** Runs only against tools/preview.py through adb reverse tcp:2078 tcp:2078.
 * Test-package preferences are separate from the reader's account and downloads.
 * No Activity is launched and the phone lock screen is not touched.
 */
public final class LocalChecks extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Context isolated = getContext();
            Api api = new Api(isolated);
            api.setServer("http://127.0.0.1:2078");
            api.forgetSession();
            api.login("preview_admin", "preview_local_only");
            if (!api.isSignedIn()) throw new AssertionError("Session cookie not retained");
            JSONObject user = new Api(isolated).me().getJSONObject("user");
            if (!"preview_admin".equals(user.optString("login")) || user.optString("nickname").isEmpty()
                    || !user.optBoolean("is_admin")) throw new AssertionError("Profile missing fields");
            api.forgetSession();
            final Throwable[] failure = new Throwable[1];
            final int[] visible = new int[1];
            runOnMainSync(() -> {
                try {
                    String paragraph = repeat("Local reader test paragraph. ", 16);
                    String text = repeat(paragraph + "\n\n", 6000);
                    Transcripts.Timeline timeline = new Transcripts.Timeline(text,
                            new int[]{0}, new int[]{5}, new double[]{0}, new double[]{1}, new int[]{0}, new int[0][]);
                    ReaderView reader = new ReaderView(new ContextThemeWrapper(getTargetContext(), R.style.Theme_CyberAudio), (c,m)->{});
                    reader.timeline(timeline);
                    reader.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.EXACTLY));
                    reader.layout(0,0,1080,1800);
                    visible[0] = reader.getChildCount();
                    if (reader.getCount() != 6000 || visible[0] < 1 || visible[0] > 30)
                        throw new AssertionError("Reader did not recycle paragraphs: " + visible[0]);
                    reader.follow(0,.5,true,true);
                    reader.follow(0,.5,false,true);
                } catch (Throwable e) { failure[0] = e; }
            });
            if (failure[0] != null) throw new AssertionError("Reader layout", failure[0]);
            result.putString("stream", "PASS: Android login, cookie reuse, profile fields; reader " + visible[0] + "/6000 paragraphs rendered.\n");
            finish(-1, result);
        } catch (Throwable e) {
            result.putString("stream", "FAIL: " + e + (e.getCause() == null ? "" : ": " + e.getCause()) + "\n");
            finish(0, result);
        }
    }
    private static String repeat(String text, int count) {
        StringBuilder result = new StringBuilder(text.length() * count);
        for (int i = 0; i < count; i++) result.append(text);
        return result.toString();
    }
}
