package dev.gubenkov.readbench;

import android.graphics.Color;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import dev.gubenkov.reading.AdaptiveLevel;
import dev.gubenkov.reading.SpeakerId;

/**
 * Профили чтецов на диске:
 * <ul>
 *   <li>{@code files/profiles/<id>.json} — имя, аватар, цвет, тип (ребёнок/взрослый/неизвестно), стартовый уровень,
 *       средний нормированный эмбеддинг и число образцов;</li>
 *   <li>{@code files/progress-<id>.tsv} — журнал попыток чтеца (формат AdaptiveLevel.Attempt, 7-й столбец — запись);</li>
 *   <li>{@code files/progress-unknown.tsv} — попытки, где чтец не определён (в уровень не идут);</li>
 *   <li>{@code files/profiles/last.txt} — последний опознанный чтец (по нему подбирается следующий текст);</li>
 *   <li>{@code files/recordings/<rec>.json} → {@code speaker}: эмбеддинг и косинусы попытки — для пересортировки.</li>
 * </ul>
 * Старый общий {@code progress.tsv} не трогается.
 */
final class Profiles {
    static final String TAG = ReadActivity.TAG;
    static final String UNKNOWN = "unknown";
    /** Стартовый уровень: сын читает целыми словами 30–50 слов/мин (04.11.2026); взрослый — сразу верхний. */
    static final int START_CHILD = 5, START_ADULT = AdaptiveLevel.MAX;
    /** Взрослый «по голосу» — осторожнее: на детской речи с телефона классификатор не проверен. */
    static final int START_ADULT_GUESS = 7;
    static final String[] AVATARS = {"🦊", "🐻", "🐼", "🐸", "🐯", "🦉", "🐰", "🐨", "🦁", "🐧", "🐙", "🦄"};
    static final int[] COLORS = {Color.rgb(239, 108, 0), Color.rgb(121, 85, 72), Color.rgb(84, 110, 122), Color.rgb(67, 160, 71),
            Color.rgb(251, 140, 0), Color.rgb(94, 53, 177), Color.rgb(236, 64, 122), Color.rgb(0, 137, 123)};

    final File root, dir;
    final List<SpeakerId.Profile> list = new ArrayList<>();
    String lastId;

    Profiles(File root) { this.root = root; this.dir = new File(root, "profiles"); dir.mkdirs(); load(); }

    void load() {
        list.clear();
        File[] fs = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (fs != null) for (File f : fs) try {
            JSONObject j = new JSONObject(read(f));
            SpeakerId.Profile p = new SpeakerId.Profile(j.getString("id"), j.optString("name", j.getString("id")));
            p.avatar = j.optString("avatar", "🙂"); p.color = j.optInt("color", Color.GRAY); p.kind = j.optString("kind", SpeakerId.UNKNOWN);
            p.n = j.optInt("n", 1); p.startLevel = j.optInt("start_level", START_CHILD); p.created = j.optLong("created");
            JSONArray e = j.getJSONArray("emb"); p.emb = new float[e.length()];
            for (int i = 0; i < p.emb.length; i++) p.emb[i] = (float) e.getDouble(i);
            list.add(p);
        } catch (Exception e) { Log.w(TAG, "профиль " + f + ": " + e); }
        Collections.sort(list, (a, b) -> Long.compare(a.created, b.created));
        try { lastId = read(new File(dir, "last.txt")).trim(); } catch (Exception e) { lastId = null; }
        if (get(lastId) == null) lastId = list.isEmpty() ? null : list.get(list.size() - 1).id;
    }

    SpeakerId.Profile get(String id) { if (id != null) for (SpeakerId.Profile p : list) if (p.id.equals(id)) return p; return null; }

    SpeakerId.Profile last() { return get(lastId); }

    void setLast(String id) { lastId = id; write(new File(dir, "last.txt"), id); }

    /** Новый профиль «Чтец N» по первому образцу; тип и стартовый уровень — по голосу, если уверенно. */
    SpeakerId.Profile create(float[] emb) {
        int k = 1; while (new File(dir, "p" + k + ".json").exists() || get("p" + k) != null) k++;
        SpeakerId.Profile p = new SpeakerId.Profile("p" + k, "Чтец " + k);
        p.avatar = AVATARS[(k - 1) % AVATARS.length]; p.color = COLORS[(k - 1) % COLORS.length];
        p.created = System.currentTimeMillis();
        SpeakerId.learn(p, emb, false);
        p.kind = SpeakerId.kindOf(SpeakerId.kidProbability(p.emb));
        p.startLevel = SpeakerId.ADULT.equals(p.kind) ? START_ADULT_GUESS : START_CHILD;
        list.add(p); save(p);
        Log.i(TAG, String.format(java.util.Locale.ROOT, "новый профиль %s «%s», по голосу %s (P(ребёнок)=%.2f), старт L%d",
                p.id, p.name, p.kind, SpeakerId.kidProbability(p.emb), p.startLevel));
        return p;
    }

    /** Тип задан родителем: меняет и стартовый уровень. */
    void setKind(SpeakerId.Profile p, String kind) {
        p.kind = kind; p.startLevel = SpeakerId.ADULT.equals(kind) ? START_ADULT : START_CHILD; save(p);
    }

    void save(SpeakerId.Profile p) {
        try {
            JSONArray e = new JSONArray(); for (float v : p.emb) e.put(Math.round(v * 1e6) / 1e6);
            JSONObject j = new JSONObject().put("id", p.id).put("name", p.name).put("avatar", p.avatar).put("color", p.color)
                    .put("kind", p.kind).put("kid_p", Math.round(SpeakerId.kidProbability(p.emb) * 1000) / 1000.0)
                    .put("n", p.n).put("start_level", p.startLevel).put("created", p.created).put("emb", e);
            write(new File(dir, p.id + ".json"), j.toString(1));
        } catch (Exception ex) { Log.w(TAG, "save " + p.id + ": " + ex); }
    }

