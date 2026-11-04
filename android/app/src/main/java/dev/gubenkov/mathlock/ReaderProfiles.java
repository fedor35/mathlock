package dev.gubenkov.mathlock;

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
 * Чтецы «Таблицы» — тот же формат файлов, что у стенда readbench (Profiles), чтобы журналы и профили
 * переносились между приложениями и разбирались теми же скриптами (ml/live_eval):
 * {@code files/profiles/<id>.json}, {@code profiles/last.txt}, {@code files/progress-<id>.tsv},
 * {@code progress-unknown.tsv}, {@code recordings/<rec>.json → speaker}.
 * Здесь только то, что нужно экрану блокировки: опознать, завести нового, вести журнал. Правка, объединение
 * и удаление чтецов — в родительском экране readbench (в «Таблице» пока нет).
 */
final class ReaderProfiles {
    static final String TAG = ReadingEngine.TAG;
    static final int START_ADULT_GUESS = 7;
    static final String[] AVATARS = {"🦊", "🐻", "🐼", "🐸", "🐯", "🦉", "🐰", "🐨", "🦁", "🐧", "🐙", "🦄"};
    static final int[] COLORS = {0xFFEF6C00, 0xFF795548, 0xFF546E7A, 0xFF43A047, 0xFFFB8C00, 0xFF5E35B1, 0xFFEC407A, 0xFF00897B};

    final File root, dir;
    final int startChild;
    final List<SpeakerId.Profile> list = new ArrayList<>();
    String lastId;

    ReaderProfiles(File root, int startChild) {
        this.root = root; this.dir = new File(root, "profiles"); this.startChild = startChild; dir.mkdirs(); load();
    }

    void load() {
        list.clear();
        File[] fs = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (fs != null) for (File f : fs) try {
            JSONObject j = new JSONObject(read(f));
            SpeakerId.Profile p = new SpeakerId.Profile(j.getString("id"), j.optString("name", j.getString("id")));
            p.avatar = j.optString("avatar", "🙂"); p.color = j.optInt("color", 0xFF888888); p.kind = j.optString("kind", SpeakerId.UNKNOWN);
            p.n = j.optInt("n", 1); p.startLevel = j.optInt("start_level", startChild); p.created = j.optLong("created");
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

    /** Новый профиль «Чтец N» по первому образцу; стартовый уровень — родительский, у «взрослого по голосу» — 7. */
    SpeakerId.Profile create(float[] emb) {
        int k = 1; while (new File(dir, "p" + k + ".json").exists() || get("p" + k) != null) k++;
        SpeakerId.Profile p = new SpeakerId.Profile("p" + k, "Чтец " + k);
        p.avatar = AVATARS[(k - 1) % AVATARS.length]; p.color = COLORS[(k - 1) % COLORS.length];
        p.created = System.currentTimeMillis();
        SpeakerId.learn(p, emb, false);
        p.kind = SpeakerId.kindOf(SpeakerId.kidProbability(p.emb));
        p.startLevel = SpeakerId.ADULT.equals(p.kind) ? Math.max(startChild, START_ADULT_GUESS) : startChild;
        list.add(p); save(p);
        Log.i(TAG, "новый профиль " + p.id + ", по голосу " + p.kind + ", старт L" + p.startLevel);
        return p;
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

    File journal(String id) { return new File(root, "progress-" + (id == null ? "unknown" : id) + ".tsv"); }

    AdaptiveLevel level(SpeakerId.Profile p) {
        return AdaptiveLevel.parse(p == null ? startChild : p.startLevel, lines(journal(p == null ? null : p.id)));
    }

    void append(String id, AdaptiveLevel.Attempt a) {
        try (FileOutputStream o = new FileOutputStream(journal(id), true)) { o.write((a.toTsv() + "\n").getBytes(StandardCharsets.UTF_8)); }
        catch (Exception e) { Log.w(TAG, "журнал " + id + ": " + e); }
    }

    /** После появления нового профиля: «не определённые» попытки, уверенно похожие на кого-то, перекладываются к нему. */
    int resortUnknown() {
        int n = 0;
        for (AdaptiveLevel.Attempt a : AdaptiveLevel.parse(1, lines(journal(null))).history) {
            if (a.rec == null) continue;
            JSONObject sp = speakerOf(a.rec); float[] e = embOf(sp); if (e == null) continue;
            SpeakerId.Decision d = SpeakerId.decide(e, (float) sp.optDouble("speech_s", 0), list);
            if (d.kind == SpeakerId.Kind.MATCH && move(a.rec, null, d.profile.id)) { SpeakerId.learn(d.profile, e, false); save(d.profile); n++; }
        }
        return n;
    }

    private boolean move(String rec, String from, String to) {
        List<String> keep = new ArrayList<>(), moved = new ArrayList<>();
        for (String l : lines(journal(from))) {
            try { if (rec.equals(AdaptiveLevel.Attempt.parse(l).rec)) { moved.add(l); continue; } } catch (Exception ignored) { }
            keep.add(l);
        }
        if (moved.isEmpty()) return false;
        writeLines(journal(from), keep);
        List<String> dst = lines(journal(to)); dst.addAll(moved);
        Collections.sort(dst, (a, b) -> Long.compare(timeOf(a), timeOf(b)));
        writeLines(journal(to), dst);
        return true;
    }

    private JSONObject speakerOf(String rec) {
        try { return new JSONObject(read(new File(root, "recordings/" + rec + ".json"))).getJSONObject("speaker"); }
        catch (Exception e) { return null; }
    }

    private static float[] embOf(JSONObject s) {
        if (s == null) return null;
        try {
            JSONArray e = s.getJSONArray("emb");
            float[] v = new float[e.length()]; for (int i = 0; i < v.length; i++) v[i] = (float) e.getDouble(i); return v;
        } catch (Exception e) { return null; }
    }

    static long timeOf(String l) { try { return Long.parseLong(l.substring(0, l.indexOf('\t'))); } catch (Exception e) { return 0; } }

    static List<String> lines(File f) {
        List<String> out = new ArrayList<>();
        try { for (String l : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) if (!l.trim().isEmpty()) out.add(l); } catch (Exception ignored) { }
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
