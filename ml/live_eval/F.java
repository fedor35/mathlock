import dev.gubenkov.reading.*; import java.nio.file.*; import java.util.*; import org.json.*;
/** Доля красных слов: озвучка (всё прочитано верно) и живые записи; strictEndings выкл/вкл. */
public class F { public static void main(String[] a) throws Exception {
  Map<String,String> tx = new HashMap<>(); Map<String,Integer> lvl = new HashMap<>();
  for (String l : Files.readAllLines(Paths.get(a[0]))) if (!l.isBlank()) { JSONObject j = new JSONObject(l); tx.put(j.getString("id"), j.getString("text")); lvl.put(j.getString("id"), j.getInt("level")); }
  int[] w = new int[10], r0 = new int[10], r1 = new int[10]; List<String> ex = new ArrayList<>();
  for (String l : Files.readAllLines(Paths.get(a[1]))) {
    JSONObject j = new JSONObject(l); String id = j.getString("id"); if (!tx.containsKey(id)) continue;
    ReadingText T = new ReadingText(tx.get(id)); String h = j.getString("hyp");
    ReadingMatcher m0 = new ReadingMatcher(), m1 = new ReadingMatcher(); m1.strictEndings = true;
    ReadingMatcher.Result x = m0.match(T, h, true), y = m1.match(T, h, true); int L = lvl.get(id);
    String hl = h.replace(" ", "");
    for (int k = 0; k < T.words.size(); k++) {
      w[L]++; if (x.states[k] == ReadingMatcher.State.WRONG) r0[L]++; if (y.states[k] == ReadingMatcher.State.WRONG) r1[L]++;
      if (x.states[k] != y.states[k] && ex.size() < 100000) ex.add(id + " " + T.words.get(k).letters + " → " + (y.hypFirst[k] >= 0 ? hl.substring(y.hypFirst[k], y.hypLast[k] + 1) : "∅"));
    }
  }
  int W=0,A=0,B=0;
  for (int L = 1; L <= 9; L++) { if (w[L]==0) continue; W+=w[L]; A+=r0[L]; B+=r1[L];
    System.out.printf("L%d: слов %d, красных %.2f%% → %.2f%%%n", L, w[L], 100.0*r0[L]/w[L], 100.0*r1[L]/w[L]); }
  System.out.printf("всего: слов %d, красных %.2f%% → %.2f%% (+%d)%n", W, 100.0*A/W, 100.0*B/W, B-A);
  for (String e : ex) System.out.println("  " + e);
}}
