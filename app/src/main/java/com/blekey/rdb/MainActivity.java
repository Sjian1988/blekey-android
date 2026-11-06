package com.blekey.rdb;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 状态与调试界面。绑定由原 App 完成, 这里只做连接、开关锁与日志。
 *
 * 界面分两层:
 *   主页面   —— 状态、电量、三个控制按钮、车辆信息, 一屏看完
 *   高级设置 —— 让位、后台保活、车钥匙详情、运行日志, 平时用不到的不占首屏
 */
public class MainActivity extends Activity {

    private static final int REQ_PERM = 1001;

    /** 从 detail 里抠 RSSI, 例 "已连接 (-62 dBm)"。 */
    private static final Pattern RE_DBM = Pattern.compile("(-?\\d+)\\s*dBm");

    /* 页面 */
    private ScrollView mainPage;
    private ScrollView advPage;
    private ScrollView cePage;
    private boolean advOpen;
    private boolean ceOpenPage;

    /* 状态胶囊 */
    private LinearLayout statusPill;
    private View statusDot;
    private TextView tvState;
    private TextView tvStateSub;
    private final View[] signalBars = new View[4];
    private ObjectAnimator pulse;

    /* 电量卡 */
    private RingView ring;
    private TextView tvPowerVolt;
    private TextView tvPowerSub;

    /* 车辆信息 */
    private TextView tvCeOpen;
    private TextView tvCeDist;

    /* 舒适进入配置页 */
    private TextView tvCeCur;        // 大读数下面那行说明
    private TextView tvCeLockBig;    // 落锁大读数
    private TextView tvCeUnlockBig;  // 开锁大读数
    private ToggleView swCe;         // 自绘开关: 系统 Switch 的配色跟令牌对不上
    private TextView tvLockVal;
    private TextView tvUnlockVal;
    private android.widget.SeekBar sbLock;
    private android.widget.SeekBar sbUnlock;
    private TextView tvCeWarn;
    /** 页面上待提交的开关状态, 点「应用」才写进车机。 */
    private boolean ceWantOpen = true;

    /* 高级设置 */
    private TextView tvKeyName;
    private TextView tvKeyAddr;
    private TextView tvKeyState;
    private TextView tvKeyBadge;
    private TextView tvAdvCe;
    private TextView tvBg;
    private TextView tvBgBadge;
    private TextView tvLog;
    private TextView tvLogTitle;
    private ScrollView logScroll;
    private LinearLayout logBox;
    private boolean logOpen;

    /** 上一次写进日志的状态, 用来去掉重复行(电量刷新会重发同一条状态广播)。 */
    private String lastLogged;

    private BluetoothAdapter adapter;

