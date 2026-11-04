package dev.gubenkov.reading;

/**
 * Сверка распознанного (жадный CTC, буквы с пробелами) с текстом. Пробелы игнорируются:
 * при чтении по слогам ASR выдаёт «ма ма мы ла». Выравнивание буквенное, с дешёвыми вставками
 * (повторы слогов, «э-э») и бесплатным непрочитанным хвостом текста (потоковая подсветка).
 */
public final class ReadingMatcher {
    public static final float INS = 0.35f, DEL = 1f, DEL_END = 1.6f, SUB = 1f, SUB_SIMILAR = 0.4f;
    /** Слово засчитано, если доля ошибок ≤ этого (по длине слова); короткие предлоги прощаем отдельно. */
    public float wordTolerance = 0.25f;
    /**
     * Прощать пропуск слов без гласной (в, с, к), если соседние прочитаны: ASR сливает их со следующим словом.
     * На озвученном корпусе (930 текстов, правильное чтение) GigaAM теряет 2,3% таких слов, а коротких слов
     * с гласной (и, а, но, на) — 0,3%, поэтому слово с гласной, от которого не услышано ни одной буквы
     * (пропущенное «и»), — ошибка (Фёдор, 04.11). Частично услышанное («во» → «в» в «вдворе») прощается.
     */
    public boolean forgiveShortWords = true;

    public enum State { PENDING, OK, WRONG }

    public static final class Result {
        public final State[] states;
        public final float[] wordCost;
        public final int progressWords; // сколько слов от начала «пройдено» (дальше — непрочитанное)
        public final boolean complete;
        /** Индексы букв гипотезы (без пробелов), сопоставленных слову: [первая, последняя] или -1. */
        public final int[] hypFirst, hypLast;
        Result(State[] s, float[] c, int p, boolean done, int[] hf, int[] hl) { states = s; wordCost = c; progressWords = p; complete = done; hypFirst = hf; hypLast = hl; }
    }

    static float sub(LanguageSpec L, char a, char b) { return a == b ? 0 : L.similar(a, b) < 1f ? SUB_SIMILAR : SUB; }

    public Result match(ReadingText text, String hypothesis, boolean finalPass) { return match(text, hypothesis, finalPass, null); }

    /** Длинная вставка — возврат назад и перечитывание или «э-э-э»: слово перед ней не штрафуется. */
    static final int MAX_CHARGED_INS = 3;

