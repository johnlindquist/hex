package anomaly.hex.android.core;

import java.util.*;

/**
 * English numbers said in words written in digits (iOS SpokenNumbers parity).
 * Runs in TranscriptFormatter.applyPipeline (every style except Verbatim) when enabled.
 * Converts decimals, percentages, cardinals >= 10 & compounds, years, money with "dollars",
 * 2..9 before plural nouns/units, times with am/pm/o'clock, ordinals in dates, 3+ spoken digits,
 * and negative numbers — while protecting capitalized brand names and custom vocabulary terms.
 */
public final class SpokenNumbers {
    private SpokenNumbers() {}

    private static final Map<String, Integer> DIGIT_WORDS = Map.of(
            "zero", 0, "one", 1, "two", 2, "three", 3, "four", 4,
            "five", 5, "six", 6, "seven", 7, "eight", 8, "nine", 9
    );
    private static final Map<String, Integer> TEEN_WORDS = Map.of(
            "ten", 10, "eleven", 11, "twelve", 12, "thirteen", 13, "fourteen", 14,
            "fifteen", 15, "sixteen", 16, "seventeen", 17, "eighteen", 18, "nineteen", 19
    );
    private static final Map<String, Integer> TENS_WORDS = Map.of(
            "twenty", 20, "thirty", 30, "forty", 40, "fifty", 50,
            "sixty", 60, "seventy", 70, "eighty", 80, "ninety", 90
    );
    private static final Map<String, Long> BIG_WORDS = Map.of(
            "thousand", 1_000L, "million", 1_000_000L,
            "billion", 1_000_000_000L, "trillion", 1_000_000_000_000L
    );
    private static final Map<String, Integer> ORDINALS = Map.ofEntries(
            Map.entry("first", 1), Map.entry("second", 2), Map.entry("third", 3), Map.entry("fourth", 4),
            Map.entry("fifth", 5), Map.entry("sixth", 6), Map.entry("seventh", 7), Map.entry("eighth", 8),
            Map.entry("ninth", 9), Map.entry("tenth", 10), Map.entry("eleventh", 11), Map.entry("twelfth", 12),
            Map.entry("thirteenth", 13), Map.entry("fourteenth", 14), Map.entry("fifteenth", 15),
            Map.entry("sixteenth", 16), Map.entry("seventeenth", 17), Map.entry("eighteenth", 18),
            Map.entry("nineteenth", 19), Map.entry("twentieth", 20), Map.entry("thirtieth", 30)
    );
    private static final Set<String> MONTHS = Set.of(
            "january", "february", "march", "april", "may", "june",
            "july", "august", "september", "october", "november", "december"
    );
    private static final Set<String> UNITS = Set.of(
            "k", "km", "kg", "mph", "kph", "gb", "mb", "tb", "kb", "lb", "lbs", "oz", "ml", "mg",
            "cm", "mm", "ft", "mile", "foot", "inch", "kilo", "kilogram", "gram", "pound", "ounce",
            "degree", "liter", "litre", "gallon", "meter", "metre", "kilometer", "kilometre"
    );
    private static final Set<String> ONE_UNITS = Set.of(
            "km", "kg", "mph", "gb", "mb", "tb", "kb", "lb", "oz", "mile", "inch", "kilo",
            "kilogram", "gram", "pound", "ounce", "degree", "liter", "litre", "gallon",
            "meter", "metre", "kilometer", "kilometre"
    );
    private static final Set<String> IRREGULAR_PLURALS = Set.of(
            "people", "children", "men", "women", "feet", "teeth", "mice", "geese", "cattle"
    );
    private static final Set<String> BRIDGES = Set.of(
            "more", "other", "extra", "new", "old", "big", "small", "little", "different",
            "separate", "additional", "whole", "full", "main", "quick", "short", "long",
            "free", "good", "great", "bad", "major", "minor", "key", "young", "large", "huge", "tiny"
    );
    private static final Set<String> NOT_PLURAL = Set.of(
            "this", "his", "hers", "its", "yes", "less", "unless", "perhaps", "always", "towards",
            "besides", "whereas", "across", "plus", "minus", "thus", "was", "has", "does", "goes",
            "gets", "says", "seems", "means", "makes", "equals", "times", "ours", "yours", "theirs",
            "sometimes", "lets", "afterwards", "nowadays", "looks", "sounds", "needs", "wants",
            "takes", "comes", "works", "feels", "stays", "becomes", "remains", "keeps", "tells",
            "gives", "knows", "thinks"
    );
    private static final Set<String> FRACTIONS = Set.of(
            "half", "halves", "third", "thirds", "quarter", "quarters", "fourths",
            "fifths", "sixths", "sevenths", "eighths", "ninths", "tenths"
    );
    private static final Set<String> DECADES = Set.of(
            "twenties", "thirties", "forties", "fifties", "sixties", "seventies", "eighties",
            "nineties", "hundreds", "thousands", "millions", "billions", "dozen", "dozens"
    );

