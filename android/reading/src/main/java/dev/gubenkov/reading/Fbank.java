package dev.gubenkov.reading;

import java.util.Arrays;

/**
 * Kaldi fbank для моделей голосовых отпечатков (3D-Speaker CAM++), совпадает с
 * torchaudio.compliance.kaldi.fbank / kaldi-native-fbank при настройках обучения модели:
 * 16 кГц, кадр 25 мс (400), шаг 10 мс (160), snip_edges, без dither, вычитание DC кадра, preemph 0.97,
 * окно povey, БПФ 512, мощность, 80 мел-фильтров Kaldi (1127·ln(1+f/700)) 20 Гц…8 кГц, ln(max(x, FLT_EPSILON)).
 *
 * <p>Перед моделью: отбор речевых кадров по энергии ({@link #voiced}) и вычитание среднего по времени.
 * Проверка — FbankTest против ml/speaker_fixture.py.
 */
public final class Fbank {
    public static final int SR = 16000, WIN = 400, HOP = 160, NFFT = 512, BINS = 80;
    static final float PREEMPH = 0.97f, EPS = Math.ulp(1.0f);   // FLT_EPSILON
    /** Речь: энергия кадра ≥ max(95-й перцентиль − VAD_DB, VAD_FLOOR) дБFS. */
    public static final double VAD_DB = 30, VAD_FLOOR = -55;
    /** Меньше стольких речевых кадров — берём все кадры (слишком мало для отбора). */
    static final int MIN_VOICED = 50;

    private final float[] window = new float[WIN];
    private final float[][] mel = new float[BINS][];
    private final int[] melFirst = new int[BINS];
    private final double[] cosT = new double[NFFT / 2], sinT = new double[NFFT / 2];
    private final int[] rev = new int[NFFT];

    public Fbank() {
        for (int i = 0; i < WIN; i++) window[i] = (float) Math.pow(0.5 - 0.5 * Math.cos(2 * Math.PI * i / (WIN - 1)), 0.85);
        double lo = melScale(20), hi = melScale(SR / 2.0), d = (hi - lo) / (BINS + 1);
        int nb = NFFT / 2;   // как в Kaldi: бины 0..255, найквист не используется
        for (int m = 0; m < BINS; m++) {
            double l = lo + m * d, c = l + d, r = c + d;
            float[] w = new float[nb]; int first = -1, last = -1;
            for (int k = 0; k < nb; k++) {
                double f = melScale((double) SR * k / NFFT);
                if (f > l && f < r) {
                    w[k] = (float) (f <= c ? (f - l) / (c - l) : (r - f) / (r - c));
                    if (first < 0) first = k; last = k;
                }
            }
            melFirst[m] = first; mel[m] = Arrays.copyOfRange(w, first, last + 1);
        }
        for (int k = 0; k < NFFT / 2; k++) { cosT[k] = Math.cos(2 * Math.PI * k / NFFT); sinT[k] = -Math.sin(2 * Math.PI * k / NFFT); }
        int bits = Integer.numberOfTrailingZeros(NFFT);
        for (int i = 0; i < NFFT; i++) rev[i] = Integer.reverse(i) >>> (32 - bits);
    }

    static double melScale(double f) { return 1127.0 * Math.log(1 + f / 700.0); }

    public static int frames(int samples) { return samples < WIN ? 0 : (samples - WIN) / HOP + 1; }

