package com.blekey.rdb;

import android.content.Context;
import android.content.SharedPreferences;

/** 本地配置: 记住车机的蓝牙地址、序列号与设备密钥。 */
public final class Prefs {

    private static final String FILE = "blekey";

    public static final String K_ADDR   = "addr";      // 系统可见的蓝牙地址
    public static final String K_SERIAL = "serial";    // 广播里解析出的 6 字节序列号(hex)
    public static final String K_PASS   = "keypass";   // deviceKey 的 hex, 32 字符
    public static final String K_NAME   = "name";
    public static final String K_AUTO   = "auto_connect";
    public static final String K_BOOT   = "boot_start";
    public static final String K_UNLOCK_ON_NEAR = "unlock_on_near";
    public static final String K_RSSI_UNLOCK = "rssi_unlock";
    public static final String K_RSSI_LOCK   = "rssi_lock";
    public static final String K_UNLOCK_TWICE = "unlock_twice";

    /** 从车机读回(7/32)的舒适进入参数, 只用于显示, 不改车机配置。 */
    public static final String K_CE_OPEN   = "ce_open";
    public static final String K_CE_LOCK   = "ce_lock";      // 落锁阈值, 例 80 -> -80dBm
    public static final String K_CE_UNLOCK = "ce_unlock";    // 开锁阈值, 例 70 -> -70dBm
    public static final String K_BG_PROMPTED = "bg_prompted";

    /**
     * 舒适进入的「兜底基线」: 第一次从车机读到的参数原样存下来, 只建立一次。
     *
     * 重置按钮写回的是这个快照, 而不是原包硬编码的 75/65 —— 每台车盒出厂标定不同,
     * 用户车上实测就是 -80/-70。也不能实时取当前值: 用户把参数改坏之后再点重置,
     * 当前值已经是坏值, 那样兜底就失效了。
     */
    public static final String K_CE_BASE_OPEN   = "ce_base_open";
    public static final String K_CE_BASE_LOCK   = "ce_base_lock";
    public static final String K_CE_BASE_UNLOCK = "ce_base_unlock";

    /** 车机自报的电量(cmd=2 响应 tag=23/32/33), 断开后保留最后一次的值。 */
    public static final String K_POWER     = "power";        // 0~100, -1 表示还没读到
    public static final String K_VOLTAGE   = "voltage";      // mV
    public static final String K_CHARGING  = "charging";     // 0 未充电 / 1 充电中
    public static final String K_POWER_TS  = "power_ts";     // 读到的时间, ms

    /** 原车机配置「开锁按 2 次, 间隔 1 秒」, 连发两帧的间隔。 */
    public static final int UNLOCK_GAP_MS = 1000;

    private Prefs() {
    }

    public static SharedPreferences get(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String addr(Context c) {
        return get(c).getString(K_ADDR, "");
    }

    public static String serial(Context c) {
        return get(c).getString(K_SERIAL, "");
    }

    public static String keypass(Context c) {
        return get(c).getString(K_PASS, "");
    }

    public static String name(Context c) {
        return get(c).getString(K_NAME, "车钥匙");
    }

    public static boolean autoConnect(Context c) {
        return get(c).getBoolean(K_AUTO, true);
    }

    /** 开机自启固定开启, 不再做成可配置项。 */
    public static boolean bootStart(Context c) {
        return true;
    }

    /** 车机配置是「开锁按 2 次」, 单发一帧车门不会开, 固定连发。 */
    public static boolean unlockTwice(Context c) {
        return true;
    }

    /** 车机里是否开着"靠近自动开锁"(舒适进入)。 */
    public static boolean ceOpen(Context c) {
        return get(c).getBoolean(K_CE_OPEN, false);
    }

    /** 开锁阈值(正数, 实际是 -N dBm)。0 表示还没从车机读到。 */
    public static int ceUnlock(Context c) {
        return get(c).getInt(K_CE_UNLOCK, 0);
    }

    /** 落锁阈值(正数, 实际是 -N dBm)。 */
    public static int ceLock(Context c) {
        return get(c).getInt(K_CE_LOCK, 0);
    }

    public static boolean hasCe(Context c) {
        return ceUnlock(c) > 0 || ceLock(c) > 0 || get(c).contains(K_CE_OPEN);
    }

    /* ---------------- 舒适进入兜底基线 ---------------- */

    /** 基线是否已经建立(至少读到过一次有效距离)。 */
    public static boolean hasCeBase(Context c) {
        return ceBaseLock(c) > 0 && ceBaseUnlock(c) > 0;
    }

    public static boolean ceBaseOpen(Context c) {
        return get(c).getBoolean(K_CE_BASE_OPEN, true);
    }

    public static int ceBaseLock(Context c) {
        return get(c).getInt(K_CE_BASE_LOCK, 0);
    }

    public static int ceBaseUnlock(Context c) {
        return get(c).getInt(K_CE_BASE_UNLOCK, 0);
    }

    /**
     * 建立基线快照。只在还没有基线时调用 —— 一旦建立就不再改,
     * 否则用户改坏参数后重置会"记住坏值"。
     */
    public static void putCeBase(Context c, boolean open, int lock, int unlock) {
        get(c).edit()
                .putBoolean(K_CE_BASE_OPEN, open)
                .putInt(K_CE_BASE_LOCK, lock)
                .putInt(K_CE_BASE_UNLOCK, unlock)
                .apply();
    }

    /**
     * 基线缺失但手上已有有效读数时补建一次。
     * 用于老版本升级上来、或者基线被系统清掉的情况, 不会覆盖已有基线。
     */
    public static void ensureCeBase(Context c) {
        if (hasCeBase(c)) {
            return;
        }
        int lock = ceLock(c);
        int unlock = ceUnlock(c);
        if (lock > 0 && unlock > 0) {
            putCeBase(c, ceOpen(c), lock, unlock);
        }
    }

    /** 车机电量百分比。-1 表示还没读到过。 */
    public static int power(Context c) {
        return get(c).getInt(K_POWER, -1);
    }

    /** 电池电压 mV, 0 表示没读到。 */
    public static int voltage(Context c) {
        return get(c).getInt(K_VOLTAGE, 0);
    }

    /** 0 未充电 / 1 充电中 / -1 未知。 */
    public static int charging(Context c) {
        return get(c).getInt(K_CHARGING, -1);
    }

    /** 电量读到的时间戳, 0 表示从来没读到。 */
    public static long powerTs(Context c) {
        return get(c).getLong(K_POWER_TS, 0L);
    }

    public static void put(Context c, String k, long v) {
        get(c).edit().putLong(k, v).apply();
    }

    public static void put(Context c, String k, String v) {
        get(c).edit().putString(k, v).apply();
    }

    /** 同步落盘。密钥必须用这个: apply() 是异步的, 进程被系统杀掉时会丢。 */
    public static void putSync(Context c, String k, String v) {
        get(c).edit().putString(k, v).commit();
    }

    public static void put(Context c, String k, boolean v) {
        get(c).edit().putBoolean(k, v).apply();
    }

    public static void put(Context c, String k, int v) {
        get(c).edit().putInt(k, v).apply();
    }

    public static void clearBinding(Context c) {
        get(c).edit()
                .remove(K_ADDR)
                .remove(K_SERIAL)
                .remove(K_PASS)
                .apply();
    }
}
