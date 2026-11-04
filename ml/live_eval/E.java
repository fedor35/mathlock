import dev.gubenkov.reading.*; import java.nio.file.*; import java.util.*; import org.json.*;
/** rec → по словам: состояние (заново по hyp), кусок гипотезы приложения. Вывод TSV: rec i word state apphyp */
public class E { public static void main(String[] a) throws Exception {
  for (String rec : Files.readAllLines(Paths.get(a[1]))) {
    if (rec.isBlank()) continue;
    JSONObject j = new JSONObject(Files.readString(Paths.get(a[0], rec + ".json")));
    ReadingText T = new ReadingText(j.getString("text")); String h = j.optString("hyp", ""); String hl = h.replace(" ", "");
    ReadingMatcher.Result r = new ReadingMatcher().match(T, h, true);
    String st = j.getString("states"); int diff = 0;
    for (int w = 0; w < T.words.size(); w++) {
      if (st.charAt(w) != r.states[w].name().charAt(0)) diff++;
      String piece = r.hypFirst[w] >= 0 && r.hypLast[w] >= r.hypFirst[w] ? hl.substring(r.hypFirst[w], r.hypLast[w] + 1) : "∅";
      System.out.println(rec + "\t" + w + "\t" + T.words.get(w).letters + "\t" + st.charAt(w) + r.states[w].name().charAt(0) + "\t" + piece + "\t" + String.format(Locale.ROOT, "%.2f", r.wordCost[w]));
    }
    if (diff > 0) System.err.println(rec + ": состояния разошлись в " + diff + " словах");
  }}}
