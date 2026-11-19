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

/**
 * Экран блокировки: случайные примеры из таблицы умножения, цифровая клавиатура — или (настройка родителя)
 * чтение текста вслух ({@link ReadingScreen}). Чтение — только если для него всё есть; при любой поломке — примеры.
 */
public class QuizActivity extends Activity implements ReadingScreen.Host {
    private Prefs prefs;
    private final Random rnd = new Random();
    private Problem cur;
    private int correct;
    private final StringBuilder input = new StringBuilder();
    private TextView tvProblem, tvInput, tvProgress, tvHint, tvOpen;
    private FactStats facts;
    private TextView tvTable;
    private final java.util.ArrayList<String> recent = new java.util.ArrayList<>();
    private long shownAt;
    /** Разблокировка уже заработана, ребёнок решает ещё — копит минуты до контрольной проверки (идея ребёнка 07.11). */
    private boolean bonusMode;
    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean done;
    private boolean guard;
    public static final String EXTRA_GUARD = "guard";
    /** Проверка родителем из настроек: "reading" — показать чтение (если оно доступно), "math" — примеры. */
    public static final String EXTRA_TASK = "task";
    /** Задание чтения на экране (null — примеры). */
    private ReadingScreen reading;
    public static final long GUARD_PASS_MS = 10 * 60_000L;
    /** Дневное время вышло (ScreenTime): примеры и чтение зарабатывают минуты, телефон открывается, когда они есть. */
    private boolean timeUp;
    /** Ребёнок ушёл звонить с экрана «время вышло» — не возвращать экран сразу. */
    private boolean calling;
    private TextView tvRead, tvCall;
    /** Экран только для родительского кода (долгое нажатие на экране чтения): примеры тут телефон не открывают. */
    private boolean codeOnly;
    /** Экран заданий на виду — время телефона не идёт (в т.ч. «реши ещё» после разблокировки). */
    static volatile boolean visible;
    private final Runnable timeUpWatch = new Runnable() {
        @Override public void run() {
            if (!timeUp || done) return;
            if (prefs.locked() && !prefs.timeUp()) { leaveTimeUp(); return; }   // новые сутки или родитель выключил лимит
            if (tvProgress != null) { tvProgress.setText(progressText()); refreshTimeUpButtons(); }
            h.postDelayed(this, 30_000L);   // наступили новые сутки — появится «Открыть телефон»
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        facts = new FactStats(new java.io.File(getFilesDir(), "facts.tsv"));
        java.io.File ext = getExternalFilesDir(null); if (ext != null) facts.mirror = new java.io.File(ext, "facts.tsv");
        setShowWhenLocked(true);
        // Экран примеры сами НЕ включают: их показывают, когда экран уже зажгли
        // кнопкой (SCREEN_ON). С turnScreenOn блокировка кнопкой тут же снова
        // зажигала экран — квиз запускался вдогонку гашению. FLAG_KEEP_SCREEN_ON здесь тоже нет: брошенный экран
        // блокировки не гас вовсе (11.11, на зарядке горело чтение); экран держит только запись чтения (ReadingScreen).
        getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        KeyguardManager km = getSystemService(KeyguardManager.class);
        if (km != null) km.requestDismissKeyguard(this, null);
        guard = getIntent().getBooleanExtra(EXTRA_GUARD, false);
        timeUp = !guard && getIntent().getStringExtra(EXTRA_TASK) == null && prefs.timeUp();
        if (timeUp) { buildUi(); next(null); h.post(timeUpWatch); return; }
        if (!startReadingIfChosen(null)) { buildUi(); next(readingHint); }
    }

    /** Для проверки родителем: почему вместо чтения показаны примеры. */
    private String readingHint;

    /** Чтение вместо примеров, если так решил родитель и всё для него есть (TaskPicker). */
    private boolean startReadingIfChosen(String force) {
        try {
            TaskPicker.Mode mode = TaskPicker.Mode.of(prefs.taskMode());
            String forced = force != null ? force : getIntent().getStringExtra(EXTRA_TASK);
            if ("reading".equals(forced)) mode = TaskPicker.Mode.READING;
            else if ("math".equals(forced)) mode = TaskPicker.Mode.MATH;
            if (guard || mode == TaskPicker.Mode.MATH) return false;
            ReadingPack pack = ReadingEngine.pack(this);
            boolean mic = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED;
            boolean ready = mic && pack.ready(prefs.htpBroken());
            int counter = prefs.mixedCounter();
            if (mode == TaskPicker.Mode.MIXED && forced == null) prefs.bumpMixedCounter();
            if (TaskPicker.pick(mode, guard, ready, counter) != TaskPicker.Kind.READING) {
                if (!ready) {
                    String why = !mic ? "нет разрешения на микрофон" : pack.missing(prefs.htpBroken());
                    android.util.Log.i(ReadingEngine.TAG, "чтение недоступно (" + why + ") — примеры");
                    if (forced != null) readingHint = "Чтение недоступно: " + why;
                }
                return false;
            }
            reading = new ReadingScreen(this, this, prefs, pack);
            if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                reading.debugWav = getIntent().getStringExtra("wav"); reading.debugTextId = getIntent().getStringExtra("text_id");
            }
            if (!reading.start()) { stopReading(); return false; }
            setContentView(reading.view());
            return true;
        } catch (Throwable e) {   // что бы ни случилось с чтением — ребёнок получает примеры
            android.util.Log.e(ReadingEngine.TAG, "экран чтения", e);
            stopReading();
            return false;
        }
    }

