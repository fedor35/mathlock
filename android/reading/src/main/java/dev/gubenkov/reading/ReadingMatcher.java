package dev.gubenkov.reading;

/**
 * Сверка распознанного (жадный CTC, буквы с пробелами) с текстом. Пробелы игнорируются:
 * при чтении по слогам ASR выдаёт «ма ма мы ла». Выравнивание буквенное, с дешёвыми вставками
 * (повторы слогов, «э-э») и бесплатным непрочитанным хвостом текста (потоковая подсветка).
 */
public final class ReadingMatcher {
    /**
     * Пропуск конечной «л» после гласной: «аул» → «ау», «подул» → «поду», «саксаул», «звенел», «спал» (сын, 07–10.11).
     * Твёрдая «л» в конце после гласной звучит почти как «у», и жадный декодер её теряет: на записях сына конечная
     * «л» пропала в 8% слов (после «е» — 16%), в середине слова — тоже 8%; по звуку (WordScore) её нет (−3,6),
     * так что это не проглатывание, а слабый звук. Цена пропуска 0,5 — трёхбуквенное слово ещё засчитывается.
     */
    public static final float DEL_SOFT_L = 0.5f;
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
    /**
     * Строгие окончания: «жил» → «жили», «маленький» → «маленькие», «звёзды» → «звёзд» — ошибка, даже если по доле букв
     * слово проходит (дети угадывают окончания; по живым записям 05.11 Вероника меняла окончание в ~2–4% слов).
     * Не ошибка: лишние буквы — начало следующего слова (запинка «Тимур по… пошёл») или повтор хвоста; пропуск «й».
     */
    public boolean strictEndings = false;
    /**
     * Второе мнение по звуку для красных слов (null — только буквы). Ставит вызывающий после каждого decode():
     * оценка опирается на кадры букв этой гипотезы.
     */
    public WordScore acoustic;
    /**
     * Поток молчит: слово, у которого не хватает только последней буквы, судим сразу. Без этого «поду» вместо
     * «подул» (сын, 10.11) висит «ещё читается» — ребёнок повторяет слово и не видит ни зелёного, ни красного.
     */
    public boolean closeLast = false;

    public enum State { PENDING, OK, WRONG }

    public static final class Result {
        public final State[] states;
        public final float[] wordCost;
        public final int progressWords; // сколько слов от начала «пройдено» (дальше — непрочитанное)
        public final boolean complete;
        /** Индексы букв гипотезы (без пробелов), сопоставленных слову: [первая, последняя] или -1. */
        public final int[] hypFirst, hypLast;
        /**
         * Засчитанное слово с подозрением на неверное окончание («жил» → «жили») — мягкая подсказка, в точность не идёт:
         * безударные окончания распознавание путает и на правильной озвучке (+0,4% слов), см. {@link #strictEndings}.
         */
        public final boolean[] endingHint;
        Result(State[] s, float[] c, int p, boolean done, int[] hf, int[] hl, boolean[] eh) { states = s; wordCost = c; progressWords = p; complete = done; hypFirst = hf; hypLast = hl; endingHint = eh; }
    }

    static float sub(LanguageSpec L, char a, char b) { return a == b ? 0 : L.similar(a, b) < 1f ? SUB_SIMILAR : SUB; }

    public Result match(ReadingText text, String hypothesis, boolean finalPass) { return match(text, hypothesis, finalPass, null); }

