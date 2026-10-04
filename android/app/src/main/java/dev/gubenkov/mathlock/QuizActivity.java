package dev.gubenkov.mathlock;

import android.app.Activity;
import android.app.KeyguardManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Random;

/** Экран блокировки: случайные примеры из таблицы умножения, цифровая клавиатура. */
public class QuizActivity extends Activity {
    private Prefs prefs;
    private final Random rnd = new Random();
    private Problem cur;
    private int correct;
    private final StringBuilder input = new StringBuilder();
    private TextView tvProblem, tvInput, tvProgress, tvHint;
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean done;
    private boolean guard;
    public static final String EXTRA_GUARD = "guard";
    public static final long GUARD_PASS_MS = 10 * 60_000L;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        KeyguardManager km = getSystemService(KeyguardManager.class);
        if (km != null) km.requestDismissKeyguard(this, null);
        guard = getIntent().getBooleanExtra(EXTRA_GUARD, false);
        buildUi();
        next(null);
    }

    @Override protected void onNewIntent(android.content.Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        boolean g = i.getBooleanExtra(EXTRA_GUARD, false);
        if (g != guard) { guard = g; correct = 0; buildUi(); next(null); }
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        buildUi();
        tvProblem.setText(guard ? "Родительский код" : cur.toString());
        tvInput.setText(input);
        tvProgress.setText(progressText());
    }

    @Override protected void onStart() {
        super.onStart();
        if (AdminReceiver.isOwner(this)) {
            try { startLockTask(); } catch (Exception ignored) { }
        }
        hideSystemBars();
    }

    @Override protected void onStop() {
        super.onStop();
        // Если нас свернули, а разблокировка не пройдена — вернёмся сами (страховка к WatchService).
        if (!done && !guard && prefs.enabled() && prefs.locked()) h.postDelayed(() -> { if (!done) LockService.showQuiz(this); }, 400);
    }

    @Override public void onBackPressed() {
        // На экране кода (защита Настроек) «назад» уводит на рабочий стол; при блокировке назад нельзя.
        if (guard) { done = true; startActivity(new android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_HOME).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); finishAndRemoveTask(); }
    }
    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) hideSystemBars(); }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    private void buildUi() {
        boolean land = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(land ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        outer.setGravity(Gravity.CENTER);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(24);
        root.setPadding(pad, dp(land ? 16 : 48), pad, dp(24));

        tvProgress = text(18, "#9FB3C8");
        root.addView(tvProgress);

        tvProblem = text(guard ? 34 : 64, "#FFFFFF");
        tvProblem.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(32);
        root.addView(tvProblem, lp);

        tvInput = text(56, "#7FD1AE");
        tvInput.setMinWidth(dp(120));
        lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(8);
        root.addView(tvInput, lp);

        tvHint = text(20, "#F2A65A");
        tvHint.setMinHeight(dp(32));
        lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(12);
        root.addView(tvHint, lp);

        if (!land) {
            View spacer = new View(this);
            root.addView(spacer, new LinearLayout.LayoutParams(-1, 0, 1f));
        }

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(3);
        String[] keys = {"1","2","3","4","5","6","7","8","9","⌫","0","✓"};
        for (String k : keys) {
            Button bt = new Button(this);
            bt.setText(k);
            bt.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
            bt.setTextColor(Color.WHITE);
            bt.setBackgroundResource(R.drawable.key_bg);
            bt.setStateListAnimator(null);
            GridLayout.LayoutParams g = new GridLayout.LayoutParams();
            int sz = land ? 64 : 84, m = land ? 5 : 8;
            g.width = dp(sz); g.height = dp(sz); g.setMargins(dp(m), dp(m), dp(m), dp(m));
            bt.setLayoutParams(g);
            bt.setOnClickListener(v -> key(k));
            grid.addView(bt);
        }
        if (land) {
            outer.addView(root, new LinearLayout.LayoutParams(0, -2, 1f));
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-2, -2);
            glp.gravity = Gravity.CENTER_VERTICAL;
            glp.rightMargin = dp(24);
            outer.addView(grid, glp);
        } else {
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-2, -2);
            glp.gravity = Gravity.CENTER_HORIZONTAL;
            root.addView(grid, glp);
            outer.addView(root, new LinearLayout.LayoutParams(-1, -1));
        }
        setContentView(outer);
    }

    private TextView text(int sp, String color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(Color.parseColor(color));
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private String progressText() {
        if (guard) return "Настройки защищены";
        int need = prefs.needNow();
        return need == 1 ? "Контрольный пример" : "Решено " + correct + " из " + need;
    }

    private void next(String hint) {
        cur = Problem.random(rnd, prefs.minFactor(), prefs.maxFactor(), cur);
        input.setLength(0);
        tvProblem.setText(guard ? "Родительский код" : cur.toString());
        tvInput.setText("");
        tvHint.setText(hint == null ? "" : hint);
        tvProgress.setText(progressText());
    }

    private void key(String k) {
        if (done) return;
        if ("⌫".equals(k)) {
            if (input.length() > 0) input.setLength(input.length() - 1);
        } else if ("✓".equals(k)) {
            if (!guard) check();
            return;
        } else {
            if (input.length() >= 8) input.deleteCharAt(0);
            input.append(k);
        }
        tvInput.setText(input);
        // Родительский код вводится как «ответ», не раскрывая себя: отдельной кнопки нет.
        String pc = prefs.parentCode();
        if (!pc.isEmpty() && input.toString().endsWith(pc)) unlock();
    }

    private void check() {
        if (input.length() == 0) return;
        int v;
        try { v = Integer.parseInt(input.toString()); } catch (NumberFormatException e) { v = -1; }
        boolean ok = v == cur.answer();
        prefs.bumpStat(!ok);
        if (ok) {
            correct++;
            if (correct >= prefs.needNow()) { unlock(); return; }
            next("Верно!");
        } else {
            correct = 0;
            String was = cur.a + " × " + cur.b + " = " + cur.answer();
            tvHint.setText("Нет. " + was);
            h.postDelayed(() -> next(null), 2500);
            input.setLength(0);
            tvInput.setText("");
        }
    }

    private void unlock() {
        done = true;
        if (guard) {
            // Родитель ввёл код: 10 минут Настройки открыты без вопросов.
            prefs.setGuardPassUntil(android.os.SystemClock.elapsedRealtime() + GUARD_PASS_MS);
            finishAndRemoveTask();
            return;
        }
        prefs.setLocked(false);
        prefs.setNeedNow(prefs.needCorrect());
        try { stopLockTask(); } catch (Exception ignored) { }
        finishAndRemoveTask();
    }
}
