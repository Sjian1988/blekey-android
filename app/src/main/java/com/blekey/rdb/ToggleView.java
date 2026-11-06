package com.blekey.rdb;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * 自绘开关。
 *
 * 系统 android.widget.Switch 的配色绑在主题上 —— 深色模式下轨道是系统灰、打开态是系统蓝,
 * 跟这套「青绿 + 近黑」的令牌对不上, 而且改不动。所以自己画, 颜色全部取 Ui 令牌。
 *
 * 尺寸 46x28dp, 圆点在轨道内左右滑动, 用 190ms 的短动画过渡。
 */
class ToggleView extends View {

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private boolean on;
    /** 0 = 完全关, 1 = 完全开。动画期间是中间值。 */
    private float pos;
    private ValueAnimator anim;

    ToggleView(Context c) {
        super(c);
        knobPaint.setStyle(Paint.Style.FILL);
        pos = 0f;
    }

    void setOn(boolean value) {
        setOn(value, true);
    }

    void setOn(boolean value, boolean animate) {
        if (on == value && (anim == null || !anim.isRunning())) {
            pos = value ? 1f : 0f;
            invalidate();
            return;
        }
        on = value;
        float from = pos;
        float to = value ? 1f : 0f;
        if (anim != null) {
            anim.cancel();
        }
        if (!animate) {
            pos = to;
            invalidate();
            return;
        }
        anim = ValueAnimator.ofFloat(from, to);
        anim.setDuration(190);
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator a) {
                pos = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        anim.start();
    }

    boolean isOn() {
        return on;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(Ui.dp(getContext(), 46), Ui.dp(getContext(), 28));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float r = h / 2f;

        // 轨道: 关是空槽色, 开是强调色
        trackPaint.setColor(on ? Ui.TEAL : Ui.TRACK);
        rect.set(0, 0, w, h);
        canvas.drawRoundRect(rect, r, r, trackPaint);

        // 圆点: 压在强调色上必须是 TEAL_ON —— 深色主题的强调色是亮薄荷,
        // 白点压上去会糊成一片, 所以跟主按钮的字色共用同一个令牌。
        float inset = Ui.dp(getContext(), 3);
        float kr = r - inset;
        float cx = kr + inset + pos * (w - 2 * (kr + inset));
        knobPaint.setColor(on ? Ui.TEAL_ON : Ui.SUB);
        canvas.drawCircle(cx, r, kr, knobPaint);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (anim != null) {
            anim.cancel();
            anim = null;
        }
        super.onDetachedFromWindow();
    }
}
