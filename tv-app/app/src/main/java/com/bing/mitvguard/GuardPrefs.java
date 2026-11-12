package com.bing.mitvguard;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 防断电持久化状态管理器。
 * 所有核心状态写入时均调用 commit() 同步落盘，确保关机或拔电源后不丢失进度。
 */
public final class GuardPrefs {

    private static final String PREF_NAME = "mitv_guard_state";
    private static final String CACHE_PREF_NAME = "mitv_quiz_cache";
    private static final String KEY_REMAINING_SECONDS = "remaining_seconds";
    private static final String KEY_IS_LOCKED = "is_locked";
    private static final String KEY_PENDING_QUESTIONS = "pending_questions";
    private static final String KEY_BYPASS_REMAINING_SECONDS = "bypass_remaining_seconds";
    private static final String KEY_BYPASS_LAST_WALL_MS = "bypass_last_wall_ms";
    private static final String KEY_QUIZ_URL = "quiz_url";
    private static final String KEY_CACHED_QUIZ_JSON = "cached_quiz_json";
    private static final String KEY_LAST_SYNC_MS = "last_sync_ms";
    private static final String KEY_LAST_REMOTE_CMD_ID = "last_remote_cmd_id";

    private static final String DEFAULT_QUIZ_URL = "http://192.168.1.100:8090/api/quiz.json";
    private static final long MIN_VALID_WALL_CLOCK_MS = 1700000000000L;
    private static final int DEFAULT_WATCH_LIMIT = 600;

    private static GuardPrefs instance;
    private final SharedPreferences prefs;
    private final SharedPreferences cachePrefs;

    private GuardPrefs(Context context) {
        Context app = context.getApplicationContext();
        this.prefs = app.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        this.cachePrefs = app.getSharedPreferences(CACHE_PREF_NAME, Context.MODE_PRIVATE);
        if (!prefs.contains(KEY_REMAINING_SECONDS)) {
            prefs.edit()
                    .putInt(KEY_REMAINING_SECONDS, DEFAULT_WATCH_LIMIT)
                    .putBoolean(KEY_IS_LOCKED, false)
                    .putInt(KEY_PENDING_QUESTIONS, 0)
                    .putInt(KEY_BYPASS_REMAINING_SECONDS, 0)
                    .commit();
        }
    }

    /**
     * 获取单例实例。
     *
     * @param context 应用或组件上下文
     * @return GuardPrefs 单例
     */
    public static synchronized GuardPrefs getInstance(Context context) {
        if (instance == null) {
            instance = new GuardPrefs(context);
        }
        return instance;
    }

    public synchronized int getRemainingSeconds() {
        return prefs.getInt(KEY_REMAINING_SECONDS, DEFAULT_WATCH_LIMIT);
    }

    public synchronized void setRemainingSeconds(int seconds) {
        prefs.edit().putInt(KEY_REMAINING_SECONDS, Math.max(0, seconds)).commit();
    }

    public synchronized boolean isLocked() {
        return prefs.getBoolean(KEY_IS_LOCKED, false);
    }

    public synchronized int getPendingQuestions() {
        return prefs.getInt(KEY_PENDING_QUESTIONS, 0);
    }

    /**
     * 触发答题锁定状态并同步落盘。
     *
     * @param pendingCount 初始需要答对的题数
     */
    public synchronized void enterLockState(int pendingCount) {
        prefs.edit()
                .putBoolean(KEY_IS_LOCKED, true)
                .putInt(KEY_PENDING_QUESTIONS, Math.max(1, pendingCount))
                .putInt(KEY_REMAINING_SECONDS, 0)
                .commit();
    }

    /**
     * 更新待答对题目数量并落盘。
     *
     * @param pendingCount 更新后的待答题数
     */
    public synchronized void setPendingQuestions(int pendingCount) {
        prefs.edit().putInt(KEY_PENDING_QUESTIONS, Math.max(0, pendingCount)).commit();
    }

