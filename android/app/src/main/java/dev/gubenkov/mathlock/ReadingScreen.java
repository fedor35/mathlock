package dev.gubenkov.mathlock;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.UnderlineSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.TimeZone;

import dev.gubenkov.reading.Achievements;
import dev.gubenkov.reading.AdaptiveLevel;
import dev.gubenkov.reading.CtcModel;
import dev.gubenkov.reading.ReadingMatcher;
import dev.gubenkov.reading.ReadingStats;
import dev.gubenkov.reading.ReadingText;
import dev.gubenkov.reading.Sounds;
import dev.gubenkov.reading.SpeakerId;
import dev.gubenkov.reading.SpeakerModel;
import dev.gubenkov.reading.StreamingRecognizer;

/**
 * Задание «прочитай текст вслух» на экране блокировки (внутри QuizActivity, по образцу ReadActivity стенда readbench,
 * но проще): уровень и награды строкой сверху, текст в карточке с подсветкой и телесуфлёром, большой микрофон,
 * «послушать» (если есть озвучка), перечитывание красных слов, автостоп. Один текст на разблокировку.
 * <p>
 * Выход на примеры (Host.onReadingFallback): модель не загрузилась, микрофон недоступен, или — кнопкой —
 * после {@link TaskPicker#FALLBACK_FAILS} неудач / {@link TaskPicker#FALLBACK_MS} на экране / долгой загрузки.
 * Долгое нажатие на строку уровня — тоже примеры (там вводится родительский код).
 */
final class ReadingScreen {
    interface Host {
        /** Текст прочитан — разблокировать. */
        void onReadingPassed();
        /** Читать нельзя или ребёнок выбрал примеры; hint — что показать над примером (может быть null). */
        void onReadingFallback(String hint);
    }

    static final String TAG = ReadingEngine.TAG;
    static final int SR = 16000;
    /** После конца текста (или в перечитывании) — столько тишины без новых букв, и запись останавливается сама. */
    static final long AUTO_STOP_MS = 2500;

    // тёмная палитра экрана блокировки (как у примеров) + цвета подсветки стенда
    static final int BG = 0xFF1B2430, CARD = 0xFF26323F, TEXT = 0xFFF0EEE6, DIM = 0xFF9FB3C8, ACCENT = 0xFF5B8DEF,
            LISTENING = 0xFFF0634A, CHIP = 0xFF2E3E55, SUCCESS = 0xFF3CAA64, SYL0 = 0xFF8FB4FF, SYL1 = 0xFFFF9B8A,
            OK_BG = 0xFF244A2A, WRONG_BG = 0xFF5A2A2A, CUR_BG = 0xFF5A4A1A, ENDING = 0xFFFFA03C, HINT = 0xFFF2A65A;

    final Activity act;
    final Host host;
    final Prefs prefs;
    final ReadingPack pack;
    final Handler h = new Handler(Looper.getMainLooper());
    final Random rnd = new Random();
    final long shownAt = SystemClock.elapsedRealtime();

    View root;
    TextView levelView, textView, status, badge, mathBtn;
    RoundIcon mic, listenBtn;

    final List<JSONObject> texts = new ArrayList<>();
    int idx;
    int textLevel = 1;
    boolean challenge;
    ReadingText cur;
    volatile ReadingMatcher.Result lastResult;
    CtcModel model;
    SpeakerModel spk;
    ReaderProfiles profiles;
    SpeakerId.Profile reader;
    AdaptiveLevel adapt;
    float[] lastEmb;

    volatile boolean recording, repairing, closed, passed;
    /** Экран погас посреди записи: результат не засчитываем (иначе телефон «разблокировался» бы в темноте). */
    volatile boolean aborted;
    /** В этой записи была подсказка «шумно». */
    volatile boolean noisy;
    volatile long recStartedAt;
    int fails;
    boolean engineHeld;
    MediaPlayer player;
    int voiceIdx;

    ReadingScreen(Activity act, Host host, Prefs prefs, ReadingPack pack) {
        this.act = act; this.host = host; this.prefs = prefs; this.pack = pack;
    }

    int dp(float v) { return Math.round(v * act.getResources().getDisplayMetrics().density); }
    File dir() { return pack.dir; }

    /** Собрать экран, выбрать текст, загрузить модель в фоне. @return false — читать нечего (тогда сразу примеры). */
    boolean start() {
        loadTexts();
        if (texts.isEmpty()) return false;
        profiles = new ReaderProfiles(dir(), clampLevel(prefs.readStartLevel()));
        loadProgress();
        pickNext();
        build();
        show();
        ReadingEngine.acquire(); engineHeld = true;
        MediaHush.hold(act);   // видео/музыка этого телефона на паузу, иначе микрофон слышит их
        mic.setEnabled(false);
        status.setText("Готовлюсь…");
        new Thread(() -> {
            CtcModel m = ReadingEngine.model(act, false);
            SpeakerModel s = ReadingEngine.speaker();
            h.post(() -> {
                if (closed) return;
                if (m == null) { host.onReadingFallback("Чтение сейчас недоступно"); return; }
                model = m; spk = s;
                mic.setEnabled(true);
                status.setText("Нажми на микрофон и читай вслух");
            });
        }, "reading-load").start();
        h.postDelayed(() -> { if (!closed && model == null) showMathButton(); }, TaskPicker.LOAD_TIMEOUT_MS);
        h.postDelayed(() -> { if (!closed && !passed) showMathButton(); }, TaskPicker.FALLBACK_MS);
        return true;
    }

