package com.bing.mitvguard;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/**
 * 题库同步与随机抽题仓库。
 * 支持从远程 JSON URL 拉取最新题库并缓存至本地；断网时自动退回本地缓存或内置四年级种子题库。
 */
public class QuizRepository {

    private static final String TAG = "QuizRepository";
    private static final int CONNECT_TIMEOUT_MS = 2500;
    private static final int READ_TIMEOUT_MS = 3500;
    private static final int RECENT_HISTORY_LIMIT = 8;

    private static QuizRepository instance;
    private final Context appContext;
    private final GuardPrefs prefs;
    private final Random random = new Random();
    private final Deque<Integer> recentQuestionIds = new ArrayDeque<Integer>();

    private QuizItem.QuizConfig currentConfig = new QuizItem.QuizConfig();
    private List<QuizItem> currentQuestions = new ArrayList<QuizItem>();

    private QuizRepository(Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = GuardPrefs.getInstance(appContext);
        loadLocalFallback();
    }

    /**
     * 获取题库仓库单例。
     *
     * @param context 上下文
     * @return QuizRepository 单例
     */
    public static synchronized QuizRepository getInstance(Context context) {
        if (instance == null) {
            instance = new QuizRepository(context);
        }
        return instance;
    }

    /**
     * 加载本地缓存题库；若无缓存则加载 APK 内置 default_quiz.json。
     */
    public synchronized void loadLocalFallback() {
        String cached = prefs.getCachedQuizJson();
        if (!cached.isEmpty() && parseAndApplyJson(cached)) {
            Log.i(TAG, "已加载本地缓存题库，共 " + currentQuestions.size() + " 题");
            return;
        }
        String assetJson = readAssetFile("default_quiz.json");
        if (!assetJson.isEmpty() && parseAndApplyJson(assetJson)) {
            Log.i(TAG, "已加载内置兜底题库，共 " + currentQuestions.size() + " 题");
        }
    }

