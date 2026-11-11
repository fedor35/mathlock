import dev.gubenkov.reading.*; import java.nio.file.*; import java.util.*; import org.json.*;
/** Reds texts.jsonl hyps.jsonl: красные слова на озвучке (всё прочитано верно) по каждому тексту; id гипотезы «<текст>.<голос>». */
public class Reds { public static void main(String[] a) throws Exception {
  Map<String,String> tx = new HashMap<>();
  for (String l : Files.readAllLines(Paths.get(a[0]))) if (!l.isBlank()) { JSONObject j = new JSONObject(l); tx.put(j.getString("id"), j.getString("text")); }
  int W = 0, R = 0; Map<String,Integer> words = new TreeMap<>();
  for (String l : Files.readAllLines(Paths.get(a[1]))) {
    JSONObject j = new JSONObject(l); String id = j.getString("id"), tid = id.split("\\.")[0]; if (!tx.containsKey(tid)) continue;
    ReadingText T = new ReadingText(tx.get(tid)); String h = j.getString("hyp"), hl = h.replace(" ", "");
    ReadingMatcher.Result r = new ReadingMatcher().match(T, h, true);
    StringBuilder sb = new StringBuilder();
    for (int k = 0; k < T.words.size(); k++) { W++;
      if (r.states[k] == ReadingMatcher.State.WRONG) { R++; String w = T.words.get(k).letters; words.merge(w, 1, Integer::sum);
        sb.append(" ").append(w).append("→").append(r.hypFirst[k] >= 0 ? hl.substring(r.hypFirst[k], r.hypLast[k] + 1) : "∅"); } }
    if (sb.length() > 0) System.out.println(id + ":" + sb);
  }
  System.out.printf("слов %d, красных %d (%.2f%%)%n", W, R, 100.0 * R / W);
  System.out.println(words);
}}