    View view() { return root; }

    /** Экран уходит (разблокировка, примеры, закрытие): запись и звук стоп, модели отпускаются. */
    void close() {
        if (closed) return;
        closed = true; recording = false;
        h.removeCallbacksAndMessages(null);
        stopAudio();
        MediaHush.release(act);
        if (engineHeld) { engineHeld = false; ReadingEngine.release(); }
    }

    /** Ушли с экрана (погас): запись обрывается, звук стоп. */
    void onStop() { if (recording) aborted = true; recording = false; stopAudio(); }

    static int clampLevel(int l) { return Math.max(AdaptiveLevel.MIN, Math.min(AdaptiveLevel.MAX, l)); }

    // ---------------- данные ----------------

    void loadTexts() {
        File f = new File(dir(), ReadingPack.TEXTS);
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                try { JSONObject j = new JSONObject(line); if (!j.optString("text").isEmpty()) texts.add(j); } catch (Exception ignored) { }
            }
        } catch (Exception e) { Log.w(TAG, "texts.jsonl: " + e); }
    }

    File commonJournal() { return new File(dir(), "progress.tsv"); }

    /** Уровень — по последнему опознанному чтецу; без голосовой модели — общий журнал (как в readbench). */
    void loadProgress() {
        reader = profiles.last();
        if (!pack.speaker()) adapt = AdaptiveLevel.parse(profiles.startChild, ReaderProfiles.lines(commonJournal()));
        else if (reader != null) adapt = profiles.level(reader);
        else adapt = new AdaptiveLevel(profiles.startChild);
    }

    /** Текст по адаптивному уровню; каждый 5-й — «вызов» на уровень выше. */
    void pickNext() {
        List<String> ids = new ArrayList<>(); List<Integer> lv = new ArrayList<>();
        for (JSONObject t : texts) { ids.add(t.optString("id")); lv.add(t.optInt("level", 1)); }
        int level = adapt.level();
        challenge = adapt.nextIsChallenge();
        int i = adapt.pick(ids, lv, challenge ? level + 1 : level, rnd);
        if (challenge && i >= 0 && lv.get(i) != level + 1) challenge = false;
        idx = i >= 0 ? i : rnd.nextInt(texts.size());
        Log.i(TAG, "уровень " + level + (challenge ? ", вызов" : "") + " → " + ids.get(idx));
    }

    String curId() { return texts.get(idx).optString("id"); }

    // ---------------- экран ----------------

    void build() {
        FrameLayout fr = new FrameLayout(act); fr.setBackgroundColor(BG);
        LinearLayout l = new LinearLayout(act); l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(18), dp(28), dp(18), dp(18));

        levelView = new TextView(act); levelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15); levelView.setTextColor(DIM);
        levelView.setGravity(Gravity.CENTER); levelView.setPadding(0, 0, 0, dp(10));
        // для родителя: долгое нажатие — экран примеров, где вводится родительский код
        levelView.setOnLongClickListener(v -> { close(); host.onReadingFallback(null); return true; });

        textView = new TextView(act); textView.setLineSpacing(0, 1.3f); textView.setTextColor(TEXT);
        textView.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        textView.setPadding(dp(20), dp(20), dp(20), dp(20));
        GradientDrawable card = new GradientDrawable(); card.setColor(CARD); card.setCornerRadius(dp(26));
        textView.setBackground(card);
        textView.setMovementMethod(android.text.method.ScrollingMovementMethod.getInstance());
        textView.setVerticalScrollBarEnabled(true);
        textView.setOnTouchListener((v, e) -> {   // палец на тексте — суфлёр уступает ручной прокрутке
            int a = e.getActionMasked();
            if (a == android.view.MotionEvent.ACTION_DOWN || a == android.view.MotionEvent.ACTION_MOVE) prompter.userTouch();
            return false;
        });

        status = new TextView(act); status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19); status.setGravity(Gravity.CENTER);
        status.setTextColor(DIM); status.setPadding(0, dp(14), 0, dp(10));

        LinearLayout row = new LinearLayout(act); row.setGravity(Gravity.CENTER_VERTICAL);
        listenBtn = new RoundIcon(act, RoundIcon.SPEAKER, CHIP, TEXT);
        listenBtn.setOnClickListener(v -> toggleListen());
        mic = new RoundIcon(act, RoundIcon.MIC, ACCENT, Color.WHITE);
        mic.setOnClickListener(v -> onMic());
        mathBtn = new TextView(act); mathBtn.setText("Примеры"); mathBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        mathBtn.setTextColor(TEXT); mathBtn.setGravity(Gravity.CENTER);
        GradientDrawable mb = new GradientDrawable(); mb.setColor(CHIP); mb.setCornerRadius(dp(24)); mathBtn.setBackground(mb);
        mathBtn.setVisibility(View.INVISIBLE);
        mathBtn.setOnClickListener(v -> { close(); host.onReadingFallback(null); });
        LinearLayout.LayoutParams side = new LinearLayout.LayoutParams(0, dp(64), 1);
        FrameLayout left = new FrameLayout(act), right = new FrameLayout(act);
        left.addView(listenBtn, new FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER));
        right.addView(mathBtn, new FrameLayout.LayoutParams(dp(110), dp(48), Gravity.CENTER));
        row.addView(left, side);
        row.addView(mic, new LinearLayout.LayoutParams(dp(140), dp(140)));
        row.addView(right, new LinearLayout.LayoutParams(0, dp(64), 1));

        l.addView(levelView);
        l.addView(textView, new LinearLayout.LayoutParams(-1, 0, 1));
        l.addView(status);
        l.addView(row, new LinearLayout.LayoutParams(-1, -2));
        fr.addView(l, new FrameLayout.LayoutParams(-1, -1));

        badge = new TextView(act); badge.setText("Молодец!"); badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 42); badge.setTextColor(Color.WHITE);
        badge.setTypeface(Typeface.DEFAULT_BOLD); badge.setPadding(dp(34), dp(16), dp(34), dp(16));
        GradientDrawable bd = new GradientDrawable(); bd.setColor(SUCCESS); bd.setCornerRadius(dp(40)); badge.setBackground(bd);
        badge.setElevation(dp(8)); badge.setVisibility(View.GONE);
        fr.addView(badge, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        root = fr;
    }

    void showMathButton() { if (mathBtn != null) mathBtn.setVisibility(View.VISIBLE); }

    void show() {
        cur = new ReadingText(texts.get(idx).optString("text"));
        textLevel = texts.get(idx).optInt("level", 1);
        showLevel();
        int n = cur.words.size();
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, n <= 10 ? 36 : n <= 45 ? 28 : n <= 90 ? 22 : 19);
        textView.setGravity((n <= 45 ? Gravity.CENTER_VERTICAL : Gravity.TOP) | Gravity.START);
        listenBtn.setVisibility(refFiles().length > 0 ? View.VISIBLE : View.INVISIBLE);
        lastResult = null;
        render();
    }

    void showLevel() {
        int level = adapt.level(); float best = adapt.best(level);
        int awards = Achievements.count(Achievements.all(adapt, TimeZone.getDefault()));
        String s = "Уровень " + level + (challenge ? " · ★ трудный текст" : "")
                + (best > 0 ? String.format(Locale.ROOT, " · рекорд %.0f", best) : "")
                + (awards > 0 ? " · 🏅 " + awards : "");
        int left = adapt.toNextLevel();
        String goal = level >= AdaptiveLevel.MAX
                ? String.format(Locale.ROOT, "Цель — %.0f слов в минуту", AdaptiveLevel.TOP_TARGET)
                : String.format(Locale.ROOT, "До уровня %d: ещё %d %s быстрее %.0f сл/мин", level + 1, left, left == 1 ? "текст" : "текста", AdaptiveLevel.target(level));
        levelView.setText(s + "\n" + goal);
    }

    // Текст собирается один раз (слоги, ударения), при обновлении подсветки меняются только спаны изменившихся слов.
    ReadingText shownFor;
    android.text.Spannable shownText;
    int[] shownState;
    Object[][] stateSpans;

    void render() {
        if (shownFor != cur) {
            SpannableStringBuilder sb = new SpannableStringBuilder(cur.source);
            if (AdaptiveLevel.syllableColors(textLevel))
                for (ReadingText.Word wd : cur.words) {
                    int pos = wd.start, s = 0;
                    for (String syl : wd.syllables) {
                        int st = pos, left = syl.length();
                        while (pos < wd.end && left > 0) { char c = cur.source.charAt(pos); if (c != ReadingText.ACUTE && c != '-') left--; pos++; }
                        while (pos < wd.end && cur.source.charAt(pos) == ReadingText.ACUTE) pos++;
                        sb.setSpan(new ForegroundColorSpan(s++ % 2 == 0 ? SYL0 : SYL1), st, pos, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        if (pos < wd.end && cur.source.charAt(pos) == '-') pos++;
                    }
                }
            if (!AdaptiveLevel.stressMarks(textLevel))   // старшие уровни — без ударений (акут прячем цветом)
                for (int i = 0; i < cur.source.length(); i++)
                    if (cur.source.charAt(i) == ReadingText.ACUTE) sb.setSpan(new ForegroundColorSpan(Color.TRANSPARENT), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            textView.setText(sb, TextView.BufferType.SPANNABLE);
            shownText = (android.text.Spannable) textView.getText();
            shownState = new int[cur.words.size()];
            stateSpans = new Object[cur.words.size()][];
            shownFor = cur;
        }
        ReadingMatcher.Result res = lastResult;
        int cw = -1;   // текущее (жёлтое) слово — первое ещё не засчитанное
        if (res != null && recording)
            for (int w = 0; w < cur.words.size(); w++) if (res.states[w] == ReadingMatcher.State.PENDING) { cw = w; break; }
        for (int w = 0; w < cur.words.size(); w++) {
            int st = 0;   // 0 — без фона, 1 — верно, 2 — ошибка, 3 — текущее, 4 — верно, но проверь окончание
            if (res != null) {
                ReadingMatcher.State s = res.states[w];
                st = s == ReadingMatcher.State.OK ? (res.endingHint[w] && !recording ? 4 : 1) : s == ReadingMatcher.State.WRONG ? 2 : w == cw ? 3 : 0;
            }
            if (st == shownState[w]) continue;
            if (stateSpans[w] != null) for (Object o : stateSpans[w]) shownText.removeSpan(o);
            ReadingText.Word wd = cur.words.get(w);
            Object[] add;
            if (st == 4) {   // зелёное слово, окончание (2 последние буквы) — оранжевым жирным
                int e = wd.end, k = 0, b = e;
                while (b > wd.start && k < 2) { b--; if (shownText.charAt(b) != ReadingText.ACUTE) k++; }
                add = new Object[]{new BackgroundColorSpan(OK_BG), new ForegroundColorSpan(ENDING), new android.text.style.StyleSpan(Typeface.BOLD), new UnderlineSpan()};
                shownText.setSpan(add[0], wd.start, wd.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                for (int q = 1; q < add.length; q++) shownText.setSpan(add[q], b, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                add = st == 1 ? new Object[]{new BackgroundColorSpan(OK_BG)}
                        : st == 2 ? new Object[]{new BackgroundColorSpan(WRONG_BG), new UnderlineSpan()}
                        : st == 3 ? new Object[]{new BackgroundColorSpan(CUR_BG)} : null;
                if (add != null) for (Object o : add) shownText.setSpan(o, wd.start, wd.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            stateSpans[w] = add; shownState[w] = st;
        }
        // телесуфлёр: во время чтения — текущее слово на трети карточки; после и при перечитывании — первое красное
        int tw = -1;
        if (res != null) {
            if (recording && !repairing && cw >= 0) tw = cw;
            else for (int w = 0; w < cur.words.size(); w++) if (res.states[w] != ReadingMatcher.State.OK) { tw = w; break; }
        }
        android.text.Layout lay = textView.getLayout();
        if (tw >= 0 && lay != null) {
            int off = cur.words.get(tw).start;
            int line = lay.getLineForOffset(off);
            float w = Math.max(1, lay.getLineRight(line) - lay.getLineLeft(line));
            float frac = Math.max(0, Math.min(1, (lay.getPrimaryHorizontal(off) - lay.getLineLeft(line)) / w));
            int lh = lay.getLineBottom(line) - lay.getLineTop(line);
            float y = lay.getLineTop(line) + frac * lh + textView.getTotalPaddingTop() - textView.getHeight() / 3f;
            int maxY = Math.max(0, lay.getHeight() + textView.getTotalPaddingTop() + textView.getTotalPaddingBottom() - textView.getHeight());
            prompter.target(Math.max(0, Math.min(y, maxY)));
        } else if (res == null) prompter.reset();
        else prompter.hold();
    }

    final Prompter prompter = new Prompter();

    /**
     * Телесуфлёр (как в readbench): прокрутка догоняет цель с постоянной скоростью — оценка темпа чтения плюс
     * поправка на отставание; нет прогресса дольше 1,2 с — плавно замирает. Палец на тексте — уступает на 4 с.
     */
    final class Prompter implements android.view.Choreographer.FrameCallback {
        float y, v, base, goal; long lastGoalNs, lastFrameNs, userUntil; boolean running;
        static final long USER_PAUSE_MS = 4000;
        final Runnable resume = () -> { y = textView.getScrollY(); if (goal != y) start(); };

        boolean userHolds() { return SystemClock.uptimeMillis() < userUntil; }
        void userTouch() {
            userUntil = SystemClock.uptimeMillis() + USER_PAUSE_MS; v = 0;
            textView.removeCallbacks(resume); textView.postDelayed(resume, USER_PAUSE_MS + 50);
        }
        void target(float g) {
            long now = System.nanoTime();
            if (lastGoalNs != 0 && g > goal) {
                float dt = (now - lastGoalNs) / 1e9f;
                if (dt > 0.05f) { float rate = (g - goal) / dt; base = base == 0 ? rate : base * 0.7f + rate * 0.3f; }
            }
            if (g != goal || lastGoalNs == 0) { if (g > goal || lastGoalNs == 0) lastGoalNs = now; goal = g; }
            if (userHolds()) return;
            textView.scrollTo(0, Math.round(y));
            start();
        }
        void hold() { if (!userHolds()) textView.scrollTo(0, Math.round(y)); }
        void reset() { running = false; y = v = base = goal = 0; lastGoalNs = 0; textView.scrollTo(0, 0); }
        void start() { if (!running) { running = true; lastFrameNs = 0; android.view.Choreographer.getInstance().postFrameCallback(this); } }

        @Override public void doFrame(long ns) {
            if (!running || closed) { running = false; return; }
            if (userHolds()) { running = false; return; }
            float dt = lastFrameNs == 0 ? 0.016f : Math.min(0.05f, (ns - lastFrameNs) / 1e9f); lastFrameNs = ns;
            float idle = (System.nanoTime() - lastGoalNs) / 1e9f;
            if (idle > 1.2f) base *= (float) Math.exp(-dt / 0.6f);
            float err = goal - y, want;
            if (err >= 0) want = base + 1.5f * err;
            else want = err < -textView.getLineHeight() ? 2f * err : 0f;
            v += (want - v) * Math.min(1f, dt * 3f);
            if (err > 0 && v * dt > err && base < 1f) v = err / dt;
            y = Math.max(0, y + v * dt);
            textView.scrollTo(0, Math.round(y));
            if (Math.abs(err) < 0.5f && Math.abs(v) < 0.5f && idle > 3f) { running = false; return; }
            android.view.Choreographer.getInstance().postFrameCallback(this);
        }
    }

    // ---------------- чтение ----------------

    void onMic() {
        if (passed || model == null) return;
        if (recording) {
            if (System.currentTimeMillis() - recStartedAt < 800) return;   // дребезг: дети жмут сериями
            recording = false; return;
        }
        stopAudio();   // не записывать эталон из динамика вместе с ребёнком
        if (act.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            close(); host.onReadingFallback("Нет доступа к микрофону"); return;
        }
        aborted = false; recording = true; recStartedAt = System.currentTimeMillis();
        new Thread(this::readLoop, "reading-loop").start();
    }

    void setListening(boolean on) {
        mic.set(on ? RoundIcon.STOP : RoundIcon.MIC, on ? LISTENING : ACCENT);
        mic.setPulsing(on);
    }

    void readLoop() {
        final ReadingMatcher.Result before = lastResult;
        // текст уже читали, остались красные — перечитывание: зелёное остаётся зелёным
        final ReadingMatcher.Result prev = before != null && !before.complete ? before : null;
        repairing = prev != null;
        final ReadingText text = cur;
        final String textId = curId(); final int level = textLevel; final boolean chall = challenge;
        int chunk = SR / 10;
        AudioRecord ar = null;
        try {
            ar = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, SR * 2);
            if (ar.getState() != AudioRecord.STATE_INITIALIZED) throw new IllegalStateException("AudioRecord не инициализирован");
            MediaHush.mute(act);   // игры фокус не слушают — глушим медиа на время записи
            ar.startRecording();
        } catch (Throwable e) {
            Log.e(TAG, "микрофон", e);
            if (ar != null) ar.release();
            MediaHush.unmute(act);
            recording = false;
            h.post(() -> { if (!closed) { close(); host.onReadingFallback("Микрофон недоступен"); } });
            return;
        }
        StreamingRecognizer rec = new StreamingRecognizer(model);
        ReadingMatcher matcher = new ReadingMatcher();
        short[] s = new short[chunk]; float[] f = new float[chunk];
        final Levels levels = new Levels(f); noisy = false; boolean hintShown = false;
        h.post(() -> { status.setText(prev != null ? "Прочитай красные слова" : "Слушаю…"); setListening(true); render(); });
        String hyp = ""; boolean done = false; long lastDecode = 0;
        int lastLen = 0; long lastChange = System.currentTimeMillis();
        long maxSamples = SR * Math.min(600L, Math.max(120L, text.words.size() * 4L));   // до 15 слов/мин
        try {
            while (recording && !closed && rec.samples() < maxSamples) {
                int r = ar.read(s, 0, chunk); if (r <= 0) break;
                for (int i = 0; i < r; i++) f[i] = s[i] / 32768f;
                rec.feed(f, r);
                levels.add(r);
                if (!hintShown && levels.size() >= 40 && levels.size() % 4 == 0) {
                    String hint = levels.hint();
                    if (hint != null) { hintShown = true; noisy = hint.startsWith(Levels.NOISY); Log.i(TAG, "уровни: " + levels + " → " + hint); h.post(() -> status.setText(hint)); }
                }
                long now = System.currentTimeMillis();
                if (now - lastDecode >= 400 && rec.samples() > SR / 2) {
                    lastDecode = now;
                    hyp = rec.decode();
                    ReadingMatcher.Result res = matcher.match(text, hyp, false, prev);
                    int len = hyp.replace(" ", "").length();
                    if (len != lastLen) { lastLen = len; lastChange = now; }
                    h.post(() -> { if (!closed) { lastResult = res; render(); } });
                    if (res.complete) { done = true; break; }
                    // дочитал до конца (или перечитал, что хотел) и молчит — заканчиваем сами
                    boolean atEnd = prev == null ? res.progressWords >= text.words.size() : len > 0;
                    if (atEnd && now - lastChange > AUTO_STOP_MS) break;
                }
            }
        } catch (Throwable e) { Log.e(TAG, "чтение", e); }
        try { ar.stop(); } catch (Exception ignored) { }
        ar.release();
        MediaHush.unmute(act);   // до праздничного звука и «послушать»
        recording = false;
        if (closed) return;
        if (aborted) {
            aborted = false;
            h.post(() -> { if (closed) return; lastResult = before; repairing = false; setListening(false); render(); status.setText("Нажми на микрофон и читай вслух"); });
            return;
        }
        try { hyp = rec.decode(); } catch (Throwable ignored) { }
        final ReadingMatcher.Result fin = matcher.match(text, hyp, true, prev);
        final ReadingMatcher.Result live = matcher.match(text, hyp, false, prev);
        int[] lfs = new int[rec.letterFrames.size()];
        for (int i = 0; i < lfs.length; i++) lfs[i] = rec.letterFrames.get(i);
        final ReadingStats stats = ReadingStats.of(fin, live, lfs, StreamingRecognizer.FRAME_SEC);
        // пустое нажатие или разговор мимо текста — тихо забываем, экран как был
        boolean junk = hyp.replace(" ", "").length() < 3 || (prev == null && live.progressWords == 0);
        if (junk && !done) {
            h.post(() -> { if (closed) return; lastResult = before; repairing = false; setListening(false); render(); status.setText("Не расслышал. Нажми и читай вслух"); });
            return;
        }
        int okWords = 0; for (ReadingMatcher.State x : fin.states) if (x == ReadingMatcher.State.OK) okWords++;
        final boolean ok = TaskPicker.passed(okWords, fin.states.length, fin.complete || done, prefs.readThresholdPct(), stats.reached);
        final int pct = Math.round(100f * okWords / Math.max(1, fin.states.length));
        final boolean allOk = fin.complete || done;
        if (ok) passed = true; else fails++;
        h.post(() -> {
            if (closed) return;
            lastResult = fin; repairing = false; setListening(false); render();
            int hints = 0; for (boolean e : fin.endingHint) if (e) hints++;
            if (ok) {
                status.setTextColor(TEXT);
                status.setText((allOk ? "Молодец! Всё верно" : "Молодец! Верно " + pct + "%") + (hints > 0 ? "\nОранжевым — проверь окончания" : ""));
                mic.setEnabled(false); listenBtn.setEnabled(false); mathBtn.setVisibility(View.INVISIBLE);
                celebrate();
            } else {
                status.setText((prefs.readThresholdPct() == TaskPicker.READ_TO_END ? "Почти! Дочитай до конца" : "Почти! Прочитай красные слова ещё раз") + (hints > 0 ? "\nОранжевым — проверь окончания" : ""));
                if (TaskPicker.offerMath(fails, SystemClock.elapsedRealtime() - shownAt)) showMathButton();
            }
        });
        // голос, запись, журнал — после того, как ребёнок увидел результат; разблокировка — при любом исходе этой части
        String news = "";
        try {
            SpeakerId.Decision d = identify(rec.audio());
            String base = save(textId, text, rec, hyp, fin, ok, d, levels);
            // перечитывание красных — не новая попытка: темп и точность считаются по первому прочтению
            if (prev == null) news = record(textId, level, chall, stats, d, base);
        } catch (Throwable e) { Log.e(TAG, "журнал чтения", e); }
        final String n = news;
        h.post(() -> {
            if (closed) return;
            if (!n.isEmpty()) status.setText(status.getText() + "\n" + n);
            showLevel();
            if (ok) h.postDelayed(() -> { if (!closed) host.onReadingPassed(); }, n.contains("🏅") ? 3800 : 2400);
        });
    }

    SpeakerId.Decision identify(float[] audio) {
        if (spk == null || audio == null) return null;
        try {
            SpeakerModel.Result r = spk.embed(audio, audio.length);
            if (r == null) return null;
            SpeakerId.Decision d = SpeakerId.decide(r.emb, r.speechS, profiles.list);
            Log.i(TAG, "голос: " + d.describe(profiles.list));
            lastEmb = r.emb;
            return d;
        } catch (Throwable e) { Log.e(TAG, "опознание", e); return null; }
    }

    /** Журнал и уровень по опознанному чтецу (как readbench). @return строка для ребёнка: рекорд, уровень, награды. */
    String record(String textId, int level, boolean chall, ReadingStats st, SpeakerId.Decision d, String rec) {
        SpeakerId.Profile who = null;
        if (d != null && d.kind == SpeakerId.Kind.MATCH) who = d.profile;
        else if (d != null && d.kind == SpeakerId.Kind.NEW && st.counts()) who = profiles.create(lastEmb);
        if (who != null) {
            if (d.kind == SpeakerId.Kind.MATCH) { SpeakerId.learn(who, lastEmb, false); profiles.save(who); }
            if (reader != who) { reader = who; adapt = profiles.level(who); }
            profiles.setLast(who.id);
        }
        // в шуме (была подсказка «шумно») неудачная попытка не роняет уровень: виноват телевизор, а не чтение
        if (noisy && st.accuracy() < AdaptiveLevel.ACC_DOWN) { Log.i(TAG, "попытка в шуме не засчитана: " + st); return ""; }
        if (!st.counts()) { Log.i(TAG, "попытка не засчитана: " + st); return ""; }
        AdaptiveLevel.Attempt a = new AdaptiveLevel.Attempt(System.currentTimeMillis(), textId, level, st.wcpm(), st.accuracy(), chall, rec);
        String msg = String.format(Locale.ROOT, "%.0f слов в минуту", st.wcpm());
        if (spk == null) {   // без голосовой модели — общий журнал
            int before = adapt.level(); float best = adapt.best(level);
            adapt.history.add(a);
            try (FileOutputStream o = new FileOutputStream(commonJournal(), true)) { o.write((a.toTsv() + "\n").getBytes(StandardCharsets.UTF_8)); }
            catch (Exception e) { Log.w(TAG, "progress.tsv: " + e); }
            msg += levelNews(st, before, best);
        } else if (who == null) {   // не определён: отдельный журнал, уровень не меняется, наград нет
            profiles.append(null, a);
            return msg;
        } else {
            int before = adapt.level(); float best = adapt.best(level);
            adapt.history.add(a); profiles.append(who.id, a);
            msg += levelNews(st, before, best);
            if (d.kind == SpeakerId.Kind.NEW && profiles.resortUnknown() > 0) adapt = profiles.level(who);
        }
        Log.i(TAG, "попытка: " + st + " → " + (who == null ? "общий журнал" : who.id) + ", уровень " + adapt.level());
        List<Achievements.Badge> fresh = Achievements.newest(adapt, TimeZone.getDefault());
        for (int i = 0; i < fresh.size() && i < 2; i++) msg += "\n🏅 " + fresh.get(i);
        if (!fresh.isEmpty()) h.post(() -> play(Sounds.coin(44100), 44100));
        return msg;
    }

    String levelNews(ReadingStats st, int before, float best) {
        int after = adapt.level(); String msg = "";
        if (st.accuracy() >= 0.95f && st.wcpm() > best && best > 0) msg += " — новый рекорд!";
        if (after > before) msg += "\nНовый уровень " + after + "!";
        else if (after < before) msg += "\nПотренируемся на уровне " + after;
        return msg;
    }

    /** Запись для дообучения: recordings/&lt;время&gt;_&lt;id&gt;.wav + .json — формат readbench. */
    String save(String textId, ReadingText text, StreamingRecognizer rec, String hyp, ReadingMatcher.Result r, boolean ok, SpeakerId.Decision dec, Levels levels) {
        String base = null;
        try {
            File d = new File(dir(), "recordings"); d.mkdirs();
            base = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new java.util.Date()) + "_" + textId;
            float[] a = rec.audio();
            ByteBuffer bb = ByteBuffer.allocate(44 + a.length * 2).order(ByteOrder.LITTLE_ENDIAN);
            bb.put("RIFF".getBytes()).putInt(36 + a.length * 2).put("WAVEfmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
              .putInt(SR).putInt(SR * 2).putShort((short) 2).putShort((short) 16).put("data".getBytes()).putInt(a.length * 2);
            for (float x : a) bb.putShort((short) Math.max(-32768, Math.min(32767, Math.round(x * 32767))));
            try (FileOutputStream o = new FileOutputStream(new File(d, base + ".wav"))) { o.write(bb.array()); }
            JSONObject j = new JSONObject().put("id", textId).put("text", text.source).put("hyp", hyp).put("auto_ok", ok)
                    .put("backend", String.valueOf(model.backend)).put("app", "mathlock").put("threshold", prefs.readThresholdPct());
            StringBuilder st = new StringBuilder(); for (ReadingMatcher.State x : r.states) st.append(x.name().charAt(0));
            j.put("states", st.toString());
            j.put("levels", levels.toString());
            if (dec != null && lastEmb != null) {
                JSONObject sp = new JSONObject().put("decision", dec.kind.name()).put("speech_s", Math.round(dec.speechS * 100) / 100.0);
                if (dec.profile != null) sp.put("profile", dec.profile.id);
                JSONObject cs = new JSONObject();
                for (int i = 0; i < profiles.list.size() && i < dec.cos.length; i++) cs.put(profiles.list.get(i).id, Math.round(dec.cos[i] * 1000) / 1000.0);
                sp.put("cos", cs);
                org.json.JSONArray e = new org.json.JSONArray(); for (float v : lastEmb) e.put(Math.round(v * 1e6) / 1e6);
                sp.put("emb", e);
                j.put("speaker", sp);
            }
            try (FileOutputStream o = new FileOutputStream(new File(d, base + ".json"))) { o.write(j.toString(1).getBytes(StandardCharsets.UTF_8)); }
        } catch (Exception e) { Log.e(TAG, "save", e); }
        return base;
    }

    /**
     * Громкость по кускам 100 мс (как Levels в readbench): пол шума — 10-й перцентиль, речь — 90-й, по последним 6 с.
     * Шумовой тест 05.11: телевизор при SNR 10 дБ меняет оценку у 29% слов живого детского чтения; тихий дом — 25–40 дБ.
     */
    static final class Levels {
        static final String NOISY = "Вокруг шумно";
        final float[] buf; final ArrayList<Float> db = new ArrayList<>();
        Levels(float[] buf) { this.buf = buf; }
        void add(int n) { double e = 0; for (int i = 0; i < n; i++) e += buf[i] * buf[i]; db.add((float) (10 * Math.log10(e / Math.max(1, n) + 1e-12))); }
        int size() { return db.size(); }
        float pct(int from, float q) { List<Float> v = new ArrayList<>(db.subList(from, db.size())); java.util.Collections.sort(v); return v.get(Math.min(v.size() - 1, (int) (q * v.size()))); }
        String hint() {
            int from = Math.max(0, db.size() - 60); float floor = pct(from, 0.1f), speech = pct(from, 0.9f);
            if (floor > -38f && speech - floor < 15f) return NOISY + " — выключи телевизор или найди тихое место";
            if (speech < -33f) return "Читай погромче или поднеси телефон ближе";
            return null;
        }
        @Override public String toString() {
            return db.isEmpty() ? "" : String.format(Locale.ROOT, "пол %.1f дБ, речь %.1f дБ", pct(0, 0.1f), pct(0, 0.9f));
        }
    }

    // ---------------- звук ----------------

    void celebrate() {
        play(Sounds.randomSuccess(44100, rnd), 44100);
        badge.setVisibility(View.VISIBLE); badge.setAlpha(0f); badge.setScaleX(0.3f); badge.setScaleY(0.3f);
        badge.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(450).setInterpolator(new android.view.animation.OvershootInterpolator(2f))
                .withEndAction(() -> badge.animate().alpha(0f).setStartDelay(1400).setDuration(400).withEndAction(() -> badge.setVisibility(View.GONE)));
    }

    static void play(short[] pcm, int sr) {
        try {
            AudioTrack t = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(sr).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(Math.max(pcm.length * 2, 4096)).build();
            t.write(pcm, 0, pcm.length);
            t.setNotificationMarkerPosition(pcm.length - 1);
            t.setPlaybackPositionUpdateListener(new AudioTrack.OnPlaybackPositionUpdateListener() {
                @Override public void onMarkerReached(AudioTrack x) { x.release(); }
                @Override public void onPeriodicNotification(AudioTrack x) { }
            });
            t.play();
        } catch (Exception e) { Log.w(TAG, "звук: " + e); }
    }

    /** Озвучка текста: tts/&lt;id&gt;.&lt;голос&gt;.ogg|wav, по нажатию — следующий голос; нажатие во время звука — стоп. */
    File[] refFiles() {
        File[] fs = new File(dir(), ReadingPack.TTS).listFiles((x, n) -> n.startsWith(curId() + ".") && (n.endsWith(".ogg") || n.endsWith(".wav")));
        if (fs == null) return new File[0];
        java.util.Arrays.sort(fs); return fs;
    }

    void toggleListen() {
        if (recording || passed) return;
        if (player != null) { stopAudio(); return; }
        File[] fs = refFiles(); if (fs.length == 0) return;
        File f = fs[Math.floorMod(voiceIdx++, fs.length)];
        try {
            player = new MediaPlayer();
            player.setDataSource(f.getPath()); player.prepare();
            player.setOnCompletionListener(mp -> stopAudio());
            player.start();
            listenBtn.set(RoundIcon.STOP, CHIP);
        } catch (Exception e) { Log.w(TAG, "озвучка " + f + ": " + e); stopAudio(); }
    }

    void stopAudio() {
        if (player != null) { try { player.stop(); } catch (Exception ignored) { } player.release(); player = null; }
        if (listenBtn != null) listenBtn.set(RoundIcon.SPEAKER, CHIP);
    }

    /** Круглая кнопка с нарисованной иконкой (без эмодзи и подписей), пульс «слушаю». */
    static final class RoundIcon extends View {
        static final int MIC = 0, STOP = 1, SPEAKER = 2;
        int icon, bg; final int fg;
        float pulse; ValueAnimator anim;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG); final RectF rect = new RectF(); final Path path = new Path();

        RoundIcon(Context c, int icon, int bg, int fg) { super(c); this.icon = icon; this.bg = bg; this.fg = fg; setClickable(true); }
        void set(int icon, int bg) { this.icon = icon; this.bg = bg; invalidate(); }
        void setPulsing(boolean on) {
            if (on == (anim != null)) return;
            if (on) {
                anim = ValueAnimator.ofFloat(0, 1); anim.setDuration(1400); anim.setRepeatCount(ValueAnimator.INFINITE);
                anim.setInterpolator(new LinearInterpolator()); anim.addUpdateListener(a -> { pulse = (float) a.getAnimatedValue(); invalidate(); });
                anim.start();
            } else { anim.cancel(); anim = null; invalidate(); }
        }
        @Override public void setEnabled(boolean e) { super.setEnabled(e); setAlpha(e ? 1f : 0.35f); }
        @Override public void setPressed(boolean b) { super.setPressed(b); invalidate(); }
        @Override protected void onDetachedFromWindow() { setPulsing(false); super.onDetachedFromWindow(); }

        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), hh = getHeight(), cx = w / 2, cy = hh / 2;
            float r = Math.min(w, hh) / 2 * (anim != null ? 0.70f : 0.90f);
            p.setStyle(Paint.Style.FILL);
            if (anim != null) for (int k = 0; k < 2; k++) {
                float ph = (pulse + k * 0.5f) % 1f;
                p.setColor(bg); p.setAlpha((int) (110 * (1 - ph))); cv.drawCircle(cx, cy, r * (1 + 0.42f * ph), p);
            }
            p.setColor(bg); p.setAlpha(isPressed() ? 200 : 255); cv.drawCircle(cx, cy, r, p);
            p.setColor(fg); p.setAlpha(255); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeWidth(r * 0.14f);
            if (icon == MIC) {
                rect.set(cx - 0.2f * r, cy - 0.62f * r, cx + 0.2f * r, cy + 0.1f * r); cv.drawRoundRect(rect, 0.2f * r, 0.2f * r, p);
                p.setStyle(Paint.Style.STROKE);
                rect.set(cx - 0.4f * r, cy - 0.35f * r, cx + 0.4f * r, cy + 0.3f * r); cv.drawArc(rect, 0, 180, false, p);
                cv.drawLine(cx, cy + 0.3f * r, cx, cy + 0.55f * r, p);
                cv.drawLine(cx - 0.22f * r, cy + 0.55f * r, cx + 0.22f * r, cy + 0.55f * r, p);
            } else if (icon == STOP) {
                rect.set(cx - 0.34f * r, cy - 0.34f * r, cx + 0.34f * r, cy + 0.34f * r); cv.drawRoundRect(rect, 0.1f * r, 0.1f * r, p);
            } else {
                path.reset();
                path.moveTo(cx - 0.55f * r, cy - 0.2f * r); path.lineTo(cx - 0.3f * r, cy - 0.2f * r); path.lineTo(cx + 0.02f * r, cy - 0.5f * r);
                path.lineTo(cx + 0.02f * r, cy + 0.5f * r); path.lineTo(cx - 0.3f * r, cy + 0.2f * r); path.lineTo(cx - 0.55f * r, cy + 0.2f * r); path.close();
                cv.drawPath(path, p);
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(r * 0.11f);
                rect.set(cx - 0.26f * r, cy - 0.28f * r, cx + 0.3f * r, cy + 0.28f * r); cv.drawArc(rect, -45, 90, false, p);
                rect.set(cx - 0.5f * r, cy - 0.52f * r, cx + 0.54f * r, cy + 0.52f * r); cv.drawArc(rect, -45, 90, false, p);
            }
        }
    }
}
