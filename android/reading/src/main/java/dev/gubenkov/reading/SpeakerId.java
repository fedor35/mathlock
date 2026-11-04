package dev.gubenkov.reading;

import java.util.List;
import java.util.Locale;

/**
 * Тихое опознание чтеца по голосовому отпечатку (эмбеддинг CAM++, нормированный, 192 числа).
 * Ничего не спрашивает: после каждого чтения решает одно из
 * <ul>
 *   <li>{@link Kind#MATCH} — уверенно похож на профиль: попытка в его журнал, профиль дообучается;</li>
 *   <li>{@link Kind#NEW} — уверенно не похож ни на кого (и речи достаточно): заводится новый профиль «Чтец N»;</li>
 *   <li>{@link Kind#UNSURE} — между профилями или близко к порогу: попытка «не определена», в уровень не идёт;</li>
 *   <li>{@link Kind#SHORT} — речи слишком мало для решения (тоже «не определена»).</li>
 * </ul>
 * Пороги подобраны на speechocean762 (80 детей 6–10 лет, 128 взрослых) и записях Фёдора (ml/speaker_tune.py):
 * профиль из 3 попыток — свой верно 98%, к чужому 0%, ложный «новый» 0,1%, остальное «не определён».
 */
public final class SpeakerId {
    private SpeakerId() {}

    /** Косинус к лучшему профилю не ниже — кандидат в «свой». */
    public static final float ACCEPT = 0.50f;
    /** И отрыв от второго профиля не меньше. */
    public static final float MARGIN = 0.12f;
    /** Лучший косинус ниже — голос незнакомый, заводим новый профиль. */
    public static final float NEW_BELOW = 0.30f;
    /** Меньше речи — не решаем (короткие клипы Фёдора 0,8–1,4 с ещё опознаются, 0,5 с — уже шум). */
    public static final float MIN_SPEECH_S = 0.8f;
    /** Новый профиль — только по записи с речью не короче. */
    public static final float MIN_SPEECH_NEW_S = 2.0f;
    /** Профиль — среднее первых 1/ALPHA_AUTO попыток, дальше скользящее среднее с этим весом (голос ребёнка меняется). */
    public static final float ALPHA_AUTO = 0.05f;
    /** Вес ручной переразметки в родительском экране. */
    public static final float ALPHA_MANUAL = 0.2f;
    /** Ребёнок/взрослый по голосу: P(ребёнок) выше — ребёнок, ниже ADULT_P — взрослый, между — неизвестно. */
    // живые голоса 05.11: ребёнок (Вероника) 0,675 по профилю, взрослые 0,17–0,20 — порог ребёнка 0,75 был выше живого ребёнка
    public static final float KID_P = 0.60f, ADULT_P = 0.25f;

    public enum Kind { MATCH, NEW, UNSURE, SHORT }

    public static final String CHILD = "child", ADULT = "adult", UNKNOWN = "unknown";

    public static final class Profile {
        public String id, name, avatar, kind = UNKNOWN;
        public int color, n, startLevel;
        public float[] emb;
        public long created;
        public Profile(String id, String name) { this.id = id; this.name = name; }
    }

    public static final class Decision {
        public final Kind kind; public final Profile profile; public final float[] cos; public final float best, second, speechS;
        Decision(Kind k, Profile p, float[] cos, float best, float second, float speechS) {
            kind = k; profile = p; this.cos = cos; this.best = best; this.second = second; this.speechS = speechS;
        }
        /** Строка для logcat: косинусы ко всем профилям и решение. */
        public String describe(List<Profile> ps) {
            StringBuilder s = new StringBuilder(String.format(Locale.ROOT, "речь %.1f с:", speechS));
            for (int i = 0; i < ps.size(); i++) s.append(String.format(Locale.ROOT, " %s(%s)=%.3f", ps.get(i).id, ps.get(i).name, cos[i]));
            s.append(" → ").append(kind);
            if (profile != null) s.append(' ').append(profile.id);
            return s.toString();
        }
    }

    public static float dot(float[] a, float[] b) { float s = 0; for (int i = 0; i < a.length; i++) s += a[i] * b[i]; return s; }

    public static float[] normalize(float[] v) {
        double s = 0; for (float x : v) s += x * x; float k = (float) (1 / Math.sqrt(Math.max(s, 1e-12)));
        float[] o = new float[v.length]; for (int i = 0; i < v.length; i++) o[i] = v[i] * k; return o;
    }

    public static Decision decide(float[] emb, float speechS, List<Profile> profiles) {
        int n = profiles.size(); float[] cos = new float[n];
        int bi = -1; float best = -1, second = -1;
        for (int i = 0; i < n; i++) {
            cos[i] = dot(emb, profiles.get(i).emb);
            if (cos[i] > best) { second = best; best = cos[i]; bi = i; } else if (cos[i] > second) second = cos[i];
        }
        Kind k;
        if (speechS < MIN_SPEECH_S) k = Kind.SHORT;
        else if (bi >= 0 && best >= ACCEPT && best - second >= MARGIN) k = Kind.MATCH;
        else if (best < NEW_BELOW) k = speechS >= MIN_SPEECH_NEW_S ? Kind.NEW : Kind.UNSURE;
        else k = Kind.UNSURE;
        return new Decision(k, k == Kind.MATCH ? profiles.get(bi) : null, cos, best, second, speechS);
    }

