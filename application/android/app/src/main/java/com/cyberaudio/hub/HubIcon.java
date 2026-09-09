package com.cyberaudio.hub;

import android.graphics.*;
import android.graphics.drawable.Drawable;

/** Чёткие пиктограммы одинаковой толщины для нижней навигации. */
public final class HubIcon extends Drawable {
    private final int kind, size;
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    public HubIcon(int kind, int color, int size) {
        this.kind = kind; this.size = size;
        ink.setColor(color); ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeWidth(1.8f); ink.setStrokeCap(Paint.Cap.ROUND);
        ink.setStrokeJoin(Paint.Join.ROUND);
    }
    @Override public void draw(Canvas canvas) {
        canvas.save();
        canvas.translate(getBounds().left, getBounds().top);
        canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
        if (kind == 0) {
            for (int x = 4; x <= 14; x += 10) for (int y = 4; y <= 14; y += 10)
                canvas.drawRoundRect(x, y, x + 6, y + 6, 1.5f, 1.5f, ink);
        } else if (kind == 1) {
            Path p = new Path(); p.moveTo(3, 7); p.lineTo(3, 19); p.lineTo(21, 19);
            p.lineTo(21, 7); p.lineTo(12, 7); p.lineTo(9, 4); p.lineTo(3, 4); p.close();
            canvas.drawPath(p, ink);
        } else if (kind == 2) {
            canvas.drawLine(12, 3, 12, 15, ink);
            canvas.drawLine(7, 10, 12, 15, ink); canvas.drawLine(17, 10, 12, 15, ink);
            Path p = new Path(); p.moveTo(4, 16); p.lineTo(4, 21); p.lineTo(20, 21); p.lineTo(20, 16);
            canvas.drawPath(p, ink);
        } else {
            canvas.drawCircle(12, 8, 4, ink);
            canvas.drawArc(4, 13, 20, 27, 180, 180, false, ink);
        }
        canvas.restore();
    }
    @Override public void setAlpha(int alpha) { ink.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter filter) { ink.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    @Override public int getIntrinsicWidth() { return size; }
    @Override public int getIntrinsicHeight() { return size; }
}
