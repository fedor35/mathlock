package dev.gubenkov.reading;

/**
 * Правила языка для сверки чтения — независимо от модели ASR.
 * Сверка идёт в «буквах сравнения» (letters): то, что выдаёт CTC-модель этого языка.
 * Для языков с непрозрачной орфографией (английский) letters = фонемы из словаря/G2P — см. docs/READING.md.
 */
public abstract class LanguageSpec {
    public final String code;
    protected LanguageSpec(String code) { this.code = code; }

    public abstract boolean isLetter(char c);
    public abstract boolean isVowel(char c);
    /** Буква текста -> буква сравнения (нижний регистр, ё→е и т.п.); 0 — выбросить. */
    public abstract char normalize(char c);
    /** Стоимость замены (0..1) для пар, которые не считаются ошибкой чтения. */
    public float similar(char a, char b) { return 1f; }
    /** Согласные, которые перед согласным остаются в левом слоге (сонорные). */
    public boolean keepsLeftBeforeConsonant(char c) { return false; }
    /** Буквы, закрывающие слог слева (ь, ъ). */
    public boolean closesSyllable(char c) { return false; }
    /** Полугласные, остающиеся слева (й). */
    public boolean isGlide(char c) { return false; }

    public static LanguageSpec forCode(String code) {
        switch (code) {
            case "ru": return RU;
            case "kk": return KK;
            default: throw new IllegalArgumentException("язык не поддержан: " + code);
        }
    }

    static final String[] RU_SIMILAR = {"ао", "еи", "ея", "иы", "дт", "бп", "зс", "гк", "вф", "жш", "ую", "еэ"};

    public static final LanguageSpec RU = new Cyrillic("ru", "абвгдеёжзийклмнопрстуфхцчшщъыьэюя", "аеёиоуыэюя", RU_SIMILAR);

    /** Казахский: 42 буквы; ASR-модель для него ещё предстоит (дообучение на KSC2), правила — черновик. */
    public static final LanguageSpec KK = new Cyrillic("kk", "аәбвгғдеёжзийкқлмнңоөпрстуұүфхһцчшщъыіьэюя", "аәеёиоөуұүыіэюя",
            new String[]{"ао", "еи", "дт", "бп", "зс", "гк", "кқ", "гғ", "нң", "оө", "уұ", "ұү", "ыі", "хһ"});

    static final class Cyrillic extends LanguageSpec {
        final String letters, vowels; final String[] sim;
        Cyrillic(String code, String letters, String vowels, String[] sim) { super(code); this.letters = letters; this.vowels = vowels; this.sim = sim; }
        @Override public boolean isLetter(char c) { return letters.indexOf(Character.toLowerCase(c)) >= 0; }
        @Override public boolean isVowel(char c) { return vowels.indexOf(Character.toLowerCase(c)) >= 0; }
        @Override public char normalize(char c) {
            char l = Character.toLowerCase(c);
            if (code.equals("ru") && l == 'ё') return 'е';
            return isLetter(l) ? l : 0;
        }
        @Override public float similar(char a, char b) {
            for (String p : sim) if ((p.charAt(0) == a && p.charAt(1) == b) || (p.charAt(0) == b && p.charAt(1) == a)) return ReadingMatcher.SUB_SIMILAR;
            return 1f;
        }
        @Override public boolean keepsLeftBeforeConsonant(char c) { return "рлмн".indexOf(Character.toLowerCase(c)) >= 0; }
        @Override public boolean closesSyllable(char c) { return "ьъ".indexOf(Character.toLowerCase(c)) >= 0; }
        @Override public boolean isGlide(char c) { return Character.toLowerCase(c) == 'й'; }
    }
}
