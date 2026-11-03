package dev.gubenkov.reading;

import java.util.ArrayList;
import java.util.List;

/**
 * Текст для чтения (язык — LanguageSpec): слова с ударением (акут U+0301 после гласной или ё) и разбивкой на слоги.
 * letters — нормализованные буквы слова для сверки с ASR (нижний регистр, ё→е, без знаков).
 */
public final class ReadingText {
    public static final char ACUTE = '́';

    public static final class Word {
        public final String display;   // как в тексте, с ударением и пунктуацией
        public final String letters;   // для сверки
        public final int stressVowel;  // индекс гласной (0..) или -1
        public final List<String> syllables; // буквы с регистром, без ударения
        public final int start, end;   // позиция в исходной строке (для подсветки)
        Word(String display, String letters, int stressVowel, List<String> syllables, int start, int end) {
            this.display = display; this.letters = letters; this.stressVowel = stressVowel;
            this.syllables = syllables; this.start = start; this.end = end;
        }

    }

    public final String source;
    public final LanguageSpec lang;
    public final List<Word> words = new ArrayList<>();

    public ReadingText(String source) { this(source, LanguageSpec.RU); }

    public ReadingText(String source, LanguageSpec lang) {
        this.source = source;
        this.lang = lang;
        int i = 0, n = source.length();
        while (i < n) {
            while (i < n && !lang.isLetter(source.charAt(i))) i++;
            if (i >= n) break;
            int s = i;
            // слово: буквы, акут и внутренний дефис (жила-была → два слова для сверки не делим)
            while (i < n && (lang.isLetter(source.charAt(i)) || source.charAt(i) == ACUTE
                    || (source.charAt(i) == '-' && i + 1 < n && lang.isLetter(source.charAt(i + 1))))) i++;
            String raw = source.substring(s, i);
            StringBuilder plain = new StringBuilder(), let = new StringBuilder();
            int vowel = -1, stress = -1;
            for (int k = 0; k < raw.length(); k++) {
                char c = raw.charAt(k);
                if (c == ACUTE) { stress = vowel; continue; }
                if (c == '-') { plain.append(c); continue; }
                plain.append(c);
                char l = Character.toLowerCase(c);
                if (lang.isVowel(l)) { vowel++; if (l == 'ё') stress = vowel; }
                char nl = lang.normalize(l);
                if (nl != 0) let.append(nl);
            }
            if (stress < 0 && vowel == 0) stress = 0;
            words.add(new Word(raw, let.toString(), stress, syllabify(plain.toString(), lang), s, i));
        }
    }


    /**
     * Школьный слогораздел для букваря: граница после гласной; й, ь, ъ остаются слева;
     * сонорный перед согласным уходит влево (кар-та, лам-па); остальные согласные — к следующей гласной (ко-шка).
     */
    public static List<String> syllabify(String w) { return syllabify(w, LanguageSpec.RU); }

    public static List<String> syllabify(String w, LanguageSpec L) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int n = w.length();
        for (int i = 0; i < n; i++) {
            char c = w.charAt(i);
            if (c == '-') { if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); } continue; }
            cur.append(c);
            if (!L.isVowel(c)) continue;
            // есть ли ещё гласные дальше в этой части слова?
            int j = i + 1; boolean more = false;
            while (j < n && w.charAt(j) != '-') { if (L.isVowel(w.charAt(j))) { more = true; break; } j++; }
            if (!more) continue;
            // согласные между гласными: w[i+1 .. j-1]. Всё до последнего ь/ъ включительно — влево (паль-то, объ-ём),
            // затем й или сонорный перед согласным — влево (май-ка, кар-та), остальное — к следующей гласной.
            int k = i + 1;
            for (int q = i + 1; q < j; q++) if (L.closesSyllable(w.charAt(q))) k = q + 1;
            for (int q = i + 1; q < k; q++) cur.append(w.charAt(q));
            if (k < j - 1 || (k < j && L.isGlide(w.charAt(k)))) {
                char d = w.charAt(k);
                if (L.isGlide(d) || (L.keepsLeftBeforeConsonant(d) && k == i + 1)) { cur.append(d); k++; }
            }
            out.add(cur.toString()); cur.setLength(0);
            i = k - 1;
        }
        if (cur.length() > 0) {
            if (out.isEmpty() || hasVowel(cur, L)) out.add(cur.toString());
            else out.set(out.size() - 1, out.get(out.size() - 1) + cur);
        }
        return out;
    }

    static boolean hasVowel(CharSequence s, LanguageSpec L) { for (int i = 0; i < s.length(); i++) if (L.isVowel(s.charAt(i))) return true; return false; }
}
