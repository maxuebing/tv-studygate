package com.bing.mitvguard;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

/**
 * 后台常驻防沉迷守护前台服务。
 * 负责 1 秒精度观看倒计时、断电状态恢复、周期性题库 JSON 同步及锁定状态维持。
 */
public class GuardService extends Service implements LockOverlayManager.UnlockCallback {

    public static final String ACTION_START = "com.bing.mitvguard.ACTION_START";
    public static final String ACTION_ENSURE_OVERLAY = "com.bing.mitvguard.ACTION_ENSURE_OVERLAY";
    public static final String ACTION_TRIGGER_LOCK_NOW = "com.bing.mitvguard.ACTION_TRIGGER_LOCK_NOW";
    public static final String ACTION_SYNC_QUIZ = "com.bing.mitvguard.ACTION_SYNC_QUIZ";
    public static final String ACTION_PARENT_BYPASS = "com.bing.mitvguard.ACTION_PARENT_BYPASS";
    public static final String ACTION_END_BYPASS = "com.bing.mitvguard.ACTION_END_BYPASS";

    private static final String TAG = "GuardService";
    private static final String CHANNEL_ID = "mitv_guard_channel";
    private static final int NOTIFICATION_ID = 1001;
    private static final int PERSIST_INTERVAL_SEC = 5;
    private static final int REMOTE_POLL_INTERVAL_SEC = 4;
    private static final long SYNC_INTERVAL_MS = 30 * 60 * 1000L;

