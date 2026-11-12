package com.bing.mitvguard;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

/**
 * 无障碍防逃逸与开机唤醒双保险服务。
 * Android 系统会在开机后自动绑定已启用的无障碍服务，从而绕过国产电视对普通开机广播的拦截；
 * 同时在答题锁定期间拦截切出操作并拉回答题遮罩焦点。
 */
public class LockAccessibilityService extends AccessibilityService {

    private static final String TAG = "LockAccessibilitySvc";

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.i(TAG, "无障碍服务已连接，立即拉起学习守护前台服务");
        startGuardService(GuardService.ACTION_START);
        GuardWatchdogJob.schedule(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        if (!GuardPrefs.getInstance(this).isLocked()) {
            return;
        }
        CharSequence pkg = event.getPackageName();
        if (pkg != null && getPackageName().equals(pkg.toString())) {
            return;
        }
        Log.d(TAG, "锁定期间检测到切出窗口或语音弹窗: " + pkg + "，立即关闭系统弹窗并拉回锁屏焦点");
        try {
            sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
        } catch (Exception ignored) {
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            performGlobalAction(GLOBAL_ACTION_BACK);
        }
        startGuardService(GuardService.ACTION_ENSURE_OVERLAY);
    }

    @Override
    public void onInterrupt() {
    }

    private void startGuardService(String action) {
        Intent intent = new Intent(this, GuardService.class);
        intent.setAction(action);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Exception e) {
            Log.e(TAG, "拉起守护服务失败: " + e.getMessage(), e);
        }
    }
}
