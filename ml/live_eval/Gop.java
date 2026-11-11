import dev.gubenkov.reading.*; import java.nio.*; import java.nio.file.*; import java.util.*; import org.json.*;
/** Gop <recdir> <lpdir>: прототип WordScore (то же в модуле reading) — оценка ожидаемого слова в его отрезке. */
public class Gop {
  static final String V = " абвгдежзийклмнопрстуфхцчшщъыьэюя"; static final int BLANK = 33;
  static float[][] load(Path p) throws Exception { ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(p)).order(ByteOrder.LITTLE_ENDIAN); int T = b.capacity() / 4 / 34; float[][] r = new float[T][34]; for (int t = 0; t < T; t++) for (int c = 0; c < 34; c++) r[t][c] = b.getFloat(); return r; }
  /** Viterbi: [любое]* слово(CTC) [любое]* на кадрах [t0,t1) минус лучший путь. На букву. */
  static float score(float[][] lp, int t0, int t1, String w) {
    int L = w.length(); int[] lab = new int[L]; for (int i = 0; i < L; i++) lab[i] = V.indexOf(w.charAt(i));
    int S = 2 * L + 1; float NEG = -1e9f;
    float[] best = new float[t1 - t0]; for (int t = t0; t < t1; t++) { float mx = NEG; for (float x : lp[t]) mx = Math.max(mx, x); best[t - t0] = mx; }
    // состояния: 0 = до слова (филлер), 1..S = CTC слова, S+1 = после слова (филлер)
    float[] a = new float[S + 2], n = new float[S + 2]; Arrays.fill(a, NEG); a[0] = 0;
    for (int t = t0; t < t1; t++) {
      Arrays.fill(n, NEG); float[] x = lp[t];
      n[0] = a[0] + best[t - t0];
      for (int s = 1; s <= S; s++) {
        int k = s - 1; boolean blank = k % 2 == 0; int c = blank ? BLANK : lab[k / 2];
        float m = a[s];
        if (s > 1) m = Math.max(m, a[s - 1]);
        if (!blank && k >= 2 && lab[k / 2] != lab[k / 2 - 1]) m = Math.max(m, a[s - 2]);
        if (s == 1 || s == 2) m = Math.max(m, a[0]);
        n[s] = m + x[c];
      }
      n[S + 1] = Math.max(a[S + 1], Math.max(a[S], a[S - 1])) + Math.max(x[BLANK], x[0]);   // после слова — только пауза
      float[] tmp = a; a = n; n = tmp;
    }
    float word = Math.max(a[S + 1], Math.max(a[S], a[S - 1]));
    float free = 0; for (float b : best) free += b;
    return (word - free) / L;
  }
  public static void main(String[] args) throws Exception {
    Path rd = Paths.get(args[0]), ld = Paths.get(args[1]);
    List<Path> fs = new ArrayList<>(); try (var ds = Files.newDirectoryStream(rd, "*.json")) { for (Path p : ds) fs.add(p); } Collections.sort(fs);
    List<String> ok = new ArrayList<>(), bad = new ArrayList<>(); Random R = new Random(1);
    for (Path p : fs) {
      String rec = p.getFileName().toString().replace(".json", ""); if (rec.compareTo("20261107") < 0) continue;
      JSONObject j = new JSONObject(Files.readString(p)); ReadingText T = new ReadingText(j.getString("text"));
      float[][] lp = load(ld.resolve(rec + ".f32"));
      StringBuilder hyp = new StringBuilder(); List<Integer> lf = new ArrayList<>(); int prevc = -1;
      for (int t = 0; t < lp.length; t++) { int c = 0; for (int k = 1; k < 34; k++) if (lp[t][k] > lp[t][c]) c = k; if (c != prevc && c != BLANK) { char ch = V.charAt(c); hyp.append(ch); if (ch != ' ') lf.add(t); } prevc = c; }
      ReadingMatcher.Result r = new ReadingMatcher().match(T, hyp.toString(), false, null);
      String hl = hyp.toString().replace(" ", "");
      for (int w = 0; w < T.words.size(); w++) {
        if (r.states[w] == ReadingMatcher.State.PENDING || r.hypFirst[w] < 0) continue;
        int f0 = lf.get(r.hypFirst[w]), f1 = lf.get(r.hypLast[w]);
        int t0 = Math.max(0, f0 - 6), t1 = Math.min(lp.length, f1 + 8);
        String wl = T.words.get(w).letters.replace("-", "");
        float s = score(lp, t0, t1, wl);
        String piece = hl.substring(r.hypFirst[w], r.hypLast[w] + 1);
        if (r.states[w] == ReadingMatcher.State.OK) {
          if (piece.equals(wl)) { ok.add(String.format(Locale.ROOT, "%.2f", s));
            // ложное слово той же длины из текста — отрицательный пример
            String other = T.words.get(R.nextInt(T.words.size())).letters.replace("-", "");
            if (!other.equals(wl) && other.length() >= 2) bad.add(String.format(Locale.ROOT, "%.2f\t%s→%s", score(lp, t0, t1, other), wl, other)); }
        } else System.out.println(String.format(Locale.ROOT, "RED %.2f\t%s\t%s\t%s", s, rec.substring(0, 15), wl, piece));
      }
    }
    float[] o = new float[ok.size()]; for (int i = 0; i < o.length; i++) o[i] = Float.parseFloat(ok.get(i)); Arrays.sort(o);
    System.out.println("OK слов " + o.length + ": p1 " + o[o.length / 100] + " p5 " + o[o.length / 20] + " медиана " + o[o.length / 2]);
    float[] b = new float[bad.size()]; for (int i = 0; i < b.length; i++) b[i] = Float.parseFloat(bad.get(i).split("\t")[0]); Arrays.sort(b);
    System.out.println("чужое слово " + b.length + ": медиана " + b[b.length / 2] + " p95 " + b[b.length * 95 / 100] + " p99 " + b[b.length * 99 / 100] + " max " + b[b.length - 1]);
    bad.sort(Comparator.comparing(x -> -Float.parseFloat(x.split("\t")[0]))); for (int i = 0; i < 12; i++) System.out.println("  " + bad.get(i));
  }
}