    private void stopReading() {
        if (reading != null) { reading.close(); reading = null; }
    }

    @Override public void onReadingPassed() {
        if (done) return;
        if (!guard && prefs.limitOn() && prefs.limitReadMin() > 0) {   // текст прочитан — минуты по скорости (показаны на экране чтения)
            float wcpm = reading != null ? reading.passedWcpm : -1;
            prefs.earnTime(ScreenTime.readReward(prefs.limitReadMin(), wcpm), String.format(java.util.Locale.ROOT, "read %.0f", wcpm));
            LockService.refresh(this);
        }
        if (prefs.limitOn() && !prefs.timeUp() && !guard && LockService.screenOn(this)) {   // прочитал — экран заработка: ещё текст или примеры, «Открыть телефон»
            stopReading(); unlock(true, "📖 Текст засчитан! Можно ещё"); return;
        }
        if (prefs.timeUp()) {   // заработок на сегодня исчерпан — назад на экран «время вышло»
            stopReading(); timeUp = true; buildUi(); next(null); h.removeCallbacks(timeUpWatch); h.post(timeUpWatch); return;
        }
        if (!LockService.screenOn(this)) {
            // экран погас, пока шло «Молодец!»: это уже новая блокировка — закрываемся, не снимая её
            done = true; stopReading(); finishAndRemoveTask(); return;
        }
        unlock();
    }

    @Override public void onReadingFallback(String hint) {
        if (done) return;
        stopReading();
        buildUi();
        next(hint);
        if (timeUp) { h.removeCallbacks(timeUpWatch); h.post(timeUpWatch); }
    }

    @Override public void onReadingAnother() {
        if (done) return;
        stopReading();
        if (!startReadingIfChosen("reading")) { buildUi(); next(readingHint); }
    }

    @Override public void onParentCode() {
        if (done) return;
        stopReading();
        codeOnly = true; h.removeCallbacks(timeUpWatch);
        buildUi(); next(null);
    }

    /** С экрана родительского кода — назад к чтению. */
    private void backToReading() {
        codeOnly = false;
        if (!startReadingIfChosen("reading")) { buildUi(); next(readingHint); }
        if (timeUp && reading == null) { h.removeCallbacks(timeUpWatch); h.post(timeUpWatch); }
    }