    /** Сырые лог-мел признаки [кадр][80], без вычитания среднего. */
    public float[][] compute(float[] x, int n) {
        int T = frames(n);
        float[][] out = new float[T][BINS];
        double[] re = new double[NFFT], im = new double[NFFT]; float[] fr = new float[WIN]; double[] pow = new double[NFFT / 2];
        for (int t = 0; t < T; t++) {
            int off = t * HOP; double mean = 0;
            for (int i = 0; i < WIN; i++) { fr[i] = x[off + i]; mean += fr[i]; }
            mean /= WIN;
            for (int i = 0; i < WIN; i++) fr[i] -= (float) mean;
            for (int i = WIN - 1; i > 0; i--) fr[i] -= PREEMPH * fr[i - 1];
            fr[0] -= PREEMPH * fr[0];
            Arrays.fill(re, 0); Arrays.fill(im, 0);
            for (int i = 0; i < WIN; i++) re[rev[i]] = fr[i] * window[i];
            fft(re, im);
            for (int k = 0; k < NFFT / 2; k++) pow[k] = re[k] * re[k] + im[k] * im[k];
            for (int m = 0; m < BINS; m++) {
                double s = 0; float[] w = mel[m]; int f0 = melFirst[m];
                for (int k = 0; k < w.length; k++) s += w[k] * pow[f0 + k];
                out[t][m] = (float) Math.log(Math.max(s, EPS));
            }
        }
        return out;
    }

    /** БПФ на месте; вход уже переставлен по битам. */
    private void fft(double[] re, double[] im) {
        for (int len = 2; len <= NFFT; len <<= 1) {
            int half = len >> 1, step = NFFT / len;
            for (int i = 0; i < NFFT; i += len)
                for (int j = 0; j < half; j++) {
                    double wr = cosT[j * step], wi = sinT[j * step];
                    int a = i + j, b = a + half;
                    double tr = re[b] * wr - im[b] * wi, ti = re[b] * wi + im[b] * wr;
                    re[b] = re[a] - tr; im[b] = im[a] - ti; re[a] += tr; im[a] += ti;
                }
        }
    }

    /** Маска речевых кадров (та же сетка, что у {@link #compute}). Совпадает с ml/speaker_eval.py voiced(). */
    public static boolean[] voiced(float[] x, int n) {
        int T = frames(n); boolean[] m = new boolean[T];
        if (T == 0) return m;
        double[] e = new double[T];
        for (int t = 0; t < T; t++) {
            double s = 0; int off = t * HOP;
            for (int i = 0; i < WIN; i++) { double v = x[off + i]; s += v * v; }
            e[t] = 10 * Math.log10(s / WIN + 1e-10);
        }
        double[] srt = e.clone(); Arrays.sort(srt);
        double pos = (T - 1) * 0.95; int lo = (int) Math.floor(pos); int hi = Math.min(T - 1, lo + 1);
        double p95 = srt[lo] + (srt[hi] - srt[lo]) * (pos - lo);   // как numpy.percentile (linear)
        double thr = Math.max(p95 - VAD_DB, VAD_FLOOR);
        for (int t = 0; t < T; t++) m[t] = e[t] >= thr;
        return m;
    }

    /** Признаки для модели: речевые кадры (не больше maxFrames, с начала), минус среднее; раскладка [t*80 + m]. */
    public float[] modelInput(float[] x, int n, int maxFrames, int[] outFrames) {
        float[][] f = compute(x, n);
        boolean[] v = voiced(x, n);
        int cnt = 0; for (boolean b : v) if (b) cnt++;
        boolean all = cnt < MIN_VOICED;
        int T = Math.min(all ? f.length : cnt, maxFrames);
        float[] out = new float[T * BINS]; double[] mean = new double[BINS];
        int j = 0;
        for (int t = 0; t < f.length && j < T; t++) {
            if (!all && !v[t]) continue;
            System.arraycopy(f[t], 0, out, j * BINS, BINS);
            for (int m = 0; m < BINS; m++) mean[m] += f[t][m];
            j++;
        }
        for (int m = 0; m < BINS; m++) mean[m] /= Math.max(1, T);
        for (int t = 0; t < T; t++) for (int m = 0; m < BINS; m++) out[t * BINS + m] -= (float) mean[m];
        outFrames[0] = T;
        return out;
    }

    /** Секунд речи в записи (по маске). */
    public static float speechSeconds(float[] x, int n) {
        int c = 0; for (boolean b : voiced(x, n)) if (b) c++;
        return c * HOP / (float) SR;
    }
}
