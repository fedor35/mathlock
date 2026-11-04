package dev.gubenkov.readbench;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.TextPaint;
import android.view.View;
import android.text.style.ForegroundColorSpan;
import android.text.style.UnderlineSpan;
import android.util.Log;
import android.view.Gravity;
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

import dev.gubenkov.reading.AdaptiveLevel;
import dev.gubenkov.reading.CtcModel;
import dev.gubenkov.reading.ReadingStats;
import dev.gubenkov.reading.ReadingMatcher;
import dev.gubenkov.reading.ReadingText;
import dev.gubenkov.reading.SpeakerId;
import dev.gubenkov.reading.SpeakerModel;
import dev.gubenkov.reading.StreamingRecognizer;

/**
 * Живое чтение: текст из texts.jsonl (getExternalFilesDir), микрофон → потоковая сверка → подсветка.
 * Записи для дообучения: files/recordings/<время>_<id>.wav + .json (гипотеза, состояния слов).
 * Озвучка: files/tts/<id>.<голос>.ogg|wav.
 */
public class ReadActivity extends Activity {
    static final String TAG = "ReadBench";
    static final int SR = 16000;
    int[] SYL = {Color.rgb(25, 60, 160), Color.rgb(190, 40, 40)};

    TextView textView, status, debug, badge;
    final List<JSONObject> texts = new ArrayList<>();
    int idx = 0;
    ReadingText cur;
    ReadingMatcher.Result lastResult;
    CtcModel model;
    volatile boolean recording;
    MediaPlayer player;
    int voiceIdx = 0;
    // последнее чтение: звук и время каждой буквы гипотезы — для «послушать себя» и нажатия на слово
    float[] lastAudio;
    int[] lastLetterFrames;
    AudioTrack track;
    KidUi.Palette pal;
    KidUi.IconButton mic, me, compare, listenBtn, nextBtn, trophy;
    /** Раунд дуэли (DuelActivity): текст задан, после чтения — результат и возврат. */
    boolean duel;
    KidUi.ConfettiView confetti;
    TextView levelView;
    // адаптивная сложность: журнал попыток чтеца files/progress-<id>.tsv, уровень выводится из него (AdaptiveLevel)
    AdaptiveLevel adapt;
    // чтецы: тихое опознание по голосу после каждого чтения (Profiles, SpeakerId); без модели — общий progress.tsv
    Profiles profiles;
    SpeakerModel spk;
    SpeakerId.Profile reader;   // последний опознанный — по нему уровень и следующий текст
    boolean challenge;
    int textLevel = 1;
    LiveTts live;   // озвучка на телефоне (RHVoice), файлы tts/<id>.rhvoice.wav + .json
    /** С какого уровня начинать без истории: сын читает целыми словами 30–50 слов/мин (04.11.2026). */
    static final int START_LEVEL = 5;

    int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    /** Детский экран: текст в карточке, одна большая кнопка-микрофон, мелкие круглые кнопки без подписей.
     *  Отладочная строка скрыта — долгое нажатие на микрофон показывает/прячет её. */
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        pal = KidUi.Palette.of(this);
        SYL = new int[]{pal.syl0, pal.syl1};
        getWindow().setStatusBarColor(pal.bg); getWindow().setNavigationBarColor(pal.bg);
        if (!pal.night) getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        FrameLayout root = new FrameLayout(this); root.setBackgroundColor(pal.bg);
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(dp(20), dp(28), dp(20), dp(20));

