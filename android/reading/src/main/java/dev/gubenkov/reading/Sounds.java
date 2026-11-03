package dev.gubenkov.reading;

/**
 * Звуки-сигналы, синтезированные в коде (без файлов и лицензий).
 * {@link #success(int)} — восходящее арпеджио до-ми-соль-до с финальным аккордом, ~0,9 с.
 */
public final class Sounds {
    private Sounds() {}

    /** Случайный звук успеха из набора. */
    public static short[] randomSuccess(int sr, java.util.Random rnd) {
        switch (rnd.nextInt(4)) {
            case 0: return coin(sr);
            case 1: return levelUp(sr);
            case 2: return sparkle(sr, rnd);
            default: return success(sr);
        }
    }

    /** «Монетка»: два коротких квадратных тона си5→ми6, как в аркадах. */
    public static short[] coin(int sr) {
        double[] x = new double[(int) (sr * 0.55)];
        square(x, sr, 0.0, 0.08, 987.77, 0.5);
        square(x, sr, 0.08, 0.45, 1318.5, 0.5);
        return norm(x);
    }

    /** «Новый уровень»: быстрая восходящая гамма квадратной волной и финальная нота с вибрато. */
    public static short[] levelUp(int sr) {
        double[] f = {523.25, 587.33, 659.25, 698.46, 783.99, 880.0, 987.77};
        double step = 0.055, tail = 0.5;
        double[] x = new double[(int) (sr * (step * f.length + tail))];
        for (int k = 0; k < f.length; k++) square(x, sr, k * step, step * 1.2, f[k], 0.35);
        int s0 = (int) (step * f.length * sr), len = (int) (tail * sr);
        for (int i = 0; i < len && s0 + i < x.length; i++) {
            double t = i / (double) sr, fr = 1046.5 * (1 + 0.012 * Math.sin(2 * Math.PI * 7 * t));
            double env = Math.exp(-t * 5) * (i > len - sr / 100 ? (len - i) / (sr / 100.0) : 1);
            x[s0 + i] += 0.4 * env * Math.signum(Math.sin(2 * Math.PI * fr * t)) * 0.6 + 0.4 * env * Math.sin(2 * Math.PI * fr * t);
        }
        return norm(x);
    }

    /** «Искры»: россыпь высоких колокольчиков пентатоники вверх. */
    public static short[] sparkle(int sr, java.util.Random rnd) {
        double[] pent = {1046.5, 1174.7, 1318.5, 1568.0, 1760.0, 2093.0, 2349.3, 2637.0};
        double[] x = new double[(int) (sr * 1.0)];
        for (int k = 0; k < 9; k++) tone(x, sr, k * 0.06 + rnd.nextDouble() * 0.02, 0.35, pent[Math.min(pent.length - 1, k)], 0.3);
        return norm(x);
    }

    static void square(double[] x, int sr, double start, double dur, double f, double amp) {
        int s0 = (int) (start * sr), len = (int) (dur * sr), fade = sr / 200;
        for (int i = 0; i < len && s0 + i < x.length; i++) {
            double t = i / (double) sr;
            double env = Math.min(1, Math.min(i / (double) fade, (len - i) / (double) fade)) * Math.exp(-t * 2.5 / dur);
            double sq = 0; for (int h = 1; h <= 7; h += 2) sq += Math.sin(2 * Math.PI * f * h * t) / h;   // мягкий квадрат
            x[s0 + i] += amp * env * sq;
        }
    }

    static short[] norm(double[] x) {
        double peak = 1e-9; for (double v : x) peak = Math.max(peak, Math.abs(v));
        short[] out = new short[x.length];
        for (int i = 0; i < x.length; i++) out[i] = (short) Math.round(x[i] / peak * 0.8 * 32767);
        return out;
    }

    /** PCM 16 бит моно. */
    public static short[] success(int sr) {
        double[] notes = {523.25, 659.25, 783.99};          // до5, ми5, соль5
        double step = 0.11, tail = 0.75;
        int n = (int) (sr * (step * notes.length + tail));
        double[] x = new double[n];
        for (int k = 0; k < notes.length; k++) tone(x, sr, k * step, 0.22, notes[k], 0.55);
        double t = step * notes.length;                       // финальный аккорд до-мажор + до6
        for (double f : new double[]{523.25, 659.25, 783.99, 1046.5}) tone(x, sr, t, tail, f, 0.32);
        tone(x, sr, t + 0.05, 0.4, 2093.0, 0.08);             // «искра» сверху
        double peak = 1e-9; for (double v : x) peak = Math.max(peak, Math.abs(v));
        short[] out = new short[n];
        for (int i = 0; i < n; i++) out[i] = (short) Math.round(x[i] / peak * 0.8 * 32767);
        return out;
    }

    /** Колокольчик: основной тон + тихие обертоны, атака 5 мс, экспоненциальный спад. */
    static void tone(double[] x, int sr, double start, double dur, double f, double amp) {
        int s0 = (int) (start * sr), len = (int) (dur * sr), att = sr / 200;
        for (int i = 0; i < len && s0 + i < x.length; i++) {
            double t = i / (double) sr;
            double env = (i < att ? i / (double) att : 1.0) * Math.exp(-t * 4.0 / dur);
            if (i > len - sr / 100) env *= (len - i) / (sr / 100.0);    // мягкий конец без щелчка
            double w = Math.sin(2 * Math.PI * f * t) + 0.25 * Math.sin(4 * Math.PI * f * t) + 0.08 * Math.sin(6 * Math.PI * f * t);
            x[s0 + i] += amp * env * w;
        }
    }
}
