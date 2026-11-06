package dev.gubenkov.reading;

import static org.junit.Assert.*;
import org.junit.Test;

/** Живая запись сына 07.11 00:17 (L7-038): «апреле» читал правильно 5 раз — осталось красным. */
public class LiveSonTest {
    static final String TEXT = "В апре́ле по у́лице побежа́ли зво́нкие весе́нние ручьи́. Ти́мур и Ди́ма реши́ли устро́ить го́нку корабли́ков.";

    static ReadingMatcher.Result m(String hyp) { return new ReadingMatcher().match(new ReadingText(TEXT), hyp, true, null); }

    @Test public void repeatedCorrectWordAfterMisreadCounts() {
        ReadingMatcher.Result r = m("в аптеки по улице побежали аптек апрель апрель апрель апрель апрель не читается слово апрель апреле апреле не читается покажимн вотмори апреле вот вот тут красные в апреле а так вот весеннее весенние ручьи тимур и дима решили устроить гонку корабль обликов");
        StringBuilder sb = new StringBuilder(); for (ReadingMatcher.State s : r.states) sb.append(s.name().charAt(0));
        System.out.println("states " + sb);
        assertEquals(ReadingMatcher.State.OK, r.states[1]);
    }

    /** То же слово дальше по тексту, прочитанное там, не спасает ошибку раньше. */
    @Test public void sameWordLaterInTextDoesNotRescue() {
        String t = "Тимур пошёл гулять. Потом Тимур вернулся домой.";
        ReadingMatcher.Result r = new ReadingMatcher().match(new ReadingText(t), "тамара пошёл гулять потом тимур вернулся домой", true, null);
        assertEquals(ReadingMatcher.State.WRONG, r.states[0]);
        assertEquals(ReadingMatcher.State.OK, r.states[4]);
    }

    /** Неверно прочитанное слово без исправления остаётся красным. */
    @Test public void noCorrectionStaysRed() {
        ReadingMatcher.Result r = m("в аптеки по улице побежали звонкие весенние ручьи тимур и дима решили устроить гонку корабликов");
        assertEquals(ReadingMatcher.State.WRONG, r.states[1]);
    }
}
