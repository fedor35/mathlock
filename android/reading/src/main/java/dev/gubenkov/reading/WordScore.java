package dev.gubenkov.reading;

import java.util.List;

/**
 * Второе мнение о красном слове по звуку: насколько ожидаемое слово объясняет кадры CTC, на которых
 * распознавание услышало что-то другое. Жадный декодер на детской речи теряет буквы, которые в звуке есть
 * («ток» → «ок», «Ерлан» → «ерла», «солнце» → «соце» — сын, 08–10.11); выход модели при этом даёт им
 * почти ту же вероятность, что и лучшему пути.
 *
 * <p>Оценка — Viterbi: [что угодно]* слово по CTC [пауза]* на отрезке слова минус лучший путь по тем же
 * кадрам, на букву (≤ 0). Пауза после слова (blank или пробел) обязательна: иначе короткая форма всегда
 * находится внутри длинной («енот» в «еноты»). Замер на записях сына 07–10.11 (1108 верно прочитанных слов):
 * при пороге −0,4 «соседняя» форма вместо сказанной проходит в 0,2–2% случаев, чужое слово — никогда;
 * настоящие ошибки сына («кругом» вместо «круглое», «летит» вместо «летел», «самое» вместо «самый») дают −0,9…−5.
 */
public final class WordScore {
    /** Порог на букву: выше — слово в звуке есть, красное снимаем. */
    public static final float ACCEPT = Float.parseFloat(System.getProperty("wordscore.accept", "-0.4"));
    /** Запас кадров (40 мс) вокруг куска речи, который сверка отдала слову. */
    static final int PAD_BEFORE = 6, PAD_AFTER = 8;

    private final float[][] lp;
    private final List<Integer> letterFrames;
    private final String[] vocab;
    private final int blank, space;

    /**
     * @param logp         лог-вероятности CTC по кадрам (как {@link StreamingRecognizer#logits()})
     * @param letterFrames кадр каждой буквы гипотезы без пробелов (как {@link StreamingRecognizer#letterFrames})
     */
    public WordScore(float[][] logp, List<Integer> letterFrames, String[] vocab, int blank) {
        this.lp = logp; this.letterFrames = letterFrames; this.vocab = vocab; this.blank = blank;
        int sp = -1; for (int i = 0; i < vocab.length; i++) if (vocab[i].equals(" ")) sp = i;
        this.space = sp;
    }

    /** Оценка слова {@code word} (буквы текста) на кадрах букв гипотезы [hypFirst..hypLast]; NaN — не по чему судить. */
    public float score(String word, int hypFirst, int hypLast) {
        int n = letterFrames.size();
        if (hypFirst < 0 || hypLast < hypFirst || hypLast >= n || word.isEmpty()) return Float.NaN;
        int t0 = letterFrames.get(hypFirst) - PAD_BEFORE, t1 = letterFrames.get(hypLast) + PAD_AFTER + 1;
        if (hypFirst > 0) t0 = Math.max(t0, letterFrames.get(hypFirst - 1) + 1);
        if (hypLast + 1 < n) t1 = Math.min(t1, letterFrames.get(hypLast + 1));
        t0 = Math.max(0, t0); t1 = Math.min(lp.length, t1);
        if (t1 - t0 < word.length()) return Float.NaN;
        int[] lab = new int[word.length()];
        for (int i = 0; i < lab.length; i++) { lab[i] = index(String.valueOf(word.charAt(i))); if (lab[i] < 0) return Float.NaN; }
        return score(lp, t0, t1, lab, blank, space);
    }

    int index(String u) { for (int i = 0; i < vocab.length; i++) if (vocab[i].equals(u)) return i; return -1; }

    /** Viterbi [любое]* слово(CTC) [пауза]* на кадрах [t0, t1) минус лучший путь, на букву. */
    static float score(float[][] lp, int t0, int t1, int[] lab, int blank, int space) {
        int L = lab.length, S = 2 * L + 1; final float NEG = -1e9f;
        float[] a = new float[S + 2], nx = new float[S + 2];
        java.util.Arrays.fill(a, NEG); a[0] = 0;
        float free = 0;
        for (int t = t0; t < t1; t++) {
            float[] x = lp[t];
            float best = x[0]; for (int c = 1; c < x.length; c++) if (x[c] > best) best = x[c];
            float pause = space >= 0 ? Math.max(x[blank], x[space]) : x[blank];
            free += best;
            java.util.Arrays.fill(nx, NEG);
            nx[0] = a[0] + best;                                  // до слова — что угодно (заминка, «по-по…»)
            for (int s = 1; s <= S; s++) {                        // CTC: blank, l1, blank, l2, …, blank
                int k = s - 1; boolean isBlank = (k & 1) == 0; int c = isBlank ? blank : lab[k >> 1];
                float m = a[s];
                if (s > 1) m = Math.max(m, a[s - 1]);
                if (!isBlank && k >= 2 && lab[k >> 1] != lab[(k >> 1) - 1]) m = Math.max(m, a[s - 2]);
                if (s <= 2) m = Math.max(m, a[0]);
                nx[s] = m + x[c];
            }
            nx[S + 1] = Math.max(a[S + 1], Math.max(a[S], a[S - 1])) + pause;   // после слова — только пауза
            float[] tmp = a; a = nx; nx = tmp;
        }
        float word = Math.max(a[S + 1], Math.max(a[S], a[S - 1]));
        return word <= NEG / 2 ? Float.NEGATIVE_INFINITY : (word - free) / L;
    }
}
