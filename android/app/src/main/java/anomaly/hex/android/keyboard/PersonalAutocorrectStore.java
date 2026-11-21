package anomaly.hex.android.keyboard;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;

/** On-device learning from user-accepted replacements and user rejections/undos. */
public final class PersonalAutocorrectStore {
    private static final int MAX_ENTRIES = 256;
    private static final String PREFS = "hex_autocorrect_learn";
    private static final String KEY_LEARNED = "learned_pairs";
    private static final String KEY_KEEP = "keep_words";
    private static final String KEY_REJECTS = "reject_counts";

    private final SharedPreferences prefs;
    private final LinkedHashMap<String, String> learned = new LinkedHashMap<>();
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

    public synchronized boolean isKept(String lowerWord) {
        return lowerWord != null && keepWords.contains(lowerWord);
    }

    /** User explicitly accepted `replacement` for `typed` (tapped suggestion or let correction stick). */
    public synchronized void recordAccepted(String typed, String replacement) {
        if (typed == null || replacement == null) return;
        String key = typed.trim().toLowerCase(Locale.ROOT);
        String val = replacement.trim();
        if (key.length() < 2 || val.isEmpty() || key.equalsIgnoreCase(val)) return;
        keepWords.remove(key);
        rejectCounts.remove(key);
        learned.remove(key);
        learned.put(key, val);
        while (learned.size() > MAX_ENTRIES) learned.remove(learned.keySet().iterator().next());
        save();
    }

    /** User rejected an autocorrection (backspace undo or verbatim tap). */
    public synchronized void recordRejected(String typed, boolean explicitVerbatim) {
        if (typed == null) return;
        String key = typed.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty()) return;
        learned.remove(key);
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
        StringBuilder sbKeep = new StringBuilder();
        for (String w : keepWords) sbKeep.append(w).append('\n');
        StringBuilder sbRej = new StringBuilder();
        for (var e : rejectCounts.entrySet()) sbRej.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
        prefs.edit()
                .putString(KEY_LEARNED, sbLearned.toString())
                .putString(KEY_KEEP, sbKeep.toString())
                .putString(KEY_REJECTS, sbRej.toString())
                .apply();
    }
}
