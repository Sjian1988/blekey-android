package com.blekey.rdb;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 界面用的设计令牌与小工具。
 *
 * 全 App 的圆角、字号、颜色都从这里取, 免得每个控件手写一遍还写歪。
 *
 * 视觉规范(两套, 跟随系统深色模式):
 *   日间 — 浅灰底 #F2F4F7 + 白卡 + 1dp 描边 + 青绿强调
 *   夜间 — 近黑底 #0B0F14 + 深卡 #141A22 + 1dp 描边 + 薄荷青绿强调
 *
 * 三条纪律(来自 ui-ux-pro-max 的 UX 准则):
 *   UX-039 只有一个强调色(TEAL), 其余颜色只在表达状态时才出现;
 *   UX-037 状态色要有语义 —— 绿=成功 红=错误 黄=需注意 蓝=进行中;
 *   UX-015 每个可点的东西都要有按下反馈, 统一走 press()。
 *
 * 主题切换靠 Activity 重建完成: manifest 里没有声明 uiMode 这个 configChange,
 * 系统切深色模式时会重建 Activity -> onCreate -> applyTheme() -> 重新取色。
 */
final class Ui {

    /** 页面底色 */
    static int BG;
    /** 卡片底色 */
    static int CARD;
    /** 卡片内嵌块底色(比卡片再深/再浅一档) */
    static int FILL;
    /** 主文字 */
    static int TEXT;
    /** 次要文字 */
    static int SUB;
    /** 弱提示文字 */
    static int HINT;
    /** 分隔线 / 卡片描边 */
    static int LINE;
    /** 进度条底槽 / 空信号条 */
    static int TRACK;
    /** 强调色(已连接 / 主按钮 / 关键图标) */
    static int TEAL;
    /** 压在强调色上的文字 */
    static int TEAL_ON;
    /** 强调色的浅底(状态胶囊背景) */
    static int TEAL_SOFT;
    /** 强调色的描边(强调卡片边框) */
    static int TEAL_LINE;
    static int GREEN;
    static int BLUE;
    static int RED;
    static int AMBER;

    /** 当前是否深色。系统栏图标颜色要跟着它翻。 */
    static boolean isDark;

    static {
        useLight();
    }

    private Ui() {
    }

    /**
     * 按系统深色模式取一套令牌。必须在创建任何 View 之前调用 ——
     * 令牌是静态字段, View 只在构造时读一次颜色, 之后再改不会生效。
     */
    static void applyTheme(Context c) {
        int mode = 0;
        if (c != null && c.getResources() != null) {
            mode = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        }
        if (mode == Configuration.UI_MODE_NIGHT_YES) {
            useDark();
        } else {
            useLight();
        }
    }

    private static void useLight() {
        isDark = false;
        BG = 0xFFF2F4F7;
        CARD = 0xFFFFFFFF;
        FILL = 0xFFF1F5F9;
        TEXT = 0xFF0F172A;
        SUB = 0xFF64748B;
        HINT = 0xFF94A3B8;
        LINE = 0xFFE3E7ED;
        TRACK = 0xFFEDF0F4;
        TEAL = 0xFF0F766E;
        TEAL_ON = 0xFFFFFFFF;
        TEAL_SOFT = 0xFFECFDF8;
        TEAL_LINE = 0xFFC7EFE6;
        GREEN = 0xFF10B981;
        BLUE = 0xFF2563EB;
        RED = 0xFFDC2626;
        AMBER = 0xFFD97706;
    }

    private static void useDark() {
        isDark = true;
        BG = 0xFF0B0F14;
        CARD = 0xFF141A22;
        FILL = 0xFF1B222C;
        TEXT = 0xFFE8EEF6;
        SUB = 0xFF8A97A8;
        HINT = 0xFF6B7787;
        LINE = 0xFF232B36;
        TRACK = 0xFF1E2833;
        TEAL = 0xFF2DD4BF;
        // 深色下强调色是亮薄荷, 压在上面的字必须反过来用深色, 否则白字糊成一片
        TEAL_ON = 0xFF07231F;
        TEAL_SOFT = 0xFF13302B;
        TEAL_LINE = 0xFF1F4A43;
        GREEN = 0xFF34D399;
        BLUE = 0xFF60A5FA;
        RED = 0xFFF87171;
        AMBER = 0xFFFBBF24;
    }

    static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 给颜色加透明度, 用于描边、叠加层这类不该抢戏的地方。 */
    static int alpha(int color, float a) {
        int al = Math.round(255 * Math.max(0f, Math.min(1f, a)));
        return (color & 0x00FFFFFF) | (al << 24);
    }

    /** 纯色圆角背景。 */
    static GradientDrawable round(Context c, int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(dp(c, radius));
        return d;
    }

