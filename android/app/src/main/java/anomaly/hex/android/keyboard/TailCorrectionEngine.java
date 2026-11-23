package anomaly.hex.android.keyboard;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure multi-word tail correction engine for the Spacebar Tail Fix gestures
 * (Flick-up scrubber, Long-press Fix Tail panel, and Hold+slide picker).
 * Parses the last up to MAX_WORDS tokens before the cursor, computes spatial + spacebar-miss +
 * real-word neighbor candidates for each word, and synthesizes 1-tap full-tail phrase fixes.
 */
public final class TailCorrectionEngine {
    public static final int MAX_WORDS = 6;
    private static final Pattern WORD_SPAN = Pattern.compile("[\\p{L}][\\p{L}'’]{0,23}");

    public record WordSlot(
            String original,
            int startInTail,
            int endInTail,
            boolean suspicious,
            List<String> candidates) {}

    public record PhraseFix(String label, String replacement) {}

    public record TailAnalysis(
            String originalTail,
            int replaceLength,
            List<WordSlot> words,
            List<PhraseFix> phraseFixes,
            int defaultFocusIndex) {
        public boolean isEmpty() { return words.isEmpty(); }
    }

    private TailCorrectionEngine() {}

    public static TailAnalysis analyze(
            String textBeforeCursor,
            WordDictionary dict,
            SpatialKeyboardDecoder.DecodeContext ctx) {
        if (textBeforeCursor == null || textBeforeCursor.isBlank()) {
            return new TailAnalysis("", 0, List.of(), List.of(), -1);
        }
        SpatialKeyboardDecoder.DecodeContext dc = ctx == null ? SpatialKeyboardDecoder.DecodeContext.EMPTY : ctx;
        Map<String, String> screen = dc.screenWords() == null ? Map.of() : dc.screenWords();
        PersonalAutocorrectStore store = dc.personalStore();

        List<int[]> spans = new ArrayList<>();
        Matcher m = WORD_SPAN.matcher(textBeforeCursor);
        while (m.find()) {
            int s = m.start(), e = m.end();
            if (s > 0) {
                char prev = textBeforeCursor.charAt(s - 1);
                if (prev == '@' || prev == '/' || prev == '#' || prev == '_' || Character.isDigit(prev)) continue;
            }
            spans.add(new int[]{s, e});
        }
        if (spans.isEmpty()) {
            return new TailAnalysis("", 0, List.of(), List.of(), -1);
        }

        int fromIdx = Math.max(0, spans.size() - MAX_WORDS);
        int tailStart = spans.get(fromIdx)[0];
        String tail = textBeforeCursor.substring(tailStart);
        int replaceLen = tail.length();

        List<WordSlot> slots = new ArrayList<>();
        int focusIndex = -1;
        for (int i = fromIdx; i < spans.size(); i++) {
            int start = spans.get(i)[0] - tailStart;
            int end = spans.get(i)[1] - tailStart;
            String orig = tail.substring(start, end);
            String lower = orig.toLowerCase(Locale.ROOT);
            String prevLower = i > 0
                    ? textBeforeCursor.substring(spans.get(i - 1)[0], spans.get(i - 1)[1]).toLowerCase(Locale.ROOT)
                    : null;
            SpatialKeyboardDecoder.DecodeContext wordCtx =
                    new SpatialKeyboardDecoder.DecodeContext(prevLower, screen, store);

            boolean known = isKnownWord(dict, lower, screen, store);
            List<String> cands = candidatesForWord(orig, lower, dict, wordCtx, screen, store);
            boolean suspicious = !known && !cands.isEmpty();
            int slotIdx = slots.size();
            slots.add(new WordSlot(orig, start, end, suspicious, cands));
            if (suspicious) focusIndex = slotIdx;
        }
        if (focusIndex < 0 && !slots.isEmpty()) {
            // If all words are valid English words, focus the rightmost word that has real-word neighbors.
            for (int i = slots.size() - 1; i >= 0; i--) {
                if (!slots.get(i).candidates().isEmpty()) {
                    focusIndex = i;
                    break;
                }
            }
            if (focusIndex < 0) focusIndex = slots.size() - 1;
        }

        List<PhraseFix> phrases = buildPhraseFixes(tail, slots, focusIndex);
        return new TailAnalysis(tail, replaceLen, List.copyOf(slots), List.copyOf(phrases), focusIndex);
    }

    /** Replaces a single WordSlot inside `originalTail` preserving all surrounding punctuation/spaces. */
    public static String replaceWord(TailAnalysis analysis, int wordIndex, String replacement) {
        if (analysis == null || wordIndex < 0 || wordIndex >= analysis.words().size() || replacement == null) {
            return analysis == null ? "" : analysis.originalTail();
        }
        WordSlot slot = analysis.words().get(wordIndex);
        String tail = analysis.originalTail();
        return tail.substring(0, slot.startInTail()) + replacement + tail.substring(slot.endInTail());
    }