    /**
     * 答题完成解除锁定并重置下一轮观看倒计时。
     *
     * @param nextWatchSeconds 下一轮允许观看秒数
     */
    public synchronized void unlockWithNextRound(int nextWatchSeconds) {
        prefs.edit()
                .putBoolean(KEY_IS_LOCKED, false)
                .putInt(KEY_PENDING_QUESTIONS, 0)
                .putInt(KEY_REMAINING_SECONDS, Math.max(30, nextWatchSeconds))
                .commit();
    }

    /**
     * 开启家长应急放行模式并解除锁定。
     *
     * @param bypassSeconds 放行总秒数（默认 3600 秒）
     */
    public synchronized void activateParentBypass(int bypassSeconds) {
        long nowWall = System.currentTimeMillis();
        prefs.edit()
                .putBoolean(KEY_IS_LOCKED, false)
                .putInt(KEY_PENDING_QUESTIONS, 0)
                .putInt(KEY_BYPASS_REMAINING_SECONDS, Math.max(60, bypassSeconds))
                .putLong(KEY_BYPASS_LAST_WALL_MS, nowWall)
                .commit();
    }

    /**
     * 获取当前剩余放行秒数，并在系统时间有效时自动扣除离线期间流逝的墙钟时间。
     *
     * @return 剩余放行秒数，0 表示未处于放行期
     */
    public synchronized int getBypassRemainingSeconds() {
        int remaining = prefs.getInt(KEY_BYPASS_REMAINING_SECONDS, 0);
        if (remaining <= 0) {
            return 0;
        }
        long lastWall = prefs.getLong(KEY_BYPASS_LAST_WALL_MS, 0L);
        long nowWall = System.currentTimeMillis();
        if (lastWall >= MIN_VALID_WALL_CLOCK_MS && nowWall >= MIN_VALID_WALL_CLOCK_MS && nowWall > lastWall) {
            long elapsedSec = (nowWall - lastWall) / 1000L;
            if (elapsedSec > 5 && elapsedSec < 86400L) {
                remaining = (int) Math.max(0L, remaining - elapsedSec);
                prefs.edit()
                        .putInt(KEY_BYPASS_REMAINING_SECONDS, remaining)
                        .putLong(KEY_BYPASS_LAST_WALL_MS, nowWall)
                        .commit();
            }
        }
        return remaining;
    }

    /**
     * 更新放行剩余秒数。
     *
     * @param seconds 剩余秒数
     * @param persist 是否立即落盘
     */
    public synchronized void setBypassRemainingSeconds(int seconds, boolean persist) {
        int safeSeconds = Math.max(0, seconds);
        if (persist || safeSeconds == 0) {
            prefs.edit()
                    .putInt(KEY_BYPASS_REMAINING_SECONDS, safeSeconds)
                    .putLong(KEY_BYPASS_LAST_WALL_MS, System.currentTimeMillis())
                    .commit();
        }
    }

    public synchronized String getQuizUrl() {
        return prefs.getString(KEY_QUIZ_URL, DEFAULT_QUIZ_URL);
    }

    public synchronized void setQuizUrl(String url) {
        if (url != null && !url.trim().isEmpty()) {
            prefs.edit().putString(KEY_QUIZ_URL, url.trim()).commit();
        }
    }

    public synchronized String getCachedQuizJson() {
        return cachePrefs.getString(KEY_CACHED_QUIZ_JSON, "");
    }

    public synchronized void setCachedQuizJson(String json) {
        if (json != null && !json.trim().isEmpty()) {
            cachePrefs.edit()
                    .putString(KEY_CACHED_QUIZ_JSON, json)
                    .putLong(KEY_LAST_SYNC_MS, System.currentTimeMillis())
                    .commit();
        }
    }

    public synchronized long getLastSyncMs() {
        return cachePrefs.getLong(KEY_LAST_SYNC_MS, 0L);
    }

    public synchronized long getLastRemoteCommandId() {
        return prefs.getLong(KEY_LAST_REMOTE_CMD_ID, 0L);
    }

    public synchronized void setLastRemoteCommandId(long cmdId) {
        prefs.edit().putLong(KEY_LAST_REMOTE_CMD_ID, cmdId).commit();
    }
}
