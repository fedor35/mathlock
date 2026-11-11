import dev.gubenkov.reading.*; import java.nio.file.*; import java.util.*; import org.json.*;
/** Sim2 <recdir> <lpdir> [-v]: поток по кадрам (шаг 400 мс) из сохранённых лог-вероятностей, как ReadingScreen:
 *  prev между записями одного текста, отпуск через 6 слов, финальная сверка. На каждой остановке — GOP слова. */
public class Sim2 { public static void main(String[] a) throws Exception {
  boolean v = a.length > 2 && a[2].equals("-v");
  List<Path> fs = new ArrayList<>(); try (var ds = Files.newDirectoryStream(Paths.get(a[0]), "*.json")) { for (Path p : ds) fs.add(p); } Collections.sort(fs);
  String lastId = null; ReadingMatcher.Result prev = null; int total = 0, pass = 0, stops = 0; float stuckSec = 0; Map<String, Float> perRec = new TreeMap<>();
  for (Path p : fs) {
    String rec = p.getFileName().toString().replace(".json", "");
    JSONObject j = new JSONObject(Files.readString(p)); String id = j.getString("id"); if (!id.equals(lastId)) prev = null; lastId = id;
    if (rec.compareTo("20261107") < 0) continue;
    float before = stuckSec; ReadingText T = new ReadingText(j.getString("text")); float[][] lp = Gop.load(Paths.get(a[1], rec + ".f32"));
    ReadingMatcher m = new ReadingMatcher(); boolean[] rel = new boolean[T.words.size()];
    boolean ac = !Arrays.asList(a).contains("-noac"), cl = !Arrays.asList(a).contains("-nocl"); int lastLen = 0, lastChange = 0;
    int stuck = -1, stopT = 0; StringBuilder log = new StringBuilder(); boolean done = false; ReadingMatcher.Result res = null; String hyp = "";
    for (int t = 10; t <= lp.length + 9 && !done; t += 10) {
      int tt = Math.min(t, lp.length); List<Integer> lf = new ArrayList<>(); hyp = greedy(lp, tt, lf);
      int len = hyp.replace(" ", "").length(); if (len != lastLen) { lastLen = len; lastChange = tt; }
      try { if (ac) m.acoustic = new WordScore(lp, lf, Gop.V.split("(?!^)"), Gop.BLANK);
      m.closeLast = cl && len > 0 && tt - lastChange >= 50; } catch (NoSuchFieldError e) { }
      ReadingMatcher.Result raw = m.match(T, hyp, false, prev);
      int fw = ReadingMatcher.firstWrong(raw, rel);
      if (fw >= 0 && raw.progressWords > fw + 1 + 6) { rel[fw] = true; log.append(" REL@").append(T.words.get(fw).letters); }
      res = ReadingMatcher.gate(raw, rel);
      int sw = ReadingMatcher.firstWrong(res, rel);
      if (sw != stuck) {
        if (sw >= 0) { stops++; String hl = hyp.replace(" ", ""); String piece = raw.hypFirst[sw] >= 0 ? hl.substring(raw.hypFirst[sw], raw.hypLast[sw] + 1) : "∅";
          float g = Float.NaN; if (raw.hypFirst[sw] >= 0) { int t0 = Math.max(0, lf.get(raw.hypFirst[sw]) - 6), t1 = Math.min(tt, lf.get(raw.hypLast[sw]) + 8); g = Gop.score(lp, t0, t1, T.words.get(sw).letters.replace("-", "")); }
          log.append(String.format(Locale.ROOT, "\n     STOP %s [%s] gop=%.2f t=%.1fs", T.words.get(sw).letters, piece, g, tt * 0.04)); }
        else log.append(String.format(Locale.ROOT, " → go t=%.1fs", tt * 0.04));
        if (sw >= 0) stopT = tt; else if (stuck >= 0) stuckSec += (tt - stopT) * 0.04f;
        stuck = sw; }
      if (res.complete) done = true;
    }
    { List<Integer> lf = new ArrayList<>(); hyp = greedy(lp, lp.length, lf); try { if (ac) m.acoustic = new WordScore(lp, lf, Gop.V.split("(?!^)"), Gop.BLANK); m.closeLast = false; } catch (NoSuchFieldError e) { } }
    if (stuck >= 0) stuckSec += (lp.length - stopT) * 0.04f;
    ReadingMatcher.Result fin = done ? res : ReadingMatcher.gate(m.match(T, hyp, true, prev), rel);
    total++; if (fin.complete) pass++; perRec.put(rec, stuckSec - before);
    System.out.println(String.format("%-28s app=%s sim=%s %s", rec, j.optBoolean("auto_ok") ? "PASS" : "----", fin.complete ? "PASS" : "----", log));
    prev = fin.complete ? null : fin;
  }
  for (var e : perRec.entrySet()) System.out.println(String.format(Locale.ROOT, "STUCK %s %.1f", e.getKey(), e.getValue()));
  System.out.println("записей " + total + ", засчитано " + pass + ", остановок " + stops + String.format(Locale.ROOT, ", стоял на красном %.0f с", stuckSec));
}
  static String greedy(float[][] lp, int n, List<Integer> lf) { StringBuilder s = new StringBuilder(); int pc = -1;
    for (int t = 0; t < n; t++) { int c = 0; for (int k = 1; k < 34; k++) if (lp[t][k] > lp[t][c]) c = k; if (c != pc && c != Gop.BLANK) { char ch = Gop.V.charAt(c); s.append(ch); if (ch != ' ') lf.add(t); } pc = c; }
    return s.toString(); }
}
