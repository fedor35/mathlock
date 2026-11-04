package dev.gubenkov.reading;

import static org.junit.Assert.*;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class SpeakerIdTest {
    static final int D = 192;

    static float[] rand(Random r) { float[] v = new float[D]; for (int i = 0; i < D; i++) v[i] = (float) r.nextGaussian(); return SpeakerId.normalize(v); }

    /** Голос = центр + шум; cos(центр, запись) ≈ 1/√(1+σ²·…) — берём σ так, чтобы свой косинус был ≈ 0,7. */
    static float[] near(float[] c, Random r, float noise) {
        float[] n = rand(r), o = new float[D];
        for (int i = 0; i < D; i++) o[i] = c[i] + noise * n[i];
        return SpeakerId.normalize(o);
    }

    static SpeakerId.Profile prof(String id, float[] e) { SpeakerId.Profile p = new SpeakerId.Profile(id, id); SpeakerId.learn(p, e, false); return p; }

    @Test public void decisions() {
        Random r = new Random(1);
        float[] a = rand(r), b = rand(r);
        List<SpeakerId.Profile> ps = new ArrayList<>();
        // нет профилей: длинная запись — новый, короткая — не решаем, средняя — не определён
        assertEquals(SpeakerId.Kind.NEW, SpeakerId.decide(a, 3f, ps).kind);
        assertEquals(SpeakerId.Kind.SHORT, SpeakerId.decide(a, 0.5f, ps).kind);
        assertEquals(SpeakerId.Kind.UNSURE, SpeakerId.decide(a, 1.5f, ps).kind);
        ps.add(prof("p1", a)); ps.add(prof("p2", b));
        SpeakerId.Decision d = SpeakerId.decide(near(a, r, 0.8f), 3f, ps);
        assertEquals(SpeakerId.Kind.MATCH, d.kind); assertSame(ps.get(0), d.profile);
        assertEquals(SpeakerId.Kind.NEW, SpeakerId.decide(rand(r), 3f, ps).kind);   // чужой голос ≈ ортогонален
        // посередине между двумя профилями — не определён
        float[] mid = new float[D]; for (int i = 0; i < D; i++) mid[i] = a[i] + b[i];
        assertEquals(SpeakerId.Kind.UNSURE, SpeakerId.decide(SpeakerId.normalize(mid), 3f, ps).kind);
        assertTrue(d.describe(ps).contains("p1(p1)="));
    }

    @Test public void learningTracksSlowChange() {
        Random r = new Random(2);
        float[] a = rand(r), b = rand(r);
        SpeakerId.Profile p = prof("p", a);
        for (int i = 0; i < 30; i++) SpeakerId.learn(p, near(a, r, 0.8f), false);
        assertTrue(SpeakerId.dot(p.emb, a) > 0.95f);
        // голос меняется: профиль постепенно сдвигается
        float before = SpeakerId.dot(p.emb, b);
        for (int i = 0; i < 20; i++) SpeakerId.learn(p, b, false);
        assertTrue(SpeakerId.dot(p.emb, b) > before + 0.3f);
        assertEquals(51, p.n);
        // ручная правка весомее автоматической
        SpeakerId.Profile q1 = prof("q", a), q2 = prof("q", a);
        for (int i = 0; i < 40; i++) { SpeakerId.learn(q1, a, false); SpeakerId.learn(q2, a, false); }
        SpeakerId.learn(q1, b, false); SpeakerId.learn(q2, b, true);
        assertTrue(SpeakerId.dot(q2.emb, b) > SpeakerId.dot(q1.emb, b));
    }

    @Test public void mergeWeightsByCount() {
        Random r = new Random(3);
        float[] a = rand(r), b = rand(r);
        SpeakerId.Profile p = prof("p", a), q = prof("q", b);
        for (int i = 0; i < 8; i++) SpeakerId.learn(p, a, false);
        SpeakerId.merge(p, q);
        assertEquals(10, p.n);
        assertTrue(SpeakerId.dot(p.emb, a) > SpeakerId.dot(p.emb, b));
    }

    @Test public void kidProbabilityIsBounded() {
        float p = SpeakerId.kidProbability(rand(new Random(4)));
        assertTrue(p > 0 && p < 1);
        assertEquals(SpeakerId.CHILD, SpeakerId.kindOf(0.9f));
        assertEquals(SpeakerId.ADULT, SpeakerId.kindOf(0.1f));
        assertEquals(SpeakerId.UNKNOWN, SpeakerId.kindOf(0.5f));
    }
}