    private static boolean isKnownWord(
            WordDictionary dict, String lower, Map<String, String> screen, PersonalAutocorrectStore store) {
        if (lower.equals("i") || lower.equals("a")) return true;
        if (screen.containsKey(lower)) return true;
        if (store != null && store.isPersonalWord(lower)) return true;
        return dict != null && dict.contains(lower);
    }

    private static List<String> candidatesForWord(
            String orig, String lower, WordDictionary dict,
            SpatialKeyboardDecoder.DecodeContext ctx,
            Map<String, String> screen, PersonalAutocorrectStore store) {
        if (dict == null || lower.length() < 2) return List.of();
        boolean title = !orig.equals(lower) && Character.isUpperCase(orig.charAt(0));
        LinkedHashSet<String> out = new LinkedHashSet<>();

        // 1. Spacebar-miss split ("wantbto" -> "want to")
        String sbSplit = SpatialKeyboardDecoder.splitSpacebarMiss(dict, lower, ctx);
        if (sbSplit != null) out.add(applyCase(title, sbSplit));

        // 2. Concatenated missed-space split ("upto" -> "up to")
        String concatSplit = dict.splitMissedSpace(lower);
        if (concatSplit != null) out.add(applyCase(title, concatSplit));

        // 3. Spatial QWERTY neighbors (works for BOTH typos AND real-word slips like "thin" -> "this"/"then")
        if (lower.length() == 2) {
            String twoFix = dict.bestTwoLetterFix(lower);
            if (twoFix != null && !twoFix.equalsIgnoreCase(orig)) out.add(applyCase(title, twoFix));
        } else {
            List<WordDictionary.Candidate> spatial = SpatialKeyboardDecoder.decode(dict, lower, 5, ctx);
            for (WordDictionary.Candidate c : spatial) {
                if (c.word().equalsIgnoreCase(lower)) continue;
                String canon = canonical(c.word(), screen, store);
                out.add(applyCase(title, canon));
                if (out.size() >= 4) break;
            }
        }
        List<String> list = new ArrayList<>(out);
        return list.size() > 4 ? list.subList(0, 4) : list;
    }

    private static List<PhraseFix> buildPhraseFixes(String tail, List<WordSlot> slots, int focusIndex) {
        LinkedHashMap<String, PhraseFix> unique = new LinkedHashMap<>();

        // Phrase 1: Fix ALL suspicious words in the tail at once
        boolean anySuspicious = false;
        StringBuilder allFixed = new StringBuilder(tail.length() + 8);
        int cursor = 0;
        for (WordSlot w : slots) {
            allFixed.append(tail, cursor, w.startInTail());
            if (w.suspicious() && !w.candidates().isEmpty()) {
                allFixed.append(w.candidates().get(0));
                anySuspicious = true;
            } else {
                allFixed.append(w.original());
            }
            cursor = w.endInTail();
        }
        allFixed.append(tail.substring(cursor));
        String p1 = allFixed.toString();
        if (anySuspicious && !p1.equals(tail)) {
            unique.put(p1, new PhraseFix(p1.trim(), p1));
        }

        // Phrases 2..3: Alternate candidates on the focused word (handles real-word slips & ambiguity)
        if (focusIndex >= 0 && focusIndex < slots.size()) {
            WordSlot focus = slots.get(focusIndex);
            for (String cand : focus.candidates()) {
                if (unique.size() >= 3) break;
                // Build from the all-fixed tail if other words were also suspicious, else from original tail
                StringBuilder sb = new StringBuilder(tail.length() + 8);
                int c = 0;
                for (int i = 0; i < slots.size(); i++) {
                    WordSlot w = slots.get(i);
                    sb.append(tail, c, w.startInTail());
                    if (i == focusIndex) {
                        sb.append(cand);
                    } else if (w.suspicious() && !w.candidates().isEmpty()) {
                        sb.append(w.candidates().get(0));
                    } else {
                        sb.append(w.original());
                    }
                    c = w.endInTail();
                }
                sb.append(tail.substring(c));
                String variant = sb.toString();
                if (!variant.equals(tail) && !unique.containsKey(variant)) {
                    unique.put(variant, new PhraseFix(variant.trim(), variant));
                }
            }
        }
        return new ArrayList<>(unique.values());
    }

    private static String canonical(String lower, Map<String, String> screen, PersonalAutocorrectStore store) {
        String s = screen.get(lower);
        if (s != null) return s;
        if (store != null) {
            String p = store.personalCanonical(lower);
            if (p != null) return p;
        }
        return lower;
    }

    private static String applyCase(boolean title, String text) {
        if (!title || text.isEmpty()) return text;
        return Character.isUpperCase(text.charAt(0))
                ? text
                : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
