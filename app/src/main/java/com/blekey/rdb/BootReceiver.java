package com.blekey.rdb;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机后把常驻服务拉起来, 这样不用手动打开 App 也能自动连车。 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(a)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(a)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            return;
        }
        if (!Prefs.bootStart(context)) {
            return;
        }
        if (Prefs.addr(context).length() == 0) {
            return;
        }
        BleKeyService.start(context);
    }
}
