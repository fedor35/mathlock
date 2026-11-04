package dev.gubenkov.reading;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Какую гласную ребёнок сделал ударной: обученный классификатор (ml/stress, docs/STRESS.md).
 *
 * Вход — то, что уже есть после распознавания: лог-вероятности CTC GigaAM [T][34] (кадр 40 мс),
 * PCM 16 кГц и текст с ударениями. Шаги повторяют ml/stress/common.py и feats.py:
 * CTC forced alignment текста (Витерби) → отрезки гласных многосложных слов → признаки
 * (длительности, энергия и F0 из лог-мел/PCM, DCT лог-мел, CTC гласных; нормировка внутри слова и предложения)
 * → оценка каждой гласной маленькой сетью → softmax по гласным слова.
 * Веса — JSON из ml/stress/export.py в resources этого модуля (stress_model.json).
 */
public final class StressClassifier {
    public static final String[] VOCAB = CtcModel.GIGAAM_VOCAB;
    public static final int BLANK = 33, N_RAW = 9, N_DCT = 20, SEG_MAX = 8;
    /** Пол лог-мел относительно максимума записи (ниже — шум округления/микрофона). */
    static final double MEL_RANGE = 10.0;
    static final String VOWELS = "аеиоуыэюя";
    static final int SR = 16000, HOP = 160, F0_WIN = 640, F0_LAG_MIN = 16000 / 600, F0_LAG_MAX = 16000 / 60;
    static final double F0_VOICED = 0.3;

    /** Результат по одному многосложному слову текста. */
    public static final class WordStress {
        public final int word;          // индекс в ReadingText.words
        public final String letters;
        public final int expected;      // ударная гласная по тексту (-1 — не размечена)
        public final int predicted;     // ударная гласная по звуку
        public final float[] prob;      // вероятности по гласным слова
        public final int[] vowelStart, vowelEnd; // отрезки гласных, кадры 40 мс [start, end)
        WordStress(int word, String letters, int expected, int predicted, float[] prob, int[] vs, int[] ve) {
            this.word = word; this.letters = letters; this.expected = expected; this.predicted = predicted;
            this.prob = prob; this.vowelStart = vs; this.vowelEnd = ve;
        }
        /** Уверенность, что ударение не там, где по тексту. */
        public float wrongProb() { return expected < 0 ? 0f : 1f - prob[expected]; }
    }

    final String[] blocks;
    final double[] gsd, mu, sd;
    final double[][] w1; final double[] b1, w2; final double b2;
    // LightGBM (hidden = -1): деревья подряд; ссылка >=0 — внутренний узел дерева, <0 — лист -(номер+1)
    int[] gFeat, gLeft, gRight, gNoff, gLoff; double[] gThr, gLeaf;
    static final double[][] DCT = dct(64, N_DCT);

    StressClassifier(String[] blocks, double[] gsd, double[] mu, double[] sd, double[][] w1, double[] b1, double[] w2, double b2) {
        this.blocks = blocks; this.gsd = gsd; this.mu = mu; this.sd = sd; this.w1 = w1; this.b1 = b1; this.w2 = w2; this.b2 = b2;
    }

