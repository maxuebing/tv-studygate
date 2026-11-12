package com.bing.mitvguard;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * 顶层全屏答题锁屏悬浮窗管理器。
 * 屏蔽返回与菜单键，支持遥控器方向键四选一答题，并支持“上上下下左左右右”调出家长 4 位密码放行面板。
 */
public class LockOverlayManager {

    private static final String TAG = "LockOverlayManager";
    private static final int[] SECRET_SEQUENCE = new int[]{
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_RIGHT
    };

    /**
     * 解锁事件回调接口。
     */
    public interface UnlockCallback {
        /** 全部题目答对解锁回调。 */
        void onQuizUnlocked();

        /** 家长应急密码校验通过回调。 */
        void onParentBypass();
    }

    private final Context appContext;
    private final WindowManager windowManager;
    private final AudioManager audioManager;
    private final GuardPrefs prefs;
    private final QuizRepository repository;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final UnlockCallback callback;

    private FrameLayout rootContainer;
    private ScrollView quizScrollView;
    private LinearLayout quizPanel;
    private LinearLayout pinPanel;

    private static final int WRONG_EXPLAIN_WAIT_SECONDS = 8;

    private TextView badgePendingView;
    private TextView subjectTagView;
    private TextView questionTextView;
    private TextView feedbackTextView;
    private Button nextQuestionButton;
    private final Button[] optionButtons = new Button[4];

    private TextView pinDisplayView;
    private TextView pinHintView;
    private Button firstPinDigitButton;
    private final StringBuilder pinBuffer = new StringBuilder();

    private final List<Integer> keyHistory = new ArrayList<Integer>();
    private QuizItem currentQuestion;
    private boolean isShowing = false;
    private boolean isAnsweringLocked = false;
    private int wrongCountdownSeconds = 0;
    private Runnable wrongCountdownRunnable;
    private AudioFocusRequest audioFocusRequest;
    private final AudioManager.OnAudioFocusChangeListener dummyAudioListener =
            new AudioManager.OnAudioFocusChangeListener() {
                @Override
                public void onAudioFocusChange(int focusChange) {
                }
            };

    /**
     * 构造方法。
     *
     * @param context  服务上下文
     * @param callback 解锁结果回调
     */
    public LockOverlayManager(Context context, UnlockCallback callback) {
        this.appContext = context.getApplicationContext();
        this.windowManager = (WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE);
        this.audioManager = (AudioManager) appContext.getSystemService(Context.AUDIO_SERVICE);
        this.prefs = GuardPrefs.getInstance(appContext);
        this.repository = QuizRepository.getInstance(appContext);
        this.callback = callback;
    }

