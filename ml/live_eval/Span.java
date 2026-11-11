import dev.gubenkov.reading.*; import java.nio.file.*; import java.util.*; import org.json.*;
/** Распределение растяжки слова (буквы речи, отданные слову, минус длина слова) для засчитанных слов. */
public class Span { public static void main(String[] a) throws Exception {
  Map<String,String> tx = new HashMap<>(); int[] hist = new int[200]; List<String> big = new ArrayList<>();
  for (String l : Files.readAllLines(Paths.get(a[0]))) if (!l.isBlank()) { JSONObject j = new JSONObject(l); tx.put(j.getString("id"), j.getString("text")); }
  List<String[]> items = new ArrayList<>();
  if (a[1].endsWith(".jsonl")) for (String l : Files.readAllLines(Paths.get(a[1]))) { JSONObject j = new JSONObject(l); items.add(new String[]{j.getString("id").split("\\.")[0], j.getString("hyp"), j.optString("file", j.getString("id"))}); }
  else try (var ds = Files.newDirectoryStream(Paths.get(a[1]), "*.json")) { for (Path p : ds) { JSONObject j = new JSONObject(Files.readString(p)); tx.put(j.getString("id"), j.getString("text")); items.add(new String[]{j.getString("id"), j.getString("hyp"), p.getFileName().toString()}); } }
  for (String[] it : items) { if (!tx.containsKey(it[0])) continue;
    ReadingText T = new ReadingText(tx.get(it[0])); String hl = it[1].replace(" ", ""); ReadingMatcher.Result r = new ReadingMatcher().match(T, it[1], true);
    for (int k = 0; k < T.words.size(); k++) { if (r.states[k] != ReadingMatcher.State.OK || r.hypFirst[k] < 0) continue;
      int len = T.words.get(k).letters.replace("-", "").length(), ex = r.hypLast[k] - r.hypFirst[k] + 1 - len;
      hist[Math.max(0, Math.min(199, ex))]++; if (ex > Integer.parseInt(a[2])) big.add(it[2] + " " + T.words.get(k).letters + " +" + ex + " [" + hl.substring(r.hypFirst[k], r.hypLast[k] + 1) + "]"); } }
  long tot = 0; for (int x : hist) tot += x; long c = 0;
  for (int e = 0; e < 200; e++) { c += hist[e]; if (hist[e] > 0 && e >= 3) System.out.printf("+%d: %d (хвост %.3f%%)%n", e, hist[e], 100.0 * (tot - c) / tot); }
  for (String b : big) System.out.println("  " + b);
}}
