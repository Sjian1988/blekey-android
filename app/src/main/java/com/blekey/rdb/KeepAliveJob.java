package com.blekey.rdb;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;
import android.util.Log;

/**
 * 周期兜底任务: 每小时检查一次常驻服务还在不在, 不在就拉起来。
 * 进程被系统清掉之后, 靠它把车钥匙重新挂上。
 */
public class KeepAliveJob extends JobService {

    private static final String TAG = "BleKey";

    @Override
    public boolean onStartJob(JobParameters params) {
        Context c = getApplicationContext();
        try {
            if (!KeepAlive.needed(c)) {
                KeepAlive.uninstall(c);
            } else {
                if (!BleKeyService.isRunning()) {
                    Log.i(TAG, "job: service gone, restarting");
                    BleKeyService.start(c);
                }
                // 顺便把闹钟续上, 两条腿保持同一个节奏互相兜
                KeepAlive.scheduleAlarm(c, KeepAlive.ALARM_PERIOD_MS);
            }
        } catch (Throwable t) {
            Log.w(TAG, "job start fail", t);
        }
        jobFinished(params, false);
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // 被系统打断, 要求重新排期
        return true;
    }
}