    @Override protected void onNewIntent(android.content.Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        boolean g = i.getBooleanExtra(EXTRA_GUARD, false);
        boolean up = !g && prefs.locked() && prefs.timeUp();
        if (up && !timeUp && !done) {   // время вышло, пока был открыт этот экран (например, «ещё примеры»)
            guard = false; timeUp = true; bonusMode = false; correct = 0; stopReading(); buildUi(); next(null);
            h.removeCallbacks(timeUpWatch); h.post(timeUpWatch); return;
        }
        if (timeUp && !up && !g && !done && prefs.locked()) { leaveTimeUp(); return; }
        if (g != guard) { guard = g; correct = 0; stopReading(); buildUi(); next(null); }
        else if (bonusMode && prefs.locked()) { bonusMode = false; correct = 0; buildUi(); next(null); }   // экран гас — новая блокировка
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        if (reading != null) { reading.arrange(); return; }   // экран чтения: альбом — текст слева, кнопки справа
        buildUi();
        tvProblem.setText(guard || codeOnly ? "Родительский код" : cur.toString());
        tvInput.setText(input);
        tvProgress.setText(progressText());
        tvOpen.setVisibility(bonusMode ? View.VISIBLE : View.GONE);
        if (timeUp) refreshTimeUpButtons();
    }

    @Override protected void onStart() {
        super.onStart();
        visible = true;
        if (!LockService.inCall(this)) calling = false;
        if (AdminReceiver.isOwner(this)) {
            try { startLockTask(); } catch (Exception ignored) { }
        }
        hideSystemBars();
    }

    @Override protected void onStop() {
        super.onStop();
        visible = false;
        if (reading != null) reading.onStop();
        // Если нас свернули, а разблокировка не пройдена — вернёмся сами (страховка к WatchService).
        if (bonusMode && !done && !prefs.locked()) { done = true; finishAndRemoveTask(); return; }   // ушёл из «ещё примеров» — просто открыто
        if (calling || LockService.inCall(this)) return;   // звонок с экрана «время вышло»: вернёт WatchService, когда уйдёт со звонилки
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
        // экраны с лишними кнопками (время вышло, заработок, код родителя): иначе нижний ряд клавиатуры уезжал за край
        boolean compact = timeUp || codeOnly || (bonusMode && prefs.limitOn());
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

        // «Моя таблица» — ребёнок собирает выученные примеры (детям важны уровни и награды; сетка для родителя — в настройках)
        if (!guard && !codeOnly) {
            tvTable = text(16, "#F2D46B");
            tvTable.setText(tableText());
            tvTable.setPadding(dp(14), dp(6), dp(14), dp(6));
            android.graphics.drawable.GradientDrawable tb = new android.graphics.drawable.GradientDrawable();
            tb.setColor(Color.parseColor("#26323F")); tb.setCornerRadius(dp(18)); tvTable.setBackground(tb);
            tvTable.setOnClickListener(v -> showMyTable());
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2); tlp.topMargin = dp(10);
            root.addView(tvTable, tlp);
        }

        tvProblem = text(guard || codeOnly ? 34 : compact ? 52 : 64, "#FFFFFF");
        tvProblem.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(compact ? 12 : 32);
        root.addView(tvProblem, lp);

        tvInput = text(compact ? 44 : 56, "#7FD1AE");
        tvInput.setMinWidth(dp(120));
        lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(8);
        root.addView(tvInput, lp);

        tvHint = text(20, "#F2A65A");
        tvHint.setMinHeight(dp(32));
        lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(12);
        root.addView(tvHint, lp);

