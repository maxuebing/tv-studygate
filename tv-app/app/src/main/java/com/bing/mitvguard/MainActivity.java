package com.bing.mitvguard;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 家长配置与状态自检主界面。
 * 启动后自动拉起守护前台服务，提供权限检查、题库同步测试、网址配置及锁屏测试入口。
 */
public class MainActivity extends Activity {

    private GuardPrefs prefs;
    private QuizRepository repository;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView statusTextView;
    private TextView permTextView;
    private TextView urlTextView;

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshStatusViews();
            handler.postDelayed(this, 2000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = GuardPrefs.getInstance(this);
        repository = QuizRepository.getInstance(this);

        startGuardService(GuardService.ACTION_START);
        setContentView(buildContentView());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatusViews();
        handler.postDelayed(refreshRunnable, 1000L);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refreshRunnable);
    }

    private View buildContentView() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(Color.parseColor("#0F172A"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(48), dp(36), dp(48), dp(36));

        TextView title = new TextView(this);
        title.setText("小米电视学习守护锁 · 控制台");
        title.setTextColor(Color.WHITE);
        title.setTextSize(28);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("每累计观看 10 分钟触发四年级答题，答错加 1 题，断电重启不重置。\n应急解锁：锁屏页依次按遥控器【上 上 下 下 左 左 右 右】输入 4 位家长密码。");
        sub.setTextColor(Color.parseColor("#94A3B8"));
        sub.setTextSize(16);
        sub.setPadding(0, dp(8), 0, dp(20));
        root.addView(sub);

        statusTextView = createInfoCard();
        root.addView(statusTextView);

        permTextView = createInfoCard();
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(12);
        root.addView(permTextView, cardLp);

        urlTextView = createInfoCard();
        root.addView(urlTextView, cardLp);

        LinearLayout btnRow1 = new LinearLayout(this);
        btnRow1.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(20);

        Button syncBtn = createActionButton("立即同步网页题库");
        syncBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                syncQuizNow();
            }
        });

        Button testLockBtn = createActionButton("立即测试锁屏答题");
        testLockBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startGuardService(GuardService.ACTION_TRIGGER_LOCK_NOW);
            }
        });

        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(0, dp(64), 1f);
        bLp.rightMargin = dp(16);
        btnRow1.addView(syncBtn, bLp);
        btnRow1.addView(testLockBtn, new LinearLayout.LayoutParams(0, dp(64), 1f));
        root.addView(btnRow1, rowLp);

        LinearLayout btnRow2 = new LinearLayout(this);
        btnRow2.setOrientation(LinearLayout.HORIZONTAL);
        rowLp.topMargin = dp(14);

        Button editUrlBtn = createActionButton("修改题库网址（需密码）");
        editUrlBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                verifyPinThenEditUrl();
            }
        });

        Button overlayPermBtn = createActionButton("检查/打开悬浮窗权限");
        overlayPermBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openOverlayPermissionSettings();
            }
        });

        btnRow2.addView(editUrlBtn, bLp);
        btnRow2.addView(overlayPermBtn, new LinearLayout.LayoutParams(0, dp(64), 1f));
        root.addView(btnRow2, rowLp);

        LinearLayout btnRow3 = new LinearLayout(this);
        btnRow3.setOrientation(LinearLayout.HORIZONTAL);

        Button bypassBtn = createActionButton("家长临时放行 1 小时（需密码）");
        bypassBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                verifyPinThenBypass();
            }
        });

        Button endBypassBtn = createActionButton("结束放行并恢复守护");
        endBypassBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startGuardService(GuardService.ACTION_END_BYPASS);
                Toast.makeText(MainActivity.this, "已结束放行，恢复 10 分钟计时", Toast.LENGTH_SHORT).show();
                refreshStatusViews();
            }
        });

        btnRow3.addView(bypassBtn, bLp);
        btnRow3.addView(endBypassBtn, new LinearLayout.LayoutParams(0, dp(64), 1f));
        root.addView(btnRow3, rowLp);

        scrollView.addView(root);
        syncBtn.requestFocus();
        return scrollView;
    }

    private TextView createInfoCard() {
        TextView tv = new TextView(this);
        tv.setTextColor(Color.parseColor("#E2E8F0"));
        tv.setTextSize(17);
        tv.setLineSpacing(dp(4), 1.0f);
        tv.setPadding(dp(20), dp(16), dp(20), dp(16));
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor("#1E293B"));
        gd.setStroke(dp(2), Color.parseColor("#334155"));
        gd.setCornerRadius(dp(12));
        tv.setBackground(gd);
        return tv;
    }

    private Button createActionButton(String label) {
        Button btn = new Button(this);
        btn.setAllCaps(false);
        btn.setText(label);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(18);
        btn.setGravity(Gravity.CENTER);
        btn.setFocusable(true);
        btn.setFocusableInTouchMode(true);

        StateListDrawable sld = new StateListDrawable();
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(Color.parseColor("#2563EB"));
        focused.setStroke(dp(3), Color.parseColor("#FACC15"));
        focused.setCornerRadius(dp(10));

        GradientDrawable normal = new GradientDrawable();
        normal.setColor(Color.parseColor("#334155"));
        normal.setStroke(dp(2), Color.parseColor("#475569"));
        normal.setCornerRadius(dp(10));

        sld.addState(new int[]{android.R.attr.state_focused}, focused);
        sld.addState(new int[]{}, normal);
        btn.setBackground(sld);
        return btn;
    }

    private void refreshStatusViews() {
        int remaining = prefs.getRemainingSeconds();
        int bypass = prefs.getBypassRemainingSeconds();
        boolean locked = prefs.isLocked();
        String modeDesc;
        if (locked) {
            modeDesc = "锁定中（需答对 " + prefs.getPendingQuestions() + " 题）";
        } else if (bypass > 0) {
            modeDesc = "家长放行期（剩余 " + (bypass / 60) + " 分 " + (bypass % 60) + " 秒）";
        } else {
            modeDesc = "正常观看计时（距离下次答题还有 " + (remaining / 60) + " 分 " + (remaining % 60) + " 秒）";
        }
        statusTextView.setText("当前状态：" + modeDesc + "\n本地生效题库：" + repository.getQuestionCount() + " 道（四年级人教版）");

        boolean overlayOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
        boolean accessOk = isAccessibilityEnabled();
        permTextView.setText(
                "关键权限自检："
                        + "\n1. 顶层悬浮窗权限：" + (overlayOk ? "已开启" : "未开启（必须授权才能锁屏）")
                        + "\n2. 无障碍防退出权限：" + (accessOk ? "已开启" : "未开启（建议通过 ADB 开启以防按主页键退出）")
        );

        urlTextView.setText("远程题库 JSON 地址：\n" + prefs.getQuizUrl());
    }

    private boolean isAccessibilityEnabled() {
        try {
            int enabled = Settings.Secure.getInt(getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0);
            if (enabled != 1) {
                return false;
            }
            String services = Settings.Secure.getString(
                    getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return services != null && services.contains(getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    private void syncQuizNow() {
        Toast.makeText(this, "正在拉取最新题库...", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = repository.syncFromRemote();
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        refreshStatusViews();
                        String msg = ok
                                ? "同步成功！当前题库共 " + repository.getQuestionCount() + " 题"
                                : "连接服务器失败，继续使用本地缓存（共 " + repository.getQuestionCount() + " 题）";
                        Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    private void verifyPinThenBypass() {
        final EditText pinInput = new EditText(this);
        pinInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pinInput.setHint("输入 4 位家长密码（默认 1234）");
        new AlertDialog.Builder(this)
                .setTitle("家长临时放行验证")
                .setView(pinInput)
                .setPositiveButton("放行 1 小时", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String entered = pinInput.getText().toString().trim();
                        if (entered.equals(repository.getConfig().getParentPin())) {
                            startGuardService(GuardService.ACTION_PARENT_BYPASS);
                            Toast.makeText(MainActivity.this, "已开启家长放行 1 小时", Toast.LENGTH_LONG).show();
                            refreshStatusViews();
                        } else {
                            Toast.makeText(MainActivity.this, "密码错误", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void verifyPinThenEditUrl() {
        final EditText pinInput = new EditText(this);
        pinInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pinInput.setHint("输入 4 位家长密码（默认 1234）");
        new AlertDialog.Builder(this)
                .setTitle("家长身份验证")
                .setView(pinInput)
                .setPositiveButton("验证", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String entered = pinInput.getText().toString().trim();
                        if (entered.equals(repository.getConfig().getParentPin())) {
                            showEditUrlDialog();
                        } else {
                            Toast.makeText(MainActivity.this, "密码错误", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showEditUrlDialog() {
        final EditText urlInput = new EditText(this);
        urlInput.setText(prefs.getQuizUrl());
        new AlertDialog.Builder(this)
                .setTitle("设置题库 JSON 地址")
                .setView(urlInput)
                .setPositiveButton("保存并同步", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String newUrl = urlInput.getText().toString().trim();
                        if (!newUrl.startsWith("http://") && !newUrl.startsWith("https://")) {
                            Toast.makeText(MainActivity.this, "网址必须以 http:// 或 https:// 开头", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        prefs.setQuizUrl(newUrl);
                        syncQuizNow();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void openOverlayPermissionSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                Intent intent = new Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())
                );
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(this, "小米电视请直接用脚本 ADB 授权即可", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startGuardService(String action) {
        Intent intent = new Intent(this, GuardService.class);
        intent.setAction(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