    private GuardPrefs prefs;
    private QuizRepository repository;
    private LockOverlayManager overlayManager;
    private PowerManager powerManager;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private int cachedRemainingSec = 600;
    private int cachedBypassSec = 0;
    private int persistTickCounter = 0;
    private int remotePollCounter = 0;
    private volatile boolean isPollingRemote = false;
    private long lastSyncAttemptMs = 0L;
    private boolean screenOn = true;

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent != null ? intent.getAction() : "";
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                screenOn = false;
                persistRuntimeCounters();
                Log.i(TAG, "检测到屏幕关闭，暂停计时并立即落盘");
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                screenOn = true;
                Log.i(TAG, "检测到屏幕亮起，恢复守护计时");
                if (prefs.isLocked()) {
                    overlayManager.show();
                }
            }
        }
    };

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            onSecondTick();
            handler.postDelayed(this, 1000L);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = GuardPrefs.getInstance(this);
        repository = QuizRepository.getInstance(this);
        overlayManager = new LockOverlayManager(this, this);
        powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("学习守护运行中"));
        GuardWatchdogJob.schedule(this);

        cachedRemainingSec = prefs.getRemainingSeconds();
        cachedBypassSec = prefs.getBypassRemainingSeconds();
        screenOn = powerManager == null || powerManager.isInteractive();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenReceiver, filter);

        triggerBackgroundSync();
        if (prefs.isLocked()) {
            overlayManager.show();
        }
        handler.postDelayed(tickRunnable, 1000L);
        Log.i(TAG, "守护服务已启动，当前剩余秒数: " + cachedRemainingSec + "，锁定状态: " + prefs.isLocked());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : "";
        if (ACTION_ENSURE_OVERLAY.equals(action)) {
            if (prefs.isLocked()) {
                overlayManager.show();
                overlayManager.ensureFocus();
            }
        } else if (ACTION_TRIGGER_LOCK_NOW.equals(action)) {
            triggerLockNow();
        } else if (ACTION_SYNC_QUIZ.equals(action)) {
            triggerBackgroundSync();
        } else if (ACTION_PARENT_BYPASS.equals(action)) {
            overlayManager.hide();
            onParentBypass();
        } else if (ACTION_END_BYPASS.equals(action)) {
            prefs.setBypassRemainingSeconds(0, true);
            cachedBypassSec = 0;
            cachedRemainingSec = repository.getConfig().getWatchLimitSeconds();
            prefs.setRemainingSeconds(cachedRemainingSec);
            Log.i(TAG, "家长手动提前结束放行期，恢复正常 10 分钟计时");
        } else if (prefs.isLocked()) {
            overlayManager.show();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(tickRunnable);
        persistRuntimeCounters();
        try {
            unregisterReceiver(screenReceiver);
        } catch (Exception ignored) {
        }
        overlayManager.hide();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onQuizUnlocked() {
        int nextWatchSeconds = repository.getConfig().getWatchLimitSeconds();
        prefs.unlockWithNextRound(nextWatchSeconds);
        cachedRemainingSec = nextWatchSeconds;
        persistTickCounter = 0;
        triggerBackgroundSync();
        Log.i(TAG, "答题解锁成功，重置观看倒计时: " + nextWatchSeconds + " 秒");
    }

    @Override
    public void onParentBypass() {
        int bypassSeconds = repository.getConfig().getBypassSeconds();
        prefs.activateParentBypass(bypassSeconds);
        cachedBypassSec = bypassSeconds;
        cachedRemainingSec = repository.getConfig().getWatchLimitSeconds();
        prefs.setRemainingSeconds(cachedRemainingSec);
        persistTickCounter = 0;
        Log.i(TAG, "家长密码放行生效，放行秒数: " + bypassSeconds);
    }

    private void onSecondTick() {
        boolean interactive = powerManager == null ? screenOn : powerManager.isInteractive();
        screenOn = interactive;

        long now = System.currentTimeMillis();
        if (now - lastSyncAttemptMs > SYNC_INTERVAL_MS) {
            triggerBackgroundSync();
        }

        remotePollCounter++;
        if (remotePollCounter >= REMOTE_POLL_INTERVAL_SEC) {
            remotePollCounter = 0;
            triggerRemoteCommandPoll();
        }

        if (prefs.isLocked()) {
            if (!overlayManager.isShowing()) {
                overlayManager.show();
            } else {
                overlayManager.ensureFocus();
            }
            return;
        }

        if (!interactive) {
            return;
        }

        persistTickCounter++;

        if (cachedBypassSec > 0) {
            cachedBypassSec--;
            if (cachedBypassSec == 0 || persistTickCounter >= PERSIST_INTERVAL_SEC) {
                prefs.setBypassRemainingSeconds(cachedBypassSec, true);
                persistTickCounter = 0;
            }
            return;
        }

        if (cachedRemainingSec > 0) {
            cachedRemainingSec--;
        }

        if (cachedRemainingSec <= 0) {
            triggerLockNow();
            return;
        }

        if (persistTickCounter >= PERSIST_INTERVAL_SEC) {
            prefs.setRemainingSeconds(cachedRemainingSec);
            persistTickCounter = 0;
        }
    }

    private void triggerRemoteCommandPoll() {
        if (isPollingRemote) {
            return;
        }
        isPollingRemote = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    repository.pollRemoteCommand(new QuizRepository.RemoteCommandCallback() {
                        @Override
                        public void onRemoteCommand(final String command, final int seconds) {
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    applyRemoteCommand(command, seconds);
                                }
                            });
                        }
                    });
                } finally {
                    isPollingRemote = false;
                }
            }
        }).start();
    }

    private void applyRemoteCommand(String command, int seconds) {
        if ("bypass".equals(command)) {
            overlayManager.hide();
            int safeSeconds = Math.max(60, seconds);
            prefs.activateParentBypass(safeSeconds);
            cachedBypassSec = safeSeconds;
            cachedRemainingSec = repository.getConfig().getWatchLimitSeconds();
            prefs.setRemainingSeconds(cachedRemainingSec);
            persistTickCounter = 0;
            Log.i(TAG, "收到手机端远程放行指令，放行秒数: " + safeSeconds);
        } else if ("end_bypass".equals(command)) {
            prefs.setBypassRemainingSeconds(0, true);
            cachedBypassSec = 0;
            cachedRemainingSec = repository.getConfig().getWatchLimitSeconds();
            prefs.setRemainingSeconds(cachedRemainingSec);
            Log.i(TAG, "收到手机端结束放行指令，恢复正常计时");
        } else if ("lock_now".equals(command)) {
            Log.i(TAG, "收到手机端立即锁屏指令");
            triggerLockNow();
        }
    }

    private void triggerLockNow() {
        cachedRemainingSec = 0;
        prefs.enterLockState(1);
        triggerBackgroundSync();
        overlayManager.show();
    }

    private void persistRuntimeCounters() {
        if (prefs == null) {
            return;
        }
        if (cachedBypassSec > 0) {
            prefs.setBypassRemainingSeconds(cachedBypassSec, true);
        } else if (!prefs.isLocked()) {
            prefs.setRemainingSeconds(cachedRemainingSec);
        }
    }

    private void triggerBackgroundSync() {
        lastSyncAttemptMs = System.currentTimeMillis();
        new Thread(new Runnable() {
            @Override
            public void run() {
                repository.syncFromRemote();
            }
        }).start();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "学习守护后台服务",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private Notification buildNotification(String text) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setContentTitle("学习守护锁")
                .setContentText(text)
                .setSmallIcon(R.drawable.app_icon)
                .setOngoing(true)
                .build();
    }
}
