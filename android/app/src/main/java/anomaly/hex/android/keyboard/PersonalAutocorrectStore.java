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
    private static final int MAX_ENTRIES = 2000;
    private static final String PREFS = "hex_autocorrect_learn";
    private static final String KEY_LEARNED = "learned_pairs";
    private static final String KEY_PENDING = "pending_pairs";
    private static final String KEY_KEEP = "keep_words";
    private static final String KEY_REJECTS = "reject_counts";

    private record PendingFix(String replacement, int count) {}

    private final SharedPreferences prefs;
    private final LinkedHashMap<String, String> learned = new LinkedHashMap<>();
    private final LinkedHashMap<String, PendingFix> pending = new LinkedHashMap<>();
    private final LinkedHashSet<String> keepWords = new LinkedHashSet<>();
    private final HashMap<String, Integer> rejectCounts = new HashMap<>();

    public PersonalAutocorrectStore(Context ctx) {
        this.prefs = ctx == null ? null : ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    /** In-memory store for unit tests. */
    public static PersonalAutocorrectStore inMemory() { return new PersonalAutocorrectStore(null); }

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

    /**
     * Records a user-accepted replacement (suggestion pick or retype).
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
        prefs.edit()
                .putString(KEY_LEARNED, sbLearned.toString())
                .putString(KEY_PENDING, sbPending.toString())
                .putString(KEY_KEEP, sbKeep.toString())
                .putString(KEY_REJECTS, sbRej.toString())
                .apply();
    }
}
