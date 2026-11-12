package com.bing.mitvguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * ADB 命令行配置下发广播接收器。
 * 支持通过 adb shell am broadcast 直接修改电视端的题库 JSON 网址并触发同步。
 */
public class ConfigReceiver extends BroadcastReceiver {

    private static final String TAG = "ConfigReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        String quizUrl = intent.getStringExtra("quiz_url");
        if (quizUrl != null && !quizUrl.trim().isEmpty()) {
            GuardPrefs.getInstance(context).setQuizUrl(quizUrl.trim());
            Log.i(TAG, "通过广播更新题库地址: " + quizUrl.trim());
            Intent syncIntent = new Intent(context, GuardService.class);
            syncIntent.setAction(GuardService.ACTION_SYNC_QUIZ);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(syncIntent);
            } else {
                context.startService(syncIntent);
            }
        }
    }
}
