import dev.gubenkov.reading.*; import java.nio.file.*; import java.util.*; import org.json.*;
/** Синтетические ошибки: по типам — доля слов, которые сверка сделала красными (обычно / strictEndings) и с подсказкой окончания.
 *  java G texts.jsonl jobs.jsonl hyps.jsonl [примеры.tsv] */
public class GA {
  static final float TOL = Float.parseFloat(System.getProperty("tol", "0.25"));
  static class C { int n, red0, red1, hint; }
  public static void main(String[] a) throws Exception {
    Map<String,String> tx = new HashMap<>();
    for (String l : Files.readAllLines(Paths.get(a[0]))) if (!l.isBlank()) { JSONObject j = new JSONObject(l); tx.put(j.getString("id"), j.getString("text")); }
    Map<String,String> hy = new HashMap<>();
    for (String l : Files.readAllLines(Paths.get(a[2]))) if (!l.isBlank()) { JSONObject j = new JSONObject(l); hy.put(j.getString("id"), j.getString("hyp")); }
    Map<String,C> by = new TreeMap<>(); List<String> ex = new ArrayList<>(); int skipped = 0;
    for (String l : Files.readAllLines(Paths.get(a[1]))) {
      if (l.isBlank()) continue; JSONObject j = new JSONObject(l); String id = j.getString("id"); java.nio.file.Path lpf = Paths.get(System.getProperty("lp"), id + ".f32"); if (!Files.exists(lpf)) continue;
      float[][] lp = Gop.load(lpf); List<Integer> lf = new ArrayList<>(); String h = Sim2.greedy(lp, lp.length, lf);
      ReadingText T = new ReadingText(tx.get(j.getString("text_id"))); String lab = j.getString("labels"); JSONArray kinds = j.getJSONArray("kinds");
      if (lab.length() != T.words.size()) { skipped++; continue; }
      ReadingMatcher m0 = new ReadingMatcher(), m1 = new ReadingMatcher(); m1.strictEndings = true; m0.wordTolerance = m1.wordTolerance = TOL; if (Boolean.getBoolean("ac")) { m0.acoustic = m1.acoustic = new WordScore(lp, lf, Gop.V.split("(?!^)"), Gop.BLANK); }
      ReadingMatcher.Result x = m0.match(T, h, true), y = m1.match(T, h, true);
      String hl = h.replace(" ", ""); String voice = j.getBoolean("child") ? "дет" : "взр";
      for (int w = 0; w < lab.length(); w++) {
        String k = kinds.getString(w);
        if (k.equals("-")) {   // нетронутое слово: рядом с ошибкой или далеко
          boolean near = (w > 0 && !kinds.getString(w - 1).equals("-")) || (w + 1 < lab.length() && !kinds.getString(w + 1).equals("-"));
          k = near ? "-рядом" : "-чисто";
        }
        boolean r0 = x.states[w] == ReadingMatcher.State.WRONG, r1 = y.states[w] == ReadingMatcher.State.WRONG, eh = x.endingHint != null && x.endingHint[w];
        for (String key : new String[]{k, k + " " + voice}) {
          C c = by.computeIfAbsent(key, z -> new C()); c.n++; if (r0) c.red0++; if (r1) c.red1++; if (eh && !r0) c.hint++;
        }
        boolean expectRed = lab.charAt(w) == 'W';
        if (ex.size() < 200000 && (expectRed ? !r1 && !eh : r0 || eh) && !k.equals("-чисто")) {
          String piece = x.hypFirst[w] >= 0 && x.hypLast[w] >= x.hypFirst[w] ? hl.substring(x.hypFirst[w], x.hypLast[w] + 1) : "∅";
          ex.add(k + "\t" + id + "\t" + T.words.get(w).letters + "\t" + piece + "\t" + x.states[w] + (eh ? "+hint" : "") + "\t" + y.states[w]);
        }
      }
    }
    System.out.println("тип        | слов  | красных | +подсказка | красных strict");
    for (Map.Entry<String,C> e : by.entrySet()) { C c = e.getValue();
      System.out.printf(Locale.ROOT, "%-10s | %5d | %6.1f%% | %9.1f%% | %6.1f%%%n", e.getKey(), c.n, 100.0*c.red0/c.n, 100.0*(c.red0+c.hint)/c.n, 100.0*c.red1/c.n); }
    String cl = System.getProperty("clean");
    if (cl != null) { int n = 0, r = 0; ReadingMatcher m = new ReadingMatcher(); m.wordTolerance = TOL;
      for (String l : Files.readAllLines(Paths.get(cl))) { if (l.isBlank()) continue; JSONObject j = new JSONObject(l); String t = tx.get(j.getString("id")); if (t == null) continue;
        ReadingText T = new ReadingText(t); ReadingMatcher.Result x = m.match(T, j.getString("hyp"), true);
        for (ReadingMatcher.State s : x.states) { n++; if (s == ReadingMatcher.State.WRONG) r++; } }
      System.out.printf(Locale.ROOT, "эталонная озвучка (всё верно): слов %d, красных %.2f%%%n", n, 100.0 * r / n); }
    if (skipped > 0) System.out.println("пропущено (не сошлось число слов): " + skipped);
    if (a.length > 3) Files.write(Paths.get(a[3]), ex);
  }
}