    /** Веса из JSON (ml/stress/export.py): blocks, gsd, mu, sd, hidden, w1 (hidden×d построчно), b1, w2, b2.
     *  hidden=0 — линейная, hidden=-1 — деревья LightGBM (gbm_*). */
    public static StressClassifier fromJson(String js) {
        Matcher bm = Pattern.compile("\"blocks\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(js);
        if (!bm.find()) throw new IllegalArgumentException("нет blocks");
        String[] blocks = bm.group(1).replace("\"", "").trim().split("\\s*,\\s*");
        double[] mu = arr(js, "mu"), sd = arr(js, "sd"), gsd = arr(js, "gsd"), w2 = arr(js, "w2");
        int hidden = (int) arr(js, "hidden")[0];
        double b2 = arr(js, "b2")[0];
        double[][] w1 = null; double[] b1 = null;
        if (hidden < 0) {
            StressClassifier c = new StressClassifier(blocks, gsd, mu, sd, null, null, w2, b2);
            c.gFeat = ints(arr(js, "gbm_feat")); c.gLeft = ints(arr(js, "gbm_left")); c.gRight = ints(arr(js, "gbm_right"));
            c.gNoff = ints(arr(js, "gbm_noff")); c.gLoff = ints(arr(js, "gbm_loff"));
            c.gThr = arr(js, "gbm_thr"); c.gLeaf = arr(js, "gbm_leaf");
            return c;
        }
        if (hidden > 0) {
            double[] flat = arr(js, "w1"); b1 = arr(js, "b1");
            int d = mu.length; w1 = new double[hidden][d];
            for (int h = 0; h < hidden; h++) System.arraycopy(flat, h * d, w1[h], 0, d);
        }
        return new StressClassifier(blocks, gsd, mu, sd, w1, b1, w2, b2);
    }

    /** Веса, лежащие в модуле: resources/dev/gubenkov/reading/stress_model.json. */
    public static StressClassifier fromResource() throws java.io.IOException {
        try (java.io.InputStream in = StressClassifier.class.getResourceAsStream("stress_model.json")) {
            if (in == null) throw new java.io.FileNotFoundException("stress_model.json");
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n); // readAllBytes — только с API 33
            return fromJson(new String(bo.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    static double[] arr(String js, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(\\[[^\\]]*\\]|[-0-9.eE+]+)").matcher(js);
        if (!m.find()) throw new IllegalArgumentException("нет " + key);
        String s = m.group(1).replace("[", "").replace("]", "").trim();
        if (s.isEmpty()) return new double[0];
        String[] p = s.split("\\s*,\\s*"); double[] out = new double[p.length];
        for (int i = 0; i < p.length; i++) out[i] = Double.parseDouble(p[i]);
        return out;
    }

    static int[] ints(double[] a) { int[] o = new int[a.length]; for (int i = 0; i < a.length; i++) o[i] = (int) a[i]; return o; }

    static double[][] dct(int nIn, int nOut) {
        double[][] m = new double[nOut][nIn];
        for (int k = 0; k < nOut; k++)
            for (int n = 0; n < nIn; n++)
                m[k][n] = Math.cos(Math.PI / nIn * (n + 0.5) * k) * Math.sqrt(2.0 / nIn) / (k == 0 ? Math.sqrt(2.0) : 1.0);
        return m;
    }

    static int tokenIndex(char c) {
        for (int i = 0; i < VOCAB.length; i++) if (VOCAB[i].charAt(0) == c) return i;
        return -1;
    }

    // ---------------------------------------------------------------- выравнивание

    /** CTC Витерби; -> {start[], end[]} кадров токенов (end включительно) или null. Как common.forced_align. */
    public static int[][] forcedAlign(float[][] lp, int T, int[] tokens) {
        int L = tokens.length, S = 2 * L + 1;
        if (T <= 0 || L == 0) return null;
        int[] lab = new int[S]; boolean[] skip = new boolean[S];
        for (int s = 0; s < S; s++) lab[s] = s % 2 == 1 ? tokens[s / 2] : BLANK;
        for (int s = 3; s < S; s += 2) skip[s] = lab[s] != lab[s - 2];
        final double NEG = -1e30;
        double[] a = new double[S], na = new double[S];
        Arrays.fill(a, NEG);
        a[0] = lp[0][BLANK]; if (S > 1) a[1] = lp[0][lab[1]];
        byte[][] bp = new byte[T][S];
        for (int t = 1; t < T; t++) {
            for (int s = 0; s < S; s++) {
                double best = a[s]; int k = 0;
                if (s >= 1 && a[s - 1] > best) { best = a[s - 1]; k = 1; }
                if (s >= 2 && skip[s] && a[s - 2] > best) { best = a[s - 2]; k = 2; }
                na[s] = best <= NEG / 2 ? NEG : best + lp[t][lab[s]];
                bp[t][s] = (byte) k;
            }
            double[] tmp = a; a = na; na = tmp;
        }
        int s = (S >= 2 && a[S - 2] > a[S - 1]) ? S - 2 : S - 1;
        if (a[s] <= NEG / 2) return null;
        int[] path = new int[T];
        for (int t = T - 1; t >= 0; t--) { path[t] = s; s -= bp[t][s]; }
        if (path[0] > 1) return null;
        int[] start = new int[L], end = new int[L];
        Arrays.fill(start, -1); Arrays.fill(end, -1);
        for (int t = 0; t < T; t++) {
            int p = path[t];
            if (p % 2 == 1) { int i = p / 2; if (start[i] < 0) start[i] = t; end[i] = t; }
        }
        return new int[][]{start, end};
    }

    // ---------------------------------------------------------------- F0

    /** F0 (Гц) кадра мел f: окно 640 отсчётов с 160*f, нормированная автокорреляция; 0 — невокализовано. */
    public static double f0Frame(float[] pcm, int samples, int f) {
        double[] x = new double[F0_WIN]; double mean = 0;
        for (int n = 0; n < F0_WIN; n++) { int i = f * HOP + n; x[n] = i < samples ? pcm[i] : 0; mean += x[n]; }
        mean /= F0_WIN;
        double r0 = 0;
        for (int n = 0; n < F0_WIN; n++) { x[n] -= mean; r0 += x[n] * x[n]; }
        double best = -1e30; int bk = F0_LAG_MIN;
        for (int k = F0_LAG_MIN; k <= F0_LAG_MAX; k++) {
            double r = 0;
            for (int n = 0; n < F0_WIN - k; n++) r += x[n] * x[n + k];
            if (r > best) { best = r; bk = k; }
        }
        return (r0 > 1e-10 && best > F0_VOICED * r0) ? (double) SR / bk : 0.0;
    }

    // ---------------------------------------------------------------- признаки

    /** Гласная многосложного слова: сырые признаки до нормировки. */
    static final class Vowel {
        int word, sent, vi, nv, letter, a, b, sb;
        double cc;
        double[] raw = new double[N_RAW], dctv = new double[N_DCT], ctc = new double[11];
        double own;
    }

    static final int[] CTC_COLS = new int[11];
    static {
        for (int i = 0; i < 9; i++) CTC_COLS[i] = tokenIndex(VOWELS.charAt(i));
        CTC_COLS[9] = 0; CTC_COLS[10] = BLANK;
    }

    /** Признаки всех гласных (для проверки с Python): строки в порядке гласных, столбцы — blocks. */
    /** Выравнивание текста и отрезки гласных многосложных слов (без акустики); null — не выровнялось. */
    public static List<Vowel> segments(ReadingText text, float[][] lp, int T) {
        List<ReadingText.Word> words = text.words;
        StringBuilder tgt = new StringBuilder();
        for (int i = 0; i < words.size(); i++) { if (i > 0) tgt.append(' '); tgt.append(words.get(i).letters); }
        int[] tok = new int[tgt.length()];
        for (int i = 0; i < tok.length; i++) tok[i] = tokenIndex(tgt.charAt(i));
        int[][] al = forcedAlign(lp, T, tok);
        if (al == null) return null;
        int[] start = al[0], end = al[1];
        int L = tok.length;
        // номера предложений: как common.parse_words (знак .!?… между словами)
        int[] sentOf = new int[words.size()]; int sent = 0, prevEnd = 0;
        for (int i = 0; i < words.size(); i++) {
            ReadingText.Word w = words.get(i);
            if (i > 0) for (int c = prevEnd; c < w.start; c++) if (".!?…".indexOf(text.source.charAt(c)) >= 0) { sent++; break; }
            sentOf[i] = sent; prevEnd = w.end;
        }
        List<Vowel> vs = new ArrayList<>();
        int k = 0;
        for (int wi = 0; wi < words.size(); wi++) {
            String w = words.get(wi).letters;
            int k0 = k; k += w.length() + 1;
            List<Integer> vi = new ArrayList<>();
            for (int j = 0; j < w.length(); j++) if (VOWELS.indexOf(w.charAt(j)) >= 0) vi.add(k0 + j);
            if (vi.size() < 2) continue;
            int last = k0 + w.length() - 1;
            int wend = Math.min(nxt(start, last, L, T), end[last] + 1 + SEG_MAX);
            int nv = vi.size();
            for (int j = 0; j < nv; j++) {
                int i = vi.get(j);
                Vowel v = new Vowel();
                v.word = wi; v.sent = sentOf[wi]; v.vi = j; v.nv = nv; v.letter = tok[i];
                v.a = start[i]; v.b = Math.min(nxt(start, i, L, T), start[i] + SEG_MAX);
                v.sb = j + 1 < nv ? start[vi.get(j + 1)] : wend;
                double cen = (start[i] + end[i] + 1) / 2.0;
                v.cc = (j + 1 < nv ? (start[vi.get(j + 1)] + end[vi.get(j + 1)] + 1) / 2.0 : wend) - cen;
                vs.add(v);
            }
        }
        return vs;
    }

    /** Признаки всех гласных (для проверки с Python): строки в порядке гласных, столбцы — blocks. */
    public double[][] features(ReadingText text, float[][] lp, int T, float[] pcm, int samples, List<Vowel> outVowels) {
        List<Vowel> vs = segments(text, lp, T);
        if (vs == null) return null;
        if (outVowels != null) { outVowels.clear(); outVowels.addAll(vs); }
        int Tm = MelFrontend.frames(samples);
        float[] mel = new MelFrontend().compute(pcm, samples, Tm);
        float mx = -Float.MAX_VALUE; for (float v : mel) mx = Math.max(mx, v);
        float floor = (float) (mx - MEL_RANGE);
        for (int i = 0; i < mel.length; i++) if (mel[i] < floor) mel[i] = floor;
        double[] energy = new double[Tm];
        for (int t = 0; t < Tm; t++) {
            double s = 0; for (int m = 0; m < 64; m++) s += Math.exp(mel[m * Tm + t]);
            energy[t] = Math.log(s + 1e-12);
        }
        double[] f0 = new double[Tm]; Arrays.fill(f0, -1);
        for (Vowel v : vs) {
            int ma = 4 * v.a, mb = Math.min(4 * v.b, Tm);
            if (mb <= ma) { ma = Math.min(ma, Tm - 1); mb = ma + 1; }
            double es = 0, ex = -1e300; int nvo = 0; double lfs = 0, lfx = -1e300, ts = 0;
            for (int t = ma; t < mb; t++) {
                es += energy[t]; ex = Math.max(ex, energy[t]);
                if (f0[t] < 0) f0[t] = f0Frame(pcm, samples, t);
                if (f0[t] > 0) { nvo++; double lf = Math.log(f0[t]); lfs += lf; lfx = Math.max(lfx, lf); ts += t * 0.01; }
            }
            int n = mb - ma;
            double slope = 0;
            if (nvo >= 3) {
                double tm = ts / nvo, ym = lfs / nvo, sxy = 0, sxx = 0;
                for (int t = ma; t < mb; t++) if (f0[t] > 0) {
                    double dt = t * 0.01 - tm; sxy += dt * (Math.log(f0[t]) - ym); sxx += dt * dt;
                }
                slope = sxx > 0 ? sxy / sxx : 0;
            }
            v.raw[0] = Math.log((v.b - v.a) * 0.04);
            v.raw[1] = Math.log(Math.max(v.sb - v.a, 1) * 0.04);
            v.raw[2] = Math.log(Math.max(v.cc, 0.5) * 0.04);
            v.raw[3] = es / n; v.raw[4] = ex;
            v.raw[5] = nvo > 0 ? lfs / nvo : Double.NaN;
            v.raw[6] = nvo > 0 ? lfx : Double.NaN;
            v.raw[7] = slope; v.raw[8] = (double) nvo / n;
            double[] mm = new double[64];
            for (int m = 0; m < 64; m++) { double s = 0; for (int t = ma; t < mb; t++) s += mel[m * Tm + t]; mm[m] = s / n; }
            for (int c = 0; c < N_DCT; c++) { double s = 0; for (int m = 0; m < 64; m++) s += DCT[c][m] * mm[m]; v.dctv[c] = s; }
            double[] cm = new double[34];
            for (int t = v.a; t < v.b; t++) for (int c = 0; c < 34; c++) cm[c] += lp[t][c];
            for (int c = 0; c < 34; c++) cm[c] /= (v.b - v.a);
            for (int c = 0; c < 11; c++) v.ctc[c] = cm[CTC_COLS[c]];
            v.own = cm[v.letter];
        }
        return normalize(vs);
    }

    static int nxt(int[] start, int i, int L, int T) { return i + 1 < L ? start[i + 1] : T; }

    double[][] normalize(List<Vowel> vs) {
        int N = vs.size();
        double[][] raw = new double[N][];
        for (int i = 0; i < N; i++) raw[i] = vs.get(i).raw.clone();
        // группы: предложения и слова (подряд)
        int[] sg = new int[N], wg = new int[N];
        for (int i = 0; i < N; i++) { sg[i] = vs.get(i).sent; wg[i] = vs.get(i).word; }
        // пропуски F0 — средним по предложению, иначе 5.0
        for (int c = 0; c < N_RAW; c++)
            for (int i = 0; i < N; i++) if (Double.isNaN(raw[i][c])) {
                double s = 0; int n = 0;
                for (int j = 0; j < N; j++) if (sg[j] == sg[i] && !Double.isNaN(vs.get(j).raw[c])) { s += vs.get(j).raw[c]; n++; }
                raw[i][c] = n > 0 ? s / n : 5.0;
            }
        double[][] out = new double[N][];
        for (int i = 0; i < N; i++) {
            Vowel v = vs.get(i);
            List<Double> f = new ArrayList<>();
            for (String b : blocks) {
                switch (b) {
                    case "pros": {
                        double[] ms = mean(raw, sg, sg[i]), mw = mean(raw, wg, wg[i]);
                        double[] sdv = new double[N_RAW];
                        int n = 0;
                        for (int j = 0; j < N; j++) if (sg[j] == sg[i]) { n++; for (int c = 0; c < N_RAW; c++) sdv[c] += (raw[j][c] - ms[c]) * (raw[j][c] - ms[c]); }
                        for (int c = 0; c < N_RAW; c++) sdv[c] = Math.sqrt(sdv[c] / n) + 0.1 * gsd[c];
                        for (int c = 0; c < N_RAW; c++) f.add((raw[i][c] - mw[c]) / sdv[c]);
                        for (int c = 0; c < N_RAW; c++) f.add((raw[i][c] - ms[c]) / sdv[c]);
                        for (int c = 0; c < N_RAW; c++) {
                            double mx = -1e300;
                            for (int j = 0; j < N; j++) if (wg[j] == wg[i]) mx = Math.max(mx, raw[j][c]);
                            f.add(raw[i][c] >= mx - 1e-12 ? 1.0 : 0.0);
                        }
                        break;
                    }
                    case "spec": {
                        double[][] d = new double[N][]; for (int j = 0; j < N; j++) d[j] = vs.get(j).dctv;
                        double[] ms = mean(d, sg, sg[i]), mw = mean(d, wg, wg[i]);
                        for (int c = 0; c < N_DCT; c++) f.add(v.dctv[c] - ms[c]);
                        for (int c = 0; c < N_DCT; c++) f.add(v.dctv[c] - mw[c]);
                        break;
                    }
                    case "ctc": {
                        double[][] d = new double[N][]; for (int j = 0; j < N; j++) d[j] = vs.get(j).ctc;
                        double[] mw = mean(d, wg, wg[i]);
                        for (int c = 0; c < 11; c++) f.add(v.ctc[c] - mw[c]);
                        double s = 0; int n = 0;
                        for (int j = 0; j < N; j++) if (wg[j] == wg[i]) { s += vs.get(j).own; n++; }
                        f.add(v.own); f.add(v.own - s / n);
                        break;
                    }
                    case "pos": {
                        int nv = Math.min(v.nv, 5), vi = v.vi;
                        if (v.nv > 5 && v.vi >= 2) vi = Math.max(v.vi - (v.nv - 5), 2);
                        for (int nn = 2; nn <= 5; nn++) for (int ii = 0; ii < nn; ii++) f.add(nv == nn && vi == ii ? 1.0 : 0.0);
                        break;
                    }
                    default: throw new IllegalStateException("блок " + b);
                }
            }
            out[i] = new double[f.size()];
            for (int c = 0; c < out[i].length; c++) out[i][c] = f.get(c);
        }
        return out;
    }

    static double[] mean(double[][] x, int[] g, int gi) {
        double[] s = new double[x[0].length]; int n = 0;
        for (int j = 0; j < x.length; j++) if (g[j] == gi) { n++; for (int c = 0; c < s.length; c++) s[c] += x[j][c]; }
        for (int c = 0; c < s.length; c++) s[c] /= n;
        return s;
    }

    /** Оценка гласной (до softmax). */
    public double score(double[] x) {
        if (gFeat != null) {
            double s = 0;
            for (int k = 0; k < gNoff.length; k++) {
                int i = 0, no = gNoff[k];
                while (true) {
                    int j = (double) (float) x[gFeat[no + i]] <= gThr[no + i] ? gLeft[no + i] : gRight[no + i];
                    if (j < 0) { s += gLeaf[gLoff[k] - j - 1]; break; }
                    i = j;
                }
            }
            return s;
        }
        int d = mu.length;
        double[] z = new double[d];
        for (int c = 0; c < d; c++) z[c] = (x[c] - mu[c]) / sd[c];
        if (w1 == null) {
            double s = b2; for (int c = 0; c < d; c++) s += w2[c] * z[c];
            return s;
        }
        double s = b2;
        for (int h = 0; h < w1.length; h++) {
            double a = b1[h]; for (int c = 0; c < d; c++) a += w1[h][c] * z[c];
            if (a > 0) s += w2[h] * a;
        }
        return s;
    }

    /**
     * Главный вход: текст, лог-вероятности CTC [T][34] (кадр 40 мс от начала записи), PCM 16 кГц.
     * Возвращает многосложные слова с предсказанной ударной гласной; null — текст не выровнялся.
     */
    public List<WordStress> analyze(ReadingText text, float[][] lp, int T, float[] pcm, int samples) {
        List<Vowel> vs = new ArrayList<>();
        double[][] X = features(text, lp, T, pcm, samples, vs);
        if (X == null) return null;
        double[] sc = new double[X.length];
        for (int i = 0; i < X.length; i++) sc[i] = score(X[i]);
        return words(text, vs, sc);
    }

    /**
     * Вариант с «головой ударения» на энкодере (ml/stress/export_enc.py, лучший по точности):
     * s[t] = v·enc_t — один дополнительный выход ONNX на кадр 40 мс. Оценка гласной — среднее s по её отрезку.
     * Пока в экспортированной модели GigaAM такого выхода нет (docs/STRESS.md).
     */
    public static List<WordStress> analyzeFrameScores(ReadingText text, float[][] lp, int T, float[] s) {
        List<Vowel> vs = segments(text, lp, T);
        if (vs == null) return null;
        double[] sc = new double[vs.size()];
        for (int i = 0; i < sc.length; i++) {
            Vowel v = vs.get(i); double sum = 0;
            for (int t = v.a; t < v.b; t++) sum += s[t];
            sc[i] = sum / (v.b - v.a);
        }
        return words(text, vs, sc);
    }

    /** Вектор v головы ударения из JSON {"v": [...]}. */
    public static float[] encHead(String js) {
        double[] v = arr(js, "v"); float[] out = new float[v.length];
        for (int i = 0; i < v.length; i++) out[i] = (float) v[i];
        return out;
    }

    static List<WordStress> words(ReadingText text, List<Vowel> vs, double[] score) {
        List<WordStress> out = new ArrayList<>();
        int i = 0;
        while (i < vs.size()) {
            int nv = vs.get(i).nv, wi = vs.get(i).word;
            double[] sc = new double[nv]; double mx = -1e300;
            for (int j = 0; j < nv; j++) { sc[j] = score[i + j]; mx = Math.max(mx, sc[j]); }
            double sum = 0; for (int j = 0; j < nv; j++) { sc[j] = Math.exp(sc[j] - mx); sum += sc[j]; }
            float[] p = new float[nv]; int best = 0;
            int[] a = new int[nv], b = new int[nv];
            for (int j = 0; j < nv; j++) {
                p[j] = (float) (sc[j] / sum); if (p[j] > p[best]) best = j;
                a[j] = vs.get(i + j).a; b[j] = vs.get(i + j).b;
            }
            ReadingText.Word w = text.words.get(wi);
            out.add(new WordStress(wi, w.letters, w.stressVowel, best, p, a, b));
            i += nv;
        }
        return out;
    }
}
