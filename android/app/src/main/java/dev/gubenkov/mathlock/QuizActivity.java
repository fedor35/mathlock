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

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        KeyguardManager km = getSystemService(KeyguardManager.class);
        if (km != null) km.requestDismissKeyguard(this, null);
        buildUi();
        next(null);
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
        if (!done && prefs.enabled() && prefs.locked()) h.postDelayed(() -> { if (!done) LockService.showQuiz(this); }, 400);
    }

    @Override public void onBackPressed() { /* назад нельзя */ }
    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) hideSystemBars(); }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(24);
        root.setPadding(pad, dp(48), pad, dp(24));

        tvProgress = text(18, "#9FB3C8");
        root.addView(tvProgress);

        tvProblem = text(64, "#FFFFFF");
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

        View spacer = new View(this);
        root.addView(spacer, new LinearLayout.LayoutParams(-1, 0, 1f));

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
            g.width = dp(84); g.height = dp(84); g.setMargins(dp(8), dp(8), dp(8), dp(8));
            bt.setLayoutParams(g);
            bt.setOnClickListener(v -> key(k));
            grid.addView(bt);
        }
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-2, -2);
        glp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(grid, glp);
        setContentView(root);
    }

    private TextView text(int sp, String color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(Color.parseColor(color));
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void next(String hint) {
        cur = Problem.random(rnd, prefs.minFactor(), prefs.maxFactor(), cur);
        input.setLength(0);
        tvProblem.setText(cur.toString());
        tvInput.setText("");
        tvHint.setText(hint == null ? "" : hint);
        tvProgress.setText("Решено " + correct + " из " + prefs.needCorrect());
    }

    private void key(String k) {
        if (done) return;
        if ("⌫".equals(k)) {
            if (input.length() > 0) input.setLength(input.length() - 1);
        } else if ("✓".equals(k)) {
            check();
            return;
        } else if (input.length() < 4) {
            input.append(k);
        }
        tvInput.setText(input);
        // Родительский код вводится как «ответ», не раскрывая себя: отдельной кнопки нет.
        String pc = prefs.parentCode();
        if (!pc.isEmpty() && input.toString().equals(pc)) unlock();
    }

    private void check() {
        if (input.length() == 0) return;
        int v = Integer.parseInt(input.toString());
        boolean ok = v == cur.answer();
        prefs.bumpStat(!ok);
        if (ok) {
            correct++;
            if (correct >= prefs.needCorrect()) { unlock(); return; }
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
        prefs.setLocked(false);
        try { stopLockTask(); } catch (Exception ignored) { }
        finishAndRemoveTask();
    }
}
