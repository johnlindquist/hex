package anomaly.hex.android.keyboard;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;

/**
 * On-device learning from user-accepted replacements and rejections/undos (iOS TypingLearning parity).
 * Promotes a typo->replacement pair only after MIN_LEARN_COUNT (2) consistent observations,
 * ignores prefix completions, drops contradictory/reversed pairs, and caps at 2,000 entries.
 */
public final class PersonalAutocorrectStore {
    public static final int MIN_LEARN_COUNT = 2;
    public static final int MIN_PERSONAL_WORD_COUNT = 2;
    private static final int MAX_ENTRIES = 2000;
    private static final int MAX_PERSONAL_WORDS = 3000;
    private static final int MAX_BIGRAMS = 2000;
    private static final String PREFS = "hex_autocorrect_learn";
    private static final String KEY_LEARNED = "learned_pairs";
    private static final String KEY_PENDING = "pending_pairs";
    private static final String KEY_KEEP = "keep_words";
    private static final String KEY_REJECTS = "reject_counts";
    private static final String KEY_WORD_COUNTS = "word_counts";
    private static final String KEY_BIGRAMS = "bigram_counts";
    private static final String KEY_CHAR_SLIPS = "char_slips";

    private record PendingFix(String replacement, int count) {}
    public record WordUsage(String canonical, int count) {}

    private final SharedPreferences prefs;
    private final LinkedHashMap<String, String> learned = new LinkedHashMap<>();
    private final LinkedHashMap<String, PendingFix> pending = new LinkedHashMap<>();
    private final LinkedHashSet<String> keepWords = new LinkedHashSet<>();
    private final HashMap<String, Integer> rejectCounts = new HashMap<>();
    private final LinkedHashMap<String, WordUsage> wordCounts = new LinkedHashMap<>();
    private final LinkedHashMap<String, Integer> bigramCounts = new LinkedHashMap<>();
    private final HashMap<String, Integer> charSlips = new HashMap<>();