    /**
     * @param prev результат прошлой записи этого же текста (перечитываем красные слова или дочитываем хвост) или null.
     *             Засчитанные тогда слова остаются засчитанными, а их пропуск в новой записи бесплатен:
     *             можно начать с любого красного слова. Неперечитанные красные остаются красными.
     */
    public Result match(ReadingText text, String hypothesis, boolean finalPass, Result prev) {
        LanguageSpec L = text.lang;
        int W = text.words.size();
        StringBuilder tb = new StringBuilder(); int[] wordOf = new int[256]; int n = 0;
        for (int w = 0; w < W; w++) for (char c : text.words.get(w).letters.toCharArray()) {
            if (c == '-') continue;
            if (n == wordOf.length) wordOf = java.util.Arrays.copyOf(wordOf, n * 2);
            wordOf[n++] = w; tb.append(c);
        }
        String t = tb.toString();
        // пропуск последней буквы слова (окончание: купила→купил, шары→шар) — дороже обычного
        float[] del = new float[n];
        for (int k = 0; k < n; k++) del[k] = prevOk(prev, wordOf[k]) ? 0f : (k + 1 == n || wordOf[k + 1] != wordOf[k]) ? DEL_END : DEL;
        StringBuilder hb = new StringBuilder();
        for (char c : hypothesis.toCharArray()) { char x = L.normalize(c); if (x != 0) hb.append(x); }
        String h = hb.toString();
        int m = h.length();
        // D[i][j]: стоимость выравнивания t[0..i) c h[0..j)
        float[][] D = new float[n + 1][m + 1];
        byte[][] B = new byte[n + 1][m + 1]; // 0 diag, 1 del (пропуск буквы текста), 2 ins (лишняя буква)
        for (int i = 1; i <= n; i++) { D[i][0] = D[i - 1][0] + del[i - 1]; B[i][0] = 1; }
        for (int j = 1; j <= m; j++) { D[0][j] = D[0][j - 1] + INS; B[0][j] = 2; }
        for (int i = 1; i <= n; i++) for (int j = 1; j <= m; j++) {
            float d = D[i - 1][j - 1] + sub(L, t.charAt(i - 1), h.charAt(j - 1)), dl = D[i - 1][j] + del[i - 1], in = D[i][j - 1] + INS;
            if (d <= dl && d <= in) { D[i][j] = d; B[i][j] = 0; } else if (dl <= in) { D[i][j] = dl; B[i][j] = 1; } else { D[i][j] = in; B[i][j] = 2; }
        }
        // конец гипотезы может прийтись на любое место текста (хвост ещё не прочитан)
        int bi = n;
        if (!finalPass) {
            float best = Float.MAX_VALUE;
            for (int i = 0; i <= n; i++) { float c = D[i][m] + 0.02f * (n - i); if (c < best) { best = c; bi = i; } }
        }
        float[] cost = new float[W];
        int[] hf = new int[W], hl = new int[W];
        java.util.Arrays.fill(hf, -1); java.util.Arrays.fill(hl, -1);
        int i = bi, j = m;
        while (i > 0 || j > 0) {
            byte b = B[i][j];
            if (i > 0 && j > 0 && b == 0) {
                int w = wordOf[i - 1]; cost[w] += sub(L, t.charAt(i - 1), h.charAt(j - 1));
                hf[w] = j - 1; if (hl[w] < 0) hl[w] = j - 1; i--; j--;
            }
            else if (i > 0 && (j == 0 || b == 1)) { cost[wordOf[i - 1]] += del[i - 1]; i--; }
            else { // вставку относим к слову перед ней; длинную (перечитывание, «э-э-э») не штрафуем и в слово не включаем
                int run = 0, j0 = j;
                while (j > 0 && (i == 0 || B[i][j] == 2)) { j--; run++; }
                if (i > 0 && run <= MAX_CHARGED_INS) { int w = wordOf[i - 1]; cost[w] += INS * 0.5f * run; hf[w] = j; if (hl[w] < 0) hl[w] = j0 - 1; }
            }
        }
        int progress = bi == 0 ? 0 : wordOf[bi - 1] + 1;
        // слово, на середине которого оборвался поток, ещё не прочитано
        boolean partial = !finalPass && bi > 0 && bi < n && wordOf[bi] == wordOf[bi - 1];
        State[] st = new State[W];
        boolean all = true;
        for (int w = 0; w < W; w++) {
            ReadingText.Word wd = text.words.get(w);
            int len = wd.letters.replace("-", "").length();
            if (w >= progress || (partial && w == progress - 1)) { st[w] = State.PENDING; all = false; continue; }
            float norm = cost[w] / Math.max(1, len);
            boolean ok = norm <= wordTolerance || (forgiveShortWords && len <= 2 && (!hasVowel(L, wd.letters) || hf[w] >= 0) && cost[w] <= DEL_END && w + 1 < progress);
            st[w] = ok ? State.OK : State.WRONG;
            if (!ok) all = false;
        }
        // последнее слово в потоке засчитываем, только когда оно целиком прочитано
        if (!finalPass && progress > 0 && progress <= W) {
            int last = progress - 1;
            if (st[last] == State.WRONG) st[last] = State.PENDING;
        }
        if (prev == null) return new Result(st, cost, progress, all && progress == W, hf, hl);
        // слияние с прошлой записью: зелёное остаётся зелёным, красное — красным, пока его не перечитали верно
        all = true;
        for (int w = 0; w < W; w++) {
            if (prev.states[w] == State.OK) { st[w] = State.OK; if (hf[w] >= 0 && cost[w] > 0) { hf[w] = hl[w] = -1; } continue; }
            if (prev.states[w] == State.WRONG && st[w] != State.OK) st[w] = State.WRONG;
            if (st[w] != State.OK) all = false;
        }
        return new Result(st, cost, Math.max(progress, prev.progressWords), all, hf, hl);
    }

    static boolean hasVowel(LanguageSpec L, String s) { for (int i = 0; i < s.length(); i++) if (L.isVowel(s.charAt(i))) return true; return false; }

    static boolean prevOk(Result prev, int w) { return prev != null && prev.states[w] == State.OK; }
}
