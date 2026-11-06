package dev.gubenkov.mathlock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Как выучен каждый пример таблицы (идея сына 07.11: «плохо изученные давать почаще»).
 * Пример = пара множителей без порядка (3×7 и 7×3 — один факт). Коробки по Лейтнеру 0…5:
 * верно и быстро — коробка вверх; верно, но медленно — остаётся; ошибка — в коробку 0.
 * Скорость влияет только на оценку выученности, не на награды: ответ можно подсмотреть на линейке.
 * Чем ниже коробка, тем чаще пример выпадает; новые примеры — почаще, пока не узнаем, как они выучены.
 */
public final class FactStats {
    public static final int MAX_BOX = 5;
    /** Быстрый ответ — выучено «наизусть»; медленнее — считает в уме или подсматривает. */
    public static final long FAST_MS = 5_000;
    static final float[] BOX_WEIGHT = {8f, 5f, 3f, 2f, 1f, 0.5f};
    static final float NEW_WEIGHT = 4f;

    public static final class Fact {
        public int box, n, wrong;
        public long emaMs, last;
    }

    private final File file;
    private final Map<String, Fact> facts = new HashMap<>();

    public FactStats(File file) { this.file = file; load(); }

    public static String key(int a, int b) { return Math.min(a, b) + "x" + Math.max(a, b); }

    public Fact get(int a, int b) { return facts.get(key(a, b)); }

    /** Записать ответ. @param ms сколько думал (с показа до «✓»). */
    public void record(int a, int b, boolean ok, long ms, long now) {
        Fact f = facts.get(key(a, b));
        if (f == null) { f = new Fact(); facts.put(key(a, b), f); }
        f.n++;
        f.last = now;
        if (!ok) { f.wrong++; f.box = 0; }
        else {
            long t = Math.max(0, Math.min(ms, 60_000));
            f.emaMs = f.emaMs == 0 ? t : Math.round(f.emaMs * 0.6 + t * 0.4);
            if (t <= FAST_MS) f.box = Math.min(MAX_BOX, f.box + 1);
            else if (f.box > 2) f.box--;   // забылось: отвечал быстро, теперь долго считает
        }
        save();
    }

    float weight(int a, int b) {
        Fact f = facts.get(key(a, b));
        if (f == null) return NEW_WEIGHT;
        float w = BOX_WEIGHT[Math.max(0, Math.min(MAX_BOX, f.box))];
        if (f.emaMs > FAST_MS * 2) w *= 1.5f;
        return w;
    }

    /** Следующий пример: взвешенный случай по выученности, не повторяя недавние. */
    public Problem pick(Random rnd, int minFactor, int maxFactor, List<String> recent) {
        if (minFactor < 1) minFactor = 1;
        if (maxFactor < minFactor) maxFactor = minFactor;
        List<int[]> cand = new ArrayList<>(); List<Float> ws = new ArrayList<>(); float sum = 0;
        for (int a = minFactor; a <= maxFactor; a++) for (int b = 2; b <= 9; b++) {
            if (recent != null && recent.contains(key(a, b))) continue;
            if (a > b && a >= 2 && a <= 9 && b >= minFactor && b <= maxFactor) continue;   // пара уже учтена как (b, a)
            float w = weight(a, b);
            cand.add(new int[]{a, b}); ws.add(w); sum += w;
        }
        if (cand.isEmpty()) return Problem.random(rnd, minFactor, maxFactor, null);
        float r = rnd.nextFloat() * sum;
        int[] p = cand.get(cand.size() - 1);
        for (int i = 0; i < cand.size(); i++) { r -= ws.get(i); if (r <= 0) { p = cand.get(i); break; } }
        return rnd.nextBoolean() ? new Problem(p[0], p[1]) : new Problem(p[1], p[0]);
    }

    void load() {
        if (file == null || !file.exists()) return;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] a = line.split("\t");
                if (a.length < 6) continue;
                try {
                    Fact f = new Fact();
                    f.box = Integer.parseInt(a[1]); f.n = Integer.parseInt(a[2]); f.wrong = Integer.parseInt(a[3]);
                    f.emaMs = Long.parseLong(a[4]); f.last = Long.parseLong(a[5]);
                    facts.put(a[0], f);
                } catch (NumberFormatException ignored) { }
            }
        } catch (Exception ignored) { }
    }

    void save() {
        if (file == null) return;
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Fact> e : facts.entrySet()) {
            Fact f = e.getValue();
            sb.append(String.format(Locale.ROOT, "%s\t%d\t%d\t%d\t%d\t%d\n", e.getKey(), f.box, f.n, f.wrong, f.emaMs, f.last));
        }
        File tmp = new File(file.getPath() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(sb.toString().getBytes(StandardCharsets.UTF_8)); }
        catch (Exception e) { return; }
        tmp.renameTo(file);
    }
}
