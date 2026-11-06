package com.blekey.rdb;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/**
 * 24x24 基准的线性图标, 用 Path 现画。
 *
 * 为什么不用 VectorDrawable: 这个 App 是纯 Java 打包(aapt2 link 没有 -R),
 * 没有任何 res 目录, 放不了 drawable 资源。系统自带的 android.R.drawable
 * 又都是拟物风格, 跟扁平卡片不搭, 所以手绘。
 *
 * 坐标系固定 24x24, 按控件实际尺寸等比缩放。
 */
class IconView extends View {

    static final int LOCK = 0;
    static final int UNLOCK = 1;
    static final int TRUNK = 2;
    static final int GEAR = 3;
    static final int CHEVRON = 4;
    /** 以下给二级页的设置行用 */
    static final int KEY = 5;
    static final int SIGNAL = 6;
    static final int BATTERY = 7;
    static final int POWER = 8;
    static final int LOG = 9;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    private final int type;
    private boolean flip;

    IconView(Context c, int type, int color) {
        super(c);
        this.type = type;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.7f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(color);
    }

    void setIconColor(int color) {
        paint.setColor(color);
        invalidate();
    }

    /** 水平镜像, 用于把右箭头变成返回箭头。 */
    void setFlip(boolean f) {
        flip = f;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float s = Math.min(w, h) / 24f;
        canvas.save();
        canvas.translate((w - 24f * s) / 2f, (h - 24f * s) / 2f);
        canvas.scale(s, s);
        if (flip) {
            canvas.translate(24f, 0f);
            canvas.scale(-1f, 1f);
        }
        drawIcon(canvas);
        canvas.restore();
    }

    private void drawIcon(Canvas canvas) {
        path.reset();
        switch (type) {
            case LOCK:
                addBody();
                path.moveTo(8, 11);
                path.lineTo(8, 7);
                rect.set(8, 3, 16, 11);
                path.arcTo(rect, 180, 180, false);
                path.lineTo(16, 11);
                canvas.drawPath(path, paint);
                break;
            case UNLOCK:
                addBody();
                // 锁梁抬起来, 和锁体之间留一道缝, 18dp 下也一眼能跟闭锁区分
                path.moveTo(8, 8.5f);
                path.lineTo(8, 6);
                rect.set(8, 2, 16, 10);
                path.arcTo(rect, 180, 180, false);
                path.lineTo(16, 8.5f);
                canvas.drawPath(path, paint);
                break;
            case TRUNK:
                // 后备箱画成一只箱子: 六边形箱体 + 三条棱。
                // 原先也是"箱体 + 提手", 跟锁几乎一样, 放在一起分不出来。
                path.moveTo(12, 3.5f);
                path.lineTo(20, 7.5f);
                path.lineTo(20, 16.5f);
                path.lineTo(12, 20.5f);
                path.lineTo(4, 16.5f);
                path.lineTo(4, 7.5f);
                path.close();
                path.moveTo(4, 7.5f);
                path.lineTo(12, 11.5f);
                path.lineTo(20, 7.5f);
                path.moveTo(12, 11.5f);
                path.lineTo(12, 20.5f);
                canvas.drawPath(path, paint);
                break;
            case GEAR:
                // 齿轮: 中间轮毂 + 一个圆环 + 8 根径向齿
                // 早先画的是三条滑轨, 在 18dp 下糊成了汉堡菜单(≡), 认不出是设置
                canvas.drawCircle(12, 12, 3.0f, paint);
                canvas.drawCircle(12, 12, 6.6f, paint);
                paint.setStrokeWidth(2.4f);
                for (int i = 0; i < 8; i++) {
                    double a = i * Math.PI / 4.0;
                    float dx = (float) Math.cos(a);
                    float dy = (float) Math.sin(a);
                    canvas.drawLine(12 + dx * 6.5f, 12 + dy * 6.5f,
                            12 + dx * 9.2f, 12 + dy * 9.2f, paint);
                }
                paint.setStrokeWidth(1.7f);
                break;
            case CHEVRON:
                path.moveTo(9, 6);
                path.lineTo(15, 12);
                path.lineTo(9, 18);
                canvas.drawPath(path, paint);
                break;
            case KEY:
                // 跟启动器图标同一把钥匙: 环 + 杆 + 两齿。
                // 线条比其它图标粗一档、齿也留够长度 —— 这个图标最小只到 24dp,
                // 按默认 1.7 的线宽画出来齿会糊成一根横杠, 读成字母 T。
                float oldWidth = paint.getStrokeWidth();
                paint.setStrokeWidth(2.0f);
                canvas.drawCircle(6.5f, 12f, 3.0f, paint);
                path.moveTo(9.2f, 12f);
                path.lineTo(20.6f, 12f);
                path.moveTo(14.8f, 12f);
                path.lineTo(14.8f, 18.6f);
                path.moveTo(17.8f, 12f);
                path.lineTo(17.8f, 16.2f);
                canvas.drawPath(path, paint);
                paint.setStrokeWidth(oldWidth);
                break;
            case SIGNAL:
                // 扩散波: 原点 + 两道弧。以 (12,12) 为圆心铺满网格, 否则图形会缩在左下角,
                // 套进圆形底座的视觉重心是歪的。
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(12f, 12f, 1.5f, paint);
                paint.setStyle(Paint.Style.STROKE);
                rect.set(7.5f, 7.5f, 16.5f, 16.5f);
                canvas.drawArc(rect, -52f, 104f, false, paint);
                rect.set(4f, 4f, 20f, 20f);
                canvas.drawArc(rect, -52f, 104f, false, paint);
                break;
            case BATTERY:
                path.reset();
                rect.set(2.5f, 6.5f, 19f, 18.5f);
                path.addRoundRect(rect, 2.4f, 2.4f, Path.Direction.CW);
                canvas.drawPath(path, paint);
                path.reset();
                path.moveTo(21f, 9.8f);
                path.lineTo(21f, 15.2f);
                canvas.drawPath(path, paint);
                // 内部电量条用填充, 空电池和满电池一眼分开
                paint.setStyle(Paint.Style.FILL);
                canvas.drawRect(4.6f, 8.6f, 13.8f, 16.4f, paint);
                paint.setStyle(Paint.Style.STROKE);
                break;
            case POWER:
                // 电源: 缺口留在正上方(12 点), 顶部一根竖线
                rect.set(5.5f, 5.5f, 18.5f, 18.5f);
                canvas.drawArc(rect, -60f, 300f, false, paint);
                path.moveTo(12f, 3.5f);
                path.lineTo(12f, 10.5f);
                canvas.drawPath(path, paint);
                break;
            case LOG:
                path.reset();
                rect.set(4.5f, 3f, 19.5f, 21f);
                path.addRoundRect(rect, 2.2f, 2.2f, Path.Direction.CW);
                canvas.drawPath(path, paint);
                path.reset();
                path.moveTo(8f, 8.5f);
                path.lineTo(16f, 8.5f);
                path.moveTo(8f, 12f);
                path.lineTo(16f, 12f);
                path.moveTo(8f, 15.5f);
                path.lineTo(12.5f, 15.5f);
                canvas.drawPath(path, paint);
                break;
            default:
                break;
        }
    }

    /** 锁体/箱体的统一外框, 三个图标共用。 */
    private void addBody() {
        rect.set(5, 11, 19, 20);
        path.addRoundRect(rect, 2, 2, Path.Direction.CW);
    }
}
