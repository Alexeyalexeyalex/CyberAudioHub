package com.cyberaudio.hub;

import android.content.Context;
import android.graphics.Typeface;
import android.text.*;
import android.text.style.*;
import android.view.*;
import android.widget.*;
import java.util.Arrays;

/** Recycles visible paragraphs. A large book never becomes one giant TextView. */
public final class ReaderView extends ListView {
    public interface Seek { void to(int chapter, int millis); }
    private Transcripts.Timeline timeline;
    private final Paragraphs adapter = new Paragraphs();
    private String message = "Загружаю текст…";
    private int[] word;
    private int edge = -1, chapter = -1;
    private long manualUntil;
    private final Seek seek;

    public ReaderView(Context context, Seek seek) {
        super(context); this.seek = seek;
        setDivider(null); setCacheColorHint(Ui.DARK);
        setBackgroundColor(Ui.SURFACE);
        setPadding(Ui.dp(context, 12), Ui.dp(context, 12), Ui.dp(context, 12), Ui.dp(context, 20));
        setClipToPadding(false); setAdapter(adapter);
        setOnScrollListener(new OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView v, int state) {
                if (state == SCROLL_STATE_TOUCH_SCROLL) manualUntil = android.os.SystemClock.uptimeMillis() + 4000;
            }
            @Override public void onScroll(AbsListView v, int first, int visible, int total) { }
        });
    }

    public void message(String text) {
        timeline = null; message = text; word = null; edge = -1;
        adapter.notifyDataSetChanged();
    }
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(
                event.getActionMasked() != android.view.MotionEvent.ACTION_UP
                && event.getActionMasked() != android.view.MotionEvent.ACTION_CANCEL);
        return super.dispatchTouchEvent(event);
    }
    public void timeline(Transcripts.Timeline value) {
        timeline = value; word = null; edge = -1;
        adapter.notifyDataSetChanged();
    }
    public void clearHighlight() {
        word = null; edge = -1; paintVisible();
    }
    public void follow(int currentChapter, double seconds, boolean following, boolean force) {
        if (timeline == null) return;
        int[] next = following ? timeline.wordAt(currentChapter, seconds) : null;
        int nextEdge = following ? timeline.spokenUntil(currentChapter, seconds) : -1;
        boolean changed = chapter != currentChapter || nextEdge != edge || !Arrays.equals(word, next);
        chapter = currentChapter; edge = nextEdge; word = next;
        if (changed || force) paintVisible();
        if (!following || word == null || (!force && android.os.SystemClock.uptimeMillis() < manualUntil)) return;
        int row = timeline.rowAt(word[0]);
        View child = getChildAt(row - getFirstVisiblePosition());
        int lineY = 0;
        if (child instanceof TextView) {
            TextView text = (TextView) child;
            Layout layout = text.getLayout();
            if (layout != null) lineY = layout.getLineTop(layout.getLineForOffset(word[0] - timeline.rows[row][0])) + text.getTotalPaddingTop();
            int y = child.getTop() + lineY;
            if (!force && y >= getHeight() / 5 && y <= getHeight() * 4 / 5) return;
        }
        if (force || child == null) setSelectionFromTop(row, getHeight() / 3 - lineY);
        else if (changed) smoothScrollToPositionFromTop(row, getHeight() / 3 - lineY, 140);
    }
    private void paintVisible() {
        for (int i = 0; i < getChildCount(); i++) {
            View view = getChildAt(i);
            if (view instanceof TextView) adapter.bind((TextView) view, getFirstVisiblePosition() + i);
        }
    }
    private final class Paragraphs extends BaseAdapter {
        @Override public int getCount() { return timeline == null ? 1 : timeline.rows.length; }
        @Override public Object getItem(int row) { return row; }
        @Override public long getItemId(int row) { return row; }
        @Override public boolean hasStableIds() { return true; }
        @Override public View getView(int row, View recycled, ViewGroup parent) {
            TextView text = recycled instanceof TextView ? (TextView) recycled : Ui.label(getContext(), "", Ui.TEXT, 17);
            text.setLineSpacing(Ui.dp(getContext(), 4), 1.15f);
            text.setPadding(0, Ui.dp(getContext(), 6), 0, Ui.dp(getContext(), 10));
            text.setClickable(true);
            text.setOnTouchListener(new OnTouchListener() {
                float x, y; boolean tapped;
                @Override public boolean onTouch(View v, android.view.MotionEvent e) {
                    if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                        x = e.getX(); y = e.getY(); tapped = true;
                    } else if (e.getActionMasked() == android.view.MotionEvent.ACTION_CANCEL) tapped = false;
                    else if (e.getActionMasked() == android.view.MotionEvent.ACTION_MOVE
                            && Math.hypot(e.getX() - x, e.getY() - y) > ViewConfiguration.get(getContext()).getScaledTouchSlop()) tapped = false;
                    else if (e.getActionMasked() == android.view.MotionEvent.ACTION_UP && tapped && timeline != null) {
                        int position = (Integer) text.getTag();
                        Layout layout = text.getLayout();
                        if (layout != null && position < timeline.rows.length) {
                            int line = layout.getLineForVertical((int)e.getY() - text.getTotalPaddingTop());
                            int offset = layout.getOffsetForHorizontal(line, e.getX() - text.getTotalPaddingLeft());
                            double[] at = timeline.timeAt(timeline.rows[position][0] + offset);
                            if (at != null) seek.to((int)at[0], (int)(at[1] * 1000));
                        }
                        text.performClick();
                    }
                    return false;
                }
            });
            bind(text, row); return text;
        }
        void bind(TextView view, int row) {
            view.setTag(row);
            if (timeline == null) { view.setText(message); return; }
            if (row >= timeline.rows.length) return;
            int start = timeline.rows[row][0], end = timeline.rows[row][1];
            SpannableString text = new SpannableString(timeline.text.substring(start, end));
            if (edge > start) text.setSpan(new ForegroundColorSpan(Ui.SPOKEN), 0, Math.min(edge - start, text.length()), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            for (int[] heading : timeline.heads) {
                if (heading[0] > end) break;
                if (heading[0] < start || heading[1] > end) continue;
                text.setSpan(new ForegroundColorSpan(heading[2] == chapter ? 0xff00ff9d : Ui.SECONDARY), heading[0] - start, heading[1] - start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                text.setSpan(new StyleSpan(Typeface.BOLD), heading[0] - start, heading[1] - start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (word != null && word[0] >= start && word[1] <= end) {
                text.setSpan(new BackgroundColorSpan(0x4600f2ff), word[0] - start, word[1] - start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                text.setSpan(new ForegroundColorSpan(Ui.PRIMARY), word[0] - start, word[1] - start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            view.setText(text);
        }
    }
}
