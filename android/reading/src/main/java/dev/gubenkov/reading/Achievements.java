package dev.gubenkov.reading;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * Награды за чтение. Как и уровень, не хранятся отдельно: каждый раз выводятся из журнала попыток чтеца,
 * поэтому правила можно менять, а уже заработанное за прошлые дни появляется сразу.
 *
 * <p>Упор — на улучшения (быстрее, чем вчера; быстрее, чем в прошлый раз на том же тексте; новый темп),
 * а не только на количество: детям важно видеть, что они растут (Вероника, 04.11).
 */
public final class Achievements {
    /** Чтение «чисто» для наград темпа: почти без ошибок, иначе темп не в счёт. */
    static final float ACC_SPEED = 0.90f, ACC_PERFECT = 0.999f;
    static final int[] TEXTS = {1, 5, 10, 25, 50, 100, 200};
    static final int[] SPEED = {20, 30, 40, 50, 60, 75, 90, 120, 150};
    static final int[] PERFECT = {1, 10, 25, 50};
    static final int[] CHALLENGE = {1, 5, 15};
    static final int[] STREAK = {2, 3, 7, 14, 30};
    static final int[] DAY_TEXTS = {5, 10};
    static final int[] IMPROVE = {1, 5, 15};
    static final int[] BEAT_YESTERDAY = {1, 5, 15};
    /** «Быстрее, чем в прошлый раз» на том же тексте — прирост темпа не меньше 15%. */
    static final float IMPROVE_BY = 1.15f;

    public static final class Badge {
        public final String id, icon, title, hint;
        /** Когда заработана (мс); 0 — ещё нет. */
        public long earnedAt;
        Badge(String id, String icon, String title, String hint) { this.id = id; this.icon = icon; this.title = title; this.hint = hint; }
        public boolean earned() { return earnedAt > 0; }
        @Override public String toString() { return icon + " " + title; }
    }

