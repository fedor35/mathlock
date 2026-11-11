package dev.gubenkov.reading;

import static org.junit.Assert.*;
import org.junit.Test;

/** «Красное — сразу перечитать»: после ошибки слова не зеленеют, повтор слова снимает остановку. */
public class StopOnRedTest {
    static final String TEXT = "В апре́ле по у́лице побежа́ли зво́нкие весе́нние ручьи́. Ти́мур и Ди́ма реши́ли устро́ить го́нку корабли́ков.";
    static final ReadingMatcher.State OK = ReadingMatcher.State.OK, WRONG = ReadingMatcher.State.WRONG, PENDING = ReadingMatcher.State.PENDING;
    final ReadingText t = new ReadingText(TEXT);
    final ReadingMatcher m = new ReadingMatcher();

    ReadingMatcher.Result shown(String hyp) { return ReadingMatcher.gate(m.match(t, hyp, false, null), null); }

    @Test public void afterRedNothingGreen() {
        ReadingMatcher.Result r = shown("в аптеки по улице");
        assertEquals(OK, r.states[0]);
        assertEquals(WRONG, r.states[1]);
        for (int w = 2; w < r.states.length; w++) assertEquals("после красного не зеленеет: " + w, PENDING, r.states[w]);
    }

    @Test public void rereadLiftsStop() {
        ReadingMatcher.Result r = shown("в аптеки по апреле по улице побежали");
        assertEquals(OK, r.states[1]);
        assertEquals(OK, r.states[3]);
    }

    /** Живая запись сына: ошибка, разговор, повторы «апреле», дальше текст — «апреле» засчитано; пропущенное «звонкие»
     *  красное (до 0.4.6 оно собиралось по буквам из разговора и зеленело). */
    @Test public void sonLiveStream() {
        String full = "в аптеки по улице побежали аптек апрель апрель апрель апрель апрель не читается слово апрель апреле апреле не читается покажимн вотмори апреле вот вот тут красные в апреле а так вот весеннее весенние ручьи тимур и дима решили устроить гонку корабль обликов";
        String[] w = full.split(" ");
        ReadingMatcher.Result r = shown(full);
        assertEquals(OK, r.states[1]);
        assertEquals("красное — пропущенное «звонкие»", 5, ReadingMatcher.firstWrong(r, null));
    }

    @Test public void releasedRedLetsReadingGoOn() {
        ReadingMatcher.Result raw = m.match(t, "в аптеки по улице", false, null);
        boolean[] rel = new boolean[t.words.size()]; rel[1] = true;
        ReadingMatcher.Result r = ReadingMatcher.gate(raw, rel);
        assertEquals(WRONG, r.states[1]);
        assertEquals(OK, r.states[2]);
    }
}
