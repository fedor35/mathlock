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
import android.text.method.LinkMovementMethod;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
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
    KidUi.IconButton mic, me, compare, listenBtn, nextBtn;
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
        textView.setMovementMethod(LinkMovementMethod.getInstance());
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
        nextBtn.setOnClickListener(v -> { recording = false; pickNext(); show(); });

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
        l.addView(levelView); l.addView(textView, new LinearLayout.LayoutParams(-1, 0, 1)); l.addView(status); l.addView(row); l.addView(mic, mlp); l.addView(debug);
        root.addView(l, new FrameLayout.LayoutParams(-1, -1));
        confetti = new KidUi.ConfettiView(this); root.addView(confetti, new FrameLayout.LayoutParams(-1, -1));

        // «Молодец!» — крупная плашка, выпрыгивает и гаснет
        badge = new TextView(this); badge.setText("Молодец!"); badge.setTextSize(44); badge.setTextColor(Color.WHITE);
        badge.setTypeface(Typeface.create("sans-serif", Typeface.BOLD)); badge.setPadding(dp(36), dp(18), dp(36), dp(18));
        GradientDrawable bd = new GradientDrawable(); bd.setColor(pal.success); bd.setCornerRadius(dp(40)); badge.setBackground(bd);
        badge.setElevation(dp(8)); badge.setVisibility(View.GONE);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2); blp.gravity = Gravity.CENTER; root.addView(badge, blp);
        setContentView(root);

        // папки создаёт само приложение: созданные через adb (владелец shell) оно не читает
        new File(dir(), "tts").mkdirs(); new File(dir(), "recordings").mkdirs();
        loadTexts();
        profiles = new Profiles(dir());
        loadProgress();
        live = new LiveTts(this, LiveTts.RHVOICE);
        if (getIntent().hasExtra("idx")) idx = getIntent().getIntExtra("idx", 0) % Math.max(1, texts.size());
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

    void record(Read w, ReadingStats st, SpeakerId.Decision d, String rec) {
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
            runOnUiThread(this::showLevel); return;
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
    }

    String levelNews(ReadingStats st, int before, float best) {
        int after = adapt.level(); String msg = "";
        if (st.accuracy() >= 0.95f && st.wcpm() > best && best > 0) msg += " — новый рекорд!";
        if (after > before) msg += "\nНовый уровень " + after + "!";
        else if (after < before) msg += "\nПотренируемся на уровне " + after;
        return msg;
    }

    void show() {
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
    void render() {
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
            {   // слово нажимается всегда: прочитанное — «ты → как надо», ещё не прочитанное — «как надо»
                final int ww = w;
                sb.setSpan(new ClickableSpan() {
                    @Override public void onClick(View v) { playWord(ww); }
                    @Override public void updateDrawState(TextPaint ds) { }
                }, wd.start, wd.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (lastResult != null) {
                ReadingMatcher.State state = lastResult.states[w];
                if (state == ReadingMatcher.State.OK)
                    sb.setSpan(new BackgroundColorSpan(pal.okBg), wd.start, wd.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                else if (state == ReadingMatcher.State.WRONG) {
                    sb.setSpan(new BackgroundColorSpan(pal.wrongBg), wd.start, wd.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new UnderlineSpan(), wd.start, wd.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else if (w == lastResult.progressWords)
                    sb.setSpan(new BackgroundColorSpan(pal.curBg), wd.start, wd.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        if (!AdaptiveLevel.stressMarks(textLevel))   // старшие уровни — как в книге, без ударений (акут — нулевой ширины, прячем цветом)
            for (int i = 0; i < cur.source.length(); i++)
                if (cur.source.charAt(i) == ReadingText.ACUTE) sb.setSpan(new ForegroundColorSpan(Color.TRANSPARENT), i, i + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int keepY = textView.getScrollY();
        textView.setText(sb);
        // длинный текст плавно едет за чтением: цель — текущее слово на второй видимой строке, со сдвигом внутри
        // строки по доле пройденного (x слова / ширина), поэтому прокрутка непрерывная, а не скачками по строкам
        if (lastResult != null && lastResult.progressWords < cur.words.size() && textView.getLayout() != null) {
            textView.scrollTo(0, keepY);   // setText не должен дёргать позицию
            android.text.Layout lay = textView.getLayout();
            int off = cur.words.get(lastResult.progressWords).start;
            int line = lay.getLineForOffset(off);
            float w = Math.max(1, lay.getLineRight(line) - lay.getLineLeft(line));
            float frac = Math.max(0, Math.min(1, (lay.getPrimaryHorizontal(off) - lay.getLineLeft(line)) / w));
            int lh = lay.getLineBottom(line) - lay.getLineTop(line);
            int y = Math.max(0, lay.getLineTop(Math.max(0, line - 1)) + Math.round(frac * lh));
            int maxY = Math.max(0, lay.getHeight() + textView.getTotalPaddingTop() + textView.getTotalPaddingBottom() - textView.getHeight());
            smoothScroll(Math.min(y, maxY));
        } else if (lastResult == null) { if (scrollAnim != null) scrollAnim.cancel(); textView.scrollTo(0, 0); }
    }

    android.animation.ValueAnimator scrollAnim;
    int scrollTarget = -1;

    /** Плавная прокрутка: длительность ~ интервалу обновления подсветки (0,4 с), чтобы движение было почти непрерывным. */
    void smoothScroll(int y) {
        if (y == scrollTarget && scrollAnim != null && scrollAnim.isRunning()) return;
        if (scrollAnim != null) scrollAnim.cancel();
        int from = textView.getScrollY();
        scrollTarget = y;
        if (Math.abs(y - from) < 2) { textView.scrollTo(0, y); return; }
        scrollAnim = android.animation.ValueAnimator.ofInt(from, y);
        scrollAnim.setDuration(Math.min(900, 450 + Math.abs(y - from) / 2));
        scrollAnim.setInterpolator(new android.view.animation.DecelerateInterpolator(1.2f));
        scrollAnim.addUpdateListener(a -> textView.scrollTo(0, (int) a.getAnimatedValue()));
        scrollAnim.start();
    }

    void readLoop() {
        if (model == null) { runOnUiThread(() -> status.setText("Подожди, готовлюсь…")); return; }
        recording = true;
        int chunk = SR / 10;
        // отладка: am start … --es wav file.wav — подать файл в темпе реального времени вместо микрофона
        String wavName = getIntent().getStringExtra("wav");
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
        runOnUiThread(() -> { status.setText("Слушаю…"); setListening(true); });
        if (ar != null) ar.startRecording();
        long lastDecode = 0; String hyp = ""; long decMs = 0; boolean done = false;
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
                    ReadingMatcher.Result res = matcher.match(cur, hyp, false);
                    final String h = hyp; final long dm = decMs; final float sec = rec.samples() / (float) SR;
                    Log.i(TAG, String.format("live %.1fs dec %dms progress %d/%d | %s", sec, dm, res.progressWords, cur.words.size(), h));
                    runOnUiThread(() -> { lastResult = res; render(); debug.setText(String.format("%.1f с, %d мс | %s", sec, dm, h)); });
                    if (res.complete) { done = true; break; }
                }
            }
        } catch (Throwable e) { Log.e(TAG, "read", e); }
        if (ar != null) { ar.stop(); ar.release(); }
        recording = false;
        try { hyp = rec.decode(); } catch (Throwable ignored) {}
        ReadingMatcher.Result fin = matcher.match(cur, hyp, true);
        final boolean ok = fin.complete || done;
        final String h = hyp;
        int[] lfs = new int[rec.letterFrames.size()];
        for (int i = 0; i < lfs.length; i++) lfs[i] = rec.letterFrames.get(i);
        final ReadingStats stats = ReadingStats.of(fin, lfs, StreamingRecognizer.FRAME_SEC);
        final float[] audio = rec.audio();
        final int[] lf = new int[rec.letterFrames.size()];
        for (int i = 0; i < lf.length; i++) lf[i] = rec.letterFrames.get(i);
        runOnUiThread(() -> {
            lastResult = fin; lastAudio = audio; lastLetterFrames = lf;
            me.setEnabled(true); compare.setEnabled(true); setListening(false);
            render();
            status.setText(ok ? "Молодец! Всё верно" : "Почти! Прочитай красные слова ещё раз");
            debug.setText(h);
            if (ok) celebrate();
        });
        // чей голос — уже после того, как ребёнок увидел результат; запись, журнал и уровень — по опознанному чтецу
        SpeakerId.Decision d = identify(audio, stats);
        String base = save(what, rec, h, fin, ok, d);
        record(what, stats, d, base);
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
