package dev.gubenkov.reading;

/**
 * Итог одного прочтения: сколько слов верно, точность и темп WCPM (words correct per minute) —
 * стандартная мера беглости чтения. Время чтения — от начала первого до конца последнего сопоставленного
 * слова по CTC-выравниванию (паузы до начала и после конца не считаются).
 */
public final class ReadingStats {
    public final int words, ok, wrong, reached;
    public final float seconds;

    public ReadingStats(int words, int ok, int wrong, int reached, float seconds) {
        this.words = words; this.ok = ok; this.wrong = wrong; this.reached = reached; this.seconds = seconds;
    }

    /** Доля верных слов среди всех слов текста. */
    public float accuracy() { return words == 0 ? 0 : ok / (float) words; }

    /** Слов верно в минуту; 0, если время не определено. */
    public float wcpm() { return seconds < 1f ? 0 : ok * 60f / seconds; }

    /** Дочитан ли текст хотя бы до 80% — только такие попытки идут в оценку уровня. */
    public boolean counts() { return words > 0 && reached >= Math.ceil(words * 0.8); }

    /** @param letterFrames кадр (FRAME_SEC) каждой буквы гипотезы без пробелов, как в StreamingRecognizer. */
    public static ReadingStats of(ReadingMatcher.Result r, int[] letterFrames, float frameSec) {
        int W = r.states.length, ok = 0, wrong = 0, reached = 0, first = -1, last = -1;
        for (int w = 0; w < W; w++) {
            if (r.states[w] == ReadingMatcher.State.OK) ok++;
            else if (r.states[w] == ReadingMatcher.State.WRONG) wrong++;
            if (r.states[w] != ReadingMatcher.State.PENDING) reached = w + 1;
            if (r.hypFirst[w] >= 0) { if (first < 0 || r.hypFirst[w] < first) first = r.hypFirst[w]; }
            if (r.hypLast[w] >= 0) last = Math.max(last, r.hypLast[w]);
        }
        float sec = 0;
        if (first >= 0 && last >= first && letterFrames != null && last < letterFrames.length)
            sec = (letterFrames[last] - letterFrames[first] + 1) * frameSec;
        return new ReadingStats(W, ok, wrong, reached, sec);
    }

    @Override public String toString() {
        return String.format(java.util.Locale.ROOT, "%d/%d верно, %.0f%%, %.1f с, %.0f слов/мин", ok, words, accuracy() * 100, seconds, wcpm());
    }
}
