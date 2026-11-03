package dev.gubenkov.reading;

import java.util.ArrayList;
import java.util.List;

/**
 * Потоковое распознавание длинного чтения окнами модели (статическое окно HTP или динамика CPU).
 * Мел-кадры считаются один раз. Окно k начинается на k*STEP и захватывает CTX слева; когда звук
 * выходит за k*STEP + STEP + CTX, выходные кадры [k*STEP, (k+1)*STEP) фиксируются, окно сдвигается.
 * Не потокобезопасен: feed() и decode() — из одного рабочего потока.
 */
public final class StreamingRecognizer {
    public static final int WIN = 1600, STEP = 1200, CTX = 200; // кадры признаков (10 мс)

    private final CtcModel model;
    private final MelFrontend mel = new MelFrontend();
    private float[] pcm = new float[16000 * 30];
    private int samples = 0;
    private final List<float[]> feats = new ArrayList<>(); // [64] на кадр
    private final List<float[]> committed = new ArrayList<>(); // выход CTC, кадры 40 мс
    private int windowK = 0;
    private final float[] buf = new float[MelFrontend.N_FFT], pow = new float[MelFrontend.N_FREQ];
    private float[][] lastLive = new float[0][];

    public StreamingRecognizer(CtcModel model) { this.model = model; }

    public void feed(float[] x, int n) {
        if (samples + n > pcm.length) pcm = java.util.Arrays.copyOf(pcm, Math.max(pcm.length * 2, samples + n));
        System.arraycopy(x, 0, pcm, samples, n); samples += n;
        int have = MelFrontend.frames(samples);
        while (feats.size() < have) {
            float[] f = new float[MelFrontend.N_MELS];
            mel.frame(pcm, feats.size() * MelFrontend.HOP, f, buf, pow);
            feats.add(f);
        }
    }

    public int samples() { return samples; }
    public float[] audio() { return java.util.Arrays.copyOf(pcm, samples); }

    /** Прогоняет текущее окно; возвращает гипотезу по всему прочитанному. */
    public String decode() throws ai.onnxruntime.OrtException {
        while (true) {
            int start = Math.max(0, windowK * STEP - CTX);
            int commitEnd = (windowK + 1) * STEP;
            if (feats.size() >= commitEnd + CTX) {
                float[][] lp = runWindow(start, Math.min(feats.size(), start + WIN));
                int from = (windowK * STEP - start) / 4, to = (commitEnd - start) / 4;
                for (int t = from; t < to && t < lp.length; t++) committed.add(lp[t]);
                windowK++;
                continue;
            }
            float[][] lp = runWindow(start, feats.size());
            int from = (windowK * STEP - start) / 4;
            int valid = CtcModel.encFrames(feats.size() - start);
            lastLive = new float[Math.max(0, valid - from)][];
            for (int t = from; t < valid; t++) lastLive[t - from] = lp[t];
            break;
        }
        float[][] all = new float[committed.size() + lastLive.length][];
        for (int i = 0; i < committed.size(); i++) all[i] = committed.get(i);
        System.arraycopy(lastLive, 0, all, committed.size(), lastLive.length);
        return model.greedy(all, all.length);
    }

    private float[][] runWindow(int start, int end) throws ai.onnxruntime.OrtException {
        int n = end - start;
        int T = model.frames > 0 ? model.frames : Math.max(n, 8);
        float[] x = new float[MelFrontend.N_MELS * T];
        java.util.Arrays.fill(x, MelFrontend.LOG_FLOOR);
        for (int t = 0; t < n && t < T; t++) {
            float[] f = feats.get(start + t);
            for (int m = 0; m < MelFrontend.N_MELS; m++) x[m * T + t] = f[m];
        }
        return model.run(x, T);
    }
}