    File journal(String id) { return new File(root, "progress-" + (id == null ? UNKNOWN : id) + ".tsv"); }

    List<AdaptiveLevel.Attempt> attempts(String id) {
        AdaptiveLevel a = AdaptiveLevel.parse(1, lines(journal(id))); return a.history;
    }

    AdaptiveLevel level(SpeakerId.Profile p) {
        return AdaptiveLevel.parse(p == null ? START_CHILD : p.startLevel, lines(journal(p == null ? null : p.id)));
    }

    void append(String id, AdaptiveLevel.Attempt a) {
        try (FileOutputStream o = new FileOutputStream(journal(id), true)) { o.write((a.toTsv() + "\n").getBytes(StandardCharsets.UTF_8)); }
        catch (Exception e) { Log.w(TAG, "журнал " + id + ": " + e); }
    }

    /** Переложить попытку (по имени записи) из журнала from в журнал to; true — нашлась. */
    boolean move(String rec, String from, String to) {
        List<String> keep = new ArrayList<>(), moved = new ArrayList<>();
        for (String l : lines(journal(from))) {
            try { if (rec.equals(AdaptiveLevel.Attempt.parse(l).rec)) { moved.add(l); continue; } } catch (Exception ignored) {}
            keep.add(l);
        }
        if (moved.isEmpty()) return false;
        writeLines(journal(from), keep);
        List<String> dst = lines(journal(to)); dst.addAll(moved); sortByTime(dst); writeLines(journal(to), dst);
        return true;
    }

    /** Объединить профиль from в into: эмбеддинги с весами, журналы по времени; from удаляется. */
    void merge(SpeakerId.Profile into, SpeakerId.Profile from) {
        SpeakerId.merge(into, from);
        List<String> all = lines(journal(into.id)); all.addAll(lines(journal(from.id))); sortByTime(all);
        writeLines(journal(into.id), all);
        journal(from.id).delete(); new File(dir, from.id + ".json").delete();
        list.remove(from); save(into);
        if (from.id.equals(lastId)) setLast(into.id);
        Log.i(TAG, "профиль " + from.id + " объединён с " + into.id);
    }

    /** Удалить профиль без попыток (заведён по обрывку); журнал переименовывается, а не стирается. */
    void delete(SpeakerId.Profile p) {
        File j = journal(p.id); if (j.exists()) j.renameTo(new File(j.getPath() + ".deleted"));
        new File(dir, p.id + ".json").renameTo(new File(dir, p.id + ".json.deleted"));
        list.remove(p);
        if (p.id.equals(lastId)) setLast(list.isEmpty() ? "" : list.get(0).id);
        Log.i(TAG, "профиль " + p.id + " удалён");
    }

    /** Голосовая часть попытки: recordings/<rec>.json → speaker {emb, speech_s, cos, decision} или null. */
    JSONObject speakerOf(String rec) {
        try { return new JSONObject(read(new File(root, "recordings/" + rec + ".json"))).getJSONObject("speaker"); }
        catch (Exception e) { return null; }
    }

    /** Эмбеддинг попытки или null. */
    float[] embOf(String rec) {
        JSONObject s = speakerOf(rec); if (s == null) return null;
        try {
            JSONArray e = s.getJSONArray("emb");
            float[] v = new float[e.length()]; for (int i = 0; i < v.length; i++) v[i] = (float) e.getDouble(i); return v;
        } catch (Exception e) { return null; }
    }

    /** После появления нового профиля: «не определённые» попытки, теперь уверенно похожие на кого-то, раскладываются. */
    int resortUnknown() {
        int n = 0;
        for (AdaptiveLevel.Attempt a : attempts(null)) {
            if (a.rec == null) continue;
            JSONObject sp = speakerOf(a.rec); float[] e = embOf(a.rec); if (sp == null || e == null) continue;
            SpeakerId.Decision d = SpeakerId.decide(e, (float) sp.optDouble("speech_s", 0), list);
            if (d.kind == SpeakerId.Kind.MATCH && move(a.rec, null, d.profile.id)) {
                SpeakerId.learn(d.profile, e, false); save(d.profile); n++;
                Log.i(TAG, "пересортировка: " + a.rec + " → " + d.profile.id + " " + d.describe(list));
            }
        }
        return n;
    }

    static void sortByTime(List<String> ls) {
        Collections.sort(ls, (a, b) -> Long.compare(timeOf(a), timeOf(b)));
    }

    static long timeOf(String l) { try { return Long.parseLong(l.substring(0, l.indexOf('\t'))); } catch (Exception e) { return 0; } }

    static List<String> lines(File f) {
        List<String> out = new ArrayList<>();
        try { for (String l : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) if (!l.trim().isEmpty()) out.add(l); } catch (Exception ignored) {}
        return out;
    }

    static void writeLines(File f, List<String> ls) {
        StringBuilder s = new StringBuilder(); for (String l : ls) s.append(l).append('\n');
        if (ls.isEmpty()) f.delete(); else write(f, s.toString());
    }

    static String read(File f) throws Exception { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }

    static void write(File f, String s) {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(s.getBytes(StandardCharsets.UTF_8)); }
        catch (Exception e) { Log.w(TAG, "запись " + f + ": " + e); return; }
        if (!tmp.renameTo(f)) Log.w(TAG, "rename " + tmp);
    }
}
