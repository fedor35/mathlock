package dev.gubenkov.mathlock;

import java.util.Calendar;

/**
 * Дневной лимит экранного времени с заработком (с 0.5.0; дневной лимит Family Link снят, у него остался отбой 23:30–6:00).
 * <p>
 * Остаток на сегодня = база + заработано (не больше потолка) + добавки вне потолка (новый уровень чтения, родитель)
 * − использовано. Использованным считается время, пока экран горит и телефон открыт (экран примеров/чтения не в счёт).
 * Сутки начинаются в {@code resetHour} (по умолчанию 6:00 — конец отбоя Family Link).
 * <p>
 * Чистая логика без Android — состояние держит {@link State}, сохраняет его Prefs.
 */
public final class ScreenTime {
    private ScreenTime() { }

    /** Состояние текущих суток. */
    public static final class State {
        public int day;          // ключ суток yyyymmdd (с учётом resetHour)
        public long usedSec;     // использовано сегодня
        public int earnedMin;    // заработано сегодня примерами и чтением (под потолком)
        public int extraMin;     // добавлено вне потолка: новый уровень, родитель
        public int warned;       // какие предупреждения уже показаны (биты WARN_*)
    }

    public static final int WARN_5 = 1, WARN_1 = 2;

    /** Настройки родителя. */
    public static final class Rules {
        public final int baseMin, earnMaxMin, resetHour;
        public Rules(int baseMin, int earnMaxMin, int resetHour) {
            this.baseMin = Math.max(0, baseMin); this.earnMaxMin = Math.max(0, earnMaxMin);
            this.resetHour = Math.max(0, Math.min(23, resetHour));
        }
    }

    /** Ключ суток: до resetHour — ещё вчерашние сутки. */
    public static int dayKey(long nowMs, int resetHour, java.util.TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(nowMs - resetHour * 3_600_000L);
        return c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH);
    }

    /** Новые сутки — обнулить. @return true, если обнулили. */
    public static boolean roll(State s, int today) {
        if (s.day == today) return false;
        s.day = today; s.usedSec = 0; s.earnedMin = 0; s.extraMin = 0; s.warned = 0;
        return true;
    }

    /** Всего доступно сегодня, секунд. */
    public static long allowedSec(State s, Rules r) {
        return (r.baseMin + Math.min(s.earnedMin, r.earnMaxMin) + (long) s.extraMin) * 60L;
    }

    public static long leftSec(State s, Rules r) { return Math.max(0, allowedSec(s, r) - s.usedSec); }

    /** Сколько ещё можно заработать сегодня, минут. */
    public static int canEarnMin(State s, Rules r) { return Math.max(0, r.earnMaxMin - s.earnedMin); }

    /** Заработать минуты (с потолком). @return сколько реально добавлено. */
    public static int earn(State s, Rules r, int min) {
        int add = Math.max(0, Math.min(min, canEarnMin(s, r)));
        s.earnedMin += add;
        return add;
    }

    /**
     * Учесть использование. Шаг больше maxStepSec (процесс спал, часы прыгнули) обрезается — лучше недосчитать,
     * чем отнять у ребёнка час за сбой.
     */
    public static void use(State s, long sec, long maxStepSec) {
        if (sec <= 0) return;
        s.usedSec += Math.min(sec, maxStepSec);
    }

    /** Время вышло — перерасход (пока открывался экран блокировки) не должен съедать будущий заработок. */
    public static void clampUsed(State s, Rules r) { s.usedSec = Math.max(0, Math.min(s.usedSec, allowedSec(s, r))); }

    /** Добавка вне потолка; минус (родитель убавил) — не ниже нуля доступного на сегодня. */
    public static void addExtra(State s, Rules r, int min) {
        s.extraMin = Math.max(s.extraMin + min, -(r.baseMin + Math.min(s.earnedMin, r.earnMaxMin)));
    }

    /** Какое предупреждение показать сейчас (0 — никакого); помечает его показанным. */
    public static int warnNow(State s, Rules r) {
        long left = leftSec(s, r);
        if (left <= 0) return 0;
        if (left <= 60 && (s.warned & WARN_1) == 0) { s.warned |= WARN_1 | WARN_5; return WARN_1; }
        if (left <= 300 && (s.warned & WARN_5) == 0) { s.warned |= WARN_5; return WARN_5; }
        return 0;
    }

    /** После добавки времени предупреждения снова актуальны, если остаток вырос. */
    public static void rearmWarnings(State s, Rules r) {
        long left = leftSec(s, r);
        if (left > 60) s.warned &= ~WARN_1;
        if (left > 300) s.warned &= ~WARN_5;
    }

    /** «1 ч 25 мин», «12 мин», «40 с». */
    public static String fmt(long sec) {
        if (sec < 60) return Math.max(0, sec) + " с";
        long m = (sec + 59) / 60;   // вверх: «осталось 1 мин», пока не меньше минуты
        if (m < 60) return m + " мин";
        return m / 60 + " ч" + (m % 60 == 0 ? "" : " " + m % 60 + " мин");
    }

    /** Минуты прописью для наград: «+5 мин», «+1 ч 30 мин». */
    public static String fmtMin(int min) { return fmt(min * 60L); }
}
