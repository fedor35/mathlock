package dev.gubenkov.readbench;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.UnderlineSpan;
import android.util.Log;
import android.view.Gravity;
import android.widget.Button;
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

import dev.gubenkov.reading.CtcModel;
import dev.gubenkov.reading.ReadingMatcher;
import dev.gubenkov.reading.ReadingText;
import dev.gubenkov.reading.StreamingRecognizer;

/**
 * Живое чтение: текст из texts.jsonl (getExternalFilesDir), микрофон → потоковая сверка → подсветка.
 * Записи для дообучения: files/recordings/<время>_<id>.wav + .json (гипотеза, состояния слов).
 * Озвучка: files/tts/<id>.<голос>.ogg|wav.
 */
public class ReadActivity extends Activity {
    static final String TAG = "ReadBench";
    static final int SR = 16000;
    static final int[] SYL = {Color.rgb(25, 60, 160), Color.rgb(190, 40, 40)};

    TextView textView, status, debug;
    final List<JSONObject> texts = new ArrayList<>();
    int idx = 0;
    ReadingText cur;
    ReadingMatcher.Result lastResult;
    CtcModel model;
    volatile boolean recording;
    MediaPlayer player;
    int voiceIdx = 0;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(32, 48, 32, 32);
        textView = new TextView(this); textView.setTextSize(34); textView.setLineSpacing(0, 1.25f);
        textView.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL)); textView.setTextColor(Color.BLACK);
        status = new TextView(this); status.setTextSize(20); status.setGravity(Gravity.CENTER); status.setPadding(0, 24, 0, 24);
        debug = new TextView(this); debug.setTextSize(12); debug.setTextColor(Color.GRAY);
        LinearLayout row = new LinearLayout(this);
        Button read = new Button(this); read.setText("🎤 Читать");
        Button listen = new Button(this); listen.setText("🔊 Послушать");
        Button next = new Button(this); next.setText("Дальше ▶");
        row.addView(read, new LinearLayout.LayoutParams(0, -2, 1)); row.addView(listen, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(next, new LinearLayout.LayoutParams(0, -2, 1));
        l.addView(textView, new LinearLayout.LayoutParams(-1, 0, 1)); l.addView(status); l.addView(row); l.addView(debug);
        l.setBackgroundColor(Color.rgb(255, 252, 240));
        setContentView(l);

        read.setOnClickListener(v -> {
            if (recording) { recording = false; return; }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1); return;
            }
            new Thread(this::readLoop).start();
        });
        next.setOnClickListener(v -> { recording = false; idx = (idx + 1) % texts.size(); show(); });
        listen.setOnClickListener(v -> listen());
        loadTexts();
        idx = getIntent().getIntExtra("idx", 0) % Math.max(1, texts.size());
        show();
        new Thread(() -> { loadModel(); if (getIntent().hasExtra("wav")) readLoop(); }).start();
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
    }

    String curId() { return texts.get(idx).optString("id"); }

    void show() {
        cur = new ReadingText(texts.get(idx).optString("text"));
        lastResult = null;
        status.setText("Нажми «Читать» и прочитай вслух");
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
                sb.setSpan(new ForegroundColorSpan(SYL[s++ % 2]), st, pos, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (pos < wd.end && cur.source.charAt(pos) == '-') pos++;
            }
            if (lastResult != null) {
                ReadingMatcher.State state = lastResult.states[w];
                if (state == ReadingMatcher.State.OK)
                    sb.setSpan(new BackgroundColorSpan(Color.rgb(200, 240, 200)), wd.start, wd.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                else if (state == ReadingMatcher.State.WRONG) {
                    sb.setSpan(new BackgroundColorSpan(Color.rgb(255, 210, 210)), wd.start, wd.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new UnderlineSpan(), wd.start, wd.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else if (w == lastResult.progressWords)
                    sb.setSpan(new BackgroundColorSpan(Color.rgb(255, 240, 170)), wd.start, wd.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        textView.setText(sb);
    }

    void readLoop() {
        if (model == null) { runOnUiThread(() -> status.setText("модель ещё грузится…")); return; }
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
        short[] s = new short[chunk]; float[] f = new float[chunk];
        runOnUiThread(() -> status.setText("Слушаю… (нажми «Читать» ещё раз, чтобы закончить)"));
        if (ar != null) ar.startRecording();
        long lastDecode = 0; String hyp = ""; long decMs = 0; boolean done = false;
        long maxSamples = SR * 120L;
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
        save(rec, h, fin, ok);
        runOnUiThread(() -> {
            lastResult = fin; render();
            status.setText(ok ? "Молодец! Всё прочитано верно 🎉" : "Почти! Красные слова прочитай ещё раз");
            debug.setText(h);
        });
    }

    void save(StreamingRecognizer rec, String hyp, ReadingMatcher.Result r, boolean ok) {
        try {
            File d = new File(dir(), "recordings"); d.mkdirs();
            String base = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.ROOT).format(new java.util.Date()) + "_" + curId();
            float[] a = rec.audio();
            ByteBuffer bb = ByteBuffer.allocate(44 + a.length * 2).order(ByteOrder.LITTLE_ENDIAN);
            bb.put("RIFF".getBytes()).putInt(36 + a.length * 2).put("WAVEfmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
              .putInt(SR).putInt(SR * 2).putShort((short) 2).putShort((short) 16).put("data".getBytes()).putInt(a.length * 2);
            for (float x : a) bb.putShort((short) Math.max(-32768, Math.min(32767, Math.round(x * 32767))));
            try (FileOutputStream o = new FileOutputStream(new File(d, base + ".wav"))) { o.write(bb.array()); }
            JSONObject j = new JSONObject().put("id", curId()).put("text", cur.source).put("hyp", hyp).put("auto_ok", ok)
                    .put("backend", String.valueOf(model.backend));
            StringBuilder st = new StringBuilder(); for (ReadingMatcher.State x : r.states) st.append(x.name().charAt(0));
            j.put("states", st.toString());
            try (FileOutputStream o = new FileOutputStream(new File(d, base + ".json"))) { o.write(j.toString(1).getBytes(StandardCharsets.UTF_8)); }
            Log.i(TAG, "сохранено " + base + " ok=" + ok + " | " + hyp);
        } catch (Exception e) { Log.e(TAG, "save", e); }
    }

    void listen() {
        File d = new File(dir(), "tts");
        File[] fs = d.listFiles((x, n) -> n.startsWith(curId() + "."));
        if (fs == null || fs.length == 0) { status.setText("Озвучки для этого текста пока нет"); return; }
        java.util.Arrays.sort(fs);
        File f = fs[voiceIdx++ % fs.length];
        try {
            if (player != null) player.release();
            player = new MediaPlayer(); player.setDataSource(f.getPath()); player.prepare(); player.start();
            status.setText("🔊 " + f.getName().split("\\.")[1]);
        } catch (Exception e) { status.setText("ошибка воспроизведения: " + e); }
    }

    @Override public void onRequestPermissionsResult(int c, String[] p, int[] g) {
        if (g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) new Thread(this::readLoop).start();
    }

    @Override protected void onDestroy() { recording = false; if (player != null) player.release(); super.onDestroy(); }
}