    /**
     * 检查是否具备悬浮窗权限。
     *
     * @return 有权限返回 true
     */
    public boolean canDrawOverlays() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(appContext);
    }

    /**
     * 当前悬浮窗是否正在显示。
     *
     * @return 显示中返回 true
     */
    public boolean isShowing() {
        return isShowing;
    }

    /**
     * 展示全屏锁屏答题界面。
     */
    public void show() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (isShowing) {
                    ensureFocus();
                    return;
                }
                if (!canDrawOverlays()) {
                    Log.e(TAG, "缺少悬浮窗权限 SYSTEM_ALERT_WINDOW，无法弹出锁屏遮罩");
                    return;
                }
                try {
                    buildRootUi();
                    WindowManager.LayoutParams params = createLayoutParams();
                    windowManager.addView(rootContainer, params);
                    isShowing = true;
                    requestMediaPause();
                    loadNextQuestion();
                    Log.i(TAG, "已展示全屏答题锁屏悬浮窗");
                } catch (Exception e) {
                    Log.e(TAG, "添加悬浮窗失败: " + e.getMessage(), e);
                    isShowing = false;
                }
            }
        });
    }

    /**
     * 重新将悬浮窗拉到焦点（用于对抗用户按 Home 键切换桌面）。
     */
    public void ensureFocus() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!isShowing || rootContainer == null) {
                    return;
                }
                try {
                    if (rootContainer.hasWindowFocus()) {
                        return;
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        windowManager.removeViewImmediate(rootContainer);
                        windowManager.addView(rootContainer, createLayoutParams());
                    } else {
                        windowManager.updateViewLayout(rootContainer, createLayoutParams());
                    }
                    if (pinPanel != null && pinPanel.getVisibility() == View.VISIBLE && firstPinDigitButton != null) {
                        firstPinDigitButton.requestFocus();
                    } else if (nextQuestionButton != null && nextQuestionButton.getVisibility() == View.VISIBLE) {
                        nextQuestionButton.requestFocus();
                    } else if (optionButtons[0] != null && !rootContainer.hasFocus()) {
                        optionButtons[0].requestFocus();
                    }
                } catch (Exception e) {
                    Log.w(TAG, "刷新悬浮窗焦点失败，尝试重建: " + e.getMessage());
                    hideInternal();
                    show();
                }
            }
        });
    }

    /**
     * 隐藏并移除锁屏悬浮窗。
     */
    public void hide() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                hideInternal();
            }
        });
    }

    private void hideInternal() {
        if (!isShowing || rootContainer == null) {
            return;
        }
        try {
            windowManager.removeViewImmediate(rootContainer);
        } catch (Exception e) {
            Log.w(TAG, "移除悬浮窗异常: " + e.getMessage());
        } finally {
            cancelWrongCountdown();
            rootContainer = null;
            isShowing = false;
            isAnsweringLocked = false;
            keyHistory.clear();
            abandonMediaPause();
        }
    }

    private WindowManager.LayoutParams createLayoutParams() {
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        int flags = WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                flags,
                PixelFormat.OPAQUE
        );
        params.gravity = Gravity.CENTER;
        return params;
    }

    private void buildRootUi() {
        rootContainer = new FrameLayout(appContext) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                if (handleInterceptedKey(event)) {
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
        };
        rootContainer.setBackgroundColor(Color.parseColor("#0F172A"));
        rootContainer.setPadding(dp(40), dp(22), dp(40), dp(22));

        quizPanel = buildQuizPanel();
        quizScrollView = new ScrollView(appContext);
        quizScrollView.setFillViewport(true);
        quizScrollView.setVerticalScrollBarEnabled(false);
        quizScrollView.addView(quizPanel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        ));

        pinPanel = buildPinPanel();
        pinPanel.setVisibility(View.GONE);

        rootContainer.addView(quizScrollView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        rootContainer.addView(pinPanel, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
    }

    private LinearLayout buildQuizPanel() {
        LinearLayout container = new LinearLayout(appContext);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout topBar = new LinearLayout(appContext);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);

        TextView titleView = new TextView(appContext);
        titleView.setText("观看时间已到，请完成小测验继续看电视");
        titleView.setTextColor(Color.parseColor("#E2E8F0"));
        titleView.setTextSize(20);
        titleView.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        topBar.addView(titleView, titleLp);

        subjectTagView = new TextView(appContext);
        subjectTagView.setTextColor(Color.parseColor("#BFDBFE"));
        subjectTagView.setTextSize(16);
        subjectTagView.setPadding(dp(14), dp(5), dp(14), dp(5));
        subjectTagView.setBackground(createRoundedBg("#1E3A8A", "#3B82F6", 20));
        LinearLayout.LayoutParams tagLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tagLp.rightMargin = dp(10);
        topBar.addView(subjectTagView, tagLp);

        badgePendingView = new TextView(appContext);
        badgePendingView.setTextColor(Color.WHITE);
        badgePendingView.setTextSize(16);
        badgePendingView.setTypeface(null, Typeface.BOLD);
        badgePendingView.setPadding(dp(16), dp(5), dp(16), dp(5));
        badgePendingView.setBackground(createRoundedBg("#B91C1C", "#F87171", 20));
        topBar.addView(badgePendingView);

        container.addView(topBar);

        LinearLayout card = new LinearLayout(appContext);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(createRoundedBg("#1E293B", "#334155", 16));
        card.setPadding(dp(24), dp(18), dp(24), dp(18));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(12);
        cardLp.bottomMargin = dp(14);

        questionTextView = new TextView(appContext);
        questionTextView.setTextColor(Color.WHITE);
        questionTextView.setTextSize(24);
        questionTextView.setTypeface(null, Typeface.BOLD);
        questionTextView.setLineSpacing(dp(4), 1.0f);
        card.addView(questionTextView);
        container.addView(card, cardLp);

        LinearLayout gridRow1 = new LinearLayout(appContext);
        gridRow1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout gridRow2 = new LinearLayout(appContext);
        gridRow2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams row2Lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        row2Lp.topMargin = dp(12);

        for (int i = 0; i < 4; i++) {
            final int optionIndex = i;
            Button btn = createTvOptionButton();
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onOptionSelected(optionIndex);
                }
            });
            optionButtons[i] = btn;
            LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(0, dp(72), 1f);
            if (i % 2 == 0) {
                btnLp.rightMargin = dp(14);
            }
            if (i < 2) {
                gridRow1.addView(btn, btnLp);
            } else {
                gridRow2.addView(btn, btnLp);
            }
        }
        container.addView(gridRow1);
        container.addView(gridRow2, row2Lp);

        feedbackTextView = new TextView(appContext);
        feedbackTextView.setTextSize(18);
        feedbackTextView.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        feedbackTextView.setLineSpacing(dp(4), 1.0f);
        feedbackTextView.setPadding(dp(18), dp(12), dp(18), dp(12));
        feedbackTextView.setBackground(createRoundedBg("#1E293B", "#334155", 12));
        LinearLayout.LayoutParams fbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        fbLp.topMargin = dp(14);
        container.addView(feedbackTextView, fbLp);

        nextQuestionButton = createPinKeyButton("请先阅读解析（8 秒）");
        nextQuestionButton.setVisibility(View.GONE);
        nextQuestionButton.setEnabled(false);
        nextQuestionButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (wrongCountdownSeconds <= 0 && isShowing) {
                    loadNextQuestion();
                }
            }
        });
        LinearLayout.LayoutParams nextBtnLp = new LinearLayout.LayoutParams(dp(380), dp(60));
        nextBtnLp.gravity = Gravity.CENTER_HORIZONTAL;
        nextBtnLp.topMargin = dp(12);
        container.addView(nextQuestionButton, nextBtnLp);

        return container;
    }

    private LinearLayout buildPinPanel() {
        LinearLayout container = new LinearLayout(appContext);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER);

        TextView title = new TextView(appContext);
        title.setText("家长应急密码验证（验证通过将放行 1 小时）");
        title.setTextColor(Color.WHITE);
        title.setTextSize(26);
        title.setTypeface(null, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        container.addView(title);

        pinDisplayView = new TextView(appContext);
        pinDisplayView.setText("_  _  _  _");
        pinDisplayView.setTextColor(Color.parseColor("#38BDF8"));
        pinDisplayView.setTextSize(38);
        pinDisplayView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        pinDisplayView.setGravity(Gravity.CENTER);
        pinDisplayView.setPadding(dp(32), dp(16), dp(32), dp(16));
        pinDisplayView.setBackground(createRoundedBg("#1E293B", "#475569", 14));
        LinearLayout.LayoutParams dispLp = new LinearLayout.LayoutParams(dp(360), LinearLayout.LayoutParams.WRAP_CONTENT);
        dispLp.topMargin = dp(20);
        dispLp.bottomMargin = dp(12);
        container.addView(pinDisplayView, dispLp);

        pinHintView = new TextView(appContext);
        pinHintView.setText("请使用遥控器选择 4 位数字密码");
        pinHintView.setTextColor(Color.parseColor("#94A3B8"));
        pinHintView.setTextSize(18);
        pinHintView.setGravity(Gravity.CENTER);
        container.addView(pinHintView);

        LinearLayout row1 = new LinearLayout(appContext);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setGravity(Gravity.CENTER);
        LinearLayout row2 = new LinearLayout(appContext);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setGravity(Gravity.CENTER);

        for (int digit = 0; digit <= 9; digit++) {
            final String digitStr = String.valueOf(digit);
            Button numBtn = createPinKeyButton(digitStr);
            if (digit == 0) {
                firstPinDigitButton = numBtn;
            }
            numBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onPinDigitPressed(digitStr);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(92), dp(68));
            lp.setMargins(dp(8), dp(8), dp(8), dp(8));
            if (digit < 5) {
                row1.addView(numBtn, lp);
            } else {
                row2.addView(numBtn, lp);
            }
        }

        LinearLayout.LayoutParams r1Lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        r1Lp.topMargin = dp(18);
        container.addView(row1, r1Lp);
        container.addView(row2);

        LinearLayout actionRow = new LinearLayout(appContext);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        actLp.topMargin = dp(14);

        Button backBtn = createPinKeyButton("退格");
        backBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (pinBuffer.length() > 0) {
                    pinBuffer.deleteCharAt(pinBuffer.length() - 1);
                    refreshPinDisplay();
                }
            }
        });
        Button clearBtn = createPinKeyButton("清空");
        clearBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pinBuffer.setLength(0);
                refreshPinDisplay();
            }
        });
        Button cancelBtn = createPinKeyButton("返回答题");
        cancelBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchToQuizMode();
            }
        });

        LinearLayout.LayoutParams actBtnLp = new LinearLayout.LayoutParams(dp(150), dp(64));
        actBtnLp.setMargins(dp(10), 0, dp(10), 0);
        actionRow.addView(backBtn, actBtnLp);
        actionRow.addView(clearBtn, actBtnLp);
        actionRow.addView(cancelBtn, actBtnLp);
        container.addView(actionRow, actLp);

        return container;
    }

    private Button createTvOptionButton() {
        Button btn = new Button(appContext);
        btn.setAllCaps(false);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(20);
        btn.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        btn.setPadding(dp(20), dp(10), dp(20), dp(10));
        btn.setFocusable(true);
        btn.setFocusableInTouchMode(true);
        btn.setBackground(createOptionSelector("#1E293B", "#2563EB", "#475569", "#FACC15"));
        return btn;
    }

    private Button createPinKeyButton(String label) {
        Button btn = new Button(appContext);
        btn.setAllCaps(false);
        btn.setText(label);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(22);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setFocusable(true);
        btn.setFocusableInTouchMode(true);
        btn.setBackground(createOptionSelector("#334155", "#2563EB", "#475569", "#FACC15"));
        return btn;
    }

    private StateListDrawable createOptionSelector(
            String normalBg,
            String focusedBg,
            String normalBorder,
            String focusedBorder) {
        StateListDrawable states = new StateListDrawable();
        states.addState(
                new int[]{android.R.attr.state_focused},
                createRoundedBg(focusedBg, focusedBorder, 12)
        );
        states.addState(
                new int[]{android.R.attr.state_pressed},
                createRoundedBg(focusedBg, focusedBorder, 12)
        );
        states.addState(
                new int[]{},
                createRoundedBg(normalBg, normalBorder, 12)
        );
        return states;
    }

    private GradientDrawable createRoundedBg(String fillHex, String strokeHex, int radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor(fillHex));
        gd.setStroke(dp(3), Color.parseColor(strokeHex));
        gd.setCornerRadius(dp(radiusDp));
        return gd;
    }

    private void loadNextQuestion() {
        cancelWrongCountdown();
        isAnsweringLocked = false;
        currentQuestion = repository.pickRandomQuestion();
        int pending = Math.max(1, prefs.getPendingQuestions());

        badgePendingView.setText("还需答对 " + pending + " 题");
        String unitLabel = currentQuestion.getUnit();
        subjectTagView.setText(currentQuestion.getSubject() + " · "
                + (unitLabel.isEmpty() ? currentQuestion.getGrade() : unitLabel));
        questionTextView.setText(currentQuestion.getQuestion());
        feedbackTextView.setText("使用遥控器方向键选择，按确定键提交（答错将追加 1 题）");
        feedbackTextView.setTextColor(Color.parseColor("#94A3B8"));

        if (nextQuestionButton != null) {
            nextQuestionButton.setVisibility(View.GONE);
            nextQuestionButton.setEnabled(false);
        }
        if (quizScrollView != null) {
            quizScrollView.post(new Runnable() {
                @Override
                public void run() {
                    quizScrollView.scrollTo(0, 0);
                }
            });
        }

        String[] prefixes = new String[]{"A. ", "B. ", "C. ", "D. "};
        List<String> opts = currentQuestion.getOptions();
        for (int i = 0; i < 4; i++) {
            String text = i < opts.size() ? opts.get(i) : "";
            optionButtons[i].setText(prefixes[i] + text);
            optionButtons[i].setFocusable(true);
            optionButtons[i].setFocusableInTouchMode(true);
            optionButtons[i].setBackground(createOptionSelector("#1E293B", "#2563EB", "#475569", "#FACC15"));
        }
        optionButtons[0].post(new Runnable() {
            @Override
            public void run() {
                if (optionButtons[0] != null) {
                    optionButtons[0].requestFocus();
                }
            }
        });
    }

    private void onOptionSelected(int selectedIndex) {
        if (isAnsweringLocked || currentQuestion == null) {
            return;
        }
        isAnsweringLocked = true;

        QuizItem.QuizConfig cfg = repository.getConfig();
        boolean isCorrect = selectedIndex == currentQuestion.getCorrectIndex();
        int currentPending = Math.max(1, prefs.getPendingQuestions());

        if (isCorrect) {
            int nextPending = currentPending - 1;
            prefs.setPendingQuestions(nextPending);
            repository.reportAnswerAsync(currentQuestion, true, nextPending);
            optionButtons[selectedIndex].setBackground(createRoundedBg("#15803D", "#86EFAC", 12));
            badgePendingView.setText("还需答对 " + nextPending + " 题");

            if (nextPending <= 0) {
                feedbackTextView.setTextColor(Color.parseColor("#4ADE80"));
                feedbackTextView.setText("全部答对！正在解锁电视...");
                mainHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        hideInternal();
                        if (callback != null) {
                            callback.onQuizUnlocked();
                        }
                    }
                }, 800);
            } else {
                feedbackTextView.setTextColor(Color.parseColor("#4ADE80"));
                feedbackTextView.setText("回答正确！还需再答对 " + nextPending + " 题，即将切换下一题...");
                mainHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (isShowing) {
                            loadNextQuestion();
                        }
                    }
                }, 1000);
            }
        } else {
            int penalty = cfg.getWrongPenalty();
            int nextPending = Math.min(cfg.getMaxPendingQuestions(), currentPending + penalty);
            prefs.setPendingQuestions(nextPending);
            repository.reportAnswerAsync(currentQuestion, false, nextPending);

            optionButtons[selectedIndex].setBackground(createRoundedBg("#B91C1C", "#FCA5A5", 12));
            int rightIdx = currentQuestion.getCorrectIndex();
            if (rightIdx >= 0 && rightIdx < 4) {
                optionButtons[rightIdx].setBackground(createRoundedBg("#166534", "#86EFAC", 12));
            }
            badgePendingView.setText("还需答对 " + nextPending + " 题");
            String explain = currentQuestion.getExplanation();
            String msg = "回答错误！惩罚 +" + penalty + " 题（还需答对 " + nextPending + " 题）";
            if (!explain.isEmpty()) {
                msg += "\n解析：" + explain;
            } else {
                msg += "\n正确答案是：" + new String[]{"A", "B", "C", "D"}[Math.max(0, Math.min(3, rightIdx))];
            }
            feedbackTextView.setTextColor(Color.parseColor("#FCA5A5"));
            feedbackTextView.setText(msg);

            startWrongExplainGate();
        }
    }

    private void startWrongExplainGate() {
        cancelWrongCountdown();
        for (int i = 0; i < 4; i++) {
            optionButtons[i].setFocusable(false);
            optionButtons[i].setFocusableInTouchMode(false);
        }
        wrongCountdownSeconds = WRONG_EXPLAIN_WAIT_SECONDS;
        nextQuestionButton.setVisibility(View.VISIBLE);
        nextQuestionButton.setEnabled(false);
        nextQuestionButton.setFocusable(true);
        nextQuestionButton.setFocusableInTouchMode(true);
        nextQuestionButton.setBackground(createRoundedBg("#475569", "#64748B", 12));
        nextQuestionButton.setText("请先看错题解析（" + wrongCountdownSeconds + " 秒）");
        nextQuestionButton.post(new Runnable() {
            @Override
            public void run() {
                if (nextQuestionButton != null) {
                    nextQuestionButton.requestFocus();
                }
                if (quizScrollView != null) {
                    quizScrollView.fullScroll(View.FOCUS_DOWN);
                }
            }
        });

        wrongCountdownRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isShowing) {
                    return;
                }
                wrongCountdownSeconds--;
                if (wrongCountdownSeconds > 0) {
                    nextQuestionButton.setText("请先看错题解析（" + wrongCountdownSeconds + " 秒）");
                    mainHandler.postDelayed(this, 1000L);
                } else {
                    nextQuestionButton.setEnabled(true);
                    nextQuestionButton.setText("我已看懂，下一道");
                    nextQuestionButton.setBackground(createOptionSelector("#2563EB", "#1D4ED8", "#93C5FD", "#FACC15"));
                    nextQuestionButton.requestFocus();
                }
            }
        };
        mainHandler.postDelayed(wrongCountdownRunnable, 1000L);
    }

    private void cancelWrongCountdown() {
        if (wrongCountdownRunnable != null) {
            mainHandler.removeCallbacks(wrongCountdownRunnable);
            wrongCountdownRunnable = null;
        }
        wrongCountdownSeconds = 0;
    }

    private boolean handleInterceptedKey(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK
                || code == KeyEvent.KEYCODE_MENU
                || code == KeyEvent.KEYCODE_APP_SWITCH
                || code == KeyEvent.KEYCODE_SEARCH
                || code == KeyEvent.KEYCODE_VOICE_ASSIST
                || code == KeyEvent.KEYCODE_ASSIST
                || code == KeyEvent.KEYCODE_HOME) {
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            if (quizScrollView != null && quizScrollView.getVisibility() == View.VISIBLE && isDirectionKey(code)) {
                recordSecretKey(code);
            }
        }
        return false;
    }

    private boolean isDirectionKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    private void recordSecretKey(int keyCode) {
        keyHistory.add(keyCode);
        if (keyHistory.size() > SECRET_SEQUENCE.length) {
            keyHistory.remove(0);
        }
        if (keyHistory.size() == SECRET_SEQUENCE.length) {
            boolean matched = true;
            for (int i = 0; i < SECRET_SEQUENCE.length; i++) {
                if (keyHistory.get(i) != SECRET_SEQUENCE[i]) {
                    matched = false;
                    break;
                }
            }
            if (matched) {
                keyHistory.clear();
                switchToPinMode();
            }
        }
    }

    private void switchToPinMode() {
        pinBuffer.setLength(0);
        refreshPinDisplay();
        pinHintView.setText("请使用遥控器选择 4 位数字密码");
        pinHintView.setTextColor(Color.parseColor("#94A3B8"));
        quizScrollView.setVisibility(View.GONE);
        pinPanel.setVisibility(View.VISIBLE);
        if (firstPinDigitButton != null) {
            firstPinDigitButton.post(new Runnable() {
                @Override
                public void run() {
                    firstPinDigitButton.requestFocus();
                }
            });
        }
    }

    private void switchToQuizMode() {
        pinPanel.setVisibility(View.GONE);
        quizScrollView.setVisibility(View.VISIBLE);
        if (nextQuestionButton != null && nextQuestionButton.getVisibility() == View.VISIBLE) {
            nextQuestionButton.post(new Runnable() {
                @Override
                public void run() {
                    nextQuestionButton.requestFocus();
                }
            });
        } else if (optionButtons[0] != null) {
            optionButtons[0].post(new Runnable() {
                @Override
                public void run() {
                    optionButtons[0].requestFocus();
                }
            });
        }
    }

    private void onPinDigitPressed(String digit) {
        if (pinBuffer.length() >= 4) {
            return;
        }
        pinBuffer.append(digit);
        refreshPinDisplay();
        if (pinBuffer.length() == 4) {
            String expected = repository.getConfig().getParentPin();
            if (pinBuffer.toString().equals(expected)) {
                pinHintView.setTextColor(Color.parseColor("#4ADE80"));
                pinHintView.setText("验证成功！已放行 1 小时");
                mainHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        hideInternal();
                        if (callback != null) {
                            callback.onParentBypass();
                        }
                    }
                }, 400);
            } else {
                pinHintView.setTextColor(Color.parseColor("#FCA5A5"));
                pinHintView.setText("密码错误，请重新输入");
                pinBuffer.setLength(0);
                refreshPinDisplay();
            }
        }
    }

    private void refreshPinDisplay() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if (i > 0) {
                sb.append("  ");
            }
            if (i < pinBuffer.length()) {
                sb.append("●");
            } else {
                sb.append("_");
            }
        }
        pinDisplayView.setText(sb.toString());
    }

    @SuppressWarnings("deprecation")
    private void requestMediaPause() {
        if (audioManager == null) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .build();
                audioManager.requestAudioFocus(audioFocusRequest);
            } else {
                audioManager.requestAudioFocus(dummyAudioListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            }
        } catch (Exception e) {
            Log.w(TAG, "请求音频焦点异常: " + e.getMessage());
        }
    }

    @SuppressWarnings("deprecation")
    private void abandonMediaPause() {
        if (audioManager == null) {
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && audioFocusRequest != null) {
                audioManager.abandonAudioFocusRequest(audioFocusRequest);
            } else {
                audioManager.abandonAudioFocus(dummyAudioListener);
            }
        } catch (Exception e) {
            Log.w(TAG, "释放音频焦点异常: " + e.getMessage());
        }
    }

    private int dp(int value) {
        float density = appContext.getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
}
