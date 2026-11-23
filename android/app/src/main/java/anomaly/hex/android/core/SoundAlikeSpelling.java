package anomaly.hex.android.core;

import java.text.Normalizer;
import java.util.*;

/**
 * Custom vocabulary's sound-alike spellings, English only (iOS SoundAlikeSpelling parity):
 * a transcribed name that sounds like a listed term and is spelled nearly the same takes
 * the listed spelling ("Stacy" -> "Stacey", "John" -> "Jon", "Sarah's" -> "Sara's",
 * "Jeff" -> "Geoff", "Versal" -> "Vercel").
 */
public final class SoundAlikeSpelling {
    private static final int MAX_EDITS = 2;

    private SoundAlikeSpelling() {}

    public static String apply(List<String> terms, String text, String language) {
        if (text == null || text.isEmpty() || terms == null || terms.isEmpty()) return text == null ? "" : text;
        if (language == null || !language.toLowerCase(Locale.ROOT).startsWith("en")) return text;

        Set<String> listed = new HashSet<>();
        List<Term> candidates = new ArrayList<>();
        for (String raw : terms) {
            if (raw == null) continue;
            listed.add(letters(raw));
            Term t = Term.create(raw);
            if (t != null) candidates.add(t);
        }
        if (candidates.isEmpty()) return text;

        List<Word> all = words(text);
        if (all.isEmpty()) return text;

        StringBuilder out = new StringBuilder(text.length());
        int copied = 0;
        for (int index = 0; index < all.size(); index++) {
            Word word = all.get(index);
            boolean opensSentence = startsSentence(word.start, text);
            boolean followsCapital = index > 0 && !opensSentence
                    && Character.isUpperCase(all.get(index - 1).stem.charAt(0))
                    && !startsSentence(all.get(index - 1).start, text);
            String term = match(word, candidates, listed, !opensSentence && !followsCapital);
            if (term == null) continue;
            out.append(text, copied, word.start);
            out.append(term);
            out.append(word.possessive);
            copied = word.end;
        }
        out.append(text, copied, text.length());
        return out.toString();
    }

    private record Term(String text, String letters, String folded, SoundKey key) {
        static Term create(String raw) {
            String trimmed = raw.trim();
            if (trimmed.length() < 3 || !Character.isUpperCase(trimmed.charAt(0))) return null;
            for (int i = 0; i < trimmed.length(); i++) {
                if (!Character.isLetter(trimmed.charAt(i))) return null;
            }
            String let = SoundAlikeSpelling.letters(trimmed);
            if (let.length() < 3) return null;
            return new Term(trimmed, let, SoundAlikeSpelling.folded(let), new SoundKey(let));
        }
    }

    private record Word(int start, int end, String stem, String possessive) {}

    private static String match(Word word, List<Term> candidates, Set<String> listed, boolean soundsAlike) {
        String stem = word.stem;
        if (stem.length() < 3 || !Character.isUpperCase(stem.charAt(0))) return null;
        if (stem.equals(stem.toUpperCase(Locale.ROOT))) return null;
        for (int i = 0; i < stem.length(); i++) {
            if (!Character.isLetter(stem.charAt(i))) return null;
        }
        String heard = letters(stem);
        if (listed.contains(heard)) return null;
        String heardFolded = folded(heard);
        SoundKey heardKey = new SoundKey(heard);

        String bestTerm = null;
        int bestRank = Integer.MAX_VALUE;
        boolean tied = false;

        for (Term term : candidates) {
            int rank;
            if (term.folded.equals(heardFolded)) {
                rank = 0;
            } else if (soundsAlike && term.key.equals(heardKey)) {
                int edits = editDistance(heard, term.letters, MAX_EDITS);
                if (edits < 0) continue;
                rank = 1 + edits;
            } else {
                continue;
            }
            if (rank >= bestRank) {
                if (rank == bestRank && !term.text.equals(bestTerm)) tied = true;
                continue;
            }
            bestTerm = term.text;
            bestRank = rank;
            tied = false;
        }
        return tied ? null : bestTerm;
    }