    /**
     * 从配置的远端 JSON 网址同步题库与参数。
     *
     * @return 同步成功且解析有效返回 true，否则保持现有本地题库返回 false
     */
    public boolean syncFromRemote() {
        String targetUrl = prefs.getQuizUrl();
        HttpURLConnection conn = null;
        try {
            URL url = new URL(targetUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setUseCaches(false);

            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "拉取题库失败，HTTP 状态码: " + code);
                return false;
            }
            String body = readStream(conn.getInputStream());
            if (parseAndApplyJson(body)) {
                prefs.setCachedQuizJson(body);
                Log.i(TAG, "远程题库同步成功，总题数: " + getQuestionCount());
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "远程题库拉取异常，继续使用本地缓存: " + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return false;
    }

    /**
     * 远程控制指令回调。
     */
    public interface RemoteCommandCallback {
        /**
         * 收到服务端新下发的家长控制指令。
         *
         * @param command 指令名称（bypass / end_bypass / lock_now）
         * @param seconds 放行时长秒数
         */
        void onRemoteCommand(String command, int seconds);
    }

    /**
     * 轮询服务端最新家长远程控制指令。
     *
     * @param callback 新指令回调
     */
    public void pollRemoteCommand(RemoteCommandCallback callback) {
        String quizUrl = prefs.getQuizUrl();
        if (quizUrl == null || !quizUrl.contains("/api/")) {
            return;
        }
        String cmdUrl = quizUrl.substring(0, quizUrl.indexOf("/api/")) + "/api/remote/command";
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(cmdUrl).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);
            conn.setUseCaches(false);
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return;
            }
            String body = readStream(conn.getInputStream());
            JSONObject obj = new JSONObject(body);
            long cmdId = obj.optLong("command_id", 0L);
            String command = obj.optString("command", "none");
            int seconds = obj.optInt("seconds", 3600);
            if (cmdId > 0 && cmdId > prefs.getLastRemoteCommandId() && !"none".equals(command)) {
                prefs.setLastRemoteCommandId(cmdId);
                if (callback != null) {
                    callback.onRemoteCommand(command, seconds);
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "远程指令轮询跳过: " + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 异步向 Web 服务端上报单次答题记录。
     *
     * @param item         作答题目
     * @param isCorrect    是否答对
     * @param pendingAfter 本次作答后剩余待答题数
     */
    public void reportAnswerAsync(final QuizItem item, final boolean isCorrect, final int pendingAfter) {
        final String quizUrl = prefs.getQuizUrl();
        if (quizUrl == null || !quizUrl.contains("/api/")) {
            return;
        }
        final String reportUrl = quizUrl.substring(0, quizUrl.indexOf("/api/")) + "/api/report";
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    JSONObject body = new JSONObject();
                    body.put("device_id", "xiaomi-a55");
                    body.put("subject", item.getSubject());
                    body.put("question_id", item.getId());
                    body.put("question_text", item.getQuestion());
                    body.put("is_correct", isCorrect);
                    body.put("pending_after", pendingAfter);

                    conn = (HttpURLConnection) new URL(reportUrl).openConnection();
                    conn.setRequestMethod("POST");
                    conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                    conn.setReadTimeout(CONNECT_TIMEOUT_MS);
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

                    byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                    OutputStream os = conn.getOutputStream();
                    os.write(bytes);
                    os.flush();
                    os.close();
                    conn.getResponseCode();
                } catch (Exception e) {
                    Log.d(TAG, "答题记录上报跳过: " + e.getMessage());
                } finally {
                    if (conn != null) {
                        conn.disconnect();
                    }
                }
            }
        }).start();
    }

    /**
     * 随机抽取一道尽量不与最近几题重复的题目。
     *
     * @return 抽取到的题目对象
     */
    public synchronized QuizItem pickRandomQuestion() {
        if (currentQuestions.isEmpty()) {
            loadLocalFallback();
        }
        if (currentQuestions.isEmpty()) {
            List<String> fallbackOpts = new ArrayList<String>();
            fallbackOpts.add("100");
            fallbackOpts.add("10");
            fallbackOpts.add("1000");
            fallbackOpts.add("1");
            return new QuizItem(0, "数学", "四年级", "1 平方千米等于多少公顷？", fallbackOpts, 0, "1 平方千米 = 100 公顷。");
        }
        List<QuizItem> candidates = new ArrayList<QuizItem>();
        for (QuizItem q : currentQuestions) {
            if (!recentQuestionIds.contains(q.getId())) {
                candidates.add(q);
            }
        }
        if (candidates.isEmpty()) {
            recentQuestionIds.clear();
            candidates.addAll(currentQuestions);
        }
        QuizItem chosen = candidates.get(random.nextInt(candidates.size()));
        recentQuestionIds.addLast(chosen.getId());
        while (recentQuestionIds.size() > RECENT_HISTORY_LIMIT && recentQuestionIds.size() > currentQuestions.size() / 2) {
            recentQuestionIds.removeFirst();
        }
        return chosen;
    }

    public synchronized QuizItem.QuizConfig getConfig() {
        return currentConfig;
    }

    public synchronized int getQuestionCount() {
        return currentQuestions.size();
    }

    private synchronized boolean parseAndApplyJson(String jsonText) {
        try {
            JSONObject root = new JSONObject(jsonText);
            QuizItem.QuizConfig parsedConfig = new QuizItem.QuizConfig();
            JSONObject cfgObj = root.optJSONObject("config");
            if (cfgObj != null) {
                parsedConfig.setWatchLimitSeconds(cfgObj.optInt("watch_limit_seconds", 600));
                parsedConfig.setWrongPenalty(cfgObj.optInt("wrong_penalty", 1));
                parsedConfig.setMaxPendingQuestions(cfgObj.optInt("max_pending_questions", 5));
                parsedConfig.setParentPin(cfgObj.optString("parent_pin", "1234"));
                parsedConfig.setBypassSeconds(cfgObj.optInt("bypass_seconds", 3600));
            }

            JSONArray arr = root.optJSONArray("questions");
            if (arr == null || arr.length() == 0) {
                return false;
            }
            List<QuizItem> parsedList = new ArrayList<QuizItem>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                JSONArray optsArr = item.optJSONArray("options");
                if (optsArr == null || optsArr.length() != 4) {
                    continue;
                }
                List<String> opts = new ArrayList<String>(4);
                for (int j = 0; j < 4; j++) {
                    opts.add(optsArr.optString(j, ""));
                }
                int correctIndex = item.optInt("correct_index", 0);
                if (correctIndex < 0 || correctIndex > 3) {
                    continue;
                }
                parsedList.add(new QuizItem(
                        item.optInt("id", i + 1),
                        item.optString("subject", "综合"),
                        item.optString("grade", "四年级上"),
                        item.optString("question", ""),
                        opts,
                        correctIndex,
                        item.optString("explanation", "")
                ));
                parsedList.get(parsedList.size() - 1).setUnit(item.optString("unit", ""));
            }
            if (parsedList.isEmpty()) {
                return false;
            }
            this.currentConfig = parsedConfig;
            this.currentQuestions = parsedList;
            return true;
        } catch (Exception e) {
            Log.e(TAG, "解析题库 JSON 失败: " + e.getMessage());
            return false;
        }
    }

    private String readAssetFile(String fileName) {
        try {
            InputStream is = appContext.getAssets().open(fileName);
            return readStream(is);
        } catch (Exception e) {
            Log.e(TAG, "读取内置题库失败: " + e.getMessage());
            return "";
        }
    }

    private String readStream(InputStream is) throws Exception {
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append('\n');
        }
        reader.close();
        return sb.toString();
    }
}
