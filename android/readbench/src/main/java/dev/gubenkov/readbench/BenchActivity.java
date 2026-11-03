package dev.gubenkov.readbench;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import dev.gubenkov.reading.CtcModel;
import dev.gubenkov.reading.MelFrontend;

/**
 * Стенд замеров: модели лежат в getExternalFilesDir() (кладём adb push).
 * Запуск без UI: am start -n dev.gubenkov.readbench/.BenchActivity --es run all
 * Результаты — в logcat с тегом ReadBench.
 */
public class BenchActivity extends Activity {
    static final String TAG = "ReadBench";
    TextView log;
    final MelFrontend mel = new MelFrontend();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL);
        Button bAll = new Button(this); bAll.setText("Прогнать все модели на test.wav");
        Button bMic = new Button(this); bMic.setText("Записать 6 с и распознать (CPU)");
        log = new TextView(this); log.setTextSize(13);
        ScrollView sv = new ScrollView(this); sv.addView(log);
        l.addView(bAll); l.addView(bMic); l.addView(sv);
        setContentView(l);
        bAll.setOnClickListener(v -> new Thread(this::runAll).start());
        bMic.setOnClickListener(v -> {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            else new Thread(this::mic).start();
        });
        if ("all".equals(getIntent().getStringExtra("run"))) new Thread(this::runAll).start();
    }

    void say(String s) { Log.i(TAG, s); runOnUiThread(() -> log.append(s + "\n")); }

    File dir() { return getExternalFilesDir(null); }

    static float[] readWav16k(File f) throws Exception {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            byte[] all = new byte[(int) r.length()]; r.readFully(all);
            ByteBuffer bb = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
            int p = 12;
            while (p + 8 <= all.length) {
                int id = bb.getInt(p), sz = bb.getInt(p + 4);
                if (id == 0x61746164) { // "data"
                    int n = Math.min(sz, all.length - p - 8) / 2; float[] x = new float[n];
                    for (int i = 0; i < n; i++) x[i] = bb.getShort(p + 8 + 2 * i) / 32768f;
                    return x;
                }
                p += 8 + sz + (sz & 1);
            }
        }
        throw new IllegalArgumentException("нет data в " + f);
    }

    void bench(String file, CtcModel.Backend be, int T, float[] pcm) {
        File f = new File(dir(), file);
        if (!f.exists()) { say("нет файла " + f); return; }
        try {
            long t0 = System.nanoTime();
            CtcModel m = new CtcModel(f.getPath(), be, T, dir().getPath());
            long t1 = System.nanoTime();
            int nf = T > 0 ? T : MelFrontend.frames(pcm.length);
            float[] feats = mel.compute(pcm, pcm.length, nf);
            long t2 = System.nanoTime();
            String txt = null; long best = Long.MAX_VALUE;
            for (int i = 0; i < 5; i++) {
                long a = System.nanoTime();
                float[][] lp = m.run(feats, nf);
                best = Math.min(best, System.nanoTime() - a);
                txt = m.greedy(lp, CtcModel.encFrames(MelFrontend.frames(pcm.length)));
            }
            say(String.format("%s %s: загрузка %.1f с, мел %.0f мс, прогон %.0f мс (аудио %.1f с, окно %s)\n  → %s",
                    file, be, (t1 - t0) / 1e9, (t2 - t1) / 1e6, best / 1e6, pcm.length / 16000f,
                    T > 0 ? (T / 100f) + " с" : "динамика", txt));
            m.close();
        } catch (Throwable e) { say(file + " " + be + " ОШИБКА: " + e); Log.e(TAG, "bench", e); }
    }

    void runAll() {
        try {
            float[] pcm = readWav16k(new File(dir(), "test.wav"));
            bench("gigaam_ctc_int8.onnx", CtcModel.Backend.CPU, 0, pcm);
            bench("gigaam_ctc_fp32.onnx", CtcModel.Backend.CPU, 0, pcm);
            bench("gigaam_ctc_T1600_fp32.onnx", CtcModel.Backend.QNN_HTP, 1600, pcm);
            bench("gigaam_ctc_T1600_qdq.onnx", CtcModel.Backend.QNN_HTP, 1600, pcm);
            say("готово");
        } catch (Throwable e) { say("ОШИБКА " + e); }
    }

    void mic() {
        int sr = 16000, n = sr * 6;
        AudioRecord ar = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sr,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, n * 2);
        short[] s = new short[n]; say("говори…"); ar.startRecording();
        int got = 0; while (got < n) { int r = ar.read(s, got, n - got); if (r <= 0) break; got += r; }
        ar.stop(); ar.release();
        float[] pcm = new float[got]; for (int i = 0; i < got; i++) pcm[i] = s[i] / 32768f;
        bench("gigaam_ctc_int8.onnx", CtcModel.Backend.CPU, 0, pcm);
    }

    @Override public void onRequestPermissionsResult(int c, String[] p, int[] g) {
        if (g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) new Thread(this::mic).start();
    }
}
