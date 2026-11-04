package dev.gubenkov.readbench;

import android.app.Activity;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import dev.gubenkov.reading.ReadingMatcher;
import dev.gubenkov.reading.ReadingText;

/**
 * Разметка попытки родителем: слова текста с решением приложения; тап по слову — «верно» ↔ «ошибка»
 * (непрочитанное — «не читал»), «▶ слово» играет его кусок из записи. Сохраняется в recordings/<rec>.labels.json —
 * это правда для оценки сверки (ml/live_eval) и метки для дообучения. Ребёнку не показывается.
 */
public class LabelActivity extends Activity {
    static final String TAG = "ReadBench";
    KidUi.Palette pal;
    File dir; String rec;
    ReadingText text; ReadingMatcher.Result r;
    char[] labels;          // O — верно, W — ошибка, P — не читал
    int[] letterFrames;     // кадр каждой буквы гипотезы (FRAME_SEC), если сохранён
    short[] pcm; int sr = 16000;
    int sel = -1;
    TextView tv, info;
    AudioTrack track;

    int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        pal = KidUi.Palette.setup(this);
        dir = new File(getExternalFilesDir(null), "recordings");
        rec = getIntent().getStringExtra("rec");
        try {
            JSONObject j = new JSONObject(new String(Files.readAllBytes(new File(dir, rec + ".json").toPath()), StandardCharsets.UTF_8));
            text = new ReadingText(j.getString("text"));
            r = new ReadingMatcher().match(text, j.optString("hyp", ""), true);
            String st = j.optString("states", "");
            JSONArray lf = j.optJSONArray("letter_frames");
            if (lf != null) { letterFrames = new int[lf.length()]; for (int i = 0; i < lf.length(); i++) letterFrames[i] = lf.getInt(i); }
            labels = new char[text.words.size()];
            for (int w = 0; w < labels.length; w++) labels[w] = w < st.length() ? st.charAt(w) : r.states[w].name().charAt(0);
            File lj = new File(dir, rec + ".labels.json");
            if (lj.exists()) {
                String saved = new JSONObject(new String(Files.readAllBytes(lj.toPath()), StandardCharsets.UTF_8)).getString("labels");
                if (saved.length() == labels.length) labels = saved.toCharArray();
            }
            pcm = readWav(new File(dir, rec + ".wav"));
        } catch (Exception e) { Log.e(TAG, "разметка " + rec, e); finish(); return; }

        ScrollView sv = new ScrollView(this); sv.setBackgroundColor(pal.bg);
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); l.setPadding(dp(16), dp(20), dp(16), dp(20));
        sv.addView(l); setContentView(sv);
        TextView h = new TextView(this); h.setText("Разметка: " + rec); h.setTextSize(18); h.setTextColor(pal.text); h.setTypeface(Typeface.DEFAULT_BOLD); l.addView(h);
        info = new TextView(this); info.setTextSize(14); info.setTextColor(pal.status); info.setPadding(0, dp(4), 0, dp(10)); l.addView(info);
        tv = new TextView(this); tv.setTextSize(24); tv.setTextColor(pal.text); tv.setLineSpacing(0, 1.3f);
        tv.setMovementMethod(LinkMovementMethod.getInstance()); tv.setHighlightColor(0);
        l.addView(tv);
        LinearLayout row = new LinearLayout(this); row.setPadding(0, dp(12), 0, 0);
        row.addView(button("▶ всё", v -> play(0, pcm.length)));
        row.addView(button("▶ слово", v -> playWord()));
        row.addView(button("Сохранить", v -> save()));
        l.addView(row);
        render();
    }

    Button button(String s, View.OnClickListener c) { Button b = new Button(this); b.setText(s); b.setAllCaps(false); b.setOnClickListener(c); return b; }

    void render() {
        SpannableStringBuilder sb = new SpannableStringBuilder(text.source);
        int ok = 0, bad = 0;
        for (int w = 0; w < labels.length; w++) {
            ReadingText.Word wd = text.words.get(w); final int ww = w;
            int bg = labels[w] == 'O' ? pal.okBg : labels[w] == 'W' ? pal.wrongBg : 0;
            if (w == sel) bg = pal.curBg;
            if (bg != 0) sb.setSpan(new BackgroundColorSpan(bg), wd.start, wd.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new ClickableSpan() {
                @Override public void onClick(View v) {
                    if (sel == ww) labels[ww] = labels[ww] == 'O' ? 'W' : labels[ww] == 'W' ? 'P' : 'O';   // второй тап — меняет метку
                    sel = ww; render(); playWord();
                }
                @Override public void updateDrawState(TextPaint ds) { ds.setUnderlineText(false); ds.setColor(pal.text); }
            }, wd.start, wd.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (labels[w] == 'O') ok++; else if (labels[w] == 'W') bad++;
        }
        tv.setText(sb);
        info.setText("Тап — выбрать слово и послушать, ещё тап — верно → ошибка → не читал. Верно " + ok + ", ошибок " + bad
                + (letterFrames == null ? "\n(в старой записи нет времени букв — кусок слова примерный)" : ""));
    }

    void playWord() {
        if (sel < 0 || r.hypFirst[sel] < 0) { play(0, pcm.length); return; }
        int a = r.hypFirst[sel], b = r.hypLast[sel]; float t0, t1;
        if (letterFrames != null && b < letterFrames.length) {
            t0 = letterFrames[a] * 0.04f - 0.15f; t1 = (letterFrames[b] + 1) * 0.04f + 0.25f;
        } else {   // примерно: доля букв гипотезы
            int n = 0; for (int x : r.hypLast) n = Math.max(n, x + 1);
            float dur = pcm.length / (float) sr; t0 = dur * a / Math.max(1, n) - 0.6f; t1 = dur * (b + 1) / Math.max(1, n) + 0.6f;
        }
        play(Math.max(0, (int) (t0 * sr)), Math.min(pcm.length, (int) (t1 * sr)));
    }

    void play(int from, int to) {
        if (to <= from) return;
        if (track != null) { try { track.stop(); } catch (Exception ignored) {} track.release(); }
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(sr).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes((to - from) * 2).build();
        track.write(pcm, from, to - from); track.play();
    }

    void save() {
        try (FileOutputStream o = new FileOutputStream(new File(dir, rec + ".labels.json"))) {
            o.write(new JSONObject().put("labels", new String(labels)).put("time", System.currentTimeMillis()).put("by", "parent")
                    .toString().getBytes(StandardCharsets.UTF_8));
            Log.i(TAG, "разметка сохранена: " + rec + " " + new String(labels));
            finish();
        } catch (Exception e) { info.setText("не сохранилось: " + e); }
    }

    static short[] readWav(File f) throws Exception {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            byte[] all = new byte[(int) raf.length()]; raf.readFully(all);
            ByteBuffer bb = ByteBuffer.wrap(all, 44, all.length - 44).order(ByteOrder.LITTLE_ENDIAN);
            short[] s = new short[(all.length - 44) / 2]; bb.asShortBuffer().get(s); return s;
        }
    }

    @Override protected void onDestroy() { if (track != null) track.release(); super.onDestroy(); }
}
