package com.blekey.rdb;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/**
 * 环形电量。
 *
 * 换掉原来的"大号数字 + 横向进度条": 那种做法里数字和页面标题抢视觉重量,
 * 而进度条在 100% 时又是一根没有信息的满格长条。环形的信息密度更高 ——
 * 百分比在圆心, 外圈直接读剩余量, 一眼就能判断。
 *
 * 颜色由调用方给(和数字同色), 自己只负责画。
 */
final class RingView extends View {

    private static final float STROKE_DP = 6f;
    private static final float DEFAULT_SIZE_DP = 76f;

    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();

    /** -1 表示还没读到电量, 只画灰色底环和一个 "--"。 */
    private int pct = -1;
    private int color = Ui.TEAL;

    RingView(Context c) {
        super(c);
        arc.setStyle(Paint.Style.STROKE);
        label.setTextAlign(Paint.Align.CENTER);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    }

    void setValue(int percent, int ringColor) {
        this.pct = percent;
        this.color = ringColor;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int def = Ui.dp(getContext(), DEFAULT_SIZE_DP);
        setMeasuredDimension(resolveSize(def, widthSpec), resolveSize(def, heightSpec));
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float sw = Ui.dp(getContext(), STROKE_DP);
        float inset = sw / 2f + Ui.dp(getContext(), 1);
        float r = Math.min(w, h) / 2f - inset;
        float cx = w / 2f;
        float cy = h / 2f;
        box.set(cx - r, cy - r, cx + r, cy + r);

        arc.setStrokeWidth(sw);
        arc.setColor(Ui.TRACK);
        arc.setStrokeCap(Paint.Cap.BUTT);
        c.drawArc(box, 0f, 360f, false, arc);

        if (pct >= 0) {
            arc.setColor(color);
            arc.setStrokeCap(Paint.Cap.ROUND);
            float sweep = 360f * Math.min(pct, 100) / 100f;
            // 从 12 点方向顺时针, 跟真实仪表盘的读数习惯一致
            c.drawArc(box, -90f, sweep, false, arc);
        }

        boolean known = pct >= 0;
        label.setColor(known ? Ui.TEXT : Ui.HINT);
        label.setTextSize(Ui.dp(getContext(), known ? 16f : 15f));
        Paint.FontMetrics fm = label.getFontMetrics();
        float baseY = cy - (fm.ascent + fm.descent) / 2f;
        c.drawText(known ? pct + "%" : "--", cx, baseY, label);
    }
}
