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
    public static final int MAX_SENTENCE_WORDS = 24;
    private static final Pattern WORD_SPAN = Pattern.compile("[\\p{L}][\\p{L}'’]{0,23}");
    private static final Map<String, String> ALWAYS_CONTRACTIONS = Map.ofEntries(
            Map.entry("dont", "don't"), Map.entry("cant", "can't"), Map.entry("wont", "won't"),
            Map.entry("didnt", "didn't"), Map.entry("doesnt", "doesn't"), Map.entry("isnt", "isn't"),
            Map.entry("arent", "aren't"), Map.entry("wasnt", "wasn't"), Map.entry("werent", "weren't"),
            Map.entry("couldnt", "couldn't"), Map.entry("wouldnt", "wouldn't"), Map.entry("shouldnt", "shouldn't"),
            Map.entry("havent", "haven't"), Map.entry("hasnt", "hasn't"), Map.entry("hadnt", "hadn't"),
            Map.entry("thats", "that's"), Map.entry("whats", "what's"), Map.entry("whos", "who's"),
            Map.entry("heres", "here's"), Map.entry("theres", "there's"), Map.entry("youre", "you're"),
            Map.entry("theyre", "they're"), Map.entry("weve", "we've"), Map.entry("youve", "you've"),
            Map.entry("theyve", "they've"), Map.entry("ive", "I've"), Map.entry("im", "I'm"),
            Map.entry("youll", "you'll"), Map.entry("theyll", "they'll"), Map.entry("youd", "you'd"),
            Map.entry("theyd", "they'd"), Map.entry("hes", "he's"), Map.entry("shes", "she's")
    );
    private static final Map<String, String> CONTEXT_CONTRACTIONS = Map.of(
            "lets", "let's",
            "its", "it's",
            "ill", "I'll",
            "id", "I'd",
            "were", "we're",
            "well", "we'll",
            "wed", "we'd",
            "hell", "he'll",
            "shell", "she'll"
    );
    private static final Set<String> LETS_FOLLOWERS = Set.of(
            "see", "go", "do", "try", "get", "make", "check", "test", "keep", "take",
            "talk", "meet", "start", "stop", "run", "build", "fix", "add", "use",
            "look", "wait", "find", "open", "send", "play", "work", "move", "just", "not"
    );

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
        return analyzeRange(textBeforeCursor, dict, ctx, MAX_WORDS, false, 3);
    }

    /**
     * Analyzes the entire current sentence before the cursor (from the last sentence boundary
     * '.', '!', '?', or newline, capped at MAX_SENTENCE_WORDS) and generates up to 4 fully
     * corrected sentence options for the full-keyboard swipe-up sentence picker.
     */
    public static TailAnalysis analyzeSentence(
            String textBeforeCursor,
            WordDictionary dict,
            SpatialKeyboardDecoder.DecodeContext ctx) {
        return analyzeRange(textBeforeCursor, dict, ctx, MAX_SENTENCE_WORDS, true, 4);
    }

    private static TailAnalysis analyzeRange(
            String textBeforeCursor,
            WordDictionary dict,
            SpatialKeyboardDecoder.DecodeContext ctx,
            int maxWords,
            boolean stopAtSentenceBoundary,
            int maxPhrases) {
        if (textBeforeCursor == null || textBeforeCursor.isBlank()) {
            return new TailAnalysis("", 0, List.of(), List.of(), -1);
        }
        SpatialKeyboardDecoder.DecodeContext dc = ctx == null ? SpatialKeyboardDecoder.DecodeContext.EMPTY : ctx;
        Map<String, String> screen = dc.screenWords() == null ? Map.of() : dc.screenWords();
        PersonalAutocorrectStore store = dc.personalStore();

        int sentenceMinIndex = 0;
        if (stopAtSentenceBoundary) {
            int trimmedEnd = textBeforeCursor.length();
            while (trimmedEnd > 0 && Character.isWhitespace(textBeforeCursor.charAt(trimmedEnd - 1))) trimmedEnd--;
            for (int k = trimmedEnd - 1; k >= 0; k--) {
                char c = textBeforeCursor.charAt(k);
                if (c == '\n' || c == '\r' || c == '.' || c == '!' || c == '?') {
                    // Ignore a trailing sentence mark at the very end of the sentence itself.
                    if (k == trimmedEnd - 1) continue;
                    sentenceMinIndex = k + 1;
                    break;
                }
            }
        }

        List<int[]> spans = new ArrayList<>();
        Matcher m = WORD_SPAN.matcher(textBeforeCursor);
        while (m.find()) {
            int s = m.start(), e = m.end();
            if (s < sentenceMinIndex) continue;
            if (s > 0) {
                char prev = textBeforeCursor.charAt(s - 1);
                if (prev == '@' || prev == '/' || prev == '#' || prev == '_' || Character.isDigit(prev)) continue;
            }
            spans.add(new int[]{s, e});
        }
        if (spans.isEmpty()) {
            return new TailAnalysis("", 0, List.of(), List.of(), -1);
        }

        int fromIdx = Math.max(0, spans.size() - maxWords);
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
            String nextLower = (i + 1 < spans.size())
                    ? textBeforeCursor.substring(spans.get(i + 1)[0], spans.get(i + 1)[1]).toLowerCase(Locale.ROOT)
                    : null;
            boolean atSentenceStart = (i == fromIdx);
            SpatialKeyboardDecoder.DecodeContext wordCtx =
                    new SpatialKeyboardDecoder.DecodeContext(prevLower, screen, store);

            boolean forcedContraction = ALWAYS_CONTRACTIONS.containsKey(lower)
                    || ("lets".equals(lower) && (atSentenceStart || (nextLower != null && LETS_FOLLOWERS.contains(nextLower))));
            boolean known = !forcedContraction && isKnownWord(dict, lower, screen, store);
            List<String> cands = candidatesForWord(
                    orig, lower, prevLower, nextLower, atSentenceStart && stopAtSentenceBoundary,
                    dict, wordCtx, screen, store);
            boolean casingIssue = hasCasingIssue(
                    orig, lower, atSentenceStart && stopAtSentenceBoundary, dict, screen, store);
            boolean contextualRealWordSlip = known && !casingIssue && !cands.isEmpty()
                    && isLikelyRealWordSlip(lower, cands.get(0).toLowerCase(Locale.ROOT), prevLower, nextLower, dict);
            boolean suspicious = (!known || casingIssue || contextualRealWordSlip) && !cands.isEmpty();
            int slotIdx = slots.size();
            slots.add(new WordSlot(orig, start, end, suspicious, cands));
            if (suspicious) focusIndex = slotIdx;
        }
        if (focusIndex < 0 && !slots.isEmpty()) {
            for (int i = slots.size() - 1; i >= 0; i--) {
                if (!slots.get(i).candidates().isEmpty()) {
                    focusIndex = i;
                    break;
                }
            }
            if (focusIndex < 0) focusIndex = slots.size() - 1;
        }

        List<PhraseFix> phrases = buildPhraseFixes(tail, slots, focusIndex, maxPhrases);
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

    /**
     * Recomputes full-sentence options targeting ONLY the user-marked word indices (and any
     * explicitly pinned per-word replacements). Powers the interactive "tap words to mark bad
     * sections" flow in {@link TailFixPanelView}.
     */
    public static List<PhraseFix> recomputePhrases(
            TailAnalysis base,
            Set<Integer> markedIndices,
            Map<Integer, String> pinnedWords,
            int focusIndex,
            int maxPhrases) {
        if (base == null || base.isEmpty()) return List.of();
        Set<Integer> marked = markedIndices == null ? Set.of() : markedIndices;
        Map<Integer, String> pinned = pinnedWords == null ? Map.of() : pinnedWords;

        List<WordSlot> adjusted = new ArrayList<>(base.words().size());
        for (int i = 0; i < base.words().size(); i++) {
            WordSlot w = base.words().get(i);
            String pin = pinned.get(i);
            boolean isMarked = marked.contains(i) || pin != null;
            List<String> cands = w.candidates();
            if (pin != null) {
                LinkedHashSet<String> reordered = new LinkedHashSet<>();
                reordered.add(pin);
                reordered.addAll(cands);
                cands = new ArrayList<>(reordered);
            }
            adjusted.add(new WordSlot(w.original(), w.startInTail(), w.endInTail(), isMarked && !cands.isEmpty(), cands));
        }

        int effFocus = focusIndex;
        if (effFocus < 0 || effFocus >= adjusted.size() || !adjusted.get(effFocus).suspicious()) {
            effFocus = -1;
            for (int i = adjusted.size() - 1; i >= 0; i--) {
                if (adjusted.get(i).suspicious()) { effFocus = i; break; }
            }
        }
        if (effFocus < 0) return List.of();
        return buildPhraseFixes(base.originalTail(), adjusted, effFocus, maxPhrases);
    }

    private static boolean isLikelyRealWordSlip(
            String lower, String topCandLower, String prevLower, String nextLower, WordDictionary dict) {
        if (dict == null || lower.equals(topCandLower)) return false;
        int origRank = dict.rankOf(lower);
        int candRank = dict.rankOf(topCandLower);
        if (origRank < 1200 || candRank < 0 || candRank > 250) return false;
        boolean origFits = CommonBigrams.isCommon(prevLower, lower) || CommonBigrams.isCommon(lower, nextLower);
        if (origFits) return false;
        return CommonBigrams.isCommon(prevLower, topCandLower) || CommonBigrams.isCommon(topCandLower, nextLower);
    }

    private static boolean isKnownWord(
            WordDictionary dict, String lower, Map<String, String> screen, PersonalAutocorrectStore store) {
        if (lower.equals("i") || lower.equals("a")) return true;
        if (screen.containsKey(lower)) return true;
        if (store != null && store.isPersonalWord(lower)) return true;
        return dict != null && dict.contains(lower);
    }

    private static boolean hasCasingIssue(
            String orig, String lower, boolean sentenceStart,
            WordDictionary dict, Map<String, String> screen, PersonalAutocorrectStore store) {
        if (CapitalizationRules.fixDoubleCap(orig) != null) return true;
        if ("i".equals(orig)) return true;
        String canon = CapitalizationRules.canonicalCase(lower);
        if (canon != null && !CapitalizationRules.isAllCaps(orig)) {
            String target = sentenceStart ? CapitalizationRules.capitalizePreservingBrand(canon) : canon;
            if (!orig.equals(target)) return true;
        }
        if (sentenceStart && !orig.isEmpty() && Character.isLowerCase(orig.charAt(0))) {
            String cap = CapitalizationRules.capitalizePreservingBrand(orig);
            if (!cap.equals(orig)) return true;
        }
        if (!sentenceStart && dict != null
                && CapitalizationRules.isLikelyAccidentalMidSentenceCap(orig, lower, dict.rankOf(lower), screen, store)) {
            return true;
        }
        return false;
    }

    private static List<String> candidatesForWord(
            String orig, String lower, String prevLower, String nextLower, boolean sentenceStart,
            WordDictionary dict,
            SpatialKeyboardDecoder.DecodeContext ctx,
            Map<String, String> screen, PersonalAutocorrectStore store) {
        if (dict == null || lower.isEmpty()) return List.of();
        String deDouble = CapitalizationRules.fixDoubleCap(orig);
        String caseSource = deDouble != null ? deDouble : orig;
        boolean forceStartCap = sentenceStart && !CapitalizationRules.isAllCaps(orig);
        LinkedHashSet<String> out = new LinkedHashSet<>();

        // Standalone lowercase "i" -> "I"
        if ("i".equals(orig)) {
            out.add("I");
            return new ArrayList<>(out);
        }
        if (lower.length() < 2) {
            if (forceStartCap && Character.isLowerCase(orig.charAt(0))) {
                out.add(orig.toUpperCase(Locale.ROOT));
            }
            return new ArrayList<>(out);
        }

        // 0a. Contraction restoration ("dont" -> "don't", "Lets" -> "Let's")
        String alwaysContr = ALWAYS_CONTRACTIONS.get(lower);
        if (alwaysContr != null) out.add(applyCase(caseSource, forceStartCap, alwaysContr));
        String ctxContr = CONTEXT_CONTRACTIONS.get(lower);
        if (ctxContr != null) out.add(applyCase(caseSource, forceStartCap, ctxContr));

        // 0b. Canonical casing ("i'm" -> "I'm", "monday" -> "Monday", "github" -> "GitHub")
        String canonCase = CapitalizationRules.canonicalCase(lower);
        if (canonCase != null && !CapitalizationRules.isAllCaps(orig)) {
            String target = forceStartCap ? CapitalizationRules.capitalizePreservingBrand(canonCase) : canonCase;
            if (!target.equals(orig)) out.add(target);
        }

        // 0c. Double-cap fix ("THis" -> "This") on known words
        if (deDouble != null && dict.contains(lower) && !deDouble.equals(orig)) {
            out.add(deDouble);
        }

        // 0d. Lowercase sentence opener ("hey" -> "Hey") when word is otherwise valid
        if (forceStartCap && Character.isLowerCase(orig.charAt(0)) && dict.contains(lower)) {
            String cap = CapitalizationRules.capitalizePreservingBrand(orig);
            if (!cap.equals(orig)) out.add(cap);
        }

        // 0e. Mid-sentence Shift-graze capital ("this Works" -> "this works")
        if (!sentenceStart
                && CapitalizationRules.isLikelyAccidentalMidSentenceCap(orig, lower, dict.rankOf(lower), screen, store)) {
            out.add(lower);
        }

        // 1. Spacebar-miss split including fuzzy halves ("wantbto" -> "want to", "hisbwekk" -> "how well")
        List<String> sbSplits = SpatialKeyboardDecoder.splitSpacebarMissCandidates(dict, lower, ctx, nextLower, 3);
        for (String split : sbSplits) {
            out.add(applyCase(caseSource, forceStartCap, split));
        }

        // 2. Concatenated missed-space split ("upto" -> "up to")
        String concatSplit = dict.splitMissedSpace(lower);
        if (concatSplit != null) out.add(applyCase(caseSource, forceStartCap, concatSplit));

        // 3. Spatial QWERTY neighbors (works for BOTH typos AND real-word slips like "Bow" -> "How", "thin" -> "this")
        if (lower.length() == 2) {
            List<WordDictionary.Candidate> twoCands = dict.twoLetterCandidates(lower);
            twoCands.sort((a, b) -> {
                int sa = a.cost() * 1000
                        - (CommonBigrams.isCommon(prevLower, a.word()) ? 10000 : 0)
                        - (CommonBigrams.isCommon(a.word(), nextLower) ? 10000 : 0);
                int sb = b.cost() * 1000
                        - (CommonBigrams.isCommon(prevLower, b.word()) ? 10000 : 0)
                        - (CommonBigrams.isCommon(b.word(), nextLower) ? 10000 : 0);
                int cmp = Integer.compare(sa, sb);
                return cmp != 0 ? cmp : Integer.compare(a.rank(), b.rank());
            });
            for (WordDictionary.Candidate c : twoCands) {
                if (c.word().equalsIgnoreCase(orig)) continue;
                out.add(applyCase(caseSource, forceStartCap, c.word()));
                if (out.size() >= 4) break;
            }
        } else {
            List<WordDictionary.Candidate> spatial = new ArrayList<>(SpatialKeyboardDecoder.decode(dict, lower, 6, ctx));
            if (nextLower != null && !nextLower.isEmpty()) {
                spatial.sort((a, b) -> {
                    int ba = CommonBigrams.isCommon(a.word(), nextLower) ? 1 : 0;
                    int bb = CommonBigrams.isCommon(b.word(), nextLower) ? 1 : 0;
                    return Integer.compare(bb, ba);
                });
            }
            for (WordDictionary.Candidate c : spatial) {
                if (c.word().equalsIgnoreCase(lower)) continue;
                String canon = canonical(c.word(), screen, store);
                out.add(applyCase(caseSource, forceStartCap, canon));
                if (out.size() >= 4) break;
            }
        }
        List<String> list = new ArrayList<>(out);
        return list.size() > 4 ? list.subList(0, 4) : list;
    }

    private static List<PhraseFix> buildPhraseFixes(String tail, List<WordSlot> slots, int focusIndex, int maxPhrases) {
        LinkedHashMap<String, PhraseFix> unique = new LinkedHashMap<>();

        // Phrase 1: Fix ALL suspicious words in the sentence at once
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

        // Alternates on the focused word first
        addAlternatesForSlot(tail, slots, focusIndex, unique, maxPhrases);

        // Alternates on other suspicious words (right-to-left)
        for (int i = slots.size() - 1; i >= 0 && unique.size() < maxPhrases; i--) {
            if (i == focusIndex || !slots.get(i).suspicious()) continue;
            addAlternatesForSlot(tail, slots, i, unique, maxPhrases);
        }

        // Finally, if we still have room, offer high-confidence real-word neighbor swaps (right-to-left)
        for (int i = slots.size() - 1; i >= 0 && unique.size() < maxPhrases; i--) {
            if (i == focusIndex || slots.get(i).suspicious() || slots.get(i).candidates().isEmpty()) continue;
            addSingleCandidateForSlot(tail, slots, i, slots.get(i).candidates().get(0), unique);
        }

        return new ArrayList<>(unique.values());
    }

    private static void addAlternatesForSlot(
            String tail, List<WordSlot> slots, int targetIndex,
            LinkedHashMap<String, PhraseFix> unique, int maxPhrases) {
        if (targetIndex < 0 || targetIndex >= slots.size()) return;
        for (String cand : slots.get(targetIndex).candidates()) {
            if (unique.size() >= maxPhrases) break;
            addSingleCandidateForSlot(tail, slots, targetIndex, cand, unique);
        }
    }

    private static void addSingleCandidateForSlot(
            String tail, List<WordSlot> slots, int targetIndex, String cand,
            LinkedHashMap<String, PhraseFix> unique) {
        StringBuilder sb = new StringBuilder(tail.length() + 8);
        int c = 0;
        for (int i = 0; i < slots.size(); i++) {
            WordSlot w = slots.get(i);
            sb.append(tail, c, w.startInTail());
            if (i == targetIndex) {
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

    private static String canonical(String lower, Map<String, String> screen, PersonalAutocorrectStore store) {
        String s = screen.get(lower);
        if (s != null) return s;
        if (store != null) {
            String p = store.personalCanonical(lower);
            if (p != null) return p;
        }
        String c = CapitalizationRules.canonicalCase(lower);
        return c != null ? c : lower;
    }

    private static String applyCase(String original, boolean forceSentenceStartCap, String text) {
        if (text == null || text.isEmpty()) return "";
        String matched = CapitalizationRules.matchCase(original, text);
        return forceSentenceStartCap ? CapitalizationRules.capitalizePreservingBrand(matched) : matched;
    }
}
