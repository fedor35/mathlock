package dev.gubenkov.reading;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/** Застревания сына 08–10.11 на «Красное — сразу перечитать» (записи в ml/live_eval, в репозиторий не входят). */
public class LiveFixesTest {
    static final ReadingMatcher.State OK = ReadingMatcher.State.OK, WRONG = ReadingMatcher.State.WRONG, PENDING = ReadingMatcher.State.PENDING;

    static ReadingMatcher.Result shown(String text, String hyp) {
        return ReadingMatcher.gate(new ReadingMatcher().match(new ReadingText(text), hyp, false, null), null);
    }

    /** «ещё в темно в темноте»: исправление сразу за словом — одна вставка, «ещё» не краснеет. */
    @Test public void selfCorrectionRightAfterWordIsOneInsertion() {
        ReadingMatcher.Result r = shown("Она́ прихо́дит в шко́лу ра́ньше всех, ещё в темноте́. Тётя Ро́за ва́рит ка́шу.",
                "она приходит в школу раньше всех еще в темно в темноте тетя роза");
        for (int w = 0; w <= 9; w++) assertEquals("слово " + w, OK, r.states[w]);
    }

    /** «соединённые стеклянные»: пропущенное слово дочитано сразу за следующим — засчитано. */
    @Test public void skippedWordReadRightAfterNext() {
        ReadingMatcher.Result r = shown("Э́то две стекля́нные ко́лбы, соединённые у́зким го́рлышком.",
                "это древние две скрепленные колбы соединенныестеклянные колбы соединенные узким");
        assertEquals(OK, r.states[2]);
        assertEquals(OK, r.states[4]);
    }

    /** Короткое красное слово, повторённое отдельно, снимает остановку: «у», «ещё», «ток», «что». */
    @Test public void shortRedWordRereadAlone() {
        ReadingMatcher.Result r = shown("У ба́бушки на по́лке стоя́т часы́.", "бабушки у у");
        assertEquals(OK, r.states[0]);
        assertEquals(OK, r.states[1]);
        r = shown("Всё э́то ток идёт по прово́дам.", "все это то идет ток ток");
        assertEquals(OK, r.states[2]);
    }

    /** Короткое слово не засчитывается куском другого слова или чужим словом. */
    @Test public void shortWordNotFromInsideOtherWord() {
        ReadingMatcher.Result r = shown("Всё э́то ток идёт по прово́дам.", "все это то идет по проводам потоки");
        assertEquals(WRONG, r.states[2]);
    }

    /** «кула» вместо «кулак» и молчание: слово судим (красное), а не «ещё читается». («поду» вместо «подул» с 0.4.5 засчитывается — слабая «л».) */
    @Test public void quietStreamJudgesWordMissingLastLetter() {
        ReadingText t = new ReadingText("Он положи́л моне́тку в кула́к, поду́л на него́.");
        ReadingMatcher m = new ReadingMatcher();
        ReadingMatcher.Result r = m.match(t, "он положил монетку в кула", false, null);
        assertEquals(PENDING, r.states[4]);
        m.closeLast = true;
        r = m.match(t, "он положил монетку в кула", false, null);
        assertEquals(WRONG, r.states[4]);
        // по слогам «ку… ла…» (не хватает двух букв и больше) — ждём дальше
        r = m.match(t, "он положил монетку в ку", false, null);
        assertEquals(PENDING, r.states[4]);
    }

    /** Буква, которую жадный путь потерял, но вероятность у неё почти как у лучшего: слово засчитано по звуку. */
    @Test public void acousticRescuesLostLetter() {
        String[] V = CtcModel.GIGAAM_VOCAB; int blank = V.length;
        // «т о к»: на кадре «т» лучший — blank (−0,1), «т» рядом (−0,3); жадный путь даёт «ок»
        String path = "#т#о#к#";
        float[][] lp = new float[path.length() + 2][V.length + 1];
        List<Integer> lf = new ArrayList<>();
        for (int t = 0; t < lp.length; t++) {
            java.util.Arrays.fill(lp[t], -9f);
            char c = t < path.length() ? path.charAt(t) : '#';
            if (c == '#') lp[t][blank] = -0.05f;
            else if (c == 'т') { lp[t][blank] = -0.1f; lp[t][idx(V, "т")] = -0.3f; }
            else { lp[t][idx(V, String.valueOf(c))] = -0.05f; lf.add(t); }
        }
        WordScore ws = new WordScore(lp, lf, V, blank);
        float good = ws.score("ток", 0, 1), bad = ws.score("сок", 0, 1);
        assertTrue("ток " + good, good >= WordScore.ACCEPT);
        assertTrue("сок " + bad, bad < WordScore.ACCEPT);
        ReadingMatcher m = new ReadingMatcher(); m.acoustic = ws;
        ReadingMatcher.Result r = m.match(new ReadingText("Ток."), "ок", true, null);
        assertEquals(OK, r.states[0]);
    }

    static int idx(String[] v, String u) { for (int i = 0; i < v.length; i++) if (v[i].equals(u)) return i; return -1; }

    /** Конечная «л» после гласной слабая — её пропуск не красит слово: «аул» → «ау», «подул» → «поду». */
    @Test public void weakFinalL() {
        ReadingMatcher.Result r = shown("Мы прие́хали в аул. Там пасу́тся ло́шади.", "мы приехали в ау там пасутся");
        assertEquals(OK, r.states[3]);
        r = shown("Ве́тер поду́л, и лодка поплыла.", "ветер поду и лодка");
        assertEquals(OK, r.states[1]);
        // другая конечная согласная так не прощается: «ток» → «то»
        r = shown("Всё э́то ток идёт по прово́дам.", "все это то идет по");
        assertNotEquals(OK, r.states[2]);
    }

    /** Поток замолчал на «ау» — трёхбуквенное слово без конечной «л» судится сразу, не висит «ещё читается». */
    @Test public void weakFinalLClosedOnSilence() {
        ReadingMatcher m = new ReadingMatcher(); m.closeLast = true;
        ReadingMatcher.Result r = m.match(new ReadingText("Мы прие́хали в аул. Там пасу́тся ло́шади."), "мы приехали в ау", false, null);
        assertEquals(OK, r.states[3]);
    }

    /** Слово не собирается по буквам из разговора между попытками: «звонкие» из «…апрель апреле не читается покажи…» (сын, 07.11). */
    @Test public void wordNotScrapedFromChatter() {
        ReadingMatcher.Result r = new ReadingMatcher().match(new ReadingText("В апре́ле по у́лице побежа́ли зво́нкие весе́нние ручьи́."),
                "в апреле по улице побежали апрель апреле не читается покажи вот мори апреле вот вот тут красные в апреле а так вот весенние ручьи", true);
        assertEquals(OK, r.states[4]);
        assertEquals(WRONG, r.states[5]);
        assertEquals(OK, r.states[6]);
    }

    /** Растянутое повторами слово, сказанное подряд, засчитывается: «цеплялись… цеплялися». */
    @Test public void stretchedByRepeatsStillOk() {
        ReadingMatcher.Result r = new ReadingMatcher().match(new ReadingText("Семена́ мо́гут цепля́ться за шерсть."),
                "семена могут цеплялись ся цеплялися за шерсть", true);
        assertEquals(OK, r.states[2]);
    }
}