    public PersonalAutocorrectStore(Context ctx) {
        this(ctx == null ? null : ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE));
    }

    PersonalAutocorrectStore(SharedPreferences prefs) {
        this.prefs = prefs;
        load();
    }

    /** In-memory store for unit tests. */
    public static PersonalAutocorrectStore inMemory() { return new PersonalAutocorrectStore((SharedPreferences) null); }

    public synchronized String lookupLearned(String lowerWord) {
        if (lowerWord == null || keepWords.contains(lowerWord)) return null;
        return learned.get(lowerWord);
    }

    /** Returns a learned fix or a 1st-observation pending fix for suggest-only strip display. */
    public synchronized String lookupSuggestion(String lowerWord) {
        if (lowerWord == null || keepWords.contains(lowerWord)) return null;
        String exact = learned.get(lowerWord);
        if (exact != null) return exact;
        PendingFix p = pending.get(lowerWord);
        return p != null ? p.replacement() : null;
    }

    public synchronized boolean isKept(String lowerWord) {
        return lowerWord != null && keepWords.contains(lowerWord);
    }

    /** Records every word the user types so the keyboard adapts to their vocabulary and frequencies over time. */
    public synchronized void recordTypedWord(String word) {
        if (word == null) return;
        String w = word.trim().replace('’', '\'');
        if (w.length() < 2 || w.length() > 24) return;
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c == '\'' && i > 0 && i + 1 < w.length()))) {
                return;
            }
        }
        String lower = w.toLowerCase(Locale.ROOT);
        WordUsage prev = wordCounts.remove(lower);
        int nextCount = prev == null ? 1 : Math.min(100000, prev.count() + 1);
        String canon;
        if (prev == null) {
            canon = w;
        } else if (hasInternalUpper(w)) {
            canon = w; // Preserve camelCase / PascalCase / acronyms like "PostgreSQL", "TypeScript", "FastAPI"
        } else if (hasInternalUpper(prev.canonical())) {
            canon = prev.canonical();
        } else if (w.equals(lower)) {
            canon = lower; // Seen lowercase mid-sentence -> not a mandatory proper noun
        } else {
            canon = prev.canonical();
        }
        wordCounts.put(lower, new WordUsage(canon, nextCount));
        while (wordCounts.size() > MAX_PERSONAL_WORDS) {
            wordCounts.remove(wordCounts.keySet().iterator().next());
        }
        save();
    }

    public synchronized int wordCount(String lowerWord) {
        if (lowerWord == null) return 0;
        WordUsage u = wordCounts.get(lowerWord);
        return u == null ? 0 : u.count();
    }

    public synchronized boolean isPersonalWord(String lowerWord) {
        return lowerWord != null && (keepWords.contains(lowerWord) || wordCount(lowerWord) >= MIN_PERSONAL_WORD_COUNT);
    }

    public synchronized String personalCanonical(String lowerWord) {
        if (lowerWord == null) return null;
        WordUsage u = wordCounts.get(lowerWord);
        return u != null ? u.canonical() : null;
    }

    /** Returns a snapshot of learned personal words (count >= 2) mapped from lower -> canonical spelling. */
    public synchronized Map<String, String> personalVocabularySnapshot() {
        if (wordCounts.isEmpty()) return Map.of();
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        for (var e : wordCounts.entrySet()) {
            if (e.getValue().count() >= MIN_PERSONAL_WORD_COUNT) {
                out.put(e.getKey(), e.getValue().canonical());
            }
        }
        return out;
    }

    /** Records a personal word-to-word bigram so contextual disambiguation improves with usage. */
    public synchronized void recordBigram(String prevWord, String word) {
        if (prevWord == null || word == null) return;
        String a = prevWord.trim().toLowerCase(Locale.ROOT);
        String b = word.trim().toLowerCase(Locale.ROOT);
        if (a.length() < 2 || b.length() < 2 || a.length() > 24 || b.length() > 24) return;
        String key = a + "\t" + b;
        Integer prev = bigramCounts.remove(key);
        bigramCounts.put(key, prev == null ? 1 : Math.min(10000, prev + 1));
        while (bigramCounts.size() > MAX_BIGRAMS) {
            bigramCounts.remove(bigramCounts.keySet().iterator().next());
        }
        save();
    }

    public synchronized int bigramCount(String prevLower, String wordLower) {
        if (prevLower == null || wordLower == null || prevLower.isEmpty() || wordLower.isEmpty()) return 0;
        Integer c = bigramCounts.get(prevLower + "\t" + wordLower);
        return c == null ? 0 : c;
    }

    /** Records adjacent QWERTY key slips observed when a user accepts a fat-thumb correction. */
    public synchronized void recordWordSlips(String typed, String intended) {
        if (typed == null || intended == null) return;
        String a = typed.trim().toLowerCase(Locale.ROOT);
        String b = intended.trim().toLowerCase(Locale.ROOT);
        if (a.length() != b.length() || a.length() < 3 || a.equals(b)) return;
        boolean changed = false;
        int slips = 0;
        for (int i = 0; i < a.length(); i++) {
            char ca = a.charAt(i), cb = b.charAt(i);
            if (ca == cb) continue;
            if (!WordDictionary.isAdjacentKey(ca, cb)) return; // Not a clean adjacent-key slip
            slips++;
            if (slips > 4) return;
        }
        for (int i = 0; i < a.length(); i++) {
            char ca = a.charAt(i), cb = b.charAt(i);
            if (ca == cb) continue;
            String key = ca + ":" + cb;
            charSlips.put(key, Math.min(1000, charSlips.getOrDefault(key, 0) + 1));
            changed = true;
        }
        if (changed) save();
    }

    public synchronized int slipCount(char typed, char intended) {
        Integer c = charSlips.get(typed + ":" + intended);
        return c == null ? 0 : c;
    }

    private static boolean hasInternalUpper(String w) {
        for (int i = 1; i < w.length(); i++) {
            char c = w.charAt(i);
            if (c >= 'A' && c <= 'Z') return true;
        }
        return false;
    }

    /**
     * Decides whether {@code typed} was plausibly a slip of {@code intended} (iOS TypingLearning.isSlip parity),
     * preventing unrelated word changes ("Sunda" -> "Saturday", "cat" -> "dog") from teaching false fixes.
     * Allows: same word with apostrophe/case ("Im" -> "I'm"), or both >= 2 letters starting alike
     * with weighted QWERTY edit cost <= max(1.0, intended.length / 4.0) where adjacent keys,
     * accent differences, transpositions, and doubled letters cost 0.5.
     */
    public static boolean isSlip(String typed, String intended) {
        if (typed == null || intended == null) return false;
        String aRaw = typed.trim().toLowerCase(Locale.ROOT).replace('’', '\'');
        String bRaw = intended.trim().toLowerCase(Locale.ROOT).replace('’', '\'');
        if (aRaw.isEmpty() || bRaw.isEmpty()) return false;
        if (aRaw.equals(bRaw)) return !typed.trim().equals(intended.trim());
        String aNoApos = aRaw.replace("'", "");
        String bNoApos = bRaw.replace("'", "");
        if (aNoApos.equals(bNoApos)) return true; // e.g. "Im" -> "I'm", "dont" -> "don't"
        if (aNoApos.length() < 2 || bNoApos.length() < 2) return false;
        char a0 = aNoApos.charAt(0), b0 = bNoApos.charAt(0);
        char a1 = aNoApos.charAt(1), b1 = bNoApos.charAt(1);
        if (!(nearKey(a0, b0) || a0 == b1 || a1 == b0)) return false;
        double limit = Math.max(1.0, bNoApos.length() / 4.0);
        return slipCost(aNoApos, bNoApos) <= limit;
    }

    private static boolean nearKey(char x, char y) {
        if (x == y) return true;
        if (baseChar(x) == baseChar(y)) return true;
        return WordDictionary.keyDistanceSq(x, y) <= 2.56f;
    }

    private static char baseChar(char c) {
        String s = java.text.Normalizer.normalize(String.valueOf(c), java.text.Normalizer.Form.NFD);
        return s.isEmpty() ? c : s.charAt(0);
    }

    private static double slipCost(String a, String b) {
        int la = a.length(), lb = b.length();
        double[][] d = new double[la + 1][lb + 1];
        for (int i = 1; i <= la; i++) d[i][0] = d[i - 1][0] + (i > 1 && a.charAt(i - 1) == a.charAt(i - 2) ? 0.5 : 1.0);
        for (int j = 1; j <= lb; j++) d[0][j] = d[0][j - 1] + (j > 1 && b.charAt(j - 1) == b.charAt(j - 2) ? 0.5 : 1.0);
        for (int i = 1; i <= la; i++) {
            for (int j = 1; j <= lb; j++) {
                char ca = a.charAt(i - 1), cb = b.charAt(j - 1);
                double sub = ca == cb ? 0.0 : (nearKey(ca, cb) ? 0.5 : 1.0);
                double delStep = i > 1 && ca == a.charAt(i - 2) ? 0.5 : 1.0;
                double insStep = j > 1 && cb == b.charAt(j - 2) ? 0.5 : 1.0;
                double cost = Math.min(d[i - 1][j - 1] + sub, Math.min(d[i - 1][j] + delStep, d[i][j - 1] + insStep));
                if (i > 1 && j > 1 && ca == b.charAt(j - 2) && a.charAt(i - 2) == cb) {
                    cost = Math.min(cost, d[i - 2][j - 2] + 0.5);
                }
                d[i][j] = cost;
            }
        }
        return d[la][lb];
    }

    /**
     * Records a word erased and retyped in its place (iOS TypingJournal.didFinish parity):
     * teaches only when the erased word was plausibly a slip of the replacement, never a change of mind.
     */
    public synchronized void recordRetyped(String erased, String replacement) {
        if (!isSlip(erased, replacement)) return;
        recordAccepted(erased, replacement);
    }

    /**
     * Records a user-accepted replacement (deliberate suggestion pick).
     * Requires MIN_LEARN_COUNT (2) consistent observations; ignores prefix completions and
     * drops contradictory or reversed pairs.
     */
    public synchronized void recordAccepted(String typed, String replacement) {
        if (typed == null || replacement == null) return;
        String key = typed.trim().toLowerCase(Locale.ROOT);
        String val = replacement.trim();
        String valLower = val.toLowerCase(Locale.ROOT);
        if (key.length() < 2 || val.isEmpty() || key.equals(valLower)) return;
        // Ignore prefix completions (e.g. typing "repla" -> picking "replace" is a completion, not a typo).
        if (valLower.startsWith(key)) return;
        // If user previously fixed the reverse direction (valLower -> key), drop both to avoid oscillation.
        if (key.equalsIgnoreCase(learned.get(valLower))) {
            learned.remove(valLower);
            pending.remove(valLower);
            pending.remove(key);
            save();
            return;
        }
        keepWords.remove(key);
        rejectCounts.remove(key);
        recordWordSlips(key, valLower);

        String existing = learned.get(key);
        if (existing != null) {
            if (!existing.equalsIgnoreCase(val)) {
                // Contradictory replacement: demote from auto-replace and restart pending count.
                learned.remove(key);
                pending.put(key, new PendingFix(val, 1));
            } else if (val.equals(valLower)) {
                // Prefer lowercase spelling when seen mid-sentence.
                learned.put(key, valLower);
            }
            save();
            return;
        }

        PendingFix prev = pending.get(key);
        if (prev != null && prev.replacement.equalsIgnoreCase(val)) {
            int nextCount = prev.count + 1;
            String bestSpelling = val.equals(valLower) ? valLower : prev.replacement;
            if (nextCount >= MIN_LEARN_COUNT) {
                pending.remove(key);
                learned.remove(key);
                learned.put(key, bestSpelling);
                while (learned.size() > MAX_ENTRIES) learned.remove(learned.keySet().iterator().next());
            } else {
                pending.put(key, new PendingFix(bestSpelling, nextCount));
            }
        } else {
            pending.remove(key);
            pending.put(key, new PendingFix(val, 1));
            while (pending.size() > MAX_ENTRIES) pending.remove(pending.keySet().iterator().next());
        }
        save();
    }

    /** User rejected an autocorrection (1 explicit Slot 0 verbatim tap or 2 Backspace undos -> keep). */
    public synchronized void recordRejected(String typed, boolean explicitVerbatim) {
        if (typed == null) return;
        String key = typed.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) return;
        learned.remove(key);
        pending.remove(key);
        int count = rejectCounts.getOrDefault(key, 0) + (explicitVerbatim ? 2 : 1);
        if (count >= 2) {
            keepWords.remove(key);
            keepWords.add(key);
            while (keepWords.size() > MAX_ENTRIES) keepWords.remove(keepWords.iterator().next());
            rejectCounts.remove(key);
        } else {
            rejectCounts.put(key, count);
        }
        save();
    }

    private void load() {
        if (prefs == null) return;
        String pairs = prefs.getString(KEY_LEARNED, "");
        if (pairs != null && !pairs.isEmpty()) {
            for (String line : pairs.split("\n")) {
                int tab = line.indexOf('\t');
                if (tab > 0 && tab + 1 < line.length()) {
                    learned.put(line.substring(0, tab), line.substring(tab + 1));
                }
            }
        }
        String pend = prefs.getString(KEY_PENDING, "");
        if (pend != null && !pend.isEmpty()) {
            for (String line : pend.split("\n")) {
                String[] parts = line.split("\t", 3);
                if (parts.length == 3) {
                    try { pending.put(parts[0], new PendingFix(parts[1], Integer.parseInt(parts[2]))); }
                    catch (NumberFormatException ignored) {}
                }
            }
        }
        String kept = prefs.getString(KEY_KEEP, "");
        if (kept != null && !kept.isEmpty()) {
            for (String w : kept.split("\n")) if (!w.isEmpty()) keepWords.add(w);
        }
        String rej = prefs.getString(KEY_REJECTS, "");
        if (rej != null && !rej.isEmpty()) {
            for (String line : rej.split("\n")) {
                int tab = line.indexOf('\t');
                if (tab > 0 && tab + 1 < line.length()) {
                    try { rejectCounts.put(line.substring(0, tab), Integer.parseInt(line.substring(tab + 1))); }
                    catch (NumberFormatException ignored) {}
                }
            }
        }
        String wc = prefs.getString(KEY_WORD_COUNTS, "");
        if (wc != null && !wc.isEmpty()) {
            for (String line : wc.split("\n")) {
                String[] parts = line.split("\t", 3);
                if (parts.length == 3) {
                    try { wordCounts.put(parts[0], new WordUsage(parts[1], Integer.parseInt(parts[2]))); }
                    catch (NumberFormatException ignored) {}
                }
            }
        }
        String bg = prefs.getString(KEY_BIGRAMS, "");
        if (bg != null && !bg.isEmpty()) {
            for (String line : bg.split("\n")) {
                String[] parts = line.split("\t", 3);
                if (parts.length == 3) {
                    try { bigramCounts.put(parts[0] + "\t" + parts[1], Integer.parseInt(parts[2])); }
                    catch (NumberFormatException ignored) {}
                }
            }
        }
        String sl = prefs.getString(KEY_CHAR_SLIPS, "");
        if (sl != null && !sl.isEmpty()) {
            for (String line : sl.split("\n")) {
                int tab = line.indexOf('\t');
                if (tab > 0 && tab + 1 < line.length()) {
                    try { charSlips.put(line.substring(0, tab), Integer.parseInt(line.substring(tab + 1))); }
                    catch (NumberFormatException ignored) {}
                }
            }
        }
    }

    private void save() {
        if (prefs == null) return;
        StringBuilder sbLearned = new StringBuilder();
        for (var e : learned.entrySet()) sbLearned.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        StringBuilder sbPending = new StringBuilder();
        for (var e : pending.entrySet()) sbPending.append(e.getKey()).append('\t').append(e.getValue().replacement).append('\t').append(e.getValue().count).append('\n');
        StringBuilder sbKeep = new StringBuilder();
        for (String w : keepWords) sbKeep.append(w).append('\n');
        StringBuilder sbRej = new StringBuilder();
        for (var e : rejectCounts.entrySet()) sbRej.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        StringBuilder sbWc = new StringBuilder();
        for (var e : wordCounts.entrySet()) sbWc.append(e.getKey()).append('\t').append(e.getValue().canonical()).append('\t').append(e.getValue().count()).append('\n');
        StringBuilder sbBg = new StringBuilder();
        for (var e : bigramCounts.entrySet()) sbBg.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        StringBuilder sbSl = new StringBuilder();
        for (var e : charSlips.entrySet()) sbSl.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        prefs.edit()
                .putString(KEY_LEARNED, sbLearned.toString())
                .putString(KEY_PENDING, sbPending.toString())
                .putString(KEY_KEEP, sbKeep.toString())
                .putString(KEY_REJECTS, sbRej.toString())
                .putString(KEY_WORD_COUNTS, sbWc.toString())
                .putString(KEY_BIGRAMS, sbBg.toString())
                .putString(KEY_CHAR_SLIPS, sbSl.toString())
                .apply();
    }
}
