package com.bing.mitvguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * 开机及应用更新自启广播接收器。
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : "";
        Log.i(TAG, "收到系统启动广播: " + action);
        GuardWatchdogJob.schedule(context);
        Intent serviceIntent = new Intent(context, GuardService.class);
        serviceIntent.setAction(GuardService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        } catch (Exception e) {
            Log.e(TAG, "广播拉起守护服务失败: " + e.getMessage(), e);
        }
    }
}