    private static final class Token {
        final int start, end;
        final String lead, core, word, trail;
        final boolean hasDigit, open, startsSentence;
        boolean protectedWord;

        Token(int start, int end, String lead, String core, String trail, boolean open, boolean startsSentence) {
            this.start = start;
            this.end = end;
            this.lead = lead;
            this.core = core;
            this.word = core.toLowerCase(Locale.ROOT);
            this.trail = trail;
            boolean d = false;
            for (int i = 0; i < core.length(); i++) if (Character.isDigit(core.charAt(i))) { d = true; break; }
            this.hasDigit = d;
            this.open = open;
            this.startsSentence = startsSentence;
        }
    }

    private record Edit(int start, int end, String text) {}

    public static String digits(String text, String language, List<String> protectedTerms) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        if (language != null && !language.toLowerCase(Locale.ROOT).startsWith("en")) return text;

        List<Token> tokens = tokenize(text);
        if (tokens.isEmpty()) return text;
        markProtected(tokens, protectedTerms);

        boolean anyNum = false;
        for (Token tk : tokens) {
            if (isNumberish(tk) || isOrdinal(tk.word)) { anyNum = true; break; }
        }
        if (!anyNum) return text;

        List<Edit> edits = new ArrayList<>();
        int i = 0;
        while (i < tokens.size()) {
            Outcome o = match(tokens, i);
            if (o == null) {
                i++;
            } else if (o.isEdit) {
                edits.add(new Edit(tokens.get(o.first).start, tokens.get(o.last).end, o.text));
                i = o.last + 1;
            } else {
                i = Math.max(o.last, i) + 1;
            }
        }
        if (edits.isEmpty()) return text;
        StringBuilder out = new StringBuilder(text.length());
        int copied = 0;
        for (Edit e : edits) {
            out.append(text, copied, e.start).append(e.text);
            copied = e.end;
        }
        out.append(text, copied, text.length());
        return out.toString();
    }

    private static final class Outcome {
        final boolean isEdit;
        final int first, last;
        final String text;
        private Outcome(boolean isEdit, int first, int last, String text) {
            this.isEdit = isEdit; this.first = first; this.last = last; this.text = text;
        }
        static Outcome edit(int first, int last, String text) { return new Outcome(true, first, last, text); }
        static Outcome skip(int last) { return new Outcome(false, last, last, null); }
    }

    private static List<Token> tokenize(String text) {
        List<Token> out = new ArrayList<>();
        int n = text.length(), i = 0;
        boolean sentenceStart = true;
        while (i < n) {
            while (i < n && Character.isWhitespace(text.charAt(i))) {
                if (text.charAt(i) == '\n' || text.charAt(i) == '\r') sentenceStart = true;
                i++;
            }
            if (i >= n) break;
            int start = i;
            while (i < n && !Character.isWhitespace(text.charAt(i))) i++;
            int end = i;
            String raw = text.substring(start, end);
            int leadEnd = 0;
            while (leadEnd < raw.length() && !Character.isLetterOrDigit(raw.charAt(leadEnd))) leadEnd++;
            int trailStart = raw.length();
            while (trailStart > leadEnd && !Character.isLetterOrDigit(raw.charAt(trailStart - 1))) trailStart--;
            String lead = raw.substring(0, leadEnd);
            String core = raw.substring(leadEnd, trailStart);
            String trail = raw.substring(trailStart);
            boolean open = trail.isEmpty();
            out.add(new Token(start, end, lead, core, trail, open, sentenceStart));
            sentenceStart = !trail.isEmpty() && ".!?…".indexOf(trail.charAt(trail.length() - 1)) >= 0;
        }
        return out;
    }

    private static void markProtected(List<Token> tokens, List<String> protectedTerms) {
        if (protectedTerms == null || protectedTerms.isEmpty()) return;
        for (String term : protectedTerms) {
            if (term == null || term.isBlank()) continue;
            String[] parts = term.trim().toLowerCase(Locale.ROOT).split("\\s+");
            if (parts.length == 0) continue;
            for (int i = 0; i <= tokens.size() - parts.length; i++) {
                boolean match = true;
                for (int j = 0; j < parts.length; j++) {
                    if (!tokens.get(i + j).word.equals(parts[j])) { match = false; break; }
                }
                if (match) {
                    for (int j = 0; j < parts.length; j++) tokens.get(i + j).protectedWord = true;
                }
            }
        }
    }

    private enum KindType { DIGIT, TEEN, TENS, TENS_UNIT, HUNDRED, BIG }
    private record Kind(KindType type, long value) {}

    private static Kind kind(String word) {
        Integer d = DIGIT_WORDS.get(word);
        if (d != null) return new Kind(KindType.DIGIT, d);
        Integer t = TEEN_WORDS.get(word);
        if (t != null) return new Kind(KindType.TEEN, t);
        Integer tn = TENS_WORDS.get(word);
        if (tn != null) return new Kind(KindType.TENS, tn);
        if ("hundred".equals(word)) return new Kind(KindType.HUNDRED, 100);
        Long b = BIG_WORDS.get(word);
        if (b != null) return new Kind(KindType.BIG, b);
        int dash = word.indexOf('-');
        if (dash > 0 && dash == word.lastIndexOf('-')) {
            Integer tens = TENS_WORDS.get(word.substring(0, dash));
            Integer unit = DIGIT_WORDS.get(word.substring(dash + 1));
            if (tens != null && unit != null && unit > 0) return new Kind(KindType.TENS_UNIT, tens + unit);
        }
        return null;
    }

    private static boolean isNumberish(Token tk) {
        return !tk.hasDigit && !tk.protectedWord && kind(tk.word) != null;
    }

    private static boolean isOrdinal(String word) {
        if (ORDINALS.containsKey(word)) return true;
        int dash = word.indexOf('-');
        if (dash > 0 && dash == word.lastIndexOf('-')) {
            return TENS_WORDS.containsKey(word.substring(0, dash))
                    && ORDINALS.containsKey(word.substring(dash + 1));
        }
        return false;
    }

    private static Integer spokenDigit(Token tk) {
        if (tk.hasDigit || tk.protectedWord) return null;
        if ("oh".equals(tk.word)) return 0;
        return DIGIT_WORDS.get(tk.word);
    }

    private static Outcome match(List<Token> t, int i) {
        Token tk = t.get(i);
        if (tk.hasDigit || tk.protectedWord) return null;

        // "November twenty second", "July fourth" ("May" and "March" only capitalized)
        if (MONTHS.contains(tk.word) && tk.open
                && ((!"may".equals(tk.word) && !"march".equals(tk.word))
                    || (!tk.core.isEmpty() && Character.isUpperCase(tk.core.charAt(0))))) {
            OrdinalMatch day = ordinal(t, i + 1);
            if (day != null) {
                return Outcome.edit(i + 1, day.end, t.get(i + 1).lead + day.text + t.get(day.end).trail);
            }
        }
        // "the fourth of July"
        if ("the".equals(tk.word) && tk.open) {
            OrdinalMatch day = ordinal(t, i + 1);
            if (day != null && t.get(day.end).open && day.end + 2 < t.size()
                    && "of".equals(t.get(day.end + 1).word) && t.get(day.end + 1).open
                    && MONTHS.contains(t.get(day.end + 2).word)) {
                return Outcome.edit(i + 1, day.end, t.get(i + 1).lead + day.text);
            }
        }
        // "minus five", "negative forty"
        if (("minus".equals(tk.word) || "negative".equals(tk.word)) && tk.open && !followsNumber(t, i)) {
            Outcome num = number(t, i + 1, true);
            if (num != null && num.isEdit && num.first == i + 1) {
                return Outcome.edit(i, num.last, tk.lead + "-" + num.text);
            }
            return null;
        }
        Outcome run = digitRun(t, i);
        if (run != null) return run;
        return number(t, i, false);
    }

    private record OrdinalMatch(String text, int end) {}

    private static OrdinalMatch ordinal(List<Token> t, int j) {
        if (j >= t.size() || t.get(j).hasDigit || t.get(j).protectedWord) return null;
        String word = t.get(j).word;
        Integer value = null;
        int end = j;
        if (ORDINALS.containsKey(word)) {
            value = ORDINALS.get(word);
        } else if (TENS_WORDS.containsKey(word) && t.get(j).open && j + 1 < t.size()
                && !t.get(j + 1).hasDigit && !t.get(j + 1).protectedWord) {
            Integer unit = ORDINALS.get(t.get(j + 1).word);
            if (unit != null && unit < 10) {
                value = TENS_WORDS.get(word) + unit;
                end = j + 1;
            }
        } else {
            int dash = word.indexOf('-');
            if (dash > 0 && dash == word.lastIndexOf('-')) {
                Integer tens = TENS_WORDS.get(word.substring(0, dash));
                Integer unit = ORDINALS.get(word.substring(dash + 1));
                if (tens != null && unit != null && unit < 10) value = tens + unit;
            }
        }
        if (value == null || value < 1 || value > 31) return null;
        return new OrdinalMatch(value + ordinalSuffix(value), end);
    }

    private static String ordinalSuffix(int v) {
        int mod100 = v % 100;
        if (mod100 >= 11 && mod100 <= 13) return "th";
        return switch (v % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
    }

    private static Outcome digitRun(List<Token> t, int i) {
        Token first = t.get(i);
        if ("oh".equals(first.word) && first.startsSentence) return null;
        Integer d0 = spokenDigit(first);
        if (d0 == null) return null;
        List<Integer> digits = new ArrayList<>();
        List<Integer> groups = new ArrayList<>();
        int curGroup = 0;
        int j = i, end = i;
        while (j < t.size()) {
            Integer d = spokenDigit(t.get(j));
            if (d == null) break;
            digits.add(d);
            curGroup++;
            end = j;
            if (!t.get(j).open) {
                if (",".equals(t.get(j).trail)) {
                    groups.add(curGroup);
                    curGroup = 0;
                    j++;
                    continue;
                }
                break;
            }
            j++;
        }
        if (curGroup > 0) groups.add(curGroup);
        if (digits.size() < 3) return null;
        if (t.get(end).open && end + 1 < t.size() && (isNumberish(t.get(end + 1)) || t.get(end + 1).hasDigit)) {
            return Outcome.skip(end);
        }
        StringBuilder joined = new StringBuilder(digits.size());
        for (int d : digits) joined.append(d);
        if (digits.size() == 10 && (groups.equals(List.of(10)) || groups.equals(List.of(3, 3, 4)))) {
            String p = joined.toString();
            return Outcome.edit(i, end, first.lead + p.substring(0, 3) + "-" + p.substring(3, 6) + "-" + p.substring(6) + t.get(end).trail);
        }
        if (groups.size() != 1) return null;
        return Outcome.edit(i, end, first.lead + joined + t.get(end).trail);
    }

    private static boolean leadsScale(List<Token> t, int i) {
        return "a".equals(t.get(i).word) && t.get(i).open && i + 2 < t.size() && t.get(i + 1).open
                && !t.get(i + 1).protectedWord && !t.get(i + 1).hasDigit
                && ("hundred".equals(t.get(i + 1).word) || "thousand".equals(t.get(i + 1).word));
    }

    private static boolean followsNumber(List<Token> t, int i) {
        return i > 0 && t.get(i - 1).open && (t.get(i - 1).hasDigit || isNumberish(t.get(i - 1)));
    }

    private static boolean isName(List<Token> t, int i, int end) {
        Token first = t.get(i);
        if (first.core.isEmpty() || !Character.isUpperCase(first.core.charAt(0))) return false;
        if (end + 1 >= t.size()) return false;
        Token next = t.get(end + 1);
        if (next.core.isEmpty() || !Character.isUpperCase(next.core.charAt(0))) return false;
        if (first.startsSentence) {
            boolean innerCap = false;
            for (int k = i + 1; k <= end; k++) {
                String c = t.get(k).core;
                if (!c.isEmpty() && Character.isUpperCase(c.charAt(0)) && !c.equals(c.toUpperCase(Locale.ROOT))) {
                    innerCap = true;
                    break;
                }
            }
            if (!innerCap) return false;
        }
        String nw = next.word;
        return !"i".equals(nw) && !nw.startsWith("i'") && !nw.startsWith("i’");
    }

    private static final class Cardinal {
        long value;
        int end, words, bigScales;
        boolean isYear, hasScale, ambiguous;
        long bigScaleValue;
        String bigScaleWord;
        Integer single;
    }

    private enum Last { NONE, UNIT, TEEN, TENS, HUNDRED, BIG, AND }

    private static Cardinal year(List<Token> t, int i) {
        if (!t.get(i).open || i + 1 >= t.size() || t.get(i + 1).hasDigit || t.get(i + 1).protectedWord) return null;
        int century;
        if ("nineteen".equals(t.get(i).word)) century = 19;
        else if ("twenty".equals(t.get(i).word)) century = 20;
        else return null;
        int j = i + 1;
        Integer rest = null;
        int end = j;
        Kind k = kind(t.get(j).word);
        if (k != null && k.type == KindType.TEEN) {
            rest = (int) k.value;
        } else if (k != null && k.type == KindType.TENS) {
            rest = (int) k.value;
            if (t.get(j).open && j + 1 < t.size() && !t.get(j + 1).hasDigit && !t.get(j + 1).protectedWord) {
                Kind k2 = kind(t.get(j + 1).word);
                if (k2 != null && k2.type == KindType.DIGIT && k2.value > 0) {
                    rest += (int) k2.value;
                    end = j + 1;
                }
            }
        } else if (k != null && k.type == KindType.TENS_UNIT) {
            rest = (int) k.value;
        } else if ("oh".equals(t.get(j).word) && t.get(j).open && j + 1 < t.size()
                && !t.get(j + 1).hasDigit && !t.get(j + 1).protectedWord) {
            Kind k2 = kind(t.get(j + 1).word);
            if (k2 != null && k2.type == KindType.DIGIT && k2.value > 0) {
                rest = (int) k2.value;
                end = j + 1;
            }
        }
        if (rest == null) return null;
        Cardinal c = new Cardinal();
        c.value = century * 100L + rest;
        c.end = end;
        c.words = end - i + 1;
        c.isYear = true;
        return c;
    }

    private static Cardinal cardinal(List<Token> t, int i) {
        if (i >= t.size() || (!isNumberish(t.get(i)) && !leadsScale(t, i))) return null;
        Cardinal parsed = year(t, i);
        if (parsed == null) {
            boolean fromA = leadsScale(t, i);
            long total = 0, current = fromA ? 1 : 0;
            Last last = fromA ? Last.UNIT : Last.NONE;
            int words = fromA ? 1 : 0;
            int end = i;
            int j = fromA ? i + 1 : i;
            boolean hasScale = false, ambiguous = false, zero = false;
            long lastBigVal = 0;
            String lastBigWord = null;
            int bigScales = 0;

            while (j < t.size()) {
                Token tk = t.get(j);
                if (tk.hasDigit || tk.protectedWord) break;
                if ("and".equals(tk.word) && (last == Last.HUNDRED || last == Last.BIG)
                        && tk.open && j + 1 < t.size() && isNumberish(t.get(j + 1)) && additive(kind(t.get(j + 1).word))) {
                    last = Last.AND;
                    j++;
                    continue;
                }
                Kind found = kind(tk.word);
                if (found == null) break;
                boolean adds = (last == Last.NONE || last == Last.HUNDRED || last == Last.BIG || last == Last.AND);
                if (found.type == KindType.DIGIT) {
                    if (found.value == 0) {
                        if (words != 0) { ambiguous = true; break; }
                        zero = true;
                    } else {
                        if (!adds && last != Last.TENS) { ambiguous = true; break; }
                        current += found.value;
                    }
                    last = Last.UNIT;
                } else if (found.type == KindType.TEEN || found.type == KindType.TENS || found.type == KindType.TENS_UNIT) {
                    if (!adds) { ambiguous = true; break; }
                    current += found.value;
                    last = found.type == KindType.TENS ? Last.TENS : (found.type == KindType.TEEN ? Last.TEEN : Last.UNIT);
                } else if (found.type == KindType.HUNDRED) {
                    if ((last != Last.UNIT && last != Last.TEEN && last != Last.TENS) || current < 1 || current > 99) {
                        ambiguous = words > 0;
                        break;
                    }
                    current *= 100;
                    last = Last.HUNDRED;
                    hasScale = true;
                } else if (found.type == KindType.BIG) {
                    if ((last != Last.UNIT && last != Last.TEEN && last != Last.TENS && last != Last.HUNDRED)
                            || current <= 0 || (lastBigVal > 0 && found.value >= lastBigVal)) {
                        ambiguous = words > 0;
                        break;
                    }
                    total += current * found.value;
                    current = 0;
                    last = Last.BIG;
                    hasScale = true;
                    lastBigVal = found.value;
                    lastBigWord = tk.word;
                    bigScales++;
                }
                words++;
                end = j;
                j++;
                if (zero || !tk.open) break;
            }
            if (words == 0) return null;
            if (fromA && end <= i + 1) return null;
            parsed = new Cardinal();
            parsed.value = total + current;
            parsed.end = end;
            parsed.words = words;
            parsed.hasScale = hasScale;
            if (last == Last.BIG) { parsed.bigScaleValue = lastBigVal; parsed.bigScaleWord = lastBigWord; }
            parsed.bigScales = bigScales;
            parsed.ambiguous = ambiguous;
            if (words == 1) {
                Kind k0 = kind(t.get(i).word);
                if (k0 != null && k0.type == KindType.DIGIT) parsed.single = (int) k0.value;
            }
        }
        if (t.get(parsed.end).open && parsed.end + 1 < t.size() && isNumberish(t.get(parsed.end + 1))) {
            parsed.ambiguous = true;
        }
        if (followsNumber(t, i) && !t.get(i - 1).hasDigit) parsed.ambiguous = true;
        if (i >= 2 && t.get(i - 1).open && t.get(i - 2).open && !t.get(i - 2).hasDigit && !t.get(i - 2).protectedWord) {
            String prev1 = t.get(i - 1).word, prev2 = t.get(i - 2).word;
            if (("and".equals(prev1) && ("hundred".equals(prev2) || BIG_WORDS.containsKey(prev2)))
                    || ("point".equals(prev1) && kind(prev2) != null)) {
                parsed.ambiguous = true;
            }
        }
        return parsed;
    }

    private static boolean additive(Kind k) {
        if (k == null) return false;
        return (k.type == KindType.DIGIT && k.value > 0)
                || k.type == KindType.TEEN || k.type == KindType.TENS || k.type == KindType.TENS_UNIT;
    }

    private static int clusterEnd(List<Token> t, int i) {
        int j = i;
        while (j + 1 < t.size() && t.get(j).open && (isNumberish(t.get(j + 1)) || "point".equals(t.get(j + 1).word))) j++;
        return j;
    }

    private static String written(Cardinal c) {
        if (c.isYear) return Long.toString(c.value);
        if (c.bigScaleWord != null && c.bigScales == 1 && c.bigScaleValue >= 1_000_000L && c.value % c.bigScaleValue == 0) {
            return (c.value / c.bigScaleValue) + " " + c.bigScaleWord;
        }
        return grouped(c.value);
    }

    private static String grouped(long v) {
        String plain = Long.toString(v);
        if (v < 10_000L) return plain;
        StringBuilder sb = new StringBuilder(plain.length() + 4);
        int len = plain.length();
        for (int i = 0; i < len; i++) {
            if (i > 0 && (len - i) % 3 == 0) sb.append(',');
            sb.append(plain.charAt(i));
        }
        return sb.toString();
    }

    private record DecimalMatch(String text, int end, boolean ambiguous) {}

    private static DecimalMatch decimal(List<Token> t, Cardinal c, int i) {
        if (c.isYear || c.bigScaleWord != null || !t.get(c.end).open || c.end + 2 >= t.size()) return null;
        if (!"point".equals(t.get(c.end + 1).word) || !t.get(c.end + 1).open || t.get(c.end + 1).hasDigit) return null;
        if (c.value == 1 && c.words == 1 && i > 0
                && Set.of("at", "some", "that", "this", "any", "the", "one", "a").contains(t.get(i - 1).word)) {
            return null;
        }
        StringBuilder text = new StringBuilder(Long.toString(c.value));
        int end = c.end;
        int points = 0;
        int j = c.end + 1;
        while (j + 1 < t.size() && t.get(end).open && "point".equals(t.get(j).word) && t.get(j).open && !t.get(j).hasDigit) {
            StringBuilder digits = new StringBuilder();
            int k = j + 1;
            TwoDigit pair = twoDigits(t, k);
            if (pair != null) {
                digits.append(pair.text);
                end = pair.end;
                k = pair.end + 1;
            } else {
                while (k < t.size()) {
                    Integer d = spokenDigit(t.get(k));
                    if (d == null) break;
                    digits.append(d);
                    end = k;
                    k++;
                    if (!t.get(end).open) break;
                }
            }
            if (digits.length() == 0) break;
            text.append('.').append(digits);
            points++;
            j = k;
        }
        if (points == 0) return null;
        boolean ambiguous = t.get(end).open && end + 1 < t.size() && isNumberish(t.get(end + 1));
        if (points == 1 && t.get(end).open && end + 1 < t.size() && BIG_WORDS.containsKey(t.get(end + 1).word)) {
            text.append(' ').append(t.get(end + 1).word);
            end++;
            ambiguous = false;
        }
        return new DecimalMatch(text.toString(), end, ambiguous);
    }

    private record TwoDigit(String text, int end) {}

    private static TwoDigit twoDigits(List<Token> t, int k) {
        if (k >= t.size() || t.get(k).hasDigit || t.get(k).protectedWord) return null;
        Kind found = kind(t.get(k).word);
        if (found == null) return null;
        if (found.type == KindType.TEEN || found.type == KindType.TENS_UNIT) {
            return new TwoDigit(Long.toString(found.value), k);
        }
        if (found.type == KindType.TENS) {
            if (t.get(k).open && k + 1 < t.size() && !t.get(k + 1).hasDigit && !t.get(k + 1).protectedWord) {
                Kind next = kind(t.get(k + 1).word);
                if (next != null && next.type == KindType.DIGIT && next.value > 0) {
                    return new TwoDigit(Long.toString(found.value + next.value), k + 1);
                }
            }
            return new TwoDigit(Long.toString(found.value), k);
        }
        return null;
    }

    private static boolean isMeridiem(Token tk) {
        String w = tk.word.replace(".", "");
        return "am".equals(w) || "pm".equals(w);
    }

    private static Outcome timeWithMeridiem(List<Token> t, int i) {
        if (!t.get(i).open || i + 2 >= t.size()) return null;
        Kind h = kind(t.get(i).word);
        if (h == null || (h.type != KindType.DIGIT && h.type != KindType.TEEN)) return null;
        long hour = h.value;
        if (hour < 1 || hour > 12) return null;
        int mIdx = i + 1;
        String mins;
        int mEnd;
        if ("oh".equals(t.get(mIdx).word) && t.get(mIdx).open && mIdx + 1 < t.size()) {
            Kind d = kind(t.get(mIdx + 1).word);
            if (d == null || d.type != KindType.DIGIT || d.value < 1 || d.value > 9) return null;
            mins = "0" + d.value;
            mEnd = mIdx + 1;
        } else {
            TwoDigit pair = twoDigits(t, mIdx);
            if (pair == null) return null;
            int mv = Integer.parseInt(pair.text);
            if (mv < 10 || mv > 59) return null;
            mins = pair.text;
            mEnd = pair.end;
        }
        if (!t.get(mEnd).open || mEnd + 1 >= t.size() || !isMeridiem(t.get(mEnd + 1))) return null;
        return Outcome.edit(i, mEnd, t.get(i).lead + hour + ":" + mins);
    }

    private static Outcome money(List<Token> t, int i, String amount, boolean isDecimal, int dollarsIdx) {
        String lead = t.get(i).lead;
        if (!isDecimal && t.get(dollarsIdx).open && dollarsIdx + 2 < t.size()) {
            int cStart = "and".equals(t.get(dollarsIdx + 1).word) && t.get(dollarsIdx + 1).open
                    ? dollarsIdx + 2 : dollarsIdx + 1;
            Cardinal cents = cardinal(t, cStart);
            if (cents != null && !cents.ambiguous && cents.value >= 1 && cents.value <= 99
                    && t.get(cents.end).open && cents.end + 1 < t.size()
                    && ("cent".equals(t.get(cents.end + 1).word) || "cents".equals(t.get(cents.end + 1).word))) {
                String cStr = cents.value < 10 ? "0" + cents.value : Long.toString(cents.value);
                return Outcome.edit(i, cents.end + 1, lead + "$" + amount + "." + cStr + t.get(cents.end + 1).trail);
            }
        }
        return Outcome.edit(i, dollarsIdx, lead + "$" + amount + t.get(dollarsIdx).trail);
    }

    private static boolean isCountNoun(List<Token> t, int idx) {
        if (idx >= t.size()) return false;
        String w = t.get(idx).word;
        if (UNITS.contains(w) || IRREGULAR_PLURALS.contains(w)) return true;
        if (BRIDGES.contains(w) && t.get(idx).open && idx + 1 < t.size()) return isCountNoun(t, idx + 1);
        if (w.length() >= 3 && w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us")
                && !w.endsWith("is") && !NOT_PLURAL.contains(w) && !FRACTIONS.contains(w) && !DECADES.contains(w)) {
            return true;
        }
        return false;
    }

    private static Outcome number(List<Token> t, int i, boolean forced) {
        if (!forced) {
            Outcome time = timeWithMeridiem(t, i);
            if (time != null) return time;
        }
        Cardinal num = cardinal(t, i);
        if (num == null) return null;
        if (num.ambiguous) return Outcome.skip(clusterEnd(t, i));
        if (!forced && isName(t, i, num.end)) return Outcome.skip(num.end);

        String lead = t.get(i).lead;
        String text = written(num);
        int end = num.end;
        boolean isDecimal = false;
        boolean plain = !num.isYear && !num.hasScale;

        DecimalMatch dec = decimal(t, num, i);
        if (dec != null) {
            if (dec.ambiguous) return Outcome.skip(clusterEnd(t, i));
            text = dec.text;
            end = dec.end;
            isDecimal = true;
            plain = false;
        }

        boolean open = t.get(end).open && end + 1 < t.size();
        int next = end + 1;

        // Arithmetic stays as said: "ten minus five is five"
        if (open && Set.of("plus", "minus", "times", "divided").contains(t.get(next).word)
                && t.get(next).open && next + 1 < t.size() && isNumberish(t.get(next + 1))) {
            int last = clusterEnd(t, next + 1);
            if (t.get(last).open && last + 2 < t.size()
                    && Set.of("is", "equals", "makes").contains(t.get(last + 1).word)
                    && t.get(last + 1).open && isNumberish(t.get(last + 2))) {
                last = clusterEnd(t, last + 2);
            }
            return Outcome.skip(last);
        }

        if (open) {
            String w = t.get(next).word;
            if ("percent".equals(w)) return Outcome.edit(i, next, lead + text + "%" + t.get(next).trail);
            if ("per".equals(w) && t.get(next).open && next + 1 < t.size() && "cent".equals(t.get(next + 1).word)) {
                return Outcome.edit(i, next + 1, lead + text + "%" + t.get(next + 1).trail);
            }
            if ("dollar".equals(w) || "dollars".equals(w)) {
                return money(t, i, text, isDecimal, next);
            }
            if (isOrdinal(w)) return Outcome.skip(next);
            if (plain && num.value >= 1 && num.value <= 12
                    && (isMeridiem(t.get(next)) || "o'clock".equals(w) || "o’clock".equals(w))) {
                return Outcome.edit(i, end, lead + text);
            }
            // Ranges ending in a count noun/unit: "five to ten minutes"
            if (!forced && plain && num.value < 100 && Set.of("to", "through", "or").contains(w) && t.get(next).open) {
                Cardinal second = cardinal(t, next + 1);
                if (second != null && !second.ambiguous && t.get(second.end).open
                        && second.end + 1 < t.size() && isCountNoun(t, second.end + 1)) {
                    if (!(num.value == 1 && second.value == 2 && "or".equals(w))) {
                        return Outcome.edit(i, end, lead + text);
                    }
                }
            }
            if (isCountNoun(t, next)) {
                if (forced || !plain || num.value >= 10) return Outcome.edit(i, end, lead + text);
                if (num.single != null) {
                    boolean possessive = i > 0 && Set.of("my", "your", "his", "her", "our", "their").contains(t.get(i - 1).word);
                    if (num.single >= 2 && !("cents".equals(w) && possessive)) return Outcome.edit(i, end, lead + text);
                    if (num.single == 1 && ONE_UNITS.contains(w)) return Outcome.edit(i, end, lead + text);
                }
                return Outcome.skip(end);
            }
            if (FRACTIONS.contains(w) || DECADES.contains(w)) return Outcome.skip(end);
            if ("and".equals(w) && next + 2 < t.size() && "a".equals(t.get(next + 1).word) && FRACTIONS.contains(t.get(next + 2).word)) {
                return Outcome.skip(end);
            }
        }
        if (forced || !plain || num.value >= 10) {
            return Outcome.edit(i, end, lead + text + t.get(end).trail);
        }
        return Outcome.skip(end);
    }
}
