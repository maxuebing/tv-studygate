package com.bing.mitvguard;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * 持久化系统级定时保活任务（跨断电重启保留）。
 * 若开机广播或无障碍链路被厂商系统延迟或杀掉，系统调度器会定时唤醒并拉起守护服务。
 */
public class GuardWatchdogJob extends JobService {

    private static final String TAG = "GuardWatchdogJob";
    private static final int JOB_ID = 20261;
    private static final long PERIOD_MS = 15 * 60 * 1000L;

    /**
     * 注册跨重启持久化的周期调度任务。
     *
     * @param context 应用或组件上下文
     */
    public static void schedule(Context context) {
        JobScheduler scheduler = (JobScheduler) context.getApplicationContext()
                .getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (scheduler == null) {
            return;
        }
        JobInfo.Builder builder = new JobInfo.Builder(
                JOB_ID,
                new ComponentName(context.getApplicationContext(), GuardWatchdogJob.class)
        )
                .setPersisted(true)
                .setPeriodic(PERIOD_MS);
        int result = scheduler.schedule(builder.build());
        Log.i(TAG, "注册持久化保活任务结果: " + result);
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        Log.i(TAG, "Watchdog 触发，检查并拉起学习守护服务");
        Intent intent = new Intent(this, GuardService.class);
        intent.setAction(GuardService.ACTION_START);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Watchdog 拉起守护服务异常: " + e.getMessage(), e);
        }
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
