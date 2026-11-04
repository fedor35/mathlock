package dev.gubenkov.reading;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Адаптивная сложность чтения. Состояние не хранится отдельно: уровень каждый раз выводится заново
 * из журнала попыток (TSV), поэтому правила можно менять без миграций.
 *
 * <p>Правила (ориентир — нормы техники чтения: 1 кл. 30–40, 2 кл. 50–70, 3 кл. 70–90, 4 кл. 90–120 слов/мин):
 * <ul>
 *   <li>повышение: 3 последних засчитанных попытки на уровне (разные тексты) с точностью ≥ 95% и темпом ≥ цели уровня;</li>
 *   <li>понижение: 2 попытки подряд с точностью &lt; 85% (уровень «слишком трудно»);</li>
 *   <li>после смены уровня окно оценки начинается заново;</li>
 *   <li>каждый 5-й текст — «вызов» на уровень выше; он не понижает, а успех (точность ≥ 95%, темп ≥ цели текущего уровня) идёт в окно повышения.</li>
 * </ul>
 * Подсказки снимаются с ростом уровня: цветные слоги до L4, ударения до L5, дальше обычный текст.
 */
public final class AdaptiveLevel {
    public static final int MIN = 1, MAX = 9;
    /** Цель темпа (слов/мин) для перехода с уровня; индекс — уровень. */
    static final float[] TARGET = {0, 15, 20, 25, 30, 40, 50, 65, 80, 95};
    /** Цель на последнем уровне растёт до верхней нормы 4 класса. */
    public static final float TOP_TARGET = 120;
    static final float ACC_UP = 0.95f, ACC_DOWN = 0.85f;
    static final int WINDOW = 3, DOWN_RUN = 2, CHALLENGE_EVERY = 5;

    public static final class Attempt {
        public final long time; public final String textId; public final int level; public final float wcpm, accuracy; public final boolean challenge;
        public Attempt(long time, String textId, int level, float wcpm, float accuracy, boolean challenge) {
            this.time = time; this.textId = textId; this.level = level; this.wcpm = wcpm; this.accuracy = accuracy; this.challenge = challenge;
        }
        public String toTsv() {
            return String.format(Locale.ROOT, "%d\t%s\t%d\t%.1f\t%.3f\t%d", time, textId, level, wcpm, accuracy, challenge ? 1 : 0);
        }
        public static Attempt parse(String line) {
            String[] p = line.split("\t");
            return new Attempt(Long.parseLong(p[0]), p[1], Integer.parseInt(p[2]), Float.parseFloat(p[3]), Float.parseFloat(p[4]),
                    p.length > 5 && p[5].equals("1"));
        }
    }

    public final List<Attempt> history = new ArrayList<>();
    private final int startLevel;

    public AdaptiveLevel(int startLevel) { this.startLevel = clamp(startLevel); }

    public static float target(int level) { return TARGET[clamp(level)]; }
    public static boolean syllableColors(int level) { return level <= 4; }
    public static boolean stressMarks(int level) { return level <= 5; }
    static int clamp(int l) { return Math.max(MIN, Math.min(MAX, l)); }

    /** Текущий уровень — прогон правил по журналу. */
    public int level() {
        int lvl = startLevel; List<Attempt> win = new ArrayList<>(); int lowRun = 0;
        for (Attempt a : history) {
            if (a.challenge) {
                if (a.level == lvl + 1 && a.accuracy >= ACC_UP && a.wcpm >= target(lvl)) {
                    win.add(a); if (win.size() > WINDOW) win.remove(0);
                    if (win.size() == WINDOW && allPass(win, lvl)) { lvl++; win.clear(); lowRun = 0; }
                }
                continue;
            }
            if (a.level != lvl) continue;   // попытка старого уровня (повтор текста) в оценку не идёт
            lowRun = a.accuracy < ACC_DOWN ? lowRun + 1 : 0;
            if (lowRun >= DOWN_RUN && lvl > MIN) { lvl--; win.clear(); lowRun = 0; continue; }
            win.add(a);
            if (win.size() > WINDOW) win.remove(0);
            if (win.size() == WINDOW && lvl < MAX && allPass(win, lvl)) { lvl++; win.clear(); lowRun = 0; }
        }
        return lvl;
    }

    static boolean allPass(List<Attempt> w, int lvl) {
        for (Attempt a : w) if (a.accuracy < ACC_UP || a.wcpm < target(lvl)) return false;
        return true;
    }

    /** Лучший темп на уровне не ниже {@code minLevel} (для «рекорда»). */
    public float best(int minLevel) {
        float b = 0; for (Attempt a : history) if (a.level >= minLevel && a.accuracy >= ACC_UP) b = Math.max(b, a.wcpm); return b;
    }

    /** Следующая попытка — вызов? Каждый CHALLENGE_EVERY-й обычный текст после последнего вызова. */
    public boolean nextIsChallenge() {
        int lvl = level(); if (lvl >= MAX) return false;
        int since = 0;
        for (int i = history.size() - 1; i >= 0 && !history.get(i).challenge; i--) since++;
        return since >= CHALLENGE_EVERY - 1 && history.size() >= CHALLENGE_EVERY - 1;
    }

    /**
     * Выбор текста: уровень {@code level} (или выше, если вызов); сначала непрочитанные, затем давно читанные.
     * @param ids идентификаторы всех текстов, {@code levels} — их уровни (те же индексы).
     * @return индекс в списке или -1, если текстов уровня нет (тогда берётся ближайший уровень).
     */
    public int pick(List<String> ids, List<Integer> levels, int level, Random rnd) {
        for (int d = 0; d <= MAX; d++) {
            for (int sign : new int[]{-1, 1}) {
                int L = level + sign * d; if (d == 0 && sign == 1) continue;
                List<Integer> cand = new ArrayList<>();
                for (int i = 0; i < ids.size(); i++) if (levels.get(i) == L) cand.add(i);
                if (cand.isEmpty()) continue;
                List<Integer> fresh = new ArrayList<>();
                for (int i : cand) if (lastRead(ids.get(i)) < 0) fresh.add(i);
                if (!fresh.isEmpty()) return fresh.get(rnd.nextInt(fresh.size()));
                int best = cand.get(0);   // все читаны — самый давний
                for (int i : cand) if (lastRead(ids.get(i)) < lastRead(ids.get(best))) best = i;
                return best;
            }
        }
        return -1;
    }

    long lastRead(String id) {
        long t = -1; for (Attempt a : history) if (a.textId.equals(id)) t = Math.max(t, a.time); return t;
    }

    public static AdaptiveLevel parse(int startLevel, Iterable<String> lines) {
        AdaptiveLevel a = new AdaptiveLevel(startLevel);
        for (String l : lines) { l = l.trim(); if (l.isEmpty() || l.startsWith("#")) continue; try { a.history.add(Attempt.parse(l)); } catch (Exception ignored) {} }
        return a;
    }
}