    private final Handler h = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String s = intent.getStringExtra(BleKeyService.EXTRA_STATE);
            String d = intent.getStringExtra(BleKeyService.EXTRA_DETAIL);
            updateStateText(s, d);
            refreshPower();
            refreshCe();
            refreshBg();
            if (s != null) {
                String line = s + "|" + d;
                if (!line.equals(lastLogged)) {
                    lastLogged = line;
                    log("[" + s + "] " + (d == null ? "" : d));
                }
            }
        }
    };

    /**
     * 状态栏/导航栏改成页面底色, 图标颜色跟着主题翻。
     * 去掉 ActionBar 之后如果不管这两个条, 顶部会留下一条深色边框, 跟卡片断开。
     */
    private void styleSystemBars() {
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // API 30 起窗口外观归 WindowInsetsController 管: APPEARANCE_LIGHT_* 置位 = 深色图标。
                // 深色主题必须把这两位置 0, 否则状态栏时间和电池图标是黑的,
                // 贴在 #0B0F14 上等于直接消失。旧的 setSystemUiVisibility 在 targetSdk 34 上不生效。
                //
                // 前提是 DecorView 已经创建: PhoneWindow.getInsetsController() 内部直接取
                // DecorView.getWindowInsetsController(), DecorView 为 null 时会 NPE。
                // 所以这个方法必须在 setContentView 之后调, 这里的 catch 只是二道保险。
                android.view.WindowInsetsController c = getWindow().getInsetsController();
                if (c != null) {
                    int mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                            | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                    c.setSystemBarsAppearance(Ui.isDark ? 0 : mask, mask);
                }
            } else {
                int flags = 0;
                if (!Ui.isDark) {
                    flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                    }
                }
                getWindow().getDecorView().setSystemUiVisibility(flags);
            }
        } catch (Throwable t) {
            // 系统栏图标颜色不该成为崩溃原因, 拿不到就维持系统默认
            android.util.Log.w("BleKey", "styleSystemBars fail", t);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 取色必须在 setContentView 之前 —— 令牌是静态字段, View 构造时只读一次
        Ui.applyTheme(this);
        // 老版本升级上来时手上已有有效读数但没有基线, 这里补建一次, 重置才有兜底值
        Prefs.ensureCeBase(this);
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm == null ? null : bm.getAdapter();
        setContentView(buildUi());
        // 放到 setContentView 之后: 取 WindowInsetsController 要求 DecorView 已存在
        styleSystemBars();
        applyIntentOverrides(getIntent());
        clearOldShortcuts();
        requestNeededPermissions();
        // 保活的 Job + 闹钟在这里就挂上, 不依赖服务是否已经起来
        if (KeepAlive.needed(this)) {
            KeepAlive.install(this);
        }
        maybeAskBattery();
    }

    /**
     * 支持外部注入绑定参数, 用于把原 App 已有的设备密钥搬到本 App:
     *   adb shell am start -n com.blekey.rdb/.MainActivity \
     *       --es setkey <32位hex> [--es setaddr AA:BB:CC:DD:EE:FF] [--es setserial 001122aabbcc]
     * 车机只认一把 deviceKey, 只要拿到原 App 那把, 就不需要解绑或车机重置。
     */
    private void applyIntentOverrides(Intent it) {
        if (it == null) {
            return;
        }
        boolean changed = false;
        String key = it.getStringExtra("setkey");
        if (key != null) {
            key = key.trim().toLowerCase().replace(":", "").replace(" ", "");
            if (key.length() == 32) {
                Prefs.putSync(this, Prefs.K_PASS, key);
                changed = true;
                log("已导入设备密钥: " + key.substring(0, 8) + "…(共32位)");
            } else {
                log("密钥格式不对, 需要 32 位十六进制, 当前 " + key.length() + " 位");
            }
        }
        String addr = it.getStringExtra("setaddr");
        if (addr != null && addr.trim().length() >= 17) {
            Prefs.put(this, Prefs.K_ADDR, addr.trim().toUpperCase());
            changed = true;
        }
        String serial = it.getStringExtra("setserial");
        if (serial != null && serial.trim().length() >= 12) {
            Prefs.put(this, Prefs.K_SERIAL, serial.trim().toLowerCase());
            changed = true;
        }
        if (changed) {
            refreshBinding();
            BleKeyService.start(this);
        }
        // 调试用: --ez open ce|adv 直接落到指定页面, 免去手点
        String open = it.getStringExtra("open");
        if ("ce".equals(open)) {
            showCe(true);
        } else if ("adv".equals(open)) {
            showAdv(true);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // singleTop: 从后台被拉起时走这里, 不再走 onCreate
        setIntent(intent);
        applyIntentOverrides(intent);
    }

    /**
     * 清掉上个版本注册过的「开后备箱」动态快捷方式。
     *
     * 那段设计已经否掉了: 让"启动 App"顺带开尾门, 等于把无关动作绑到不可撤销的机械动作上。
     * 现在只保留最朴素的链路 —— 小爱/桌面把 App 打开, 人进去自己点「后备箱」。
     * 这段代码只做清理, 别再加回注册逻辑; 等确认所有设备都升过一次之后可以整个删掉。
     */
    private void clearOldShortcuts() {
        if (Build.VERSION.SDK_INT < 25) {
            return;
        }
        try {
            android.content.pm.ShortcutManager sm =
                    getSystemService(android.content.pm.ShortcutManager.class);
            if (sm != null) {
                sm.removeAllDynamicShortcuts();
            }
        } catch (Throwable ignored) {
        }
    }

    /** 第一次进来主动弹一次电池白名单, 后面靠界面按钮。 */
    private void maybeAskBattery() {
        if (KeepAlive.ignoringBattery(this)) {
            return;
        }
        if (Prefs.get(this).getBoolean(Prefs.K_BG_PROMPTED, false)) {
            return;
        }
        Prefs.put(this, Prefs.K_BG_PROMPTED, true);
        h.postDelayed(new Runnable() {
            public void run() {
                log("请在系统弹窗里选「允许」, 让车钥匙在后台常驻");
                KeepAlive.requestIgnoreBattery(MainActivity.this);
            }
        }, 1500);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter(BleKeyService.BCAST_STATE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, f);
        }
        refreshBinding();
        refreshBg();
        refreshCe();
        refreshPower();
        // 从服务里回填当前状态, 否则重建界面后要等下一次广播才对得上
        updateStateText(BleKeyService.lastState(), BleKeyService.lastDetail());
        // 电量会随时间走, 每次回到界面顺手让车机重报一次(服务里做了 3 秒防抖)
        if (BleKeyService.STATE_READY.equals(BleKeyService.lastState())
                && System.currentTimeMillis() - Prefs.powerTs(this) > 60 * 1000L) {
            BleKeyService.send(this, BleKeyService.ACTION_REFRESH);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(stateReceiver);
        } catch (Exception ignored) {
        }
        stopPulse();
    }

    @Override
    public void onBackPressed() {
        if (ceOpenPage) {
            showCe(false);
            return;
        }
        if (advOpen) {
            showAdv(false);
            return;
        }
        super.onBackPressed();
    }

    /* ------------------------------ 页面骨架 ------------------------------ */

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        mainPage = buildMainPage();
        advPage = buildAdvPage();
        cePage = buildCePage();
        advPage.setVisibility(View.GONE);
        cePage.setVisibility(View.GONE);
        root.addView(mainPage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(advPage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(cePage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return root;
    }

    private ScrollView buildMainPage() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        sv.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 28));
        sv.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        /* ---------------- 标题栏 ---------------- */
        LinearLayout head = Ui.row(this);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(Ui.medium(this, "蓝牙车钥匙", 21, Ui.TEXT));
        TextView sub = Ui.text(this, subtitle(), 11, Ui.HINT);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = Ui.dp(this, 3);
        titles.addView(sub, subLp);
        head.addView(titles, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View gear = Ui.iconButton(this, new IconView(this, IconView.GEAR, Ui.SUB), Ui.CARD, 36);
        gear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showAdv(true);
            }
        });
        head.addView(gear);
        root.addView(head, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 2, 0));

        /* ---------------- 连接状态 ---------------- */
        // 一条胶囊说清"现在能不能用": 状态点 + 状态名 + RSSI + 信号条。
        // 早先是白卡里两行, 和电量卡并列, 最该被看见的信息反而最不显眼。
        statusPill = Ui.row(this);
        statusPill.setBackgroundDrawable(Ui.outline(this, Ui.FILL, Ui.LINE, 11, 1f));
        int pillPad = Ui.dp(this, 11);
        statusPill.setPadding(pillPad, Ui.dp(this, 9), pillPad, Ui.dp(this, 9));

        statusDot = new View(this);
        statusDot.setBackgroundDrawable(Ui.circle(this, Ui.HINT));
        statusPill.addView(statusDot, Ui.lp(Ui.dp(this, 8), Ui.dp(this, 8)));

        tvState = Ui.medium(this, "未连接", 13, Ui.TEXT);
        LinearLayout.LayoutParams stateLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        stateLp.leftMargin = Ui.dp(this, 9);
        statusPill.addView(tvState, stateLp);

        tvStateSub = Ui.text(this, "等待车机", 11, Ui.HINT);
        tvStateSub.setMaxLines(1);
        tvStateSub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        statusPill.addView(tvStateSub);

        // 信号强度条: 四根由矮到高的竖条, 按 RSSI 点亮
        LinearLayout sig = new LinearLayout(this);
        sig.setOrientation(LinearLayout.HORIZONTAL);
        sig.setGravity(Gravity.BOTTOM);
        int[] heights = {4, 7, 10, 13};
        for (int i = 0; i < heights.length; i++) {
            View bar = new View(this);
            bar.setBackgroundDrawable(Ui.round(this, Ui.TRACK, 1.5f));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    Ui.dp(this, 3), Ui.dp(this, heights[i]));
            blp.leftMargin = Ui.dp(this, i == 0 ? 10 : 2);
            sig.addView(bar, blp);
            signalBars[i] = bar;
        }
        statusPill.addView(sig);
        root.addView(statusPill, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 16, 0));

        /* ---------------- 电量 ---------------- */
        LinearLayout pw = Ui.card(this, 16, 13);

        LinearLayout pwRow = Ui.row(this);

        ring = new RingView(this);
        pwRow.addView(ring, Ui.lp(Ui.dp(this, 76), Ui.dp(this, 76)));

        LinearLayout pwText = new LinearLayout(this);
        pwText.setOrientation(LinearLayout.VERTICAL);
        pwText.addView(Ui.text(this, "车机电量", 11, Ui.HINT));
        tvPowerVolt = Ui.text(this, "尚未读取", 13, Ui.TEXT);
        LinearLayout.LayoutParams voltLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        voltLp.topMargin = Ui.dp(this, 5);
        pwText.addView(tvPowerVolt, voltLp);
        tvPowerSub = Ui.text(this, "", 11, Ui.HINT);
        LinearLayout.LayoutParams pwSubLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        pwSubLp.topMargin = Ui.dp(this, 3);
        pwText.addView(tvPowerSub, pwSubLp);
        LinearLayout.LayoutParams pwTextLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        pwTextLp.leftMargin = Ui.dp(this, 14);
        pwRow.addView(pwText, pwTextLp);

        TextView refresh = Ui.medium(this, "刷新", 12, Ui.TEAL);
        refresh.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 2), Ui.dp(this, 8));
        refresh.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!BleKeyService.STATE_READY.equals(BleKeyService.lastState())) {
                    log("还没连上车机, 连上后会自动读电量");
                    return;
                }
                BleKeyService.send(MainActivity.this, BleKeyService.ACTION_REFRESH);
                log("已请求车机重报电量…");
            }
        });
        pwRow.addView(refresh);
        pw.addView(pwRow);
        root.addView(pw, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 10, 0));

        /* ---------------- 三个主操作 ---------------- */
        // 三个动作都是"点一次就执行", 但视觉权重不同: 解锁是主操作(填充),
        // 后备箱加一圈强调描边 —— 它是"双手拿东西"这个场景真正要按的那一个,
        // 不该和落锁长得一模一样。落锁保持中性, 因为它是收尾动作, 不抢注意力。
        LinearLayout ctrl = new LinearLayout(this);
        ctrl.setOrientation(LinearLayout.HORIZONTAL);

        ctrl.addView(controlButton(IconView.UNLOCK, "解锁", CTRL_PRIMARY, new View.OnClickListener() {
            public void onClick(View v) {
                BleKeyService.send(MainActivity.this, BleKeyService.ACTION_UNLOCK);
                log("发送解锁");
            }
        }), controlLp(0));

        ctrl.addView(controlButton(IconView.LOCK, "落锁", CTRL_PLAIN, new View.OnClickListener() {
            public void onClick(View v) {
                BleKeyService.send(MainActivity.this, BleKeyService.ACTION_LOCK);
                log("发送落锁");
            }
        }), controlLp(8));

        ctrl.addView(controlButton(IconView.TRUNK, "后备箱", CTRL_ACCENT, new View.OnClickListener() {
            public void onClick(View v) {
                BleKeyService.send(MainActivity.this, BleKeyService.ACTION_TRUNK);
                log("发送后备箱");
            }
        }), controlLp(8));

        root.addView(ctrl, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 12, 0));

        /* ---------------- 车辆信息 ---------------- */
        root.addView(Ui.section(this, "车辆信息"));

        LinearLayout info = Ui.card(this, 16, 0);

        tvCeOpen = Ui.text(this, "—", 12, Ui.HINT);
        info.addView(infoRow("靠近自动开锁", tvCeOpen, true));

        info.addView(Ui.divider(this));

        tvCeDist = Ui.text(this, "连接后读取", 12, Ui.HINT);
        info.addView(infoRow("落锁 / 开锁距离", tvCeDist, false));

        root.addView(info, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0));

        /* ---------------- 高级设置入口 ---------------- */
        LinearLayout entry = Ui.row(this);
        entry.setBackgroundDrawable(Ui.outline(this, Ui.CARD, Ui.LINE, 16, 1f));
        int ep = Ui.dp(this, 14);
        entry.setPadding(ep, ep, ep, ep);

        entry.addView(new IconView(this, IconView.GEAR, Ui.SUB),
                Ui.lp(Ui.dp(this, 18), Ui.dp(this, 18)));

        LinearLayout entryText = new LinearLayout(this);
        entryText.setOrientation(LinearLayout.VERTICAL);
        entryText.addView(Ui.medium(this, "高级设置", 13, Ui.TEXT));
        entryText.addView(Ui.text(this, "让位、后台保活、运行日志", 11, Ui.HINT));
        LinearLayout.LayoutParams entryTextLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        entryTextLp.leftMargin = Ui.dp(this, 10);
        entry.addView(entryText, entryTextLp);

        entry.addView(new IconView(this, IconView.CHEVRON, Ui.HINT),
                Ui.lp(Ui.dp(this, 16), Ui.dp(this, 16)));

        Ui.press(entry);
        entry.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showAdv(true);
            }
        });
        root.addView(entry, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 10, 0));

        TextView foot = Ui.text(this, versionFoot(), 11, Ui.HINT);
        foot.setGravity(Gravity.CENTER);
        root.addView(foot, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 20, 0));

        return sv;
    }

    /** 底部版本号。写死 v1.0 会跟 build 脚本里的 versionName 对不上, 直接从包里读。 */
    private String versionFoot() {
        String v = "";
        try {
            v = "v" + getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName + " · ";
        } catch (Throwable ignored) {
        }
        return v + "密钥与日志只存在本机";
    }

    private ScrollView buildAdvPage() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 28));
        sv.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        /* ---------------- 标题栏 ---------------- */
        root.addView(Ui.pageHeader(this, "高级设置", new View.OnClickListener() {
            public void onClick(View v) {
                showAdv(false);
            }
        }));

        /* ---------------- 车钥匙 ----------------
           原来是三行文字平铺(名称 / MAC / 密钥状态), 看着像一段纯文本。
           现在左边一个钥匙底座定位"这是什么", 右边名称 + MAC, 右上角一枚绑定徽章,
           密钥说明压到分隔线下面 —— 一眼能分出主信息和附注。 */
        root.addView(Ui.section(this, "车钥匙"));

        LinearLayout key = Ui.card(this, 18, 14);

        LinearLayout keyTop = Ui.row(this);
        keyTop.addView(Ui.iconTile(this, IconView.KEY, 42, Ui.TEAL, Ui.TEAL_SOFT));

        LinearLayout keyText = new LinearLayout(this);
        keyText.setOrientation(LinearLayout.VERTICAL);
        tvKeyName = Ui.medium(this, "—", 15, Ui.TEXT);
        keyText.addView(tvKeyName);
        tvKeyAddr = Ui.text(this, "—", 11, Ui.HINT);
        tvKeyAddr.setTypeface(Typeface.MONOSPACE);
        keyText.addView(tvKeyAddr, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 4, 0));
        LinearLayout.LayoutParams keyTextLp = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        keyTextLp.leftMargin = Ui.dp(this, 12);
        keyTop.addView(keyText, keyTextLp);

        tvKeyBadge = Ui.badge(this, "未绑定", Ui.HINT);
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        badgeLp.leftMargin = Ui.dp(this, 8);
        keyTop.addView(tvKeyBadge, badgeLp);
        key.addView(keyTop, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0));

        key.addView(Ui.divider(this, 12, 10));

        tvKeyState = Ui.text(this, "", 10.5f, Ui.HINT);
        tvKeyState.setLineSpacing(Ui.dp(this, 2), 1f);
        key.addView(tvKeyState);

        root.addView(key, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0));

        /* ---------------- 靠近自动开锁 ---------------- */
        root.addView(Ui.section(this, "靠近自动开锁"));

        LinearLayout ceEntry = Ui.row(this);
        ceEntry.setBackgroundDrawable(Ui.outline(this, Ui.CARD, Ui.LINE, 18, 1f));
        int cep = Ui.dp(this, 14);
        ceEntry.setPadding(cep, cep, cep, cep);
        ceEntry.addView(Ui.iconTile(this, IconView.SIGNAL, 42, Ui.TEAL, Ui.TEAL_SOFT));

        LinearLayout ceText = new LinearLayout(this);
        ceText.setOrientation(LinearLayout.VERTICAL);
        ceText.addView(Ui.medium(this, "落锁与开锁阈值", 14, Ui.TEXT));
        tvAdvCe = Ui.text(this, "连接后读取", 11, Ui.HINT);
        ceText.addView(tvAdvCe, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 3, 0));
        LinearLayout.LayoutParams ceTextLp = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        ceTextLp.leftMargin = Ui.dp(this, 12);
        ceEntry.addView(ceText, ceTextLp);

        ceEntry.addView(new IconView(this, IconView.CHEVRON, Ui.HINT),
                Ui.lp(Ui.dp(this, 18), Ui.dp(this, 18)));

        Ui.press(ceEntry);
        ceEntry.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showCe(true);
            }
        });
        root.addView(ceEntry, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0));

        /* ---------------- 与原 App 共存 ----------------
           说明分两级: 一句讲清冲突, 一句讲清怎么办。原来三段话挤在一个字号里,
           读完不知道哪句是重点。 */
        root.addView(Ui.section(this, "与原 App 共存"));

        LinearLayout coexist = Ui.card(this, 18, 14);
        TextView coexistMain = Ui.text(this, "蓝牙连接是独占的，两个 App 不能同时连。", 12.5f, Ui.SUB);
        coexistMain.setLineSpacing(Ui.dp(this, 3), 1f);
        coexist.addView(coexistMain);

        TextView coexistSub = Ui.text(this,
                "刷固件、改参数前先让位；让位期间点解锁或落锁会立即收回连接。", 11, Ui.HINT);
        coexistSub.setLineSpacing(Ui.dp(this, 3), 1f);
        coexist.addView(coexistSub, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 6, 0));

        TextView yield = Ui.outlineButton(this, "让给原 App · 30 分钟");
        yield.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                BleKeyService.send(MainActivity.this, BleKeyService.ACTION_YIELD);
                log("已断开并让位 30 分钟, 这期间原 App 可以正常连接");
                updateStateText(BleKeyService.STATE_IDLE, "已让位给原 App, 30 分钟内不抢连接");
            }
        });
        coexist.addView(yield, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 12, 0));

        root.addView(coexist, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0));

        /* ---------------- 后台运行 ----------------
           原来是"一段结论 + 两个并排按钮 + 又一段小字", 信息全靠 11sp 灰字堆着。
           改成两行设置项: 左边说明这是什么, 右边直接给状态或箭头, 点整行就动手。
           脚注挪到卡片外面, 卡片里只留可操作的东西。 */
        root.addView(Ui.section(this, "后台运行"));

        LinearLayout bgCard = Ui.card(this, 18, 0);
        bgCard.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);

        LinearLayout rowBattery = Ui.settingRow(this, IconView.BATTERY,
                "电池白名单", "锁屏后不被系统冻结", null);
        tvBgBadge = Ui.badge(this, "未加入", Ui.RED);
        rowBattery.addView(tvBgBadge);
        Ui.press(rowBattery);
        rowBattery.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (KeepAlive.ignoringBattery(MainActivity.this)) {
                    log("已经在电池白名单里了");
                    refreshBg();
                    return;
                }
                log("请在系统弹窗里选「允许」/「不限制」");
                KeepAlive.requestIgnoreBattery(MainActivity.this);
            }
        });
        bgCard.addView(rowBattery, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0));

        bgCard.addView(Ui.divider(this));

        LinearLayout rowAuto = Ui.settingRow(this, IconView.POWER,
                "自启动管理", "重启后自动把服务拉起来", null);
        rowAuto.addView(new IconView(this, IconView.CHEVRON, Ui.HINT),
                Ui.lp(Ui.dp(this, 17), Ui.dp(this, 17)));
        Ui.press(rowAuto);
        rowAuto.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                boolean ok = KeepAlive.openAutoStartSettings(MainActivity.this);
                log(ok ? "已打开自启动管理, 请允许本应用自启动"
                        : "这台机器没有标准自启动页, 请到 设置→应用→自启动 手动打开");
            }
        });
        bgCard.addView(rowAuto, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0));

        root.addView(bgCard, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0));

        tvBg = Ui.text(this, "", 10.5f, Ui.HINT);
        tvBg.setLineSpacing(Ui.dp(this, 3), 1f);
        root.addView(tvBg, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 8, 0));

        /* ---------------- 运行日志 ---------------- */
        root.addView(Ui.section(this, "运行日志"));

        LinearLayout logCard = Ui.card(this, 18, 14);

        LinearLayout logHead = Ui.row(this);
        logHead.addView(new IconView(this, IconView.LOG, Ui.SUB),
                Ui.lp(Ui.dp(this, 18), Ui.dp(this, 18)));
        TextView logName = Ui.medium(this, "连接与指令记录", 13, Ui.TEXT);
        LinearLayout.LayoutParams logNameLp = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        logNameLp.leftMargin = Ui.dp(this, 10);
        logHead.addView(logName, logNameLp);

        tvLogTitle = Ui.medium(this, "展开 ▾", 11.5f, Ui.SUB);
        logHead.addView(tvLogTitle);
        Ui.press(logHead);
        logHead.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                setLogOpen(!logOpen);
            }
        });
        logCard.addView(logHead, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0));

        logBox = new LinearLayout(this);
        logBox.setOrientation(LinearLayout.VERTICAL);
        logBox.setVisibility(View.GONE);

        logScroll = new ScrollView(this);
        logScroll.setBackgroundDrawable(Ui.round(this, Ui.FILL, 10));
        // 内层滚动与外层 ScrollView 抢手势: 手指落在日志区就不让外层拦截
        logScroll.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                if (advPage != null) {
                    advPage.requestDisallowInterceptTouchEvent(true);
                }
                return false;
            }
        });

        tvLog = new TextView(this);
        tvLog.setTextSize(10.5f);
        tvLog.setTextColor(Ui.SUB);
        tvLog.setTypeface(Typeface.MONOSPACE);
        int lp8 = Ui.dp(this, 10);
        tvLog.setPadding(lp8, lp8, lp8, lp8);
        tvLog.setLineSpacing(Ui.dp(this, 3), 1f);
        logScroll.addView(tvLog, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams logLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 220));
        logLp.topMargin = Ui.dp(this, 12);
        logBox.addView(logScroll, logLp);
        logCard.addView(logBox);

        root.addView(logCard, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0));

        return sv;
    }

    /* --------------------- 靠近自动开锁(舒适进入)配置页 --------------------- */

    /** 阈值是 RSSI 的绝对值: -45dBm(很近) ~ -95dBm(很远), 步进 5。 */
    private static final int CE_MIN_DBM = 45;
    private static final int CE_STEP_DBM = 5;
    private static final int CE_STEPS = 10;   // 45 + 10*5 = 95, 共 11 档

    private ScrollView buildCePage() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 28));
        sv.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        /* ---------------- 标题栏 ---------------- */
        root.addView(Ui.pageHeader(this, "靠近自动开锁", new View.OnClickListener() {
            public void onClick(View v) {
                showCe(false);
            }
        }));

        TextView lead = Ui.text(this, "走远自动落锁，靠近自动开锁，靠车盒的蓝牙信号强弱判断。", 12, Ui.SUB);
        lead.setLineSpacing(Ui.dp(this, 3), 1f);
        root.addView(lead, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 2));

        /* ---------------- 车机当前生效 ----------------
           原来是一行 "已开启 · 落锁 -80 · 开锁 -70 dBm", 两个数字埋在中文字中间,
           看不出谁大谁小。现在拆成左右两块大读数, 中间一条竖线, 大小关系一眼可见。 */
        root.addView(Ui.section(this, "车机当前生效"));

        LinearLayout cur = Ui.card(this, 18, 16);

        LinearLayout nums = new LinearLayout(this);
        nums.setOrientation(LinearLayout.HORIZONTAL);

        LinearLayout leftCol = new LinearLayout(this);
        leftCol.setOrientation(LinearLayout.VERTICAL);
        leftCol.addView(Ui.text(this, "落锁", 11, Ui.HINT));
        tvCeLockBig = Ui.metric(this, "—", 27, Ui.TEXT);
        leftCol.addView(tvCeLockBig, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 7, 0));
        leftCol.addView(Ui.text(this, "走远触发", 10.5f, Ui.HINT),
                Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 5, 0));
        nums.addView(leftCol, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        View vline = new View(this);
        vline.setBackgroundColor(Ui.alpha(Ui.SUB, 0.35f));
        LinearLayout.LayoutParams vlineLp = new LinearLayout.LayoutParams(
                Math.max(1, Ui.dp(this, 1f)), ViewGroup.LayoutParams.MATCH_PARENT);
        vlineLp.leftMargin = Ui.dp(this, 14);
        vlineLp.rightMargin = Ui.dp(this, 14);
        nums.addView(vline, vlineLp);

        LinearLayout rightCol = new LinearLayout(this);
        rightCol.setOrientation(LinearLayout.VERTICAL);
        rightCol.addView(Ui.text(this, "开锁", 11, Ui.HINT));
        tvCeUnlockBig = Ui.metric(this, "—", 27, Ui.TEXT);
        rightCol.addView(tvCeUnlockBig, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 7, 0));
        rightCol.addView(Ui.text(this, "靠近触发", 10.5f, Ui.HINT),
                Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 5, 0));
        nums.addView(rightCol, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        cur.addView(nums);

        cur.addView(Ui.divider(this, 14, 10));

        tvCeCur = Ui.text(this, "连接车机后自动读取", 10.5f, Ui.HINT);
        tvCeCur.setLineSpacing(Ui.dp(this, 2), 1f);
        cur.addView(tvCeCur);

        root.addView(cur, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0));

        /* ---------------- 调节 ---------------- */
        root.addView(Ui.section(this, "调节"));

        LinearLayout edit = Ui.card(this, 18, 16);

        LinearLayout swRow = Ui.row(this);
        LinearLayout swText = new LinearLayout(this);
        swText.setOrientation(LinearLayout.VERTICAL);
        swText.addView(Ui.medium(this, "自动开锁", 13, Ui.TEXT));
        swText.addView(Ui.text(this, "关掉后只响应手动操作", 10.5f, Ui.HINT),
                Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 3, 0));
        swRow.addView(swText, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        swCe = new ToggleView(this);
        swRow.addView(swCe);
        Ui.press(swRow);
        swRow.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                ceWantOpen = !ceWantOpen;
                swCe.setOn(ceWantOpen);
                refreshCeEdit();
            }
        });
        edit.addView(swRow, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 2));

        edit.addView(Ui.divider(this, 12, 14));

        /* 两个阈值各占一个内嵌块: 标题 + 实时数值 + 滑块 + 两端刻度。
           滑块本身看不出方向, 标上 "很近 / 很远" 才知道往左拖意味着什么。 */
        edit.addView(thresholdBox(true));
        edit.addView(thresholdBox(false), Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 10, 0));

        tvCeWarn = Ui.text(this, "", 10.5f, Ui.AMBER);
        tvCeWarn.setLineSpacing(Ui.dp(this, 3), 1f);
        tvCeWarn.setVisibility(View.GONE);
        edit.addView(tvCeWarn, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 12, 0));

        root.addView(edit, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0, 0));

        /* ---------------- 操作 ---------------- */
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);

        TextView reset = Ui.outlineButton(this, "恢复默认");
        reset.setMinimumHeight(Ui.dp(this, 46));
        reset.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                restoreDefaultCe();
            }
        });
        btnRow.addView(reset, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView apply = Ui.primaryButton(this, "应用", Ui.TEAL, Ui.TEAL_ON);
        apply.setMinimumHeight(Ui.dp(this, 46));
        apply.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                applyCe();
            }
        });
        LinearLayout.LayoutParams applyLp = Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        applyLp.leftMargin = Ui.dp(this, 8);
        btnRow.addView(apply, applyLp);

        root.addView(btnRow, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 16, 0));

        TextView foot = Ui.text(this,
                "参数直接写进车机，写完会重读一次确认。"
                        + "车机低电量时会自己把自动开锁关掉，所以显示「已开启」也可能随时变回关闭。",
                10.5f, Ui.HINT);
        foot.setLineSpacing(Ui.dp(this, 3), 1f);
        root.addView(foot, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 12, 0));

        return sv;
    }

    /** 一个阈值块: 标题 + 数值 + 滑块 + 两端刻度。isLock 决定绑哪一组控件。 */
    private LinearLayout thresholdBox(boolean isLock) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundDrawable(Ui.round(this, Ui.FILL, 12));
        int bp = Ui.dp(this, 12);
        box.setPadding(bp, bp, bp, Ui.dp(this, 8));

        LinearLayout head = Ui.row(this);
        head.addView(Ui.text(this, isLock ? "落锁阈值" : "开锁阈值", 12.5f, Ui.SUB),
                Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView val = Ui.metric(this, "—", 14, Ui.TEXT);
        head.addView(val);
        box.addView(head);

        android.widget.SeekBar sb = new android.widget.SeekBar(this);
        sb.setMax(CE_STEPS);
        tintSlider(sb);
        box.addView(sb, Ui.lp(this, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 2, 0));

        LinearLayout scale = Ui.row(this);
        scale.addView(Ui.text(this, "-45 很近", 9.5f, Ui.HINT),
                Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView far = Ui.text(this, "-95 很远", 9.5f, Ui.HINT);
        far.setGravity(Gravity.RIGHT);
        scale.addView(far, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        box.addView(scale);

        if (isLock) {
            tvLockVal = val;
            sbLock = sb;
        } else {
            tvUnlockVal = val;
            sbUnlock = sb;
        }

        android.widget.SeekBar.OnSeekBarChangeListener l =
                new android.widget.SeekBar.OnSeekBarChangeListener() {
                    public void onProgressChanged(android.widget.SeekBar s, int p, boolean fromUser) {
                        refreshCeEdit();
                    }

                    public void onStartTrackingTouch(android.widget.SeekBar s) {
                    }

                    public void onStopTrackingTouch(android.widget.SeekBar s) {
                    }
                };
        sb.setOnSeekBarChangeListener(l);
        return box;
    }

    /** "-80" + 小一号的 " dBm", 免得多出一个跟数值同高的大单位。 */
    private CharSequence withUnit(String num) {
        String unit = " dBm";
        android.text.SpannableString s = new android.text.SpannableString(num + unit);
        int from = num.length();
        int to = s.length();
        // 0.6 倍是下限 —— 再小就变成看不清的下标了, 单位得能读但明显弱于数值
        s.setSpan(new android.text.style.RelativeSizeSpan(0.6f), from, to,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new android.text.style.ForegroundColorSpan(Ui.SUB), from, to,
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    private void tintSlider(android.widget.SeekBar sb) {
        android.content.res.ColorStateList cs =
                android.content.res.ColorStateList.valueOf(Ui.TEAL);
        sb.setProgressTintList(cs);
        sb.setThumbTintList(cs);
        sb.setPadding(Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6));
    }

    private void showCe(boolean show) {
        if (cePage == null) {
            return;
        }
        if (show) {
            loadCeIntoEditor();
            ceOpenPage = true;
            cePage.setVisibility(View.VISIBLE);
            advPage.setVisibility(View.GONE);
            mainPage.setVisibility(View.GONE);
            cePage.setAlpha(0f);
            cePage.animate().alpha(1f).setDuration(160).start();
            cePage.scrollTo(0, 0);
        } else {
            ceOpenPage = false;
            cePage.setVisibility(View.GONE);
            // 从「高级设置」进来的, 退回去也要回到高级设置
            advPage.setVisibility(View.VISIBLE);
            mainPage.setVisibility(View.GONE);
            advOpen = true;
            refreshCe();
        }
    }

    /** 打开配置页时, 用 Prefs 里当前生效值填满滑块。 */
    private void loadCeIntoEditor() {
        boolean open = Prefs.ceOpen(this);
        int lock = Prefs.ceLock(this);
        int unlock = Prefs.ceUnlock(this);
        // 没读到过就先用基线, 再没有就落在常用档位
        if (lock <= 0) {
            lock = Prefs.hasCeBase(this) ? Prefs.ceBaseLock(this) : 80;
        }
        if (unlock <= 0) {
            unlock = Prefs.hasCeBase(this) ? Prefs.ceBaseUnlock(this) : 70;
        }
        ceWantOpen = !Prefs.hasCe(this) || open;
        sbLock.setProgress(progressOf(lock));
        sbUnlock.setProgress(progressOf(unlock));
        refreshCeEdit();
        refreshCeCur();
    }

    private void refreshCeCur() {
        if (tvCeCur == null) {
            return;
        }
        if (!Prefs.hasCe(this)) {
            tvCeLockBig.setText("—");
            tvCeUnlockBig.setText("—");
            tvCeLockBig.setTextColor(Ui.HINT);
            tvCeUnlockBig.setTextColor(Ui.HINT);
            tvCeCur.setText("连接车机后自动读取，断开时显示最后一次读到的值。");
            tvCeCur.setTextColor(Ui.HINT);
            return;
        }
        boolean open = Prefs.ceOpen(this);
        int lock = Prefs.ceLock(this);
        int unlock = Prefs.ceUnlock(this);
        tvCeLockBig.setText(lock > 0 ? withUnit("-" + lock) : "—");
        tvCeUnlockBig.setText(unlock > 0 ? withUnit("-" + unlock) : "—");
        tvCeLockBig.setTextColor(Ui.TEXT);
        tvCeUnlockBig.setTextColor(Ui.TEXT);
        tvCeCur.setText(open
                ? "自动开锁已开启，显示的是车机当前生效的参数。"
                : "自动开锁已关闭，车机不会自动开锁。");
        tvCeCur.setTextColor(open ? Ui.HINT : Ui.AMBER);
    }

    /** 滑块拖动的实时反馈: 数值、开关态、以及两条阈值是否讲得通。 */
    private void refreshCeEdit() {
        if (swCe == null) {
            return;
        }
        // 开关状态只有一个来源 ceWantOpen, 点行和点开关都改它, 再统一回写视图
        swCe.setOn(ceWantOpen);

        int lock = valueOf(sbLock.getProgress());
        int unlock = valueOf(sbUnlock.getProgress());
        tvLockVal.setText(withUnit("-" + lock));
        tvUnlockVal.setText(withUnit("-" + unlock));

        boolean off = !ceWantOpen;
        sbLock.setEnabled(!off);
        sbUnlock.setEnabled(!off);
        sbLock.setAlpha(off ? 0.4f : 1f);
        sbUnlock.setAlpha(off ? 0.4f : 1f);
        tvLockVal.setTextColor(off ? Ui.HINT : Ui.TEXT);
        tvUnlockVal.setTextColor(off ? Ui.HINT : Ui.TEXT);

        if (off) {
            tvCeWarn.setVisibility(View.GONE);
            return;
        }
        if (unlock >= lock) {
            warnCe("开锁要发生在落锁之前：开锁阈值必须比落锁更靠近"
                    + "（数值小 5dBm 以上），否则会出现走远了也不落锁。", Ui.RED);
        } else if (lock - unlock > 25) {
            warnCe("两档相差 " + (lock - unlock) + "dBm，跨度偏大，"
                    + "可能出现走到车边不开锁。", Ui.AMBER);
        } else {
            tvCeWarn.setVisibility(View.GONE);
        }
    }

    /** 阈值组合不成立时的提示: 加一层同色淡底, 裸文字在卡片上会被当成说明略过。 */
    private void warnCe(String text, int color) {
        tvCeWarn.setText(text);
        tvCeWarn.setTextColor(color);
        tvCeWarn.setBackgroundDrawable(Ui.pill(this, color));
        int px = Ui.dp(this, 10);
        int py = Ui.dp(this, 8);
        tvCeWarn.setPadding(px, py, px, py);
        tvCeWarn.setVisibility(View.VISIBLE);
    }

    /**
     * 恢复默认: 写回第一次从车机读到的标定值。
     *
     * 之所以用基线而不是硬编码, 是因为每台车盒的标定不同 —— 这台机器实测就是
     * 落锁 -80 / 开锁 -70, 原包那套 75/65 只是它打开总开关时的初值。
     */
    private void restoreDefaultCe() {
        int lock = Prefs.ceBaseLock(this);
        int unlock = Prefs.ceBaseUnlock(this);
        if (lock <= 0 || unlock <= 0) {
            // 还没建立基线, 退一步用当前 Prefs 里的值; 两者都没有就只能提示
            lock = Prefs.ceLock(this);
            unlock = Prefs.ceUnlock(this);
        }
        if (lock <= 0 || unlock <= 0) {
            toast("还没从车机读到参数，请先连接一次车机");
            return;
        }
        ceWantOpen = true;
        sbLock.setProgress(progressOf(lock));
        sbUnlock.setProgress(progressOf(unlock));
        refreshCeEdit();
        if (!BleKeyService.isConnected()) {
            // 没连接时只把值填回滑块, 不能假装已经写进车机
            toast("已填回落锁 -" + lock + " / 开锁 -" + unlock + "，连上车机后再点应用");
            return;
        }
        BleKeyService.sendCe(this, true, lock, unlock);
        log("恢复默认: 落锁 -" + lock + " / 开锁 -" + unlock + " dBm");
        toast("已恢复为落锁 -" + lock + " / 开锁 -" + unlock + " dBm");
    }

    private void applyCe() {
        int lock = valueOf(sbLock.getProgress());
        int unlock = valueOf(sbUnlock.getProgress());
        if (ceWantOpen && unlock >= lock) {
            toast("开锁阈值必须比落锁更近，请调小开锁值");
            return;
        }
        if (!BleKeyService.isConnected()) {
            toast("车机未连接，连上后再应用");
            return;
        }
        BleKeyService.sendCe(this, ceWantOpen, ceWantOpen ? lock : 0, ceWantOpen ? unlock : 0);
        log(ceWantOpen
                ? "写入舒适进入: 落锁 -" + lock + " / 开锁 -" + unlock + " dBm"
                : "写入舒适进入: 关闭");
        toast(ceWantOpen ? "已写入，正在重读确认" : "已关闭自动开锁");
    }

    private static int valueOf(int progress) {
        return CE_MIN_DBM + progress * CE_STEP_DBM;
    }

    private static int progressOf(int dbm) {
        // 四舍五入到最近的 5 的整数倍, 防止车机报上来的值不在刻度上
        int p = Math.round((dbm - CE_MIN_DBM) / (float) CE_STEP_DBM);
        if (p < 0) {
            p = 0;
        }
        if (p > CE_STEPS) {
            p = CE_STEPS;
        }
        return p;
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    /** 主操作: 强调色填充。 */
    private static final int CTRL_PRIMARY = 1;
    /** 次要操作: 卡片底 + 淡描边。 */
    private static final int CTRL_PLAIN = 0;
    /** 强调的次要操作(后备箱): 描边和图标用强调色, 但不填充, 免得抢主按钮。 */
    private static final int CTRL_ACCENT = 2;

    /** 一个主操作块: 图标在上、文字在下, 按 style 决定填充还是描边。 */
    private View controlButton(int icon, String label, int style, View.OnClickListener l) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);

        int fg;
        int iconColor;
        if (style == CTRL_PRIMARY) {
            b.setBackgroundDrawable(Ui.round(this, Ui.TEAL, 14));
            fg = Ui.TEAL_ON;
            iconColor = fg;
        } else if (style == CTRL_ACCENT) {
            b.setBackgroundDrawable(Ui.outline(this, Ui.CARD, Ui.TEAL_LINE, 14, 1f));
            fg = Ui.TEXT;
            iconColor = Ui.TEAL;
        } else {
            b.setBackgroundDrawable(Ui.outline(this, Ui.CARD, Ui.LINE, 14, 1f));
            fg = Ui.TEXT;
            iconColor = fg;
        }

        b.addView(new IconView(this, icon, iconColor), Ui.lp(Ui.dp(this, 26), Ui.dp(this, 26)));

        TextView t = Ui.medium(this, label, 13, fg);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tLp.topMargin = Ui.dp(this, 7);
        b.addView(t, tLp);

        Ui.press(b);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams controlLp(int leftMargin) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                0, Ui.dp(this, 84), 1f);
        p.leftMargin = Ui.dp(this, leftMargin);
        return p;
    }

    /** 车辆信息里的一行, 左侧标题、右侧取值。 */
    private View infoRow(String label, TextView value, boolean first) {
        LinearLayout r = Ui.row(this);
        int px = Ui.dp(this, 14);
        r.setPadding(px, 0, px, 0);
        r.addView(Ui.text(this, label, 13, Ui.TEXT),
                Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(value);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 46));
        p.topMargin = Ui.dp(this, first ? 0 : 0);
        r.setLayoutParams(p);
        return r;
    }

    private void showAdv(boolean show) {
        if (mainPage == null || advPage == null) {
            return;
        }
        advOpen = show;
        View in = show ? advPage : mainPage;
        View out = show ? mainPage : advPage;
        out.setVisibility(View.GONE);
        in.setVisibility(View.VISIBLE);
        in.setAlpha(0f);
        in.animate().alpha(1f).setDuration(160).start();
        if (in instanceof ScrollView) {
            ((ScrollView) in).scrollTo(0, 0);
        }
    }

    /* ------------------------------ 状态 ------------------------------ */

    private String subtitle() {
        if (Prefs.keypass(this).length() < 32) {
            return "未配对 · 请先用原 App 配对";
        }
        String addr = Prefs.addr(this);
        String name = Prefs.name(this);
        if (addr.length() >= 5) {
            return name + " · " + addr.substring(addr.length() - 5);
        }
        return name;
    }

    private void updateStateText(String s, String d) {
        if (tvState == null) {
            return;
        }

        int dotColor;
        boolean active;
        if (BleKeyService.STATE_READY.equals(s)) {
            // 用 TEAL 而不是 GREEN: 青绿是本 App 的"正常态", 状态胶囊整条都会跟着染色,
            // 绿色留给"操作成功"这类瞬时反馈, 免得界面常态就在发绿光。
            dotColor = Ui.TEAL;
            active = false;
        } else if (BleKeyService.STATE_FAIL.equals(s)) {
            dotColor = Ui.RED;
            active = false;
        } else if (BleKeyService.STATE_NEED_UNBIND.equals(s)
                || BleKeyService.STATE_NEED_CONFIRM.equals(s)) {
            dotColor = Ui.AMBER;
            active = false;
        } else if (s == null || BleKeyService.STATE_IDLE.equals(s)) {
            dotColor = Ui.HINT;
            active = false;
        } else {
            // 连接/发现服务/鉴权, 都属于"正在连"
            dotColor = Ui.BLUE;
            active = true;
        }

        statusDot.setBackgroundDrawable(Ui.circle(this, dotColor));

        // 整条胶囊跟着状态走色: 同一个状态色做极淡的底 + 稍深的描边。
        // 用 alpha 叠加而不是给每个状态配一套 hex, 深浅两套主题都不用额外维护令牌 ——
        // 透出来的是页面底色, 白天夜里都自动合适。
        if (statusPill != null) {
            int pillFill = dotColor == Ui.HINT
                    ? Ui.FILL : Ui.alpha(dotColor, Ui.isDark ? 0.14f : 0.10f);
            int pillLine = dotColor == Ui.HINT
                    ? Ui.LINE : Ui.alpha(dotColor, Ui.isDark ? 0.42f : 0.30f);
            statusPill.setBackgroundDrawable(Ui.outline(this, pillFill, pillLine, 11, 1f));
        }

        // 主文案只放状态名(已连接/正在连接/连接失败…)。
        // 早先是直接拿 detail 当主文案, 连不上时 "AA:BB:CC:DD:EE:FF (等待靠近)"
        // 剥掉括号就只剩一串地址, 顶在界面最显眼的位置像 Bug。
        tvState.setText(stateLabel(s));
        tvState.setTextColor(dotColor == Ui.HINT ? Ui.SUB : dotColor);

        int rssi = parseRssi(d);
        if (rssi != 0) {
            // 信号条已经把强弱画出来了, 这里只留数字, 不再叠一句"信号良好"
            tvStateSub.setText(rssi + " dBm");
            tvStateSub.setTextColor(rssiWordColor(rssi));
            setSignal(rssi);
        } else {
            tvStateSub.setText(noteOf(s, d));
            tvStateSub.setTextColor(dotColor == Ui.HINT ? Ui.HINT : Ui.alpha(dotColor, 0.85f));
            setSignal(0);
        }

        startPulse(active);
    }

    /** 状态名, 用作状态卡的主文案。短、稳、不夹带地址。 */
    private String stateLabel(String s) {
        if (BleKeyService.STATE_READY.equals(s)) {
            return "已连接";
        }
        if (BleKeyService.STATE_FAIL.equals(s)) {
            return "连接失败";
        }
        if (BleKeyService.STATE_NEED_UNBIND.equals(s)) {
            return "需要配对";
        }
        if (BleKeyService.STATE_NEED_CONFIRM.equals(s)) {
            return "等待确认";
        }
        if (BleKeyService.STATE_AUTHING.equals(s)) {
            return "校验密钥";
        }
        if (BleKeyService.STATE_DISCOVERING.equals(s)) {
            return "发现服务";
        }
        if (BleKeyService.STATE_CONNECTING.equals(s)) {
            return "正在连接";
        }
        return "未连接";
    }

    /**
     * 副文案: 优先用服务那边给的具体提示(开锁结果、让位剩余时间、失败原因),
     * 但要先剔除 MAC 和 dBm 括号——这两样在主文案/信号条里已经有了,
     * 再跟着飘一行地址只会让人以为出错了。
     */
    private String noteOf(String s, String d) {
        if (d == null) {
            return subText(s);
        }
        String n = d.replaceAll("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}", " ")
                .replaceAll("\\(\\s*-?\\d+\\s*dBm\\s*\\)", " ")
                .replaceAll("\\s*\\(([^)]*)\\)", " · $1")
                .replaceAll("\\s+", " ")
                .trim();
        n = n.replaceAll("^[:：·,，、\\s]+", "").replaceAll("[:：·,，、\\s]+$", "").trim();
        if (n.length() == 0 || n.equals(stateLabel(s))) {
            return subText(s);
        }
        return n;
    }

    private String subText(String s) {
        if (BleKeyService.STATE_FAIL.equals(s)) {
            return "请检查蓝牙开关与车机电源";
        }
        if (BleKeyService.STATE_NEED_UNBIND.equals(s)) {
            return "需要先用原 App 完成配对";
        }
        if (s == null || BleKeyService.STATE_IDLE.equals(s)) {
            return "等待车机";
        }
        return "正在建立连接…";
    }

    private int parseRssi(String d) {
        if (d == null) {
            return 0;
        }
        Matcher m = RE_DBM.matcher(d);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private String rssiWord(int rssi) {
        if (rssi >= -60) {
            return "信号良好";
        }
        if (rssi >= -72) {
            return "信号一般";
        }
        if (rssi >= -82) {
            return "信号偏弱";
        }
        return "信号很弱";
    }

    private int rssiWordColor(int rssi) {
        if (rssi >= -72) {
            // 原来硬编码 #047857, 深色主题下这个深绿贴在近黑底上等于看不见
            return Ui.TEAL;
        }
        if (rssi >= -82) {
            return Ui.AMBER;
        }
        return Ui.RED;
    }

    /** 按 RSSI 点亮信号条, 传 0 表示全部置灰。 */
    private void setSignal(int rssi) {
        int level;
        int color;
        if (rssi == 0) {
            level = 0;
            color = Ui.TRACK;
        } else if (rssi >= -60) {
            level = 4;
            color = Ui.TEAL;
        } else if (rssi >= -72) {
            level = 3;
            color = Ui.TEAL;
        } else if (rssi >= -82) {
            level = 2;
            color = Ui.AMBER;
        } else {
            level = 1;
            color = Ui.RED;
        }
        // 未点亮的条用"次要色 + 透明度", 不用 TRACK: TRACK 是给进度条槽设计的,
        // 贴在胶囊底上几乎和背景同色, 深色主题下整组条会直接消失。
        int off = Ui.alpha(Ui.SUB, Ui.isDark ? 0.38f : 0.30f);
        for (int i = 0; i < signalBars.length; i++) {
            if (signalBars[i] != null) {
                signalBars[i].setBackgroundDrawable(Ui.round(this,
                        i < level ? color : off, 1.5f));
            }
        }
    }

    /** 连接过程中让状态点呼吸, 一眼能看出还在尝试。 */
    private void startPulse(boolean on) {
        if (!on) {
            stopPulse();
            return;
        }
        if (statusDot == null) {
            return;
        }
        if (pulse != null && pulse.isRunning()) {
            return;
        }
        pulse = ObjectAnimator.ofFloat(statusDot, "alpha", 1f, 0.3f);
        pulse.setDuration(700);
        pulse.setRepeatMode(ValueAnimator.REVERSE);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.start();
    }

    private void stopPulse() {
        if (pulse != null) {
            pulse.cancel();
            pulse = null;
        }
        if (statusDot != null) {
            statusDot.setAlpha(1f);
        }
    }

    /* ------------------------------ 电量 ------------------------------ */

    /** 把 Prefs 里的电量刷到卡片上。断开时保留最后一次读到的值。 */
    private void refreshPower() {
        if (ring == null) {
            return;
        }
        int p = Prefs.power(this);
        long ts = Prefs.powerTs(this);
        boolean ready = BleKeyService.STATE_READY.equals(BleKeyService.lastState());

        if (p < 0) {
            ring.setValue(-1, Ui.TRACK);
            tvPowerVolt.setText("尚未读取");
            tvPowerVolt.setTextColor(Ui.HINT);
            tvPowerSub.setText(ready ? "车机还没回报, 点「刷新」重试" : "未连接, 连上后自动读取");
            tvPowerSub.setTextColor(Ui.HINT);
            return;
        }

        // 只有低电量才破例用红/黄 —— 这两种颜色是"需要你注意"的语义,
        // 电量正常时无论多少一律用强调色, 否则界面天天在报警。
        int color = p <= 20 ? Ui.RED : (p <= 40 ? Ui.AMBER : Ui.TEAL);
        ring.setValue(p, color);

        int mv = Prefs.voltage(this);
        tvPowerVolt.setText(mv > 0 ? String.format(Locale.ENGLISH, "%.2f V", mv / 1000.0) : "--");
        tvPowerVolt.setTextColor(color == Ui.TEAL ? Ui.TEXT : color);

        StringBuilder sb = new StringBuilder(ago(ts));
        if (p <= 20) {
            sb.append(" · 建议充电");
        }
        tvPowerSub.setText(sb.toString());
        tvPowerSub.setTextColor(p <= 20 ? Ui.RED : Ui.HINT);
    }

    private String ago(long ts) {
        if (ts <= 0) {
            return "";
        }
        long d = System.currentTimeMillis() - ts;
        if (d < 60 * 1000L) {
            return "刚刚更新";
        }
        if (d < 60 * 60 * 1000L) {
            return (d / (60 * 1000L)) + " 分钟前更新";
        }
        if (d < 24 * 60 * 60 * 1000L) {
            return (d / (60 * 60 * 1000L)) + " 小时前更新";
        }
        return new SimpleDateFormat("MM-dd HH:mm", Locale.ENGLISH).format(new Date(ts));
    }

    /* ------------------------------ 其它卡片 ------------------------------ */

    private void setLogOpen(boolean open) {
        logOpen = open;
        if (logBox != null) {
            logBox.setVisibility(open ? View.VISIBLE : View.GONE);
        }
        if (tvLogTitle != null) {
            tvLogTitle.setText(open ? "收起 ▴" : "展开 ▾");
        }
        if (open) {
            scrollLogToBottom();
        } else if (advPage != null) {
            // 收起后把手势还给外层, 否则整页滑不动
            advPage.requestDisallowInterceptTouchEvent(false);
        }
    }

    private void scrollLogToBottom() {
        if (logScroll == null) {
            return;
        }
        logScroll.post(new Runnable() {
            public void run() {
                logScroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    /** 后台保活体检: 电池白名单是唯一会导致"锁屏就掉线"的硬开关。 */
    private void refreshBg() {
        boolean ok = KeepAlive.ignoringBattery(this);
        if (tvBgBadge != null) {
            int c = ok ? Ui.TEAL : Ui.RED;
            tvBgBadge.setText(ok ? "已加入" : "未加入");
            tvBgBadge.setTextColor(c);
            tvBgBadge.setBackgroundDrawable(Ui.pill(this, c));
        }
        if (tvBg == null) {
            return;
        }
        // 脚注只讲后果, 状态本身由徽章承担, 不要再重复一遍「已加入」
        tvBg.setText(ok
                ? "开机自启、被杀后自动重启都已内置。加入白名单后锁屏不会被冻结。"
                : "开机自启、被杀后自动重启都已内置。白名单还没加入：锁屏久了会被冻结，"
                + "Android 12+ 还会拦掉后台重连。");
        tvBg.setTextColor(ok ? Ui.HINT : Ui.RED);
    }

    /** 舒适进入参数直接读 Prefs, 不去解析 ceText 那串中文。 */
    private void refreshCe() {
        if (tvCeOpen == null) {
            return;
        }
        if (!Prefs.hasCe(this)) {
            tvCeOpen.setText("—");
            tvCeOpen.setTextColor(Ui.HINT);
            tvCeDist.setText("连接后读取");
            tvCeDist.setTextColor(Ui.HINT);
            if (tvAdvCe != null) {
                tvAdvCe.setText("连接后读取");
            }
            if (ceOpenPage) {
                refreshCeCur();
            }
            return;
        }
        boolean open = Prefs.ceOpen(this);
        tvCeOpen.setText(open ? "已开启" : "已关闭");
        tvCeOpen.setTextColor(open ? Ui.TEAL : Ui.SUB);

        int lock = Prefs.ceLock(this);
        int unlock = Prefs.ceUnlock(this);
        tvCeDist.setText((lock > 0 ? "-" + lock : "—") + " / "
                + (unlock > 0 ? "-" + unlock : "—") + " dBm");
        tvCeDist.setTextColor(Ui.SUB);

        if (tvAdvCe != null) {
            tvAdvCe.setText(lock > 0 && unlock > 0
                    ? "落锁 -" + lock + " · 开锁 -" + unlock + " dBm"
                    : "阈值未上报");
        }
        if (ceOpenPage) {
            refreshCeCur();
        }
    }

    private void refreshBinding() {
        String addr = Prefs.addr(this);
        boolean bound = Prefs.keypass(this).length() >= 32;

        if (tvKeyName != null) {
            tvKeyName.setText(bound ? Prefs.name(this) : "未绑定车机");
            tvKeyAddr.setText(addr.length() > 0 ? addr : "—");
            tvKeyAddr.setTextColor(addr.length() > 0 ? Ui.SUB : Ui.HINT);
            tvKeyState.setText(bound ? "密钥只保存在本机，不上传" : "请先用原 App 完成配对");
            tvKeyState.setTextColor(bound ? Ui.HINT : Ui.RED);
            int badgeColor = bound ? Ui.TEAL : Ui.RED;
            tvKeyBadge.setText(bound ? "已绑定" : "未绑定");
            tvKeyBadge.setTextColor(badgeColor);
            tvKeyBadge.setBackgroundDrawable(Ui.pill(this, badgeColor));
        }

        if (bound) {
            BleKeyService.start(this);
        }
    }

    private void log(final String s) {
        runOnUiThread(new Runnable() {
            public void run() {
                if (tvLog == null) {
                    return;
                }
                String ts = new SimpleDateFormat("HH:mm:ss", Locale.ENGLISH).format(new Date());
                // 只保留最近一段, 避免长期运行把内存吃满
                if (tvLog.length() > 12000) {
                    CharSequence old = tvLog.getText();
                    tvLog.setText(old.subSequence(old.length() / 2, old.length()));
                }
                tvLog.append(ts + "  " + s + "\n");
                if (logOpen) {
                    scrollLogToBottom();
                }
            }
        });
    }

    /* ------------------------------ 权限 ------------------------------ */

    private boolean hasScanPerm() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestNeededPermissions() {
        List<String> need = new ArrayList<String>();
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        } else {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.ACCESS_FINE_LOCATION);
            }
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            need.add("android.permission.POST_NOTIFICATIONS");
        }
        if (need.isEmpty()) {
            return;
        }
        requestPermissions(need.toArray(new String[0]), REQ_PERM);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM) {
            boolean ok = true;
            for (int g : grantResults) {
                if (g != PackageManager.PERMISSION_GRANTED) {
                    ok = false;
                }
            }
            log(ok ? "权限已授予" : "权限被拒绝, 蓝牙功能不可用");
        }
    }
}
