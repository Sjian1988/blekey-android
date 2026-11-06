package com.blekey.rdb;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** 闹钟兜底: Doze 状态下也能把常驻服务重新拉起来。 */
public class KeepAliveReceiver extends BroadcastReceiver {

    public static final String ACTION_TICK = "com.blekey.rdb.action.KEEPALIVE";

    private static final String TAG = "BleKey";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!KeepAlive.needed(context)) {
            KeepAlive.uninstall(context);
            return;
        }
        if (!BleKeyService.isRunning()) {
            Log.i(TAG, "alarm: service gone, restarting");
            try {
                BleKeyService.start(context);
            } catch (Throwable t) {
                Log.w(TAG, "alarm restart fail", t);
            }
        }
        // 续下一次
        KeepAlive.scheduleAlarm(context, KeepAlive.ALARM_PERIOD_MS);
    }
}
