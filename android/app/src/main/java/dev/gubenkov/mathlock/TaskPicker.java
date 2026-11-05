package dev.gubenkov.mathlock;

/**
 * Какое задание дать на экране блокировки и когда оно выполнено. Чистая логика без Android — под JUnit.
 * Главное правило: чтение — только если всё для него есть; иначе и при любой поломке — примеры.
 * Ребёнок никогда не должен остаться без способа разблокироваться.
 */
public final class TaskPicker {
    private TaskPicker() {}

    /** Настройка родителя. Порядок = значение в Prefs.taskMode(). */
    public enum Mode {
        MATH, READING, MIXED;
        public static Mode of(int v) { return v >= 0 && v < values().length ? values()[v] : MATH; }
    }

    /** Что показать в этот раз. */
    public enum Kind { MATH, READING }

    /** После стольких неудачных прочтений одного сеанса появляется кнопка «Примеры». */
    public static final int FALLBACK_FAILS = 3;
    /** …или через столько времени на экране чтения без разблокировки. */
    public static final long FALLBACK_MS = 4 * 60_000L;
    /** Модель грузится дольше — кнопка «Примеры» (загрузка продолжается, следующий раз будет быстро). */
    public static final long LOAD_TIMEOUT_MS = 30_000L;
    /** Порог точности: пределы, которые может задать родитель. */
    public static final int MIN_THRESHOLD = 50, MAX_THRESHOLD = 100;
    /** Порог 0: засчитать, если текст дочитан до конца; красные слова перечитывать не нужно. */
    public static final int READ_TO_END = 0;

    /**
     * @param guard экран родительского кода (защита Настроек) — всегда клавиатура
     * @param readingReady пакет чтения на месте, модель загружаема, микрофон разрешён
     * @param mixedCounter номер сеанса «вперемешку»: чётный — чтение, нечётный — примеры
     */
    public static Kind pick(Mode mode, boolean guard, boolean readingReady, int mixedCounter) {
        if (guard || !readingReady || mode == null || mode == Mode.MATH) return Kind.MATH;
        if (mode == Mode.READING) return Kind.READING;
        return Math.floorMod(mixedCounter, 2) == 0 ? Kind.READING : Kind.MATH;
    }

    /**
     * Засчитано ли чтение для разблокировки.
     * @param ok верных слов (с учётом перечитывания красных), {@code words} — всего слов текста
     * @param complete ReadingMatcher: все слова верно
     * @param thresholdPct порог родителя; 100 — только complete (как в readbench: красные перечитать);
     *        0 ({@link #READ_TO_END}) — достаточно дочитать до конца, красные не перечитывать
     * @param reached до какого слова дочитал (ReadingStats.reached)
     */
    public static boolean passed(int ok, int words, boolean complete, int thresholdPct, int reached) {
        if (words <= 0) return false;
        if (complete) return true;
        if (thresholdPct == READ_TO_END)   // дочитал (последнее слово можно запнуть) и не меньше половины верно — не болтовня
            return reached >= words - 1 && ok * 2L >= words;
        int t = clampThreshold(thresholdPct);
        if (t >= MAX_THRESHOLD) return false;
        return ok * 100L >= (long) t * words;
    }

    public static boolean passed(int ok, int words, boolean complete, int thresholdPct) { return passed(ok, words, complete, thresholdPct, 0); }

    public static int clampThreshold(int pct) { return pct == READ_TO_END ? READ_TO_END : Math.max(MIN_THRESHOLD, Math.min(MAX_THRESHOLD, pct)); }

    /** Показать кнопку «Примеры» на экране чтения: несколько неудач подряд или долго не выходит. */
    public static boolean offerMath(int fails, long msOnScreen) {
        return fails >= FALLBACK_FAILS || msOnScreen >= FALLBACK_MS;
    }
}