    /** Дообучение профиля: накопленное среднее, после 1/α попыток — скользящее с весом α (ручная правка весомее). */
    public static void learn(Profile p, float[] emb, boolean manual) {
        p.n++;
        if (p.emb == null) { p.emb = normalize(emb); return; }
        float a = Math.max(1f / p.n, manual ? ALPHA_MANUAL : ALPHA_AUTO);
        float[] o = new float[emb.length];
        for (int i = 0; i < o.length; i++) o[i] = (1 - a) * p.emb[i] + a * emb[i];
        p.emb = normalize(o);
    }

    /** Объединение двух профилей одного человека: среднее с весами по числу образцов. */
    public static void merge(Profile into, Profile from) {
        float wa = Math.max(1, into.n), wb = Math.max(1, from.n);
        float[] o = new float[into.emb.length];
        for (int i = 0; i < o.length; i++) o[i] = wa * into.emb[i] + wb * from.emb[i];
        into.emb = normalize(o); into.n += from.n;
    }

    /** P(ребёнок) по эмбеддингу: логистическая регрессия на speechocean762 (дети 6–10 / взрослые ≥ 19),
     *  89% по одной фразе на отложенных людях. На русской речи с телефона не проверена — только грубая оценка. */
    public static float kidProbability(float[] emb) {
        double z = KID_B; for (int i = 0; i < KID_W.length; i++) z += KID_W[i] * emb[i];
        return (float) (1 / (1 + Math.exp(-z)));
    }

    public static String kindOf(float kidP) { return kidP >= KID_P ? CHILD : kidP <= ADULT_P ? ADULT : UNKNOWN; }

    // ml/speaker_tune.py → kid_adult_lr.json
    static final float[] KID_W = {
            0.40230f, 0.21920f, -0.11298f, 0.09978f, -0.04385f, -0.00444f, 0.09027f, 0.13202f, -0.35472f, 0.22187f, -0.67749f, 0.56862f,
            0.15150f, 0.00908f, -0.44853f, -0.00264f, -0.57097f, 0.00524f, 0.25443f, 0.42583f, -0.34903f, 0.34505f, 0.40589f, -0.01675f,
            0.20390f, -0.09697f, -0.59980f, -0.21294f, 0.10578f, 0.51179f, -0.12020f, -0.16227f, -0.30670f, -0.27695f, 0.26690f, 0.14726f,
            0.02636f, -0.41575f, 0.54367f, 0.07054f, 0.17206f, -0.24123f, -0.08932f, 0.74353f, -0.23067f, -0.24480f, 0.31448f, 0.03531f,
            -0.02750f, 0.30495f, 0.40252f, 0.02690f, -0.22145f, -0.09407f, 0.02499f, 0.43770f, 0.40417f, -0.31931f, -0.32592f, 0.39109f,
            -0.12568f, -0.08322f, -0.40045f, 0.57142f, 0.13348f, -0.67776f, 0.32825f, -0.15244f, -0.41986f, -0.18079f, -0.43539f, -0.26655f,
            -0.42029f, -0.09003f, -0.14278f, 0.20959f, 0.72222f, 0.30061f, 0.35035f, 0.26719f, 0.05704f, -0.44784f, -0.09667f, -0.68978f,
            0.36144f, 0.02743f, 0.45243f, 0.50043f, -0.28342f, 0.11652f, -0.42806f, 0.14246f, -0.05609f, -0.50340f, 0.12533f, -0.03251f,
            -0.30556f, 0.08541f, -0.10892f, -0.09090f, 0.34474f, 0.20345f, -0.21430f, -0.54120f, 0.17442f, -0.41379f, -0.02684f, -0.10725f,
            -0.18384f, 0.14101f, -0.15851f, -0.20321f, -0.34543f, 0.16803f, -0.49407f, -0.73312f, -0.05275f, 0.49125f, -0.24585f, 0.35680f,
            -0.26190f, 0.61986f, 0.30900f, -0.27682f, -0.27610f, -0.27016f, 0.10747f, -0.60818f, 0.71591f, -0.09986f, -0.09720f, -0.09988f,
            -0.59391f, 0.11405f, -0.15966f, 0.54857f, 0.10036f, 0.17096f, 0.06474f, -0.00732f, -0.18329f, -0.48025f, -0.44896f, 0.43175f,
            0.15718f, 0.33309f, 0.06973f, -0.46164f, -0.08222f, 0.41404f, -0.00752f, -0.34837f, 0.22384f, 0.04344f, -0.29477f, -0.26329f,
            -0.10230f, -0.19791f, -0.23374f, 0.48108f, -0.06693f, 0.09445f, 0.09325f, 0.11658f, -0.14463f, 0.23274f, 0.20992f, 0.66456f,
            -0.09689f, -0.10781f, 0.05099f, -0.20445f, 0.01998f, -0.28989f, -0.31856f, -0.06031f, -0.09577f, -0.22157f, 0.44151f, -0.09072f,
            -0.27834f, 0.72699f, -0.10547f, 0.13385f, 0.48005f, -0.66611f, 0.04142f, 0.14777f, 0.02977f, 0.23633f, 0.62048f, -0.02905f};
    static final float KID_B = -1.22312f;
}