    /** Длинная вставка — возврат назад и перечитывание или «э-э-э»: слово перед ней не штрафуется. */
    static final int MAX_CHARGED_INS = 3;
    /**
     * Надбавка за начало вставки: из выравниваний одной цены берём то, где лишние буквы идут одним куском.
     * Без неё «ещё в темно в темноте» (сын, 08.11) делилось на «ещ[евт]е[мно]» — два коротких куска внутри «ещё»,
     * оба штрафуются, «ещё» краснело, и чтение вставало.
     */
    static final float INS_OPEN = 0.001f;
    /** Сверх скольких лишних букв (и половины длины слова) кусок речи слова считается растянутым. */
    static final int STRETCH_MIN = Integer.getInteger("stretch.min", 4);
    /** Сколько букв речи после короткого красного слова искать его повтор отдельным словом. */
    static final int LATER_WINDOW_SHORT = 60;
    /** Сколько букв речи после слова просматривать в поисках его исправленного прочтения. */
    static final int LATER_WINDOW = 150;

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
        // конечная «й» в быстрой речи глотается («дымко[й]», «ровно[й]» у взрослого, 04.11) — обычная цена пропуска
        for (int k = 0; k < n; k++) del[k] = prevOk(prev, wordOf[k]) ? 0f : softEnd(L, t, wordOf, n, k) ? DEL_SOFT_L : (k + 1 == n || wordOf[k + 1] != wordOf[k]) && t.charAt(k) != 'й' ? DEL_END : DEL;
        StringBuilder hb = new StringBuilder();
        // gap[j]: перед буквой j гипотезы был пробел (CTC отделил слово) — вставка после пробела не «окончание», а заминка/следующее слово
        boolean[] gap = new boolean[hypothesis.length() + 1]; boolean sp = false;
        for (char c : hypothesis.toCharArray()) { char x = L.normalize(c); if (x != 0) { gap[hb.length()] = sp; hb.append(x); sp = false; } else if (c == ' ') sp = true; }
        String h = hb.toString();
        int m = h.length();
        // Выравнивание t[0..i) с h[0..j) с отдельной ценой начала вставки (Gotoh): X — последний шаг буква текста
        // (совпадение/замена или пропуск), Y — лишняя буква речи. Строки X/Y храним две, указатели — все:
        // B: бит 0 — X пришёл пропуском (иначе по диагонали), бит 1 — X пришёл из Y, бит 2 — Y продолжает вставку.
        final float INF = Float.MAX_VALUE / 4;
        float[] xp = new float[m + 1], yp = new float[m + 1], xc = new float[m + 1], yc = new float[m + 1];
        float[] lastCol = new float[n + 1]; boolean[] lastY = new boolean[n + 1];
        byte[][] B = new byte[n + 1][m + 1];
        xp[0] = 0; yp[0] = INF;
        for (int j = 1; j <= m; j++) { xp[j] = INF; yp[j] = j == 1 ? INS + INS_OPEN : yp[j - 1] + INS; B[0][j] = (byte) (j > 1 ? 4 : 0); }
        lastCol[0] = Math.min(xp[m], yp[m]); lastY[0] = yp[m] < xp[m];
        for (int i = 1; i <= n; i++) {
            xc[0] = xp[0] + del[i - 1]; yc[0] = INF; B[i][0] = 1;
            char tc = t.charAt(i - 1); float dc = del[i - 1];
            for (int j = 1; j <= m; j++) {
                boolean dY = yp[j - 1] < xp[j - 1], lY = yp[j] < xp[j];
                float d = (dY ? yp[j - 1] : xp[j - 1]) + sub(L, tc, h.charAt(j - 1)), dl = (lY ? yp[j] : xp[j]) + dc;
                byte b;
                if (d <= dl) { xc[j] = d; b = (byte) (dY ? 2 : 0); } else { xc[j] = dl; b = (byte) (1 | (lY ? 2 : 0)); }
                float open = xc[j - 1] + INS + INS_OPEN, ext = yc[j - 1] + INS;
                if (ext <= open) { yc[j] = ext; b |= 4; } else yc[j] = open;
                B[i][j] = b;
            }
            lastCol[i] = Math.min(xc[m], yc[m]); lastY[i] = yc[m] < xc[m];
            float[] q = xp; xp = xc; xc = q; q = yp; yp = yc; yc = q;
        }
        // конец гипотезы может прийтись на любое место текста (хвост ещё не прочитан)
        int bi = n;
        if (!finalPass) {
            float best = Float.MAX_VALUE;
            for (int i = 0; i <= n; i++) { float c = lastCol[i] + 0.02f * (n - i); if (c < best) { best = c; bi = i; } }
        }
        // слово, на середине которого оборвался поток, ещё не прочитано — если поток не молчит (closeLast)
        boolean partial = !finalPass && bi > 0 && bi < n && wordOf[bi] == wordOf[bi - 1], closedLast = false;
        // молчание посреди слова — обычно чтение по слогам («при… цесса»), закрываем, только если не хватает одной буквы
        if (partial && closeLast && (bi + 1 == n || wordOf[bi + 1] != wordOf[bi]) && (wordLen(wordOf, n, bi) >= 4 || softEnd(L, t, wordOf, n, bi))) {
            bi++; partial = false; closedLast = true;
        }
        float[] cost = new float[W];
        boolean[] endErr = new boolean[W];
        int[] hf = new int[W], hl = new int[W];
        java.util.Arrays.fill(hf, -1); java.util.Arrays.fill(hl, -1);
        int[] own = new int[m]; java.util.Arrays.fill(own, -1);   // буква речи → слово текста (только совпавшие по диагонали)
        int i = bi, j = m; boolean inY = lastY[bi];
        while (i > 0 || j > 0) {
            byte b = B[i][j];
            if (!inY && i > 0 && j > 0 && (b & 1) == 0) {
                inY = (b & 2) != 0;
                int w = wordOf[i - 1]; cost[w] += sub(L, t.charAt(i - 1), h.charAt(j - 1));
                // замена в окончании; похожие гласные (о/а, е/и, е/я, и/ы) — аканье и иканье, не ошибка: «лото» звучит «лота»
                if (t.charAt(i - 1) != h.charAt(j - 1) && L.similar(t.charAt(i - 1), h.charAt(j - 1)) >= 1f && inEnding(wordOf, n, i - 1)
                        && endingLetter(L, t.charAt(i - 1)) && endingLetter(L, h.charAt(j - 1))) endErr[w] = true;
                own[j - 1] = w; hf[w] = j - 1; if (hl[w] < 0) hl[w] = j - 1; i--; j--;
            }
            else if (!inY) {
                inY = (b & 2) != 0;
                cost[wordOf[i - 1]] += del[i - 1];
                if (isLast(wordOf, n, i - 1) && L.isVowel(t.charAt(i - 1)) && del[i - 1] > 0 && wordLen(wordOf, n, i - 1) >= 3) endErr[wordOf[i - 1]] = true;
                i--;
            }
            else { // вставку относим к слову перед ней; длинную (перечитывание, «э-э-э») не штрафуем и в слово не включаем
                int run = 0, j0 = j;
                while (inY) { inY = (B[i][j] & 4) != 0; j--; run++; }
                if (i > 0 && run <= MAX_CHARGED_INS) {
                    int w = wordOf[i - 1]; cost[w] += INS * 0.5f * run; hf[w] = j; if (hl[w] < 0) hl[w] = j0 - 1;
                    // вставка сразу за последней буквой слова: «жил|и», «звёзд|ы» — окончание; «Тимур|по» (начало следующего) и «похожа|а» (повтор) — нет
                    if (run <= 2 && isLast(wordOf, n, i - 1) && wordLen(wordOf, n, i - 1) >= 3) {
                        String ins = h.substring(j, j0);
                        boolean nextStart = i < n && t.startsWith(ins, i);
                        int ws = i - 1; while (ws > 0 && wordOf[ws - 1] == wordOf[i - 1]) ws--;
                        boolean repeat = t.substring(ws, i).endsWith(ins);
                        boolean letters = true; for (char c : ins.toCharArray()) letters &= endingLetter(L, c);
                        if (!nextStart && !repeat && letters && !gap[j]) endErr[w] = true;
                    }
                }
            }
        }
        int progress = bi == 0 ? 0 : wordOf[bi - 1] + 1;
        State[] st = new State[W];
        boolean all = true;
        for (int w = 0; w < W; w++) {
            ReadingText.Word wd = text.words.get(w);
            int len = wd.letters.replace("-", "").length();
            if (w >= progress || (partial && w == progress - 1)) { st[w] = State.PENDING; all = false; continue; }
            float norm = cost[w] / Math.max(1, len);
            boolean ok = (norm <= wordTolerance && !(strictEndings && endErr[w])) || (forgiveShortWords && len <= 2 && (!hasVowel(L, wd.letters) || hf[w] >= 0) && cost[w] <= DEL_END && w + 1 < progress);
            st[w] = ok ? State.OK : State.WRONG;
            if (!ok) all = false;
        }
        // Слово, набранное по буквам из длинного куска речи («звонкие» из «…апрель апреле не читается покажи… весеннее»,
        // сын 07.11; «казахстана» из звука игры): длинные вставки внутри слова бесплатны, и буквы находятся вразброс.
        // Растянутое слово засчитываем, только если внутри его куска оно сказано подряд (повторы «цеплялись… цеплялися» —
        // да). На эталонной озвучке растянутых засчитанных слов нет вовсе.
        boolean[] none = new boolean[m];
        for (int w = 0; w < W; w++) {
            if (st[w] != State.OK || hf[w] < 0 || prevOk(prev, w)) continue;
            String wl = text.words.get(w).letters.replace("-", "");
            if (wl.length() < 3 || hl[w] - hf[w] + 1 - wl.length() <= Math.max(STRETCH_MIN, wl.length() / 2)) continue;
            if (laterReading(L, wl, h.substring(0, hl[w] + 1), hf[w], none) / wl.length() > wordTolerance) { st[w] = State.WRONG; all = false; }
        }
        // по звуку: буквы слова в кадрах есть, жадный путь их потерял
        if (acoustic != null)
            for (int w = 0; w < W; w++) {
                // только «по буквам не то»: строгие окончания звук не отменяет — безударный слог он не различит
                if (st[w] != State.WRONG || hf[w] < 0 || cost[w] / Math.max(1, text.words.get(w).letters.replace("-", "").length()) <= wordTolerance) continue;
                float s = acoustic.score(text.words.get(w).letters.replace("-", ""), hf[w], hl[w]);
                if (s >= WordScore.ACCEPT) st[w] = State.OK;
            }
        // Исправление после ушедшего вперёд чтения: «в аптеки по улице побежали … апреле апреле» (сын, 07.11) —
        // ребёнок дочитал дальше и вернулся к красному слову. Монотонное выравнивание отдаёт слову только
        // первую попытку, поэтому ищем верное прочтение слова дальше в гипотезе. Слова от 4 букв: короткие
        // («и», «на») встречаются в речи сами по себе и засчитывались бы зря.
        boolean[] taken = new boolean[m];   // буквы речи, уже отданные другим словам текста (то же слово дальше в тексте)
        for (int k = 0; k < m; k++) taken[k] = own[k] >= 0 && st[own[k]] == State.OK;
        // Только одиночное красное слово среди прочитанных и повтор неподалёку: иначе на живых записях
        // с видео на фоне засчитывались слова из чужой речи («ведро», «жарко» в летсплее).
        State[] orig = st.clone();
        for (int w = 0; w < W; w++) {
            if (st[w] != State.WRONG) continue;
            if ((w > 0 && orig[w - 1] != State.OK) || (w + 1 < W && w + 1 < progress && orig[w + 1] != State.OK)) continue;
            String wl = text.words.get(w).letters.replace("-", "");
            int from = hl[w] >= 0 ? hl[w] + 1 : w > 0 && hl[w - 1] >= 0 ? hl[w - 1] + 1 : 0;
            if (wl.length() < 4) {
                // короткое слово засчитываем, только если его сказали отдельным словом: «у… у у», «ещё ещё», «ток ток»
                // (сын, 08–09.11: после «Стоп!» повторял слово по 5–7 раз, а оно оставалось красным)
                if (laterToken(L, wl, h, gap, from, Math.min(m, from + LATER_WINDOW_SHORT), taken, wordTolerance)) st[w] = State.OK;
                continue;
            }
            int to = Math.min(m, from + LATER_WINDOW + wl.length());
            float best = laterReading(L, wl, h.substring(0, to), from, taken);
            if (best / wl.length() <= wordTolerance) { st[w] = State.OK; cost[w] = best; }
        }
        all = true;
        for (int w = 0; w < W; w++) if (st[w] != State.OK) all = false;
        // последнее слово в потоке засчитываем, только когда оно целиком прочитано (или поток молчит)
        if (!finalPass && !(closeLast && closedLast) && progress > 0 && progress <= W) {
            int last = progress - 1;
            if (st[last] == State.WRONG) st[last] = State.PENDING;
        }
        boolean[] hint = new boolean[W];
        for (int w = 0; w < W; w++) hint[w] = endErr[w] && st[w] == State.OK && !prevOk(prev, w);
        if (prev == null) return new Result(st, cost, progress, all && progress == W, hf, hl, hint);
        // слияние с прошлой записью: зелёное остаётся зелёным, красное — красным, пока его не перечитали верно
        all = true;
        for (int w = 0; w < W; w++) {
            if (prev.states[w] == State.OK) { st[w] = State.OK; if (hf[w] >= 0 && cost[w] > 0) { hf[w] = hl[w] = -1; } continue; }
            if (prev.states[w] == State.WRONG && st[w] != State.OK) st[w] = State.WRONG;
            if (st[w] != State.OK) all = false;
        }
        for (int w = 0; w < W; w++) if (st[w] != State.OK) hint[w] = false;
        return new Result(st, cost, Math.max(progress, prev.progressWords), all, hf, hl, hint);
    }

    /** Лучшая цена прочтения слова {@code w} где-то в {@code h[from..)} (полуглобальное выравнивание: начало и конец свободны). */
    static float laterReading(LanguageSpec L, String w, String h, int from, boolean[] taken) {
        int n = w.length(), m = h.length() - from;
        if (m < n - 1) return Float.MAX_VALUE;
        float[] prevRow = new float[m + 1], row = new float[m + 1];   // по буквам слова; столбец — позиция в h, старт бесплатно
        for (int i = 1; i <= n; i++) {
            row[0] = i * DEL;
            for (int j = 1; j <= m; j++) {
                boolean busy = taken[from + j - 1];
                float d = busy ? Float.MAX_VALUE / 4 : prevRow[j - 1] + sub(L, w.charAt(i - 1), h.charAt(from + j - 1));
                row[j] = Math.min(d, Math.min(prevRow[j] + DEL, busy ? Float.MAX_VALUE / 4 : row[j - 1] + INS));
            }
            float[] t = prevRow; prevRow = row; row = t;
        }
        float best = Float.MAX_VALUE;
        for (int j = 0; j <= m; j++) best = Math.min(best, prevRow[j]);
        return best;
    }

    /** Отдельное слово речи (между пробелами CTC) в h[from..to), совпадающее с {@code w}, из незанятых букв. */
    static boolean laterToken(LanguageSpec L, String w, String h, boolean[] gap, int from, int to, boolean[] taken, float tol) {
        int len = w.length();
        for (int k = from; k + len <= to; k++) {
            if (k > 0 && !gap[k]) continue;
            int e = k + 1; while (e < h.length() && !gap[e]) e++;
            if (e - k != len) continue;
            float c = 0; boolean free = true;
            for (int q = 0; q < len; q++) { c += sub(L, w.charAt(q), h.charAt(k + q)); free &= !taken[k + q]; }
            if (free && c / len <= tol) return true;
        }
        return false;
    }

    // ---------- «Красное — сразу перечитать» (Фёдор, 07.11) ----------
    // Раньше ошибка краснела, когда ребёнок уже читал дальше, а следующие слова зеленели — непонятно,
    // перечитывать сейчас или после текста. Теперь: после первого красного слова зелёное не показывается,
    // пока его не перечитали (повтор засчитывает laterReading) или пока его не отпустили (см. ReadingScreen).

    /** Первое красное слово, на котором стоим (не отпущенное), или -1. */
    public static int firstWrong(Result r, boolean[] released) {
        for (int w = 0; w < r.states.length; w++)
            if (r.states[w] == State.WRONG && (released == null || !released[w])) return w;
        return -1;
    }

    /** Слова после первого неотпущенного красного — снова «не прочитаны». */
    public static Result gate(Result r, boolean[] released) {
        int f = firstWrong(r, released);
        if (f < 0) return r;
        State[] st = r.states.clone(); boolean[] eh = r.endingHint.clone();
        int[] hf = r.hypFirst.clone(), hl = r.hypLast.clone();
        for (int w = f + 1; w < st.length; w++) { st[w] = State.PENDING; eh[w] = false; hf[w] = hl[w] = -1; }
        return new Result(st, r.wordCost, Math.min(r.progressWords, f + 1), false, hf, hl, eh);
    }

    /** Буква окончания: гласная, «й» или «м»/«х»/«в» (-ом, -ых, -ов) — согласные внутри основы так не считаем. */
    static boolean endingLetter(LanguageSpec L, char c) { return L.isVowel(c) || c == 'й' || c == 'м' || c == 'х' || c == 'в'; }
    static boolean isLast(int[] wordOf, int n, int k) { return k + 1 == n || wordOf[k + 1] != wordOf[k]; }
    static int wordLen(int[] wordOf, int n, int k) { int a = k, b = k; while (a > 0 && wordOf[a - 1] == wordOf[k]) a--; while (b + 1 < n && wordOf[b + 1] == wordOf[k]) b++; return b - a + 1; }
    /** Позиция k — среди двух последних букв слова длиной от 3. */
    static boolean inEnding(int[] wordOf, int n, int k) { int len = wordLen(wordOf, n, k); return len >= 3 && (isLast(wordOf, n, k) || isLast(wordOf, n, k + 1)); }

    /** Буква k — конечная «л» после гласной в слове от 3 букв. */
    static boolean softEnd(LanguageSpec L, String t, int[] wordOf, int n, int k) {
        return t.charAt(k) == 'л' && isLast(wordOf, n, k) && k > 0 && wordOf[k - 1] == wordOf[k] && L.isVowel(t.charAt(k - 1)) && wordLen(wordOf, n, k) >= 3;
    }

    static boolean hasVowel(LanguageSpec L, String s) { for (int i = 0; i < s.length(); i++) if (L.isVowel(s.charAt(i))) return true; return false; }

    static boolean prevOk(Result prev, int w) { return prev != null && prev.states[w] == State.OK; }
}
