import dev.gubenkov.reading.*; import java.nio.file.*; import java.util.*; import org.json.*;
/** Шум: по условиям — доля красных слов, доля слов, сменивших состояние относительно чистой записи, доля «прочитано целиком». */
public class Noise { public static void main(String[] a) throws Exception {
  Map<String,String> tx = new HashMap<>();
  for (String l : Files.readAllLines(Paths.get(a[0]))) if (!l.isBlank()) { JSONObject j = new JSONObject(l); tx.put(j.getString("id"), j.getString("text")); }
  Map<String, Map<String, ReadingMatcher.Result>> by = new LinkedHashMap<>(); Map<String,String> idOf = new HashMap<>();
  for (String l : Files.readAllLines(Paths.get(a[1]))) {
    JSONObject j = new JSONObject(l); String id = j.getString("id"); if (!tx.containsKey(id)) continue;
    by.computeIfAbsent(j.getString("file"), k -> new LinkedHashMap<>()).put(j.getString("cond"), new ReadingMatcher().match(new ReadingText(tx.get(id)), j.getString("hyp"), true));
    idOf.put(j.getString("file"), id);
  }
  for (String kind : new String[]{"живые (.wav)", "озвучка (.ogg)"}) {
    boolean live = kind.startsWith("живые");
    Map<String,int[]> s = new LinkedHashMap<>();   // cond → [слов, красных, сменилось, текстов, целиком]
    for (Map.Entry<String, Map<String, ReadingMatcher.Result>> e : by.entrySet()) {
      if (e.getKey().endsWith(".wav") != live) continue;
      ReadingMatcher.Result clean = e.getValue().get("clean"); if (clean == null) continue;
      for (Map.Entry<String, ReadingMatcher.Result> c : e.getValue().entrySet()) {
        int[] v = s.computeIfAbsent(c.getKey(), k -> new int[5]); ReadingMatcher.Result r = c.getValue();
        for (int w = 0; w < r.states.length; w++) { v[0]++; if (r.states[w] == ReadingMatcher.State.WRONG) v[1]++; if (r.states[w] != clean.states[w]) v[2]++; }
        v[3]++; if (r.complete) v[4]++;
      }
    }
    System.out.println(kind + ":");
    for (Map.Entry<String,int[]> e : s.entrySet()) { int[] v = e.getValue();
      System.out.printf(Locale.ROOT, "  %-9s красных %5.2f%%  сменилось %5.2f%%  целиком %3d/%d%n", e.getKey(), 100.0*v[1]/v[0], 100.0*v[2]/v[0], v[4], v[3]); }
  }
}}
