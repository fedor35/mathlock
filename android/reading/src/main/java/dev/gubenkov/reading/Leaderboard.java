package dev.gubenkov.reading;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

/**
 * Соревнования: подсчёты без Android.
 * <ul>
 *   <li>{@link Duel} — дуэль на одном тексте: раунды, лучший результат каждого игрока, места;
 *       счёт раунда = слов в минуту × точность;</li>
 *   <li>{@link #stats} — семейная таблица рекордов по журналу чтеца: всего прочитано, сегодня, звёзды за сегодня,
 *       серия дней подряд, лучший темп (точность не ниже 95%).</li>
 * </ul>
 * «День» — календарный день в заданном часовом поясе.
 */
public final class Leaderboard {
    private Leaderboard() {}

    static final long DAY_MS = 86400000L;

    /** Номер календарного дня (от эпохи) в поясе tz. */
    public static long day(long time, TimeZone tz) { return Math.floorDiv(time + tz.getOffset(time), DAY_MS); }

    /** Счёт раунда дуэли: темп с поправкой на точность. 60 слов/мин при 90% = 54. */
    public static float score(float wcpm, float accuracy) { return wcpm * accuracy; }

    /** Один раунд дуэли. {@code key} — чтец (id профиля или «игрок N»), {@code name} — как показать. */
    public static final class Round {
        public final String key; public String name;
        public final float seconds, wcpm, accuracy;
        public Round(String key, String name, float seconds, float wcpm, float accuracy) {
            this.key = key; this.name = name; this.seconds = seconds; this.wcpm = wcpm; this.accuracy = accuracy;
        }
        public float score() { return Leaderboard.score(wcpm, accuracy); }
    }

    /** Строка таблицы дуэли: лучший раунд игрока и его место (1 — лидер; равный счёт — одно место). */
    public static final class Row {
        public final Round best; public final int rounds, place;
        Row(Round best, int rounds, int place) { this.best = best; this.rounds = rounds; this.place = place; }
    }

    public static final class Duel {
        public final List<Round> rounds = new ArrayList<>();

        public void add(Round r) { rounds.add(r); }

        /** Переназначить все раунды чтеца oldKey другому игроку (тап по строке таблицы). */
        public void reassign(String oldKey, String newKey, String newName) {
            for (int i = 0; i < rounds.size(); i++) {
                Round r = rounds.get(i);
                if (r.key.equals(oldKey)) rounds.set(i, new Round(newKey, newName, r.seconds, r.wcpm, r.accuracy));
            }
        }

        /** Таблица: по игроку — его лучший раунд; отсортирована по счёту, места с учётом ничьих. */
        public List<Row> table() {
            List<Round> best = new ArrayList<>(); List<Integer> cnt = new ArrayList<>();
            for (Round r : rounds) {
                int i = -1; for (int k = 0; k < best.size(); k++) if (best.get(k).key.equals(r.key)) i = k;
                if (i < 0) { best.add(r); cnt.add(1); }
                else { cnt.set(i, cnt.get(i) + 1); if (r.score() > best.get(i).score()) best.set(i, r); }
            }
            List<Integer> order = new ArrayList<>(); for (int i = 0; i < best.size(); i++) order.add(i);
            order.sort((a, b) -> Float.compare(best.get(b).score(), best.get(a).score()));
            List<Row> out = new ArrayList<>(); int place = 0; float prev = Float.NaN;
            for (int k = 0; k < order.size(); k++) {
                Round r = best.get(order.get(k));
                if (r.score() != prev) { place = k + 1; prev = r.score(); }
                out.add(new Row(r, cnt.get(order.get(k)), place));
            }
            return out;
        }

        /** Победитель — первая строка таблицы (при ничьей — кто раньше прочитал); null без раундов. */
        public Round winner() { List<Row> t = table(); return t.isEmpty() ? null : t.get(0).best; }
    }

    /** Итоги чтеца для семейной таблицы. */
    public static final class Stats {
        public int total, today, streak;
        public float best;
        /** Звёзды за сегодня: по одной за каждый засчитанный текст. */
        public int stars() { return today; }
    }

    public static Stats stats(List<AdaptiveLevel.Attempt> history, long now, TimeZone tz) {
        Stats s = new Stats(); long today = day(now, tz); Set<Long> days = new HashSet<>();
        for (AdaptiveLevel.Attempt a : history) {
            s.total++; long d = day(a.time, tz); days.add(d);
            if (d == today) s.today++;
            if (a.accuracy >= AdaptiveLevel.ACC_UP) s.best = Math.max(s.best, a.wcpm);
        }
        // серия: дни подряд до сегодня; если сегодня ещё не читали, серия со вчера не сгорает
        long d = days.contains(today) ? today : today - 1;
        while (days.contains(d)) { s.streak++; d--; }
        return s;
    }
}