        tvOpen = text(22, "#FFFFFF");
        tvOpen.setText("Открыть телефон");
        tvOpen.setTypeface(Typeface.DEFAULT_BOLD);
        tvOpen.setPadding(dp(28), dp(12), dp(28), dp(12));
        android.graphics.drawable.GradientDrawable ob = new android.graphics.drawable.GradientDrawable();
        ob.setColor(Color.parseColor("#3CAA64")); ob.setCornerRadius(dp(28)); tvOpen.setBackground(ob);
        tvOpen.setOnClickListener(v -> openPhone());
        tvOpen.setVisibility(bonusMode ? View.VISIBLE : View.GONE);
        lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(8);
        root.addView(tvOpen, lp);
        tvRead = null; tvCall = null;
        if (codeOnly) {
            TextView back = pill("📖 Читать", "#5B8DEF");
            back.setOnClickListener(v -> backToReading());
            lp = new LinearLayout.LayoutParams(-2, -2); lp.topMargin = dp(10);
            root.addView(back, lp);
        } else if (bonusMode && prefs.limitOn()) {
            tvRead = pill("📖 Ещё текст: до +" + ScreenTime.fmtMin(ScreenTime.readRewardForTier(prefs.limitReadMin(), ScreenTime.READ_TIER_WPM.length - 1)), "#5B8DEF");
            tvRead.setOnClickListener(v -> readForTime());
            boolean can = ScreenTime.canEarnMin(prefs.timeState(), prefs.limitRules()) > 0;
            tvRead.setVisibility(can && prefs.limitReadMin() > 0 && readingAvailable() ? View.VISIBLE : View.GONE);
            lp = new LinearLayout.LayoutParams(-2, -2); lp.topMargin = dp(10);
            root.addView(tvRead, lp);
        } else if (timeUp) {
            LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER);
            tvRead = pill("📖 Почитать: до +" + ScreenTime.fmtMin(ScreenTime.readRewardForTier(prefs.limitReadMin(), ScreenTime.READ_TIER_WPM.length - 1)), "#5B8DEF");
            tvRead.setOnClickListener(v -> readForTime());
            tvCall = pill("📞 Позвонить", "#26323F");
            tvCall.setOnClickListener(v -> call());
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, -2); rp.setMargins(dp(6), 0, dp(6), 0);
            row.addView(tvRead, rp); row.addView(tvCall, new LinearLayout.LayoutParams(rp));
            lp = new LinearLayout.LayoutParams(-2, -2); lp.topMargin = dp(10);
            root.addView(row, lp);
            refreshTimeUpButtons();
        }

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
            int sz = land ? 64 : compact ? 74 : 84, m = land ? 5 : compact ? 6 : 8;
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

    /** Время снова есть без заработка (новые сутки, лимит выключен) — обычная разблокировка. */
    private void leaveTimeUp() {
        timeUp = false; h.removeCallbacks(timeUpWatch); correct = 0; stopReading();
        if (!startReadingIfChosen(null)) { buildUi(); next(null); }
    }

    private TextView pill(String s, String color) {
        TextView t = text(18, "#FFFFFF"); t.setText(s); t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(20), dp(10), dp(20), dp(10));
        android.graphics.drawable.GradientDrawable b = new android.graphics.drawable.GradientDrawable();
        b.setColor(Color.parseColor(color)); b.setCornerRadius(dp(24)); t.setBackground(b);
        return t;
    }

    /** Экран «время вышло»: «Открыть телефон», когда есть минуты; «Почитать», пока можно заработать и чтение доступно. */
    private void refreshTimeUpButtons() {
        if (!timeUp || tvOpen == null) return;
        long left = prefs.timeLeftSec();
        tvOpen.setText("Открыть телефон · " + ScreenTime.fmt(left));
        tvOpen.setVisibility(left > 0 ? View.VISIBLE : View.GONE);
        boolean canEarn = ScreenTime.canEarnMin(prefs.timeState(), prefs.limitRules()) > 0;
        if (tvRead != null) tvRead.setVisibility(canEarn && prefs.limitReadMin() > 0 && readingAvailable() ? View.VISIBLE : View.GONE);
    }

    private boolean readingAvailable() {
        try {
            boolean mic = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED;
            return mic && ReadingEngine.pack(this).ready(prefs.htpBroken());
        } catch (Throwable e) { return false; }
    }

    /** Чтение ради минут с экрана «время вышло». Не вышло — остаёмся на примерах. */
    private void readForTime() {
        if (done) return;
        h.removeCallbacks(timeUpWatch);
        if (!startReadingIfChosen("reading")) { tvHint.setText(readingHint != null ? readingHint : "Чтение сейчас недоступно"); h.post(timeUpWatch); }
    }

    private void call() {
        calling = true;
        try { stopLockTask(); } catch (Exception ignored) { }
        try {
            startActivity(new android.content.Intent(android.content.Intent.ACTION_DIAL).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) { calling = false; tvHint.setText("Не открылась звонилка"); }
    }

    /** «Открыть телефон»: из «ещё примеров» (уже открыт) или с экрана «время вышло», когда минуты заработаны. */
    private void openPhone() {
        if (timeUp) {
            if (prefs.timeLeftSec() <= 0) return;
            prefs.setLocked(false); prefs.setNeedNow(prefs.needCorrect());
            prefs.logTime("open", 0);
            LockService.refresh(this);
        }
        finishUnlock();
    }

    private String tableText() { return "⭐ Моя таблица: " + facts.learnedCount() + " из " + FactStats.TOTAL; }

    /** Поздравление: пример выучен, а если с ним выучен весь столбик — кубок. */
    private String starNews(int a, int b) {
        for (int k : new int[]{a, b}) if (k >= 2 && k <= 9 && facts.rowLearned(k)) return "🏆 Все примеры на " + k + " выучены!";
        return "⭐ Выучен пример " + Math.min(a, b) + "×" + Math.max(a, b) + "! Теперь " + facts.learnedCount() + " из " + FactStats.TOTAL;
    }

    /** Сетка 2…9 для ребёнка: выученные — ответ на зелёном, остальные — «?». Подсказкой не служит: ответов там, где не выучено, нет. */
    private void showMyTable() {
        if (done) return;
        android.widget.FrameLayout over = new android.widget.FrameLayout(this);
        over.setBackgroundColor(Color.parseColor("#F01B2430")); over.setClickable(true);
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView title = text(24, "#FFFFFF"); title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setText("Моя таблица: " + facts.learnedCount() + " из " + FactStats.TOTAL);
        box.addView(title);
        TextView sub = text(15, "#9FB3C8"); sub.setText("Пример выучен, когда трижды подряд решён быстро"); sub.setPadding(0, dp(6), 0, dp(14));
        box.addView(sub);
        GridLayout g = new GridLayout(this); g.setColumnCount(9);
        int cell = Math.min(dp(38), (getResources().getDisplayMetrics().widthPixels - dp(40)) / 9);
        for (int r = 1; r <= 9; r++) for (int c = 1; c <= 9; c++) {
            TextView t = text(14, "#FFFFFF");
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(); lp.width = cell; lp.height = cell; lp.setMargins(2, 2, 2, 2);
            t.setLayoutParams(lp);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable(); bg.setCornerRadius(dp(6));
            if (r == 1 || c == 1) { t.setText(r == 1 && c == 1 ? "×" : String.valueOf(r == 1 ? c : r)); t.setTextColor(Color.parseColor("#9FB3C8")); }
            else if (facts.learned(r, c)) { t.setText(String.valueOf(r * c)); bg.setColor(Color.parseColor("#3CAA64")); t.setTypeface(Typeface.DEFAULT_BOLD); }
            else { t.setText("?"); t.setTextColor(Color.parseColor("#5E7189")); bg.setColor(Color.parseColor("#26323F")); }
            t.setBackground(bg);
            g.addView(t);
        }
        box.addView(g);
        StringBuilder rows = new StringBuilder();
        for (int k = 2; k <= 9; k++) if (facts.rowLearned(k)) rows.append(rows.length() == 0 ? "🏆 Выучены все на " : ", ").append(k);
        if (rows.length() > 0) { TextView rw = text(17, "#F2D46B"); rw.setText(rows); rw.setPadding(0, dp(14), 0, 0); box.addView(rw); }
        TextView close = text(20, "#FFFFFF"); close.setText("Решать дальше"); close.setTypeface(Typeface.DEFAULT_BOLD);
        close.setPadding(dp(28), dp(12), dp(28), dp(12));
        android.graphics.drawable.GradientDrawable cb = new android.graphics.drawable.GradientDrawable();
        cb.setColor(Color.parseColor("#5B8DEF")); cb.setCornerRadius(dp(28)); close.setBackground(cb);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2); clp.topMargin = dp(22);
        box.addView(close, clp);
        over.addView(box, new android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        android.view.ViewGroup content = findViewById(android.R.id.content);
        content.addView(over, new android.view.ViewGroup.LayoutParams(-1, -1));
        close.setOnClickListener(v -> content.removeView(over));
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
        if (codeOnly) return "Для родителей";
        if (timeUp) {
            ScreenTime.State st = prefs.timeState(); ScreenTime.Rules r = prefs.limitRules();
            long left = ScreenTime.leftSec(st, r);
            int can = ScreenTime.canEarnMin(st, r);
            String earned = "Заработано сегодня: " + ScreenTime.fmtMin(Math.min(st.earnedMin, r.earnMaxMin)) + " из " + ScreenTime.fmtMin(r.earnMaxMin);
            if (left > 0) return "Есть " + ScreenTime.fmt(left) + "! Реши ещё — +" + ScreenTime.fmtMin(prefs.bonusPerExampleMin()) + "\n" + earned;
            if (can <= 0) return "⏰ На сегодня всё!\nЗавтра с " + r.resetHour + ":00 снова " + ScreenTime.fmtMin(r.baseMin);
            return "⏰ Время на сегодня вышло\nЗаработай: текст +" + ScreenTime.fmtMin(prefs.limitReadMin()) + "…+"
                    + ScreenTime.fmtMin(ScreenTime.readRewardForTier(prefs.limitReadMin(), ScreenTime.READ_TIER_WPM.length - 1))
                    + " (быстрее — больше), пример +" + ScreenTime.fmtMin(prefs.bonusPerExampleMin()) + "\n" + earned;
        }
        if (bonusMode && prefs.limitOn()) {
            ScreenTime.State st = prefs.timeState(); ScreenTime.Rules r = prefs.limitRules();
            return "Открыто! Копи время: пример +" + ScreenTime.fmtMin(prefs.bonusPerExampleMin()) + ", текст — больше\nОсталось сегодня: "
                    + ScreenTime.fmt(ScreenTime.leftSec(st, r)) + " · можно заработать ещё " + ScreenTime.fmtMin(ScreenTime.canEarnMin(st, r));
        }
        if (bonusMode) {
            int bank = prefs.checkBankMin(), max = prefs.bankMaxMin();
            return bank >= max ? "Запас полный: " + bank + " мин" : "Открыто! Реши ещё — +" + prefs.bonusPerExampleMin() + " мин до проверки\nЗапас: " + bank + " из " + max + " мин";
        }
        int need = prefs.needNow();
        String t = need == 1 ? "Контрольный пример" : "Решено " + correct + " из " + need;
        if (prefs.limitOn()) t += "\n⏳ Осталось сегодня: " + ScreenTime.fmt(prefs.timeLeftSec());
        return t;
    }

    private void next(String hint) {
        cur = facts.pick(rnd, prefs.minFactor(), prefs.maxFactor(), recent);
        recent.add(FactStats.key(cur.a, cur.b)); while (recent.size() > 3) recent.remove(0);
        shownAt = android.os.SystemClock.elapsedRealtime();
        input.setLength(0);
        tvProblem.setText(guard || codeOnly ? "Родительский код" : cur.toString());
        if (timeUp && ScreenTime.canEarnMin(prefs.timeState(), prefs.limitRules()) <= 0) tvProblem.setText("🌙");   // заработок исчерпан: клавиатура — для кода родителя
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
        if (!pc.isEmpty() && input.toString().endsWith(pc)) {
            if (timeUp) {   // родитель добавляет время (вне потолка заработка)
                prefs.addExtraTime(Math.max(1, prefs.limitParentMin()), "parent_code");
            }
            unlock();
        }
    }

    private void check() {
        if (input.length() == 0 || codeOnly) { input.setLength(0); tvInput.setText(""); return; }
        int v;
        try { v = Integer.parseInt(input.toString()); } catch (NumberFormatException e) { v = -1; }
        boolean ok = v == cur.answer();
        prefs.bumpStat(!ok);
        long ms = android.os.SystemClock.elapsedRealtime() - shownAt, now = System.currentTimeMillis();
        boolean fresh = facts.record(cur.a, cur.b, ok, ms, now);
        facts.logAnswer(cur.a, cur.b, v, ok, ms, now);
        String star = fresh ? starNews(cur.a, cur.b) : null;
        if (tvTable != null) tvTable.setText(tableText());
        if (timeUp || (bonusMode && prefs.limitOn())) {   // зарабатываем минуты к дневному времени
            if (timeUp && ScreenTime.canEarnMin(prefs.timeState(), prefs.limitRules()) <= 0) { input.setLength(0); tvInput.setText(""); return; }
            if (ok) {
                int add = prefs.earnTime(prefs.bonusPerExampleMin(), timeUp ? "example_timeup" : "example");
                LockService.refresh(this);
                tvProgress.setText(progressText());
                if (timeUp) refreshTimeUpButtons();
                if (add <= 0 && bonusMode) { h.postDelayed(this::finishUnlock, 1200); done = true; return; }
                next(star != null ? star : add > 0 ? "Верно! +" + ScreenTime.fmtMin(add) : "Верно! На сегодня заработано всё");
            } else {
                tvHint.setText("Нет. " + cur.a + " × " + cur.b + " = " + cur.answer());
                h.postDelayed(() -> next(null), 2500);
                input.setLength(0); tvInput.setText("");
            }
            return;
        }
        if (bonusMode) {
            if (ok) {
                int bank = Math.min(prefs.bankMaxMin(), prefs.checkBankMin() + prefs.bonusPerExampleMin());
                prefs.setCheckBankMin(bank);
                if (bank >= prefs.bankMaxMin()) { tvProgress.setText(progressText()); h.postDelayed(this::finishUnlock, 1200); done = true; return; }
                next(star != null ? star : "Верно! +" + prefs.bonusPerExampleMin() + " мин");
            } else {
                tvHint.setText("Нет. " + cur.a + " × " + cur.b + " = " + cur.answer());
                h.postDelayed(() -> next(null), 2500);
                input.setLength(0); tvInput.setText("");
            }
            return;
        }
        if (ok) {
            correct++;
            if (correct >= prefs.needNow()) { unlock(true, star); return; }
            next(star != null ? star : "Верно!");
        } else {
            correct = 0;
            String was = cur.a + " × " + cur.b + " = " + cur.answer();
            tvHint.setText("Нет. " + was);
            h.postDelayed(() -> next(null), 2500);
            input.setLength(0);
            tvInput.setText("");
        }
    }

    @Override protected void onDestroy() { h.removeCallbacks(timeUpWatch); stopReading(); super.onDestroy(); }

    private void unlock() { unlock(false, null); }
    private void unlock(boolean offerBonus) { unlock(offerBonus, null); }

    /** @param offerBonus разблокировка заработана примерами — предложить «ещё пример +5 мин» (не для кода родителя и чтения). */
    private void unlock(boolean offerBonus, String star) {
        done = true;
        stopReading();
        if (guard) {
            // Родитель ввёл код: 10 минут Настройки открыты без вопросов.
            prefs.setGuardPassUntil(android.os.SystemClock.elapsedRealtime() + GUARD_PASS_MS);
            finishAndRemoveTask();
            return;
        }
        if (timeUp) h.removeCallbacks(timeUpWatch);
        prefs.setLocked(false);
        prefs.setNeedNow(prefs.needCorrect());
        LockService.refresh(this);
        // Заработал разблокировку примерами — можно решить ещё: с дневным лимитом — минуты к времени, без него — отодвинуть проверку
        boolean canBonus = prefs.limitOn() ? ScreenTime.canEarnMin(prefs.timeState(), prefs.limitRules()) > 0
                : prefs.checkIntervalMin() > 0 && prefs.checkBankMin() < prefs.bankMaxMin();
        if (offerBonus && (tvOpen != null || prefs.limitOn()) && prefs.bonusPerExampleMin() > 0 && canBonus) {
            done = false; bonusMode = true; correct = 0; timeUp = false;
            buildUi();   // после чтения примеров на экране ещё не было; с лимитом — кнопка «Ещё текст»
            tvOpen.setVisibility(View.VISIBLE);
            next(star != null ? star : "Верно!");
            return;
        }
        if (star != null && tvHint != null) {   // поздравление успеть увидеть
            tvHint.setText(star); if (tvTable != null) tvTable.setText(tableText());
            h.postDelayed(this::finishUnlock, 2200); return;
        }
        finishUnlock();
    }

    private void finishUnlock() {
        done = true;
        try { stopLockTask(); } catch (Exception ignored) { }
        finishAndRemoveTask();
    }
}