    /** Lowercase ASCII letters, diacritics removed. */
    public static String letters(String word) {
        String nfd = Normalizer.normalize(word, Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(nfd.length());
        for (int i = 0; i < nfd.length(); i++) {
            char c = Character.toLowerCase(nfd.charAt(i));
            if (c >= 'a' && c <= 'z') sb.append(c);
        }
        return sb.toString();
    }

    /** Evens out spellings English names write interchangeably. */
    public static String folded(String word) {
        String s = letters(word);
        s = s.replace("ph", "f").replace("chr", "kr").replace("ck", "k");
        if (s.startsWith("ai")) s = "a" + s.substring(2);

        char[] arr = s.toCharArray();
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] == 'c') {
                char next = i + 1 < arr.length ? arr[i + 1] : 0;
                if (next == 'h') continue;
                arr[i] = (next == 'e' || next == 'i' || next == 'y') ? 's' : 'k';
            }
        }
        s = new String(arr).replace("ae", "e");
        if (s.length() > 3 && (s.endsWith("ey") || s.endsWith("ie") || s.endsWith("ee"))) {
            s = s.substring(0, s.length() - 2) + "i";
        }
        s = s.replace('y', 'i');

        StringBuilder kept = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == 'h' && i > 0) {
                char prev = s.charAt(i - 1);
                char next = i + 1 < s.length() ? s.charAt(i + 1) : 0;
                if (isVowel(prev) && !isVowel(next)) continue;
                if ("gkrdbjw".indexOf(prev) >= 0) continue;
            }
            if (kept.length() > 0 && kept.charAt(kept.length() - 1) == c) continue;
            kept.append(c);
        }
        return kept.toString();
    }

    private static final Map<String, String> VOWEL_SOUNDS = Map.ofEntries(
            Map.entry("a", "a"), Map.entry("ai", "a"), Map.entry("ay", "a"), Map.entry("ae", "a"), Map.entry("aa", "a"),
            Map.entry("e", "e"), Map.entry("ee", "e"), Map.entry("ea", "e"), Map.entry("ei", "e"), Map.entry("ey", "e"), Map.entry("eo", "e"),
            Map.entry("i", "i"), Map.entry("y", "i"), Map.entry("ia", "i"), Map.entry("ya", "i"), Map.entry("ie", "i"), Map.entry("yi", "i"), Map.entry("io", "i"), Map.entry("yo", "i"),
            Map.entry("o", "o"), Map.entry("oa", "o"), Map.entry("oe", "o"), Map.entry("au", "o"), Map.entry("aw", "o"),
            Map.entry("oi", "oi"), Map.entry("oy", "oi"), Map.entry("ou", "ou"), Map.entry("ow", "ou"),
            Map.entry("u", "u"), Map.entry("ue", "u"), Map.entry("oo", "u"), Map.entry("ui", "u")
    );

    private static final class SoundKey {
        final String consonants;
        final String firstVowel;
        final boolean endsOnVowel;
        final int syllables;

        SoundKey(String word) {
            String w = SoundAlikeSpelling.letters(word);
            this.consonants = SoundAlikeSpelling.consonants(w);
            int start = 0;
            while (start < w.length() && !(isVowel(w.charAt(start)) || (w.charAt(start) == 'y' && start > 0))) {
                start++;
            }
            int end = start;
            while (end < w.length() && (isVowel(w.charAt(end)) || w.charAt(end) == 'y')) {
                end++;
            }
            this.firstVowel = vowelSound(w.substring(start, end));

            String tail = w;
            if (tail.length() > 2 && tail.charAt(tail.length() - 1) == 'h' && isVowel(tail.charAt(tail.length() - 2))) {
                tail = tail.substring(0, tail.length() - 1);
            }
            if (!tail.isEmpty()) {
                char last = tail.charAt(tail.length() - 1);
                this.endsOnVowel = "aiouy".indexOf(last) >= 0
                        || (last == 'e' && tail.length() > 1 && isVowel(tail.charAt(tail.length() - 2)));
            } else {
                this.endsOnVowel = false;
            }

            int groups = 0;
            boolean inGroup = false;
            for (int i = 0; i < tail.length(); i++) {
                char c = tail.charAt(i);
                boolean vowel = isVowel(c) || (c == 'y' && i > 0);
                if (vowel && !inGroup) groups++;
                inGroup = vowel;
            }
            if (tail.length() > 2 && tail.charAt(tail.length() - 1) == 'e'
                    && !isVowel(tail.charAt(tail.length() - 2)) && groups > 1) {
                groups--;
            }
            this.syllables = groups;
        }

        private static String vowelSound(String group) {
            if (group.isEmpty()) return "";
            String v = VOWEL_SOUNDS.get(group);
            if (v != null) return v;
            if (group.length() >= 2) {
                v = VOWEL_SOUNDS.get(group.substring(0, 2));
                if (v != null) return v;
            }
            v = VOWEL_SOUNDS.get(group.substring(0, 1));
            return v != null ? v : "";
        }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SoundKey other)) return false;
            return endsOnVowel == other.endsOnVowel && syllables == other.syllables
                    && consonants.equals(other.consonants) && firstVowel.equals(other.firstVowel);
        }

        @Override public int hashCode() {
            return Objects.hash(consonants, firstVowel, endsOnVowel, syllables);
        }
    }

    private static String consonants(String letters) {
        String w = letters;
        if (w.startsWith("kn") || w.startsWith("gn") || w.startsWith("pn")) w = "n" + w.substring(2);
        else if (w.startsWith("ae")) w = "e" + w.substring(2);
        else if (w.startsWith("wr")) w = "r" + w.substring(2);
        if (w.startsWith("x")) w = "s" + w.substring(1);
        if (w.startsWith("wh")) w = "w" + w.substring(2);

        StringBuilder single = new StringBuilder(w.length());
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if (single.length() > 0 && single.charAt(single.length() - 1) == c && c != 'c') continue;
            single.append(c);
        }
        w = single.toString();

        StringBuilder out = new StringBuilder(w.length());
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            char prev = i > 0 ? w.charAt(i - 1) : 0;
            char next = i + 1 < w.length() ? w.charAt(i + 1) : 0;
            char next2 = i + 2 < w.length() ? w.charAt(i + 2) : 0;

            if (isVowel(c) || (c == 'y' && i > 0)) {
                if (i == 0) out.append(c);
                continue;
            }
            switch (c) {
                case 'b' -> {
                    if (!(prev == 'm' && i == w.length() - 1)) out.append('b');
                }
                case 'c' -> {
                    if (next == 'h') {
                        out.append(prev == 's' || next2 == 'r' ? 'k' : 'x');
                    } else if (next == 'i' && next2 == 'a') {
                        out.append('x');
                    } else if (isSoft(next)) {
                        if (prev != 's') out.append('s');
                    } else {
                        out.append('k');
                    }
                }
                case 'd' -> out.append(next == 'g' && isSoft(next2) ? 'j' : 'd');
                case 'g' -> {
                    if (next == 'h' && !(i + 2 >= w.length() || isVowelOrY(next2))) continue;
                    if (next == 'n' && i + 2 == w.length()) continue;
                    if (prev == 'd' && isSoft(next)) continue;
                    out.append(isSoft(next) ? 'j' : 'g');
                }
                case 'h' -> {
                    if (prev != 0 && "cspgt".indexOf(prev) >= 0) continue;
                    if (i > 0 && !isVowelOrY(next)) continue;
                    out.append('h');
                }
                case 'k' -> {
                    if (prev != 'c') out.append('k');
                }
                case 'p' -> out.append(next == 'h' ? 'f' : 'p');
                case 'q' -> out.append('k');
                case 's' -> out.append(next == 'h' || (next == 'i' && (next2 == 'o' || next2 == 'a')) ? 'x' : 's');
                case 't' -> {
                    if (next == 'i' && (next2 == 'o' || next2 == 'a')) out.append('x');
                    else if (next == 'h') out.append('0');
                    else if (!(next == 'c' && next2 == 'h')) out.append('t');
                }
                case 'w' -> { if (isVowelOrY(next)) out.append('w'); }
                case 'x' -> out.append("ks");
                case 'y' -> { if (isVowelOrY(next)) out.append('y'); }
                case 'z' -> out.append('s');
                default -> out.append(c);
            }
        }
        StringBuilder key = new StringBuilder(out.length());
        for (int i = 0; i < out.length(); i++) {
            char c = out.charAt(i);
            if (key.length() == 0 || key.charAt(key.length() - 1) != c) key.append(c);
        }
        return key.toString();
    }

    private static boolean isVowel(char c) { return c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u'; }
    private static boolean isVowelOrY(char c) { return isVowel(c) || c == 'y'; }
    private static boolean isSoft(char c) { return c == 'e' || c == 'i' || c == 'y'; }

    private static int editDistance(String a, String b, int limit) {
        int la = a.length(), lb = b.length();
        if (Math.abs(la - lb) > limit) return -1;
        int[][] d = new int[la + 1][lb + 1];
        for (int i = 0; i <= la; i++) d[i][0] = i;
        for (int j = 0; j <= lb; j++) d[0][j] = j;
        for (int i = 1; i <= la; i++) {
            for (int j = 1; j <= lb; j++) {
                int sub = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                int v = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + sub);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    v = Math.min(v, d[i - 2][j - 2] + 1);
                }
                d[i][j] = v;
            }
        }
        return d[la][lb] <= limit ? d[la][lb] : -1;
    }

    private static List<Word> words(String text) {
        List<Word> out = new ArrayList<>();
        int start = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '\'' || c == '’') {
                if (start < 0) start = i;
            } else if (start >= 0) {
                appendWord(start, i, text, out);
                start = -1;
            }
        }
        if (start >= 0) appendWord(start, text.length(), text, out);
        return out;
    }

    private static void appendWord(int start, int end, String text, List<Word> out) {
        int e = end;
        while (e > start && (text.charAt(e - 1) == '\'' || text.charAt(e - 1) == '’')) e--;
        if (e <= start || !Character.isLetter(text.charAt(start))) return;
        String word = text.substring(start, e);
        String stem = word;
        String possessive = "";
        if (stem.endsWith("'s") || stem.endsWith("’s")) {
            possessive = stem.substring(stem.length() - 2);
            stem = stem.substring(0, stem.length() - 2);
        }
        if (stem.indexOf('\'') >= 0 || stem.indexOf('’') >= 0) return;
        out.add(new Word(start, start + stem.length() + possessive.length(), stem, possessive));
    }

    private static boolean startsSentence(int index, String text) {
        for (int i = index - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\t' || "\"“”'‘(".indexOf(c) >= 0) continue;
            return ".!?…:\n".indexOf(c) >= 0;
        }
        return true;
    }
}
