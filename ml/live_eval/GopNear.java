import dev.gubenkov.reading.*; import java.nio.file.*; import java.util.*; import org.json.*;
/** На верно прочитанных словах (кусок речи == слово, ≥4 букв): GOP «соседних» форм — сколько из них прошло бы порог. */
public class GopNear { public static void main(String[] a) throws Exception {
  List<Path> fs = new ArrayList<>(); try (var ds = Files.newDirectoryStream(Paths.get(a[0]), "*.json")) { for (Path p : ds) fs.add(p); } Collections.sort(fs);
  String VOW = "аоуыэяеиюё"; float[] thr = {-0.3f, -0.5f, -0.7f, -1.0f}; int[] passDrop = new int[4], passVow = new int[4], passAdd = new int[4], passOther = new int[4]; int nDrop = 0, nVow = 0, nAdd = 0, nOther = 0, nWords = 0; Random R = new Random(2);
  for (Path p : fs) {
    String rec = p.getFileName().toString().replace(".json", "");
    JSONObject j = new JSONObject(Files.readString(p)); ReadingText T = new ReadingText(j.getString("text")); float[][] lp = Gop.load(Paths.get(a[1], rec + ".f32"));
    List<Integer> lf = new ArrayList<>(); String hyp = Sim2.greedy(lp, lp.length, lf); String hl = hyp.replace(" ", "");
    ReadingMatcher.Result r = new ReadingMatcher().match(T, hyp, false, null);
    for (int w = 0; w < T.words.size(); w++) {
      if (r.states[w] != ReadingMatcher.State.OK || r.hypFirst[w] < 0) continue;
      String wl = T.words.get(w).letters.replace("-", ""); if (wl.length() < 4 || !hl.substring(r.hypFirst[w], r.hypLast[w] + 1).equals(wl)) continue;
      int t0 = Math.max(0, lf.get(r.hypFirst[w]) - 6), t1 = Math.min(lp.length, lf.get(r.hypLast[w]) + 8); nWords++;
      List<String[]> vs = new ArrayList<>();
      vs.add(new String[]{"drop", wl.substring(0, wl.length() - 1)});
      char last = wl.charAt(wl.length() - 1);
      if (VOW.indexOf(last) >= 0) for (char c : "аоуыяеи".toCharArray()) if (c != last) vs.add(new String[]{"vow", wl.substring(0, wl.length() - 1) + c});
      vs.add(new String[]{"add", wl + "и"}); vs.add(new String[]{"add", wl + "а"});
      String o = T.words.get(R.nextInt(T.words.size())).letters.replace("-", ""); if (o.length() >= 4 && !o.equals(wl)) vs.add(new String[]{"other", o});
      for (String[] v : vs) { float g = Gop.score(lp, t0, t1, v[1]);
        for (int k = 0; k < 4; k++) if (g >= thr[k]) { if (v[0].equals("drop")) passDrop[k]++; else if (v[0].equals("vow")) passVow[k]++; else if (v[0].equals("add")) passAdd[k]++; else passOther[k]++; }
        if (v[0].equals("drop")) nDrop++; else if (v[0].equals("vow")) nVow++; else if (v[0].equals("add")) nAdd++; else nOther++; }
    }
  }
  System.out.println("слов " + nWords);
  for (int k = 0; k < 4; k++) System.out.println(String.format(Locale.ROOT, "порог %.1f: без последней буквы %.1f%%, другая гласная на конце %.1f%%, лишняя буква %.1f%%, чужое слово %.1f%%", thr[k], 100f * passDrop[k] / nDrop, 100f * passVow[k] / nVow, 100f * passAdd[k] / nAdd, 100f * passOther[k] / nOther));
}}