        textView = new TextView(this); textView.setTextSize(36); textView.setLineSpacing(0, 1.3f);
        textView.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL)); textView.setTextColor(pal.text);
        textView.setGravity(Gravity.CENTER_VERTICAL | Gravity.START); textView.setPadding(dp(22), dp(22), dp(22), dp(22));
        GradientDrawable card = new GradientDrawable(); card.setColor(pal.card); card.setCornerRadius(dp(28));
        textView.setBackground(card); textView.setElevation(dp(2));
        textView.setMovementMethod(android.text.method.ScrollingMovementMethod.getInstance());
        textView.setVerticalScrollBarEnabled(true);
        // палец на тексте — суфлёр уступает (иначе он каждый кадр возвращает позицию и текст «не листается»)
        // долгое нажатие на слово — «ты → как надо» (обычный тап не занят: им листают текст)
        final android.view.GestureDetector words = new android.view.GestureDetector(this, new android.view.GestureDetector.SimpleOnGestureListener() {
            @Override public void onLongPress(android.view.MotionEvent e) {
                int w = wordAt(e.getX(), e.getY());
                if (w >= 0) { textView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); playWord(w); }
            }
        });
        textView.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN || e.getActionMasked() == android.view.MotionEvent.ACTION_MOVE) prompter.userTouch();
            words.onTouchEvent(e);
            return false;
        });
        textView.setHighlightColor(Color.TRANSPARENT);
        textView.setDefaultFocusHighlightEnabled(false);   // иначе фокус красит карточку серым прямоугольником

        status = new TextView(this); status.setTextSize(19); status.setGravity(Gravity.CENTER); status.setTextColor(pal.status);
        status.setPadding(0, dp(18), 0, dp(14));

        debug = new TextView(this); debug.setTextSize(11); debug.setTextColor(pal.debug); debug.setVisibility(View.GONE);
        debug.setPadding(0, dp(6), 0, 0);

        // второстепенные действия: послушать, я, сравнить, дальше — иконками
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER);
        listenBtn = new KidUi.IconButton(this, KidUi.Icon.SPEAKER, pal.chip, pal.chipIcon);
        me = new KidUi.IconButton(this, KidUi.Icon.PERSON, pal.chip, pal.chipIcon);
        compare = new KidUi.IconButton(this, KidUi.Icon.COMPARE, pal.chip, pal.chipIcon);
        nextBtn = new KidUi.IconButton(this, KidUi.Icon.NEXT, pal.nextChip, pal.nextIcon);
        for (View v : new View[]{listenBtn, me, compare, nextBtn}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(60), dp(60)); lp.setMargins(dp(10), 0, dp(10), 0); row.addView(v, lp);
        }
        me.setOnClickListener(v -> playMine(0, lastAudio == null ? 0 : lastAudio.length, null));
        compare.setOnClickListener(v -> playMine(0, lastAudio == null ? 0 : lastAudio.length, this::listen));
        listenBtn.setOnClickListener(v -> listen());
        nextBtn.setOnClickListener(v -> { recording = false; if (duel) finish(); else { pickNext(); show(); } });

        // главная кнопка: микрофон; во время чтения — красная, пульсирует, иконка «стоп»
        mic = new KidUi.IconButton(this, KidUi.Icon.MIC, pal.accent, Color.WHITE);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(dp(150), dp(150)); mlp.gravity = Gravity.CENTER_HORIZONTAL; mlp.topMargin = dp(10);
        mic.setOnClickListener(v -> {
            if (recording) { recording = false; return; }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1); return;
            }
            new Thread(this::readLoop).start();
        });
        mic.setOnLongClickListener(v -> { debug.setVisibility(debug.getVisibility() == View.GONE ? View.VISIBLE : View.GONE); return true; });

        levelView = new TextView(this); levelView.setTextSize(15); levelView.setTextColor(pal.status); levelView.setGravity(Gravity.CENTER);
        levelView.setPadding(0, 0, 0, dp(10));
        // скрытый вход для родителей: долгое нажатие на строку уровня
        levelView.setOnLongClickListener(v -> { startActivity(new android.content.Intent(this, ParentActivity.class)); return true; });
        // строка уровня: по центру текст, справа небольшая кнопка-кубок — меню «Соревнования»
        FrameLayout top = new FrameLayout(this);
        top.addView(levelView, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));
        trophy = new KidUi.IconButton(this, KidUi.Icon.TROPHY, pal.trophyChip, pal.trophyIcon);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.END | Gravity.CENTER_VERTICAL); tlp.bottomMargin = dp(10);
        top.addView(trophy, tlp);
        trophy.setOnClickListener(v -> startActivity(new android.content.Intent(this, CompeteActivity.class)));
        duel = getIntent().getBooleanExtra("duel", false);
        if (duel) trophy.setVisibility(View.GONE);
        l.addView(top); l.addView(textView, new LinearLayout.LayoutParams(-1, 0, 1)); l.addView(status); l.addView(row); l.addView(mic, mlp); l.addView(debug);
        root.addView(l, new FrameLayout.LayoutParams(-1, -1));
        confetti = new KidUi.ConfettiView(this); root.addView(confetti, new FrameLayout.LayoutParams(-1, -1));

        // «Молодец!» — крупная плашка, выпрыгивает и гаснет
        badge = new TextView(this); badge.setText("Молодец!"); badge.setTextSize(44); badge.setTextColor(Color.WHITE);
        badge.setTypeface(Typeface.create("sans-serif", Typeface.BOLD)); badge.setPadding(dp(36), dp(18), dp(36), dp(18));
        GradientDrawable bd = new GradientDrawable(); bd.setColor(pal.success); bd.setCornerRadius(dp(40)); badge.setBackground(bd);
        badge.setElevation(dp(8)); badge.setVisibility(View.GONE);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2); blp.gravity = Gravity.CENTER; root.addView(badge, blp);

        // «Новые слова» — карточка снизу после успешного чтения: слово и пояснение, тап — пояснение вслух (RHVoice)
        wordsCard = new LinearLayout(this); wordsCard.setOrientation(LinearLayout.VERTICAL);
        wordsCard.setPadding(dp(22), dp(16), dp(22), dp(18));
        GradientDrawable wc = new GradientDrawable(); wc.setColor(pal.card); wc.setCornerRadius(dp(28)); wordsCard.setBackground(wc);
        wordsCard.setElevation(dp(10)); wordsCard.setVisibility(View.GONE);
        FrameLayout.LayoutParams wlp = new FrameLayout.LayoutParams(-1, -2); wlp.gravity = Gravity.BOTTOM;
        wlp.setMargins(dp(14), 0, dp(14), dp(18)); root.addView(wordsCard, wlp);
        setContentView(root);

        // папки создаёт само приложение: созданные через adb (владелец shell) оно не читает
        new File(dir(), "tts").mkdirs(); new File(dir(), "recordings").mkdirs();
        loadTexts();
        profiles = new Profiles(dir());
        loadProgress();
        live = new LiveTts(this, LiveTts.RHVOICE);
        if (getIntent().hasExtra("text_id")) { idx = Math.max(0, indexOf(getIntent().getStringExtra("text_id"))); }
        else if (getIntent().hasExtra("idx")) idx = getIntent().getIntExtra("idx", 0) % Math.max(1, texts.size());
        else pickNext();
        show();
        new Thread(() -> {
            loadModel();
            // отладка: am start … --es spk_wav file.wav — эмбеддинг файла в logcat (сверка с Python)
            if (getIntent().hasExtra("spk_wav")) spkTest(getIntent().getStringExtra("spk_wav"));
            if (getIntent().hasExtra("wav")) readLoop();
            // отладка: am start … --ez tts_test true — озвучить текущий текст на телефоне, замерить и разметить слова
            if (getIntent().getBooleanExtra("tts_test", false)) {
                for (int i = 0; i < 50 && !live.ready(); i++) try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                runOnUiThread(this::listenLive);
            }
        }).start();
    }

    void setListening(boolean on) {
        mic.set(on ? KidUi.Icon.STOP : KidUi.Icon.MIC, on ? pal.listening : pal.accent, Color.WHITE);
        mic.setPulsing(on);
    }

    File dir() { return getExternalFilesDir(null); }

    void loadTexts() {
        File f = new File(dir(), "texts.jsonl");
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line; while ((line = r.readLine()) != null) if (!line.trim().isEmpty()) texts.add(new JSONObject(line));
        } catch (Exception e) { Log.w(TAG, "texts.jsonl: " + e); }
        if (texts.isEmpty()) try {
            texts.add(new JSONObject().put("id", "demo-1").put("text", "Ма́ма мы́ла ра́му."));
            texts.add(new JSONObject().put("id", "demo-2").put("text", "Ко́т спи́т на ди́ване."));
        } catch (Exception ignored) {}
    }

    void loadModel() {
        File d = dir();
        try {
            long t = System.nanoTime();
            File htp = new File(d, "gigaam_ctc_T1600_fp32.onnx");
            if (htp.exists()) model = new CtcModel(htp.getPath(), CtcModel.Backend.QNN_HTP, 1600, d.getPath());
            else model = new CtcModel(new File(d, "gigaam_ctc_int8.onnx").getPath(), CtcModel.Backend.CPU, 0, null);
            String s = "модель " + model.backend + " за " + (System.nanoTime() - t) / 1000000 + " мс";
            Log.i(TAG, s); runOnUiThread(() -> debug.setText(s));
        } catch (Throwable e) {
            Log.e(TAG, "model", e);
            try { model = new CtcModel(new File(d, "gigaam_ctc_int8.onnx").getPath(), CtcModel.Backend.CPU, 0, null); }
            catch (Throwable e2) { runOnUiThread(() -> debug.setText("нет модели: " + e2)); }
        }
        File sf = new File(d, SpeakerModel.FILE);
        if (sf.exists()) try {
            long t = System.nanoTime(); spk = new SpeakerModel(sf.getPath());
            Log.i(TAG, "голосовая модель за " + (System.nanoTime() - t) / 1000000 + " мс, профилей " + profiles.list.size());
        } catch (Throwable e) { Log.e(TAG, "голосовая модель", e); }
        else Log.i(TAG, "нет " + SpeakerModel.FILE + " — без опознания чтецов, журнал progress.tsv");
    }

    void spkTest(String name) {
        try {
            float[] x = BenchActivity.readWav16k(new File(dir(), name));
            SpeakerModel.Result r = null;
            for (int i = 0; i < 4; i++) r = spk.embed(x, x.length);   // первый прогон — прогрев
            StringBuilder sb = new StringBuilder();
            for (float v : r.emb) sb.append(String.format(java.util.Locale.ROOT, "%.6f,", v));
            Log.i(TAG, String.format(java.util.Locale.ROOT, "spk_test %s: звук %.1f с, речь %.1f с, кадров %d, признаки %d мс, модель %d мс | %s",
                    name, x.length / (float) SR, r.speechS, r.frames, r.featMs, r.modelMs, sb));
        } catch (Throwable e) { Log.e(TAG, "spk_test", e); }
    }

    String curId() { return texts.get(idx).optString("id"); }

    int indexOf(String id) { for (int i = 0; i < texts.size(); i++) if (texts.get(i).optString("id").equals(id)) return i; return -1; }

    /** Общий журнал — только без голосовой модели (как было до профилей). */
    File progressFile() { return new File(dir(), "progress.tsv"); }

    /** Уровень текущего чтеца: последний опознанный; профилей ещё нет — стартовый уровень с пустой историей. */
    void loadProgress() {
        reader = profiles.last();
        if (!new File(dir(), SpeakerModel.FILE).exists()) adapt = AdaptiveLevel.parse(START_LEVEL, Profiles.lines(progressFile()));
        else if (reader != null) adapt = profiles.level(reader);
        else adapt = new AdaptiveLevel(START_LEVEL);
        Log.i(TAG, "чтец " + (reader == null ? "—" : reader.id + " «" + reader.name + "»") + ": " + adapt.history.size()
                + " попыток, уровень " + adapt.level() + ", профилей " + profiles.list.size());
    }

    @Override protected void onRestart() {   // вернулись из родительского экрана: имена, объединения, переразметка
        super.onRestart();
        profiles.load(); loadProgress(); showLevel();
    }

    /** Следующий текст по адаптивному уровню; каждый 5-й — «вызов» на уровень выше. */
    void pickNext() {
        List<String> ids = new ArrayList<>(); List<Integer> lv = new ArrayList<>();
        for (JSONObject t : texts) { ids.add(t.optString("id")); lv.add(t.optInt("level", 1)); }
        int level = adapt.level();
        challenge = adapt.nextIsChallenge();
        int i = adapt.pick(ids, lv, challenge ? level + 1 : level, rnd);
        if (challenge && i >= 0 && lv.get(i) != level + 1) challenge = false;   // старшего уровня ещё нет в корпусе
        idx = i >= 0 ? i : (idx + 1) % texts.size();
        Log.i(TAG, "уровень " + level + (challenge ? ", вызов" : "") + " → " + ids.get(idx));
    }

    void showLevel() {
        int level = adapt.level(); float best = adapt.best(level);
        if (duel) { levelView.setText("Дуэль · уровень " + textLevel); return; }
        String s = "Уровень " + level + (challenge ? " · ★ трудный текст" : "")
                + (best > 0 ? String.format(java.util.Locale.ROOT, " · рекорд %.0f сл/мин", best) : "")
                + String.format(java.util.Locale.ROOT, " · цель %.0f", level >= AdaptiveLevel.MAX ? AdaptiveLevel.TOP_TARGET : AdaptiveLevel.target(level));
        levelView.setText(s);
    }

    /**
     * Чей голос: эмбеддинг записи → косинусы ко всем профилям → MATCH (его журнал, профиль дообучается),
     * NEW (новый «Чтец N», только по засчитанной попытке), иначе «не определён». Ребёнку ничего не показывается.
     */
    SpeakerId.Decision identify(float[] audio, ReadingStats st) {
        if (spk == null || audio == null) return null;
        try {
            SpeakerModel.Result r = spk.embed(audio, audio.length);
            if (r == null) { Log.i(TAG, "голос: речи нет"); return null; }
            SpeakerId.Decision d = SpeakerId.decide(r.emb, r.speechS, profiles.list);
            Log.i(TAG, String.format(java.util.Locale.ROOT, "голос: признаки %d мс, модель %d мс (%d кадров); ", r.featMs, r.modelMs, r.frames)
                    + d.describe(profiles.list) + String.format(java.util.Locale.ROOT, ", P(ребёнок)=%.2f", SpeakerId.kidProbability(r.emb)));
            lastEmb = r.emb;
            return d;
        } catch (Throwable e) { Log.e(TAG, "опознание", e); return null; }
    }
    float[] lastEmb;

    /** Что читали: текст, его уровень и «вызов» — на момент начала чтения. */
    static final class Read {
        final String id; final ReadingText text; final int level; final boolean challenge;
        Read(String id, ReadingText text, int level, boolean challenge) { this.id = id; this.text = text; this.level = level; this.challenge = challenge; }
    }

    /** @return опознанный чтец (null — не определён или без голосовой модели). */
    SpeakerId.Profile record(Read w, ReadingStats st, SpeakerId.Decision d, String rec) {
        SpeakerId.Profile who = null;
        if (d != null && d.kind == SpeakerId.Kind.MATCH) who = d.profile;
        else if (d != null && d.kind == SpeakerId.Kind.NEW && st.counts()) who = profiles.create(lastEmb);
        if (who != null) {
            if (d.kind == SpeakerId.Kind.MATCH) { SpeakerId.learn(who, lastEmb, false); profiles.save(who); }
            if (reader != who) { reader = who; adapt = profiles.level(who); }
            profiles.setLast(who.id);
        }
        if (!st.counts()) {
            Log.i(TAG, "попытка не засчитана (дочитано " + st.reached + "/" + st.words + "): " + st);
            runOnUiThread(this::showLevel); return who;
        }
        AdaptiveLevel.Attempt a = new AdaptiveLevel.Attempt(System.currentTimeMillis(), w.id, w.level, st.wcpm(), st.accuracy(), w.challenge, rec);
        String msg = String.format(java.util.Locale.ROOT, "%.0f слов в минуту", st.wcpm());
        if (spk == null) {   // без голосовой модели — один общий журнал, как раньше
            int before = adapt.level(); float best = adapt.best(w.level);
            adapt.history.add(a);
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(progressFile(), true)) {
                o.write((a.toTsv() + "\n").getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) { Log.w(TAG, "progress.tsv: " + e); }
            msg += levelNews(st, before, best);
        } else if (who == null) {   // не определён: отдельный журнал, уровень не меняется
            profiles.append(null, a);
            Log.i(TAG, "попытка: " + st + " → чтец не определён (progress-unknown.tsv)");
        } else {
            int before = adapt.level(); float best = adapt.best(w.level);
            adapt.history.add(a); profiles.append(who.id, a);
            msg += levelNews(st, before, best);
            Log.i(TAG, "попытка: " + st + (w.challenge ? " (вызов)" : "") + " → " + who.id + ", уровень " + before + " → " + adapt.level());
            if (d.kind == SpeakerId.Kind.NEW) { int n = profiles.resortUnknown(); if (n > 0) adapt = profiles.level(who); }
        }
        final String m = msg;
        runOnUiThread(() -> { status.setText(status.getText() + "\n" + m); showLevel(); });
        return who;
    }

    String levelNews(ReadingStats st, int before, float best) {
        int after = adapt.level(); String msg = "";
        if (st.accuracy() >= 0.95f && st.wcpm() > best && best > 0) msg += " — новый рекорд!";
        if (after > before) msg += "\nНовый уровень " + after + "!";
        else if (after < before) msg += "\nПотренируемся на уровне " + after;
        return msg;
    }

    void show() {
        if (wordsCard != null) { wordsCard.setVisibility(View.GONE); if (live != null) live.stop(); }
        if (nextBtn != null) nextBtn.setPulsing(false);
        cur = new ReadingText(texts.get(idx).optString("text"));
        textLevel = texts.get(idx).optInt("level", 1);
        showLevel();
        // длинный текст — мельче шрифт, чтобы помещался в карточку
        textView.setTextSize(cur.words.size() <= 10 ? 36 : cur.words.size() <= 45 ? 28 : cur.words.size() <= 90 ? 22 : 19);
        textView.setGravity((cur.words.size() <= 45 ? Gravity.CENTER_VERTICAL : Gravity.TOP) | Gravity.START);
        lastResult = null; lastAudio = null; lastLetterFrames = null;
        me.setEnabled(false); compare.setEnabled(false);
        status.setText("Нажми на микрофон и читай вслух");
        render();
    }

    /** Слоги — чередующимся цветом, ударение — акутом из исходного текста; фон — состояние слова. */
    // Текст собирается один раз на текст (слоги, нажатия, ударения), а при обновлении подсветки меняются только
    // спаны слов, чьё состояние изменилось. Пересборка всего текста каждые 0,4 с давала моргание: блоки
    // отрисовки DynamicLayout иногда показывали кадр без фона у целого куска строк.
    ReadingText shownFor;
    android.text.Spannable shownText;
    int[] shownState;
    Object[][] stateSpans;

    void render() {
        if (shownFor != cur) {
            SpannableStringBuilder sb = new SpannableStringBuilder(cur.source);
            for (int w = 0; w < cur.words.size(); w++) {
                ReadingText.Word wd = cur.words.get(w);
                int pos = wd.start, s = 0;
                for (String syl : wd.syllables) {
                    int st = pos, left = syl.length();
                    while (pos < wd.end && left > 0) { char c = cur.source.charAt(pos); if (c != ReadingText.ACUTE && c != '-') left--; pos++; }
                    while (pos < wd.end && (cur.source.charAt(pos) == ReadingText.ACUTE)) pos++;
                    if (AdaptiveLevel.syllableColors(textLevel)) sb.setSpan(new ForegroundColorSpan(SYL[s++ % 2]), st, pos, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    if (pos < wd.end && cur.source.charAt(pos) == '-') pos++;
                }
                // нажатие на слово («ты → как надо», playWord) временно убрано: мешало прокрутке пальцем (Фёдор, 04.11)
            }
            if (!AdaptiveLevel.stressMarks(textLevel))   // старшие уровни — как в книге, без ударений (акут — нулевой ширины, прячем цветом)
                for (int i = 0; i < cur.source.length(); i++)
                    if (cur.source.charAt(i) == ReadingText.ACUTE) sb.setSpan(new ForegroundColorSpan(Color.TRANSPARENT), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            textView.setText(sb, TextView.BufferType.SPANNABLE);
            shownText = (android.text.Spannable) textView.getText();
            shownState = new int[cur.words.size()];
            stateSpans = new Object[cur.words.size()][];
            shownFor = cur;
        }
        // текущее (жёлтое) слово — первое ещё не засчитанное: слово, на котором оборвался поток, тоже «не засчитано»,
        // и раньше жёлтым становилось следующее за ним, а само оно оставалось белым («белое — жёлтое — белое»)
        int cw = -1;
        if (lastResult != null && recording)
            for (int w = 0; w < cur.words.size(); w++) if (lastResult.states[w] == ReadingMatcher.State.PENDING) { cw = w; break; }
        for (int w = 0; w < cur.words.size(); w++) {
            int st = 0;   // 0 — без фона, 1 — верно, 2 — ошибка, 3 — текущее
            if (lastResult != null) {
                ReadingMatcher.State state = lastResult.states[w];
                st = state == ReadingMatcher.State.OK ? 1 : state == ReadingMatcher.State.WRONG ? 2 : w == cw ? 3 : 0;
            }
            if (st == shownState[w]) continue;
            if (stateSpans[w] != null) for (Object o : stateSpans[w]) shownText.removeSpan(o);
            ReadingText.Word wd = cur.words.get(w);
            Object[] add = st == 1 ? new Object[]{new BackgroundColorSpan(pal.okBg)}
                    : st == 2 ? new Object[]{new BackgroundColorSpan(pal.wrongBg), new UnderlineSpan()}
                    : st == 3 ? new Object[]{new BackgroundColorSpan(pal.curBg)} : null;
            if (add != null) for (Object o : add) shownText.setSpan(o, wd.start, wd.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            stateSpans[w] = add; shownState[w] = st;
        }
        // телесуфлёр: текст едет непрерывно со скоростью чтения; цель — текущее слово на трети высоты карточки,
        // со сдвигом внутри строки по доле пройденного (x слова / ширина строки)
        // во время чтения — текущее место; после чтения и при перечитывании — первое не засчитанное (красное) слово
        int tw = -1;
        if (lastResult != null) {
            if (recording && !repairing && cw >= 0) tw = cw;
            else for (int w = 0; w < cur.words.size(); w++) if (lastResult.states[w] != ReadingMatcher.State.OK) { tw = w; break; }
        }
        if (tw >= 0 && textView.getLayout() != null) {
            android.text.Layout lay = textView.getLayout();
            int off = cur.words.get(tw).start;
            int line = lay.getLineForOffset(off);
            float w = Math.max(1, lay.getLineRight(line) - lay.getLineLeft(line));
            float frac = Math.max(0, Math.min(1, (lay.getPrimaryHorizontal(off) - lay.getLineLeft(line)) / w));
            int lh = lay.getLineBottom(line) - lay.getLineTop(line);
            float y = lay.getLineTop(line) + frac * lh + textView.getTotalPaddingTop() - textView.getHeight() / 3f;
            int maxY = Math.max(0, lay.getHeight() + textView.getTotalPaddingTop() + textView.getTotalPaddingBottom() - textView.getHeight());
            prompter.target(Math.max(0, Math.min(y, maxY)));
        } else if (lastResult == null) prompter.reset();
        else prompter.hold();
    }

    /** Индекс слова под точкой касания (координаты view) или -1. */
    int wordAt(float x, float y) {
        if (cur == null || textView.getLayout() == null) return -1;
        int off = textView.getOffsetForPosition(x, y);
        for (int w = 0; w < cur.words.size(); w++) { ReadingText.Word wd = cur.words.get(w); if (off >= wd.start && off <= wd.end) return w; }
        return -1;
    }

    LinearLayout wordsCard;

    /** Новые слова текста (new_words из корпуса): показываем после успешного чтения, пояснение озвучивается по тапу. */
    void showNewWords() {
        org.json.JSONArray nw = texts.get(idx).optJSONArray("new_words");
        if (nw == null || nw.length() == 0 || duel) return;
        boolean marks = AdaptiveLevel.stressMarks(textLevel);
        wordsCard.removeAllViews();
        LinearLayout head = new LinearLayout(this); head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this); title.setText(nw.length() == 1 ? "Новое слово" : "Новые слова");
        title.setTextSize(20); title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD)); title.setTextColor(pal.text);
        TextView close = new TextView(this); close.setText("✕"); close.setTextSize(22); close.setTextColor(pal.status);
        close.setPadding(dp(12), dp(4), dp(4), dp(4));
        close.setOnClickListener(v -> { live.stop(); wordsCard.setVisibility(View.GONE); });
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1)); head.addView(close);
        wordsCard.addView(head);
        for (int i = 0; i < nw.length(); i++) {
            JSONObject o = nw.optJSONObject(i); if (o == null) continue;
            final String explain = o.optString("explain");
            TextView row = new TextView(this);
            String word = o.optString("word"), ex = explain;
            if (!marks) { word = LiveTts.plain(word); ex = LiveTts.plain(ex); }
            SpannableStringBuilder sb = new SpannableStringBuilder("🔊 " + word + "\n" + ex);
            sb.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), 3, 3 + word.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ForegroundColorSpan(pal.accent), 3, 3 + word.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            row.setText(sb); row.setTextSize(18); row.setTextColor(pal.text); row.setLineSpacing(0, 1.15f);
            row.setPadding(0, dp(10), 0, dp(4));
            row.setOnClickListener(v -> { Log.i(TAG, "пояснение вслух: " + LiveTts.plain(explain)); live.speak(explain); });
            wordsCard.addView(row);
        }
        wordsCard.setVisibility(View.VISIBLE); wordsCard.setAlpha(0f); wordsCard.setTranslationY(dp(60));
        wordsCard.animate().alpha(1f).translationY(0).setDuration(350).start();
        Log.i(TAG, "новые слова: " + nw.length());
    }

    final Prompter prompter = new Prompter();

    /**
     * Телесуфлёр: позиция прокрутки догоняет цель с постоянной скоростью, а не рывками.
     * Скорость = оценка темпа (скорость роста цели между обновлениями подсветки, сглаженная) + поправка
     * на отставание. Нет прогресса дольше 1,2 с — темп плавно гаснет и текст замирает у цели.
     * Назад не едет, кроме случая, когда ушёл вперёд больше чем на строку.
     */
    final class Prompter implements android.view.Choreographer.FrameCallback {
        float y, v, base, goal; long lastGoalNs, lastFrameNs; boolean running;
        /** Палец на тексте: суфлёр уступает ручной прокрутке и возвращается к чтению через USER_PAUSE без касаний. */
        long userUntil;
        static final long USER_PAUSE_MS = 4000;

        boolean userHolds() { return android.os.SystemClock.uptimeMillis() < userUntil; }
        void userTouch() {
            userUntil = android.os.SystemClock.uptimeMillis() + USER_PAUSE_MS;
            v = 0;
            textView.removeCallbacks(resume); textView.postDelayed(resume, USER_PAUSE_MS + 50);
        }
        final Runnable resume = () -> { y = textView.getScrollY(); if (goal != y) start(); };

        void target(float g) {
            long now = System.nanoTime();
            if (lastGoalNs != 0 && g > goal) {
                float dt = (now - lastGoalNs) / 1e9f;
                if (dt > 0.05f) { float rate = (g - goal) / dt; base = base == 0 ? rate : base * 0.7f + rate * 0.3f; }
            }
            if (g != goal || lastGoalNs == 0) { if (g > goal || lastGoalNs == 0) lastGoalNs = now; goal = g; }
            if (lastGoalNs == 0) lastGoalNs = now;
            if (userHolds()) return;
            textView.scrollTo(0, Math.round(y));   // setText не должен сбивать позицию
            start();
        }
        void hold() { if (!userHolds()) textView.scrollTo(0, Math.round(y)); }
        void reset() { running = false; y = v = base = goal = 0; lastGoalNs = 0; textView.scrollTo(0, 0); }
        void start() { if (!running) { running = true; lastFrameNs = 0; android.view.Choreographer.getInstance().postFrameCallback(this); } }

        @Override public void doFrame(long ns) {
            if (!running) return;
            if (userHolds()) { running = false; return; }
            float dt = lastFrameNs == 0 ? 0.016f : Math.min(0.05f, (ns - lastFrameNs) / 1e9f); lastFrameNs = ns;
            float idle = (System.nanoTime() - lastGoalNs) / 1e9f;
            if (idle > 1.2f) base *= (float) Math.exp(-dt / 0.6f);           // ребёнок остановился — плавно тормозим
            float err = goal - y, want;
            if (err >= 0) want = base + 1.5f * err;                          // догоняем: темп + отставание
            else want = err < -textView.getLineHeight() ? 2f * err : 0f;     // впереди цели — ждём (назад только при большом уходе)
            v += (want - v) * Math.min(1f, dt * 3f);                         // инерция: без рывков скорости
            if (err > 0 && v * dt > err && base < 1f) v = err / dt;          // не перелетать цель на остановке
            y = Math.max(0, y + v * dt);
            textView.scrollTo(0, Math.round(y));
            if (Math.abs(err) < 0.5f && Math.abs(v) < 0.5f && idle > 3f) { running = false; return; }
            android.view.Choreographer.getInstance().postFrameCallback(this);
        }
    }

    /** Идёт перечитывание красных слов (новая запись дописывает к прошлому результату этого же текста). */
    volatile boolean repairing;
    /** Отладка перечитывания: --es wav2 file.wav — после первой записи через 3 с подать вторую (как повторное нажатие микрофона). */
    String debugWav;
    boolean wav2Done;
    /** После конца текста (или в перечитывании) — столько тишины без новых букв, и запись останавливается сама. */
    static final long AUTO_STOP_MS = 2500;

    void readLoop() {
        if (model == null) { runOnUiThread(() -> status.setText("Подожди, готовлюсь…")); return; }
        // текст уже читали и остались красные или непрочитанные слова — это перечитывание: зелёное остаётся зелёным
        final ReadingMatcher.Result prev = (!duel && lastResult != null && !lastResult.complete && shownFor == cur) ? lastResult : null;
        repairing = prev != null;
        recording = true;
        int chunk = SR / 10;
        // отладка: am start … --es wav file.wav — подать файл в темпе реального времени вместо микрофона
        String wavName = debugWav != null ? debugWav : getIntent().getStringExtra("wav");
        debugWav = null;
        float[] wav = null;
        if (wavName != null) try { wav = BenchActivity.readWav16k(new File(dir(), wavName)); } catch (Exception e) { Log.e(TAG, "wav", e); }
        int wavPos = 0;
        AudioRecord ar = wav != null ? null : new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SR,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, SR * 2);
        StreamingRecognizer rec = new StreamingRecognizer(model);
        ReadingMatcher matcher = new ReadingMatcher();
        // что читали — запоминаем: опознание голоса идёт после, а ребёнок к тому времени может нажать «дальше»
        final Read what = new Read(curId(), cur, textLevel, challenge);
        short[] s = new short[chunk]; float[] f = new float[chunk];
        runOnUiThread(() -> { status.setText(prev != null ? "Прочитай красные слова" : "Слушаю…"); setListening(true); render(); });
        if (ar != null) ar.startRecording();
        long lastDecode = 0; String hyp = ""; long decMs = 0; boolean done = false;
        int lastLen = 0; long lastChange = System.currentTimeMillis();
        long maxSamples = SR * Math.min(600L, Math.max(120L, cur.words.size() * 4L));   // до 15 слов/мин
        try {
            while (recording && rec.samples() < maxSamples) {
                int r;
                if (wav != null) {
                    r = Math.min(chunk, wav.length - wavPos); if (r <= 0) break;
                    System.arraycopy(wav, wavPos, f, 0, r); wavPos += r; Thread.sleep(100);
                } else {
                    r = ar.read(s, 0, chunk); if (r <= 0) break;
                    for (int i = 0; i < r; i++) f[i] = s[i] / 32768f;
                }
                rec.feed(f, r);
                long now = System.currentTimeMillis();
                if (now - lastDecode >= 400 && rec.samples() > SR / 2) {
                    lastDecode = now;
                    long a = System.nanoTime(); hyp = rec.decode(); decMs = (System.nanoTime() - a) / 1000000;
                    ReadingMatcher.Result res = matcher.match(cur, hyp, false, prev);
                    int len = hyp.replace(" ", "").length();
                    if (len != lastLen) { lastLen = len; lastChange = now; }
                    final String h = hyp; final long dm = decMs; final float sec = rec.samples() / (float) SR;
                    Log.i(TAG, String.format("live %.1fs dec %dms progress %d/%d | %s", sec, dm, res.progressWords, cur.words.size(), h));
                    runOnUiThread(() -> { lastResult = res; render(); debug.setText(String.format("%.1f с, %d мс | %s", sec, dm, h)); });
                    if (res.complete) { done = true; break; }
                    // дочитал до конца (или перечитал, что хотел) и молчит — заканчиваем сами, красные слова покажем
                    boolean atEnd = prev == null ? res.progressWords >= cur.words.size() : len > 0;
                    if (atEnd && now - lastChange > AUTO_STOP_MS) { Log.i(TAG, "автостоп: тишина после " + (prev == null ? "конца текста" : "перечитывания")); break; }
                }
            }
        } catch (Throwable e) { Log.e(TAG, "read", e); }
        if (ar != null) { ar.stop(); ar.release(); }
        recording = false;
        try { hyp = rec.decode(); } catch (Throwable ignored) {}
        ReadingMatcher.Result fin = matcher.match(cur, hyp, true, prev);
        final boolean ok = fin.complete || done;
        final String h = hyp;
        int[] lfs = new int[rec.letterFrames.size()];
        for (int i = 0; i < lfs.length; i++) lfs[i] = rec.letterFrames.get(i);
        final ReadingStats stats = ReadingStats.of(fin, lfs, StreamingRecognizer.FRAME_SEC);
        final float[] audio = rec.audio();
        final int[] lf = new int[rec.letterFrames.size()];
        for (int i = 0; i < lf.length; i++) lf[i] = rec.letterFrames.get(i);
        runOnUiThread(() -> {
            lastResult = fin; lastAudio = audio; lastLetterFrames = lf; repairing = false;
            me.setEnabled(true); compare.setEnabled(true); setListening(false);
            render();
            status.setText(ok ? "Молодец! Всё верно" : "Почти! Прочитай красные слова ещё раз");
            debug.setText(h);
            if (ok && !duel) nextBtn.setPulsing(true);   // подсказка: можно к следующему тексту
            if (ok) { celebrate(); final int at = idx; textView.postDelayed(() -> { if (idx == at && !recording) showNewWords(); }, 2300); }
        });
        // чей голос — уже после того, как ребёнок увидел результат; запись, журнал и уровень — по опознанному чтецу
        SpeakerId.Decision d = identify(audio, stats);
        String base = save(what, rec, h, fin, ok, d);
        // перечитывание красных слов — не новая попытка: темп и точность считаются по первому прочтению текста
        SpeakerId.Profile who = prev == null ? record(what, stats, d, base) : null;
        if (prev != null) Log.i(TAG, "перечитывание: " + (ok ? "все слова верно" : "остались красные") + ", в журнал не пишется");
        if (!wav2Done && getIntent().hasExtra("wav2")) {
            wav2Done = true; debugWav = getIntent().getStringExtra("wav2");
            textView.postDelayed(() -> new Thread(this::readLoop).start(), 3000);
        }
        if (duel) {   // раунд дуэли: результат — в DuelActivity, экран закрывается сам (ребёнок успевает увидеть подсветку и конфетти)
            android.content.Intent res = new android.content.Intent().putExtra("wcpm", stats.wcpm()).putExtra("accuracy", stats.accuracy())
                    .putExtra("seconds", stats.seconds).putExtra("counts", stats.counts()).putExtra("rec", base);
            if (who != null) res.putExtra("profile", who.id);
            setResult(RESULT_OK, res);
            textView.postDelayed(this::finish, ok ? 2600 : 1800);
        }
    }

    final java.util.Random rnd = new java.util.Random();

    /** Успех: случайный игровой звук (Sounds), конфетти и плашка «Молодец!». Возгласы голосом убраны (Фёдору не понравились, 04.11). */
    void celebrate() {
        int sr = 44100; short[] pcm = dev.gubenkov.reading.Sounds.randomSuccess(sr, rnd);
        Log.i(TAG, "успех: звук " + pcm.length * 1000 / sr + " мс");
        playPcm(pcm, sr, null);
        confetti.start(pal.confetti);
        badge.setVisibility(View.VISIBLE); badge.setAlpha(0f); badge.setScaleX(0.3f); badge.setScaleY(0.3f);
        badge.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(450).setInterpolator(new android.view.animation.OvershootInterpolator(2f))
                .withEndAction(() -> badge.animate().alpha(0f).setStartDelay(1600).setDuration(500).withEndAction(() -> badge.setVisibility(View.GONE)));
    }

    void playFile(File f) {
        Log.i(TAG, "играю " + f.getName());
        try {
            if (player != null) player.release();
            player = new MediaPlayer(); player.setDataSource(f.getPath()); player.prepare(); player.start();
        } catch (Exception e) { Log.w(TAG, "praise " + f + ": " + e); }
    }

    String save(Read w, StreamingRecognizer rec, String hyp, ReadingMatcher.Result r, boolean ok, SpeakerId.Decision dec) {
        String base = null;
        try {
            File d = new File(dir(), "recordings"); d.mkdirs();
            base = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.ROOT).format(new java.util.Date()) + "_" + w.id;
            float[] a = rec.audio();
            ByteBuffer bb = ByteBuffer.allocate(44 + a.length * 2).order(ByteOrder.LITTLE_ENDIAN);
            bb.put("RIFF".getBytes()).putInt(36 + a.length * 2).put("WAVEfmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
              .putInt(SR).putInt(SR * 2).putShort((short) 2).putShort((short) 16).put("data".getBytes()).putInt(a.length * 2);
            for (float x : a) bb.putShort((short) Math.max(-32768, Math.min(32767, Math.round(x * 32767))));
            try (FileOutputStream o = new FileOutputStream(new File(d, base + ".wav"))) { o.write(bb.array()); }
            JSONObject j = new JSONObject().put("id", w.id).put("text", w.text.source).put("hyp", hyp).put("auto_ok", ok)
                    .put("backend", String.valueOf(model.backend));
            if (duel) j.put("duel", true);
            StringBuilder st = new StringBuilder(); for (ReadingMatcher.State x : r.states) st.append(x.name().charAt(0));
            j.put("states", st.toString());
            if (dec != null) {   // голос: эмбеддинг и косинусы — чтобы потом можно было пересортировать попытки
                JSONObject sp = new JSONObject().put("decision", dec.kind.name()).put("speech_s", Math.round(dec.speechS * 100) / 100.0);
                if (dec.profile != null) sp.put("profile", dec.profile.id);
                JSONObject cs = new JSONObject();
                for (int i = 0; i < profiles.list.size(); i++) cs.put(profiles.list.get(i).id, Math.round(dec.cos[i] * 1000) / 1000.0);
                sp.put("cos", cs);
                org.json.JSONArray e = new org.json.JSONArray(); for (float v : lastEmb) e.put(Math.round(v * 1e6) / 1e6);
                sp.put("emb", e);
                j.put("speaker", sp);
            }
            try (FileOutputStream o = new FileOutputStream(new File(d, base + ".json"))) { o.write(j.toString(1).getBytes(StandardCharsets.UTF_8)); }
            Log.i(TAG, "сохранено " + base + " ok=" + ok + " | " + hyp);
        } catch (Exception e) { Log.e(TAG, "save", e); }
        return base;
    }

    File liveFile() { return new File(dir(), "tts/" + curId() + ".rhvoice.wav"); }

    /** Озвучить текущий текст на телефоне и сыграть; время слов размечается в фоне своей же CTC-моделью. */
    void listenLive() {
        File out = liveFile(); out.getParentFile().mkdirs();
        if (out.exists() && out.length() > 1000) { playFile(out); status.setText(voiceName(out)); return; }
        status.setText("Готовлю озвучку…");
        final String id = curId(); final ReadingText text = cur; final long t0 = System.nanoTime();
        live.synth(text.source, out, f -> runOnUiThread(() -> {
            if (f == null) { status.setText("Озвучка на телефоне не получилась"); return; }
            long ms = (System.nanoTime() - t0) / 1000000;
            if (id.equals(curId())) { playFile(f); status.setText(voiceName(f)); }
            new Thread(() -> alignLive(f, text, ms)).start();
        }));
    }

    /** Разметка слов озвучки: распознаём свою же моделью и сверяем с текстом (как в ml/word_times.py, но жадно). */
    void alignLive(File wav, ReadingText text, long synthMs) {
        try {
            long a = System.nanoTime();
            Pcm p = decode(wav);
            int n = (int) ((long) p.data.length * SR / p.sr); float[] x = new float[n];
            for (int i = 0; i < n; i++) {   // линейная передискретизация в 16 кГц
                double src = (double) i * p.sr / SR; int k = (int) src; double fr = src - k;
                int k1 = Math.min(p.data.length - 1, k + 1);
                x[i] = (float) ((p.data[k] * (1 - fr) + p.data[k1] * fr) / 32768.0);
            }
            if (model == null) { Log.w(TAG, "разметка: модель не загружена"); return; }
            StreamingRecognizer rec = new StreamingRecognizer(model);
            for (int i = 0; i < n; i += SR / 10) rec.feed(java.util.Arrays.copyOfRange(x, i, Math.min(n, i + SR / 10)), Math.min(SR / 10, n - i));
            String hyp = rec.decode();
            ReadingMatcher.Result r = new ReadingMatcher().match(text, hyp, true);
            int W = text.words.size(); double[][] t = new double[W][];
            for (int w = 0; w < W; w++) if (r.hypFirst[w] >= 0 && r.hypLast[w] < rec.letterFrames.size())
                t[w] = new double[]{rec.letterFrames.get(r.hypFirst[w]) * StreamingRecognizer.FRAME_SEC, (rec.letterFrames.get(r.hypLast[w]) + 1) * StreamingRecognizer.FRAME_SEC};
            int okW = 0; double dur = n / (double) SR;
            for (int w = 0; w < W; w++) {   // несопоставленные слова — между соседями
                if (t[w] != null) { okW++; continue; }
                double lo = 0; for (int k = w - 1; k >= 0; k--) if (t[k] != null) { lo = t[k][1]; break; }
                double hi = dur; for (int k = w + 1; k < W; k++) if (t[k] != null) { hi = t[k][0]; break; }
                t[w] = new double[]{lo, Math.max(lo + 0.2, hi)};
            }
            org.json.JSONArray ws = new org.json.JSONArray();
            for (double[] q : t) ws.put(new org.json.JSONArray().put(Math.round(q[0] * 100) / 100.0).put(Math.round(q[1] * 100) / 100.0));
            JSONObject j = new JSONObject().put("words", ws).put("engine", "rhvoice:" + live.voice).put("hyp", hyp)
                    .put("synth_ms", synthMs).put("audio_s", dur).put("matched", okW);
            File jf = new File(wav.getPath().replaceAll("\\.wav$", ".json"));
            try (FileOutputStream o = new FileOutputStream(jf)) { o.write(j.toString(1).getBytes(StandardCharsets.UTF_8)); }
            Log.i(TAG, String.format(java.util.Locale.ROOT, "озвучка на телефоне: %d слов, звук %.1f с, синтез %d мс (RTF %.3f), разметка %d мс, слов сопоставлено %d/%d | %s",
                    W, dur, synthMs, synthMs / 1000.0 / dur, (System.nanoTime() - a) / 1000000, okW, W, hyp));
        } catch (Exception e) { Log.e(TAG, "разметка озвучки", e); }
    }

    void listen() {
        if (live.ready() && !liveFile().exists()) { listenLive(); return; }
        File[] fs = refFiles();
        if (fs.length == 0) { status.setText("Озвучки для этого текста пока нет"); return; }
        File f = fs[Math.floorMod(++voiceIdx, fs.length)];
        try {
            if (player != null) player.release();
            player = new MediaPlayer(); player.setDataSource(f.getPath()); player.prepare(); player.start();
            status.setText(voiceName(f));
        } catch (Exception e) { status.setText("ошибка воспроизведения: " + e); }
    }

    /** Своё слово: от первой до последней буквы, сопоставленных ему, с запасом 0,15/0,25 с. */
    void playWord(int w) {
        Runnable ref = () -> playRefWord(w);
        if (lastResult == null || lastLetterFrames == null || lastAudio == null) { ref.run(); return; }
        int a = lastResult.hypFirst[w], b = lastResult.hypLast[w];
        if (a < 0 || b >= lastLetterFrames.length) { ref.run(); return; }
        float t0 = lastLetterFrames[a] * StreamingRecognizer.FRAME_SEC - 0.15f, t1 = lastLetterFrames[b] * StreamingRecognizer.FRAME_SEC + 0.25f;
        playMine(Math.max(0, (int) (t0 * SR)), Math.min(lastAudio.length, (int) (t1 * SR)), ref);
    }

    /** Эталонное слово: tts/<id>.<голос>.json хранит [t0,t1] каждого слова (ml/word_times.py). */
    void playRefWord(int w) {
        File[] fs = refFiles();
        if (fs.length == 0) { status.setText("Озвучки для этого текста пока нет"); return; }
        File f = fs[Math.floorMod(voiceIdx, fs.length)];
        try {
            File jf = new File(f.getPath().replaceAll("\\.(ogg|wav)$", ".json"));
            org.json.JSONArray ws = new JSONObject(new String(java.nio.file.Files.readAllBytes(jf.toPath()), StandardCharsets.UTF_8)).getJSONArray("words");
            if (w >= ws.length()) return;
            double t0 = Math.max(0, ws.getJSONArray(w).getDouble(0) - 0.1), t1 = ws.getJSONArray(w).getDouble(1) + 0.15;
            // MediaPlayer.seekTo по ogg/opus на части устройств игнорируется (играет с начала) — режем декодированный PCM
            Pcm a = decode(f);
            int from = (int) (t0 * a.sr), to = Math.min(a.data.length, (int) (t1 * a.sr));
            if (to <= from) return;
            if (player != null && player.isPlaying()) player.pause();
            playPcm(java.util.Arrays.copyOfRange(a.data, from, to), a.sr, null);
            Log.i(TAG, String.format("эталон слова %d «%s» %.2f–%.2f с из %s", w, cur.words.get(w).display, t0, t1, f.getName()));
            status.setText("Как надо: " + cur.words.get(w).display + " (" + voiceName(f) + ")");
        } catch (Exception e) { status.setText("нет разметки слов: " + e.getMessage()); }
    }

    static final class Pcm { final short[] data; final int sr; Pcm(short[] d, int s) { data = d; sr = s; } }
    final java.util.Map<String, Pcm> pcmCache = new java.util.HashMap<>();

    /** ogg/opus (или wav) → PCM 16 бит моно через MediaExtractor + MediaCodec; кэш по пути. */
    Pcm decode(File f) throws Exception {
        Pcm c = pcmCache.get(f.getPath()); if (c != null) return c;
        android.media.MediaExtractor ex = new android.media.MediaExtractor(); ex.setDataSource(f.getPath());
        android.media.MediaFormat fmt = ex.getTrackFormat(0); ex.selectTrack(0);
        android.media.MediaCodec dec = android.media.MediaCodec.createDecoderByType(fmt.getString(android.media.MediaFormat.KEY_MIME));
        dec.configure(fmt, null, null, 0); dec.start();
        int sr = fmt.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE), ch = fmt.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
        boolean inEnd = false;
        while (true) {
            if (!inEnd) {
                int i = dec.dequeueInputBuffer(10000);
                if (i >= 0) {
                    int n = ex.readSampleData(dec.getInputBuffer(i), 0);
                    if (n < 0) { dec.queueInputBuffer(i, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM); inEnd = true; }
                    else { dec.queueInputBuffer(i, 0, n, ex.getSampleTime(), 0); ex.advance(); }
                }
            }
            int o = dec.dequeueOutputBuffer(info, 10000);
            if (o == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                android.media.MediaFormat of = dec.getOutputFormat();
                sr = of.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE); ch = of.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT);
            } else if (o >= 0) {
                ByteBuffer b = dec.getOutputBuffer(o); byte[] chunk = new byte[info.size]; b.position(info.offset); b.get(chunk); out.write(chunk);
                dec.releaseOutputBuffer(o, false);
                if ((info.flags & android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            }
        }
        dec.stop(); dec.release(); ex.release();
        java.nio.ShortBuffer sb = ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        short[] all = new short[sb.remaining()]; sb.get(all);
        short[] mono = new short[all.length / ch];
        for (int i = 0; i < mono.length; i++) { int s = 0; for (int k = 0; k < ch; k++) s += all[i * ch + k]; mono[i] = (short) (s / ch); }
        c = new Pcm(mono, sr); pcmCache.put(f.getPath(), c); return c;
    }

    void playPcm(short[] pcm, int sr, Runnable then) {
        if (track != null) { try { track.stop(); } catch (Exception ignored) {} track.release(); }
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(sr).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(Math.max(pcm.length * 2, 4096)).build();
        track.write(pcm, 0, pcm.length);
        if (then != null) {
            track.setNotificationMarkerPosition(pcm.length - 1);
            track.setPlaybackPositionUpdateListener(new AudioTrack.OnPlaybackPositionUpdateListener() {
                @Override public void onMarkerReached(AudioTrack t) { textView.postDelayed(then, 400); }
                @Override public void onPeriodicNotification(AudioTrack t) { }
            });
        }
        track.play();
    }

    static String voiceName(File f) {
        String v = f.getName().split("\\.")[1];
        switch (v) {
            case "skazochnik": return "Сказочник"; case "devochka": return "Девочка"; case "malchik": return "Мальчик";
            case "velikan": return "Великан"; case "feya": return "Фея"; case "rhvoice": return "Голос телефона"; default: return v;
        }
    }

    File[] refFiles() {
        File[] fs = new File(dir(), "tts").listFiles((x, n) -> n.startsWith(curId() + ".") && (n.endsWith(".ogg") || n.endsWith(".wav")));
        if (fs == null) return new File[0];
        java.util.Arrays.sort(fs); return fs;
    }

    void playMine(int from, int to, Runnable then) {
        if (lastAudio == null || to <= from) { status.setText("Сначала прочитай текст"); return; }
        if (track != null) { try { track.stop(); } catch (Exception ignored) {} track.release(); }
        short[] pcm = new short[to - from];
        float peak = 1e-3f; for (int i = from; i < to; i++) peak = Math.max(peak, Math.abs(lastAudio[i]));
        float g = Math.min(8f, 0.9f / peak); // тихую запись поднимаем
        for (int i = 0; i < pcm.length; i++) pcm[i] = (short) Math.max(-32768, Math.min(32767, lastAudio[from + i] * g * 32767));
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(SR).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.length * 2).build();
        track.write(pcm, 0, pcm.length);
        if (then != null) {
            track.setNotificationMarkerPosition(pcm.length - 1);
            track.setPlaybackPositionUpdateListener(new AudioTrack.OnPlaybackPositionUpdateListener() {
                @Override public void onMarkerReached(AudioTrack t) { textView.postDelayed(then, 400); }
                @Override public void onPeriodicNotification(AudioTrack t) { }
            });
        }
        track.play();
        status.setText(then != null ? "Ты… потом как надо" : "Твоё чтение");
    }

    @Override public void onRequestPermissionsResult(int c, String[] p, int[] g) {
        if (g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) new Thread(this::readLoop).start();
    }

    @Override protected void onDestroy() {
        recording = false; if (player != null) player.release(); if (track != null) track.release(); super.onDestroy();
    }
}