    /** 圆角描边背景。 */
    static GradientDrawable outline(Context c, int fill, int strokeColor, float radius, float strokeWidth) {
        GradientDrawable d = round(c, fill, radius);
        d.setStroke(dp(c, strokeWidth), strokeColor);
        return d;
    }

    /** 圆形背景。 */
    static GradientDrawable circle(Context c, int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    static TextView text(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    /** 中等字重。Android 只有 normal/bold, sans-serif-medium 更接近设计里的 500。 */
    static TextView medium(Context c, String s, float sp, int color) {
        TextView t = text(c, s, sp, color);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    /**
     * 卡片: 圆角 + 描边 + 内容纵向排列。
     *
     * 描边不是装饰 —— 日间是白卡贴在浅灰底、夜间是深卡贴在近黑底,
     * 两者对比度都只有一点点, 不加描边卡片边界会糊在背景里, 分层全靠猜。
     */
    static LinearLayout card(Context c, float radius, float padding) {
        return strokeCard(c, radius, padding, LINE);
    }

    static LinearLayout strokeCard(Context c, float radius, float padding, int lineColor) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackgroundDrawable(outline(c, CARD, lineColor, radius, 1f));
        int p = dp(c, padding);
        l.setPadding(p, p, p, p);
        return l;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    static LinearLayout.LayoutParams lp(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    /** 带外边距的 LayoutParams。 */
    static LinearLayout.LayoutParams lp(Context c, int w, int h, float top, float bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = dp(c, top);
        p.bottomMargin = dp(c, bottom);
        return p;
    }

    /** 0.5dp 分隔线。 */
    static View divider(Context c) {
        View v = new View(c);
        v.setBackgroundColor(LINE);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 0.5f)));
        v.setLayoutParams(p);
        return v;
    }

    /** 带上下外边距的分隔线。 */
    static View divider(Context c, float topDp, float bottomDp) {
        View v = new View(c);
        v.setBackgroundColor(LINE);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 0.5f)));
        p.topMargin = dp(c, topDp);
        p.bottomMargin = dp(c, bottomDp);
        v.setLayoutParams(p);
        return v;
    }

    /**
     * 按下时降低透明度。返回 false 是为了不吞掉 click 事件。
     * 圆角背景没法套系统波纹(要两份 drawable 来回换), alpha 反馈最省事也够用。
     */
    static void press(View v) {
        v.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View view, MotionEvent e) {
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) {
                    view.setAlpha(0.72f);
                } else if (a == MotionEvent.ACTION_UP
                        || a == MotionEvent.ACTION_CANCEL
                        || a == MotionEvent.ACTION_OUTSIDE) {
                    view.setAlpha(1f);
                }
                return false;
            }
        });
    }

    /** 主按钮: 强调色填充。 */
    static TextView primaryButton(Context c, String label, int bg, int fg) {
        TextView t = medium(c, label, 13, fg);
        t.setGravity(Gravity.CENTER);
        t.setBackgroundDrawable(round(c, bg, 14));
        int px = dp(c, 14);
        int py = dp(c, 12);
        t.setPadding(px, py, px, py);
        press(t);
        return t;
    }

    /** 次要按钮: 内嵌块底色。 */
    static TextView ghostButton(Context c, String label) {
        return primaryButton(c, label, FILL, TEXT);
    }

    /** 圆形图标按钮。 */
    static View iconButton(Context c, View icon, int bgColor, float sizeDp) {
        int size = dp(c, sizeDp);
        android.widget.FrameLayout f = new android.widget.FrameLayout(c);
        f.setBackgroundDrawable(outline(c, bgColor, LINE, sizeDp / 2f, 1f));
        FrameLayoutCompat.addCentered(f, icon, dp(c, sizeDp * 0.5f));
        f.setLayoutParams(lp(size, size));
        press(f);
        return f;
    }

    /** FrameLayout 居中挂子 View 的薄包装, 避免每处重复写 LayoutParams。 */
    static final class FrameLayoutCompat {
        static void addCentered(android.widget.FrameLayout parent, View child, int sizePx) {
            android.widget.FrameLayout.LayoutParams p =
                    new android.widget.FrameLayout.LayoutParams(sizePx, sizePx);
            p.gravity = Gravity.CENTER;
            parent.addView(child, p);
        }
    }

    /** 小标题: 12sp 灰字, 全大写字母的感觉靠字距拉开。 */
    static TextView section(Context c, String s) {
        TextView t = medium(c, s, 12, HINT);
        t.setLetterSpacing(0.06f);
        t.setPadding(dp(c, 4), dp(c, 16), 0, dp(c, 6));
        return t;
    }

    /* ------------------------- 二级页组件 ------------------------- */

    /**
     * 二级页标题栏: 返回箭头 + 20sp 大标题。
     *
     * 箭头视觉上压在 16dp 页边距上, 但热区有 40dp —— 光靠一个 20dp 的小箭头
     * 横向够不到 44dp 的最小触控宽度, 手指一偏就点不动。
     */
    static LinearLayout pageHeader(Context c, String title, View.OnClickListener back) {
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        int inset = dp(c, 16) - dp(c, 10);
        head.setPadding(inset, dp(c, 6), 0, dp(c, 14));

        android.widget.FrameLayout tap = new android.widget.FrameLayout(c);
        tap.setLayoutParams(lp(dp(c, 40), dp(c, 40)));
        IconView arrow = new IconView(c, IconView.CHEVRON, SUB);
        arrow.setFlip(true);
        FrameLayoutCompat.addCentered(tap, arrow, dp(c, 21));
        press(tap);
        tap.setOnClickListener(back);
        head.addView(tap);

        TextView t = medium(c, title, 20, TEXT);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.leftMargin = dp(c, 6);
        head.addView(t, p);
        return head;
    }

    /** 状态徽章的底色: 同色系低透明度, 深色主题下要更浓一点才看得见。 */
    static GradientDrawable pill(Context c, int color) {
        return round(c, alpha(color, isDark ? 0.20f : 0.11f), 8);
    }

    /** 状态徽章: "已连接" / "未加入" 这类短状态。 */
    static TextView badge(Context c, String text, int color) {
        TextView t = medium(c, text, 10.5f, color);
        t.setGravity(Gravity.CENTER);
        t.setBackgroundDrawable(pill(c, color));
        t.setPadding(dp(c, 9), dp(c, 4), dp(c, 9), dp(c, 4));
        return t;
    }

    /** 图标底座: 圆角方块 + 居中的线性图标, 给设备卡和入口卡定位用。 */
    static View iconTile(Context c, int iconType, float sizeDp, int fg, int bg) {
        android.widget.FrameLayout f = new android.widget.FrameLayout(c);
        // 浅色下 TEAL_SOFT 几乎是白的, 贴在白卡上看不出这是一块底座, 补一圈同色淡描边
        f.setBackgroundDrawable(outline(c, bg, alpha(fg, isDark ? 0.24f : 0.16f),
                sizeDp * 0.30f, 1f));
        FrameLayoutCompat.addCentered(f, new IconView(c, iconType, fg), dp(c, sizeDp * 0.62f));
        f.setLayoutParams(lp(dp(c, sizeDp), dp(c, sizeDp)));
        return f;
    }

    /**
     * 设置行: 左图标 + 标题/副标题 + 右侧控件。
     *
     * 行自带上下 14dp 内边距和 48dp 最小高度, 两行之间用 divider 隔开,
     * 比一排并排按钮更容易扫读 —— 每行左边是一个具体开关, 右边就是它的状态。
     */
    static LinearLayout settingRow(Context c, int iconType, String title, CharSequence sub,
                                   View right) {
        LinearLayout r = new LinearLayout(c);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(c, 52));
        int py = dp(c, 12);
        r.setPadding(0, py, 0, py);

        r.addView(new IconView(c, iconType, SUB), lp(dp(c, 19), dp(c, 19)));

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(medium(c, title, 13, TEXT));
        if (sub != null) {
            TextView s = text(c, sub.toString(), 11, HINT);
            col.addView(s, lp(c, ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 3, 0));
        }
        LinearLayout.LayoutParams clp = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.leftMargin = dp(c, 12);
        r.addView(col, clp);

        if (right != null) {
            r.addView(right);
        }
        return r;
    }

    /** 描边按钮: 内嵌块底色 + 细描边, 用于「让给原 App」「恢复默认」这类中性但重要的动作。 */
    static TextView outlineButton(Context c, String label) {
        TextView t = medium(c, label, 13, TEXT);
        t.setGravity(Gravity.CENTER);
        // 底色用 FILL 而不是 CARD: 卡片底色和按钮底色同为 CARD 时, 深色下按钮会整块消失,
        // 只剩一圈描边在那儿飘着, 看不出是可以按的。
        t.setBackgroundDrawable(outline(c, FILL, LINE, 14, 1f));
        int px = dp(c, 14);
        int py = dp(c, 13);
        t.setPadding(px, py, px, py);
        press(t);
        return t;
    }

    /** 大号数值, 用于阈值这种要一眼对比的读数。 */
    static TextView metric(Context c, String s, float sp, int color) {
        TextView t = medium(c, s, sp, color);
        t.setIncludeFontPadding(false);
        return t;
    }

    static boolean apiAtLeast(int level) {
        return Build.VERSION.SDK_INT >= level;
    }
}
