package dev.gubenkov.reading;

/**
 * Лог-мел признаки, совпадающие с препроцессором GigaAM-v3:
 * torchaudio MelSpectrogram(sr=16000, n_fft=320, win=320 Hann periodic, hop=160, center=False,
 * power=2, n_mels=64, mel_scale=htk, norm=None, f 0..8000) + ln(clamp(x, 1e-9, 1e9)).
 */
public final class MelFrontend {
    public static final int SR = 16000, N_FFT = 320, HOP = 160, N_MELS = 64, N_FREQ = N_FFT / 2 + 1;
    public static final float LOG_FLOOR = (float) Math.log(1e-9);

    private final float[] window = new float[N_FFT];
    private final float[] cos = new float[N_FREQ * N_FFT], sin = new float[N_FREQ * N_FFT];
    private final float[][] fbank = new float[N_MELS][N_FREQ];

    public MelFrontend() {
        for (int n = 0; n < N_FFT; n++) window[n] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * n / N_FFT));
        for (int k = 0; k < N_FREQ; k++)
            for (int n = 0; n < N_FFT; n++) {
                double a = 2 * Math.PI * k * n / N_FFT;
                cos[k * N_FFT + n] = (float) Math.cos(a);
                sin[k * N_FFT + n] = (float) Math.sin(a);
            }
        // torchaudio.functional.melscale_fbanks, htk, norm=None
        double mMin = hz2mel(0), mMax = hz2mel(SR / 2.0);
        double[] fPts = new double[N_MELS + 2];
        for (int i = 0; i < fPts.length; i++) fPts[i] = mel2hz(mMin + (mMax - mMin) * i / (N_MELS + 1));
        for (int k = 0; k < N_FREQ; k++) {
            double f = (double) SR / 2 * k / (N_FREQ - 1);
            for (int m = 0; m < N_MELS; m++) {
                double down = (f - fPts[m]) / (fPts[m + 1] - fPts[m]);
                double up = (fPts[m + 2] - f) / (fPts[m + 2] - fPts[m + 1]);
                fbank[m][k] = (float) Math.max(0, Math.min(down, up));
            }
        }
    }

    private static double hz2mel(double f) { return 2595.0 * Math.log10(1.0 + f / 700.0); }
    private static double mel2hz(double m) { return 700.0 * (Math.pow(10.0, m / 2595.0) - 1.0); }

    public static int frames(int samples) { return samples < N_FFT ? 0 : (samples - N_FFT) / HOP + 1; }

    /** Возвращает [N_MELS * outFrames] в раскладке [mel][frame]; хвост после реальных кадров = LOG_FLOOR. */
    public float[] compute(float[] pcm, int samples, int outFrames) {
        int nf = Math.min(frames(samples), outFrames);
        float[] out = new float[N_MELS * outFrames];
        java.util.Arrays.fill(out, LOG_FLOOR);
        float[] buf = new float[N_FFT], pow = new float[N_FREQ];
        for (int t = 0; t < nf; t++) {
            int off = t * HOP;
            for (int n = 0; n < N_FFT; n++) buf[n] = pcm[off + n] * window[n];
            for (int k = 0; k < N_FREQ; k++) {
                float re = 0, im = 0; int b = k * N_FFT;
                for (int n = 0; n < N_FFT; n++) { re += buf[n] * cos[b + n]; im -= buf[n] * sin[b + n]; }
                pow[k] = re * re + im * im;
            }
            for (int m = 0; m < N_MELS; m++) {
                float s = 0; float[] fb = fbank[m];
                for (int k = 0; k < N_FREQ; k++) s += fb[k] * pow[k];
                out[m * outFrames + t] = (float) Math.log(Math.min(1e9f, Math.max(1e-9f, s)));
            }
        }
        return out;
    }
}