    /** Все награды по порядку показа; заработанные — с временем. */
    public static List<Badge> all(AdaptiveLevel lv, TimeZone tz) {
        Map<String, Badge> m = new LinkedHashMap<>();
        for (int n : TEXTS) put(m, "texts_" + n, "📖", n == 1 ? "Первый текст" : n + " " + texts(n), "Прочитай " + n + " " + texts(n));
        for (int v : SPEED) put(m, "speed_" + v, "⚡", v + " слов в минуту", "Прочитай без ошибок со скоростью " + v + " слов в минуту");
        for (int l = AdaptiveLevel.MIN + 1; l <= AdaptiveLevel.MAX; l++) put(m, "level_" + l, "🎯", "Уровень " + l, "Дойди до уровня " + l);
        for (int n : BEAT_YESTERDAY) put(m, "yesterday_" + n, "🚀", n == 1 ? "Быстрее, чем вчера" : "Быстрее, чем вчера ×" + n, "Прочитай быстрее, чем в прошлый день");
        for (int n : IMPROVE) put(m, "improve_" + n, "📈", n == 1 ? "Лучше, чем в прошлый раз" : "Лучше, чем в прошлый раз ×" + n, "Перечитай знакомый текст быстрее");
        for (int n : PERFECT) put(m, "perfect_" + n, "💎", n == 1 ? "Без единой ошибки" : "Без ошибок ×" + n, "Прочитай текст без единой ошибки");
        for (int n : CHALLENGE) put(m, "challenge_" + n, "🏔", n == 1 ? "Трудный текст" : "Трудные тексты ×" + n, "Прочитай трудный текст (★) почти без ошибок");
        for (int n : STREAK) put(m, "streak_" + n, "🔥", n + " " + days(n) + " подряд", "Читай " + n + " " + days(n) + " подряд");
        for (int n : DAY_TEXTS) put(m, "day_" + n, "🏃", n + " текстов за день", "Прочитай " + n + " текстов за один день");

        List<AdaptiveLevel.Attempt> h = lv.history;
        Map<String, Float> lastOnText = new HashMap<>();
        Map<Long, Integer> perDay = new HashMap<>();
        Set<Long> days = new HashSet<>();
        long prevDay = Long.MIN_VALUE; float prevDayBest = 0, curDayBest = 0; long curDay = Long.MIN_VALUE;
        boolean beatToday = false;
        int perfect = 0, challenge = 0, improve = 0, beat = 0, maxLevel = lv.startLevel();
        for (int i = 0; i < h.size(); i++) {
            AdaptiveLevel.Attempt a = h.get(i); long t = a.time; long d = Leaderboard.day(t, tz);
            earn(m, TEXTS, "texts_", i + 1, t);
            boolean clean = a.accuracy >= ACC_SPEED;
            if (clean) for (int v : SPEED) if (a.wcpm >= v) earn(m, "speed_" + v, t);
            int lvl = lv.levelAfter(i + 1);
            for (int l = maxLevel + 1; l <= lvl; l++) earn(m, "level_" + l, t);
            maxLevel = Math.max(maxLevel, lvl);
            if (a.accuracy >= ACC_PERFECT) earn(m, PERFECT, "perfect_", ++perfect, t);
            if (a.challenge && a.accuracy >= AdaptiveLevel.ACC_UP) earn(m, CHALLENGE, "challenge_", ++challenge, t);
            Float last = lastOnText.get(a.textId);
            if (clean && last != null && a.wcpm >= last * IMPROVE_BY) earn(m, IMPROVE, "improve_", ++improve, t);
            if (clean) lastOnText.put(a.textId, Math.max(a.wcpm, last == null ? 0 : last));
            // быстрее, чем в прошлый день чтения (раз в день; сравниваем лучший чистый темп)
            if (d != curDay) { if (curDay != Long.MIN_VALUE && curDayBest > 0) { prevDay = curDay; prevDayBest = curDayBest; } curDay = d; curDayBest = 0; beatToday = false; }
            if (clean) {
                curDayBest = Math.max(curDayBest, a.wcpm);
                if (!beatToday && prevDayBest > 0 && a.wcpm > prevDayBest) { beatToday = true; earn(m, BEAT_YESTERDAY, "yesterday_", ++beat, t); }
            }
            int nDay = perDay.merge(d, 1, Integer::sum);
            for (int n : DAY_TEXTS) if (nDay >= n) earn(m, "day_" + n, t);
            days.add(d);
            int streak = 0; for (long k = d; days.contains(k); k--) streak++;
            for (int n : STREAK) if (streak >= n) earn(m, "streak_" + n, t);
        }
        return new ArrayList<>(m.values());
    }

    /** Награды, заработанные последней попыткой журнала (показать сразу после чтения). */
    public static List<Badge> newest(AdaptiveLevel lv, TimeZone tz) {
        List<Badge> out = new ArrayList<>();
        if (lv.history.isEmpty()) return out;
        long t = lv.history.get(lv.history.size() - 1).time;
        for (Badge b : all(lv, tz)) if (b.earnedAt == t) out.add(b);
        return out;
    }

    public static int count(List<Badge> bs) { int n = 0; for (Badge b : bs) if (b.earned()) n++; return n; }

    private static void put(Map<String, Badge> m, String id, String icon, String title, String hint) { m.put(id, new Badge(id, icon, title, hint)); }

    private static void earn(Map<String, Badge> m, String id, long t) { Badge b = m.get(id); if (b != null && b.earnedAt == 0) b.earnedAt = t; }

    private static void earn(Map<String, Badge> m, int[] tiers, String prefix, int n, long t) { for (int k : tiers) if (n >= k) earn(m, prefix + k, t); }

    static String texts(int n) { return plural(n, "текст", "текста", "текстов"); }
    static String days(int n) { return plural(n, "день", "дня", "дней"); }

    static String plural(int n, String one, String few, String many) {
        int a = n % 100, b = n % 10;
        if (a >= 11 && a <= 14) return many;
        return b == 1 ? one : b >= 2 && b <= 4 ? few : many;
    }
}
