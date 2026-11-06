package com.blekey.rdb;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

/**
 * 后台保活。
 *
 * 三条腿一起用, 单独任何一条在国产 ROM 上都可能被削掉:
 *   1. 前台服务 (BleKeyService) —— 系统最低优先级最高的一档
 *   2. JobScheduler 周期任务 (1 小时) —— 进程被杀后把服务拉回来
 *   3. AlarmManager 闹钟 (setAndAllowWhileIdle) —— Doze 下也能醒, 兜底
 *
 * 另外还需要用户在系统里给两个开关 (只能用户手动点, App 无法代劳):
 *   - 电池优化白名单 (省电策略 -> 无限制)
 *   - 自启动 / 后台弹出界面
 * 电池白名单还顺带解开了 Android 12+ 的"后台不允许启动前台服务"限制,
 * 所以这一步对 autoConnect 常驻连接是必须的。
 */
public final class KeepAlive {

    private static final String TAG = "BleKey";

    private static final int JOB_ID = 8201;
    /**
     * 兜底检查周期, 统一 1 小时。
     * JobScheduler 周期下限是 15 分钟; 常驻服务靠前台通知自己活着,
     * 这个任务只在进程被系统清掉后把它拉回来, 不需要太勤。
     */
    private static final long JOB_PERIOD_MS = 60 * 60 * 1000L;
    public static final long ALARM_PERIOD_MS = 60 * 60 * 1000L;
    private static final int ALARM_REQ = 8301;

    private KeepAlive() {
    }

    /** 是否已经配过车机 —— 没配过就不用保活了。 */
    public static boolean needed(Context c) {
        return Prefs.addr(c).length() > 0;
    }

    /** 装齐 Job + 闹钟。幂等, 重复调用无副作用。 */
    public static void install(Context c) {
        scheduleJob(c);
        scheduleAlarm(c, 60 * 1000L);
    }

    public static void uninstall(Context c) {
        cancelAlarm(c);
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js != null) {
                js.cancel(JOB_ID);
            }
        } catch (Throwable t) {
            Log.w(TAG, "cancel job fail", t);
        }
    }

    /* ------------------------------ JobScheduler ------------------------------ */

    public static void scheduleJob(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) {
                return;
            }
            JobInfo.Builder b = new JobInfo.Builder(JOB_ID,
                    new ComponentName(c, KeepAliveJob.class))
                    .setPersisted(true)          // 重启后仍然有效, 需要 RECEIVE_BOOT_COMPLETED
                    .setPeriodic(JOB_PERIOD_MS);
            js.schedule(b.build());
        } catch (Throwable t) {
            Log.w(TAG, "schedule job fail", t);
        }
    }

    /* ------------------------------ AlarmManager ------------------------------ */

    public static void scheduleAlarm(Context c, long delayMs) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am == null) {
                return;
            }
            long at = System.currentTimeMillis() + Math.max(2000L, delayMs);
            PendingIntent pi = alarmPi(c);
            if (Build.VERSION.SDK_INT >= 23) {
                // Doze 下也能触发, 但系统会限流(最短约 9 分钟), 够用来兜底
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            } else {
                am.set(AlarmManager.RTC_WAKEUP, at, pi);
            }
        } catch (Throwable t) {
            Log.w(TAG, "schedule alarm fail", t);
        }
    }

    public static void cancelAlarm(Context c) {
        try {
            AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                am.cancel(alarmPi(c));
            }
        } catch (Throwable ignored) {
        }
    }

    private static PendingIntent alarmPi(Context c) {
        Intent i = new Intent(c, KeepAliveReceiver.class);
        i.setAction(KeepAliveReceiver.ACTION_TICK);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(c, ALARM_REQ, i, flags);
    }

    /* ------------------------------ 电池优化白名单 ------------------------------ */

    public static boolean ignoringBattery(Context c) {
        if (Build.VERSION.SDK_INT < 23) {
            return true;
        }
        try {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 弹系统的"允许后台高耗电 / 不限制电池"对话框。 */
    public static void requestIgnoreBattery(Activity a) {
        if (Build.VERSION.SDK_INT < 23) {
            return;
        }
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + a.getPackageName()));
            a.startActivity(i);
            return;
        } catch (Throwable ignored) {
        }
        try {
            a.startActivity(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        } catch (Throwable ignored) {
        }
    }

    /* ------------------------------ 自启动管理 ------------------------------ */

    /**
     * 尽量跳到各家的"自启动"页面。跳不过去就返回 false, 由界面给文字指引。
     * 不用 resolveActivity 判断, 因为 Android 11+ 的包可见性会让它误报 null。
     */
    public static boolean openAutoStartSettings(Context c) {
        String pkg = c.getPackageName();
        String[][] explicit = {
                {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
                {"com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"},
                {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
                {"com.letv.android.letvsafe", "com.letv.android.letvsafe.AutobootManageActivity"},
        };
        for (String[] t : explicit) {
            try {
                Intent i = new Intent();
                i.setComponent(new ComponentName(t[0], t[1]));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
                return true;
            } catch (Throwable ignored) {
            }
        }
        // 小米/红米走隐式 action, 组件名在不同版本会变
        try {
            Intent i = new Intent("miui.intent.action.OP_AUTO_START");
            i.addCategory("android.intent.category.DEFAULT");
            i.putExtra("extra_pkgname", pkg);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return true;
        } catch (Throwable ignored) {
        }
        return false;
    }
}
