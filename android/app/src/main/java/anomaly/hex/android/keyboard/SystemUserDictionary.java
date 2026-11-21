package anomaly.hex.android.keyboard;

import android.content.Context;
import android.database.Cursor;
import android.provider.UserDictionary;
import java.util.*;

/** Reads Android's UserDictionary.Words (shortcuts & personal terms) safely off the UI thread. */
public final class SystemUserDictionary {
    public record Snapshot(Map<String, String> shortcuts, Set<String> termsLower, List<String> terms) {
        public static final Snapshot EMPTY = new Snapshot(Map.of(), Set.of(), List.of());

        public static Snapshot of(Map<String, String> shortcuts, List<String> terms) {
            Map<String, String> sc = new HashMap<>();
            if (shortcuts != null) {
                for (var e : shortcuts.entrySet()) {
                    if (e.getKey() != null && e.getValue() != null) {
                        String k = e.getKey().trim().toLowerCase(Locale.ROOT);
                        String v = e.getValue().trim();
                        if (!k.isEmpty() && !v.isEmpty()) sc.put(k, v);
                    }
                }
            }
            Set<String> lower = new HashSet<>();
            List<String> list = new ArrayList<>();
            if (terms != null) {
                for (String t : terms) {
                    if (t != null && !t.isBlank()) {
                        String trimmed = t.trim();
                        if (lower.add(trimmed.toLowerCase(Locale.ROOT))) list.add(trimmed);
                    }
                }
            }
            return new Snapshot(Map.copyOf(sc), Set.copyOf(lower), List.copyOf(list));
        }
    }

    private SystemUserDictionary() {}

    /** Queries Android's personal dictionary; gracefully returns EMPTY if restricted or unavailable. */
    public static Snapshot load(Context ctx) {
        if (ctx == null) return Snapshot.EMPTY;
        Map<String, String> shortcuts = new HashMap<>();
        List<String> terms = new ArrayList<>();
        String[] projection = { UserDictionary.Words.WORD, UserDictionary.Words.SHORTCUT };
        try (Cursor c = ctx.getApplicationContext().getContentResolver()
                .query(UserDictionary.Words.CONTENT_URI, projection, null, null, null)) {
            if (c == null) return Snapshot.EMPTY;
            int wordCol = c.getColumnIndex(UserDictionary.Words.WORD);
            int scCol = c.getColumnIndex(UserDictionary.Words.SHORTCUT);
            while (c.moveToNext()) {
                String word = wordCol >= 0 ? c.getString(wordCol) : null;
                String sc = scCol >= 0 ? c.getString(scCol) : null;
                if (word == null || word.isBlank()) continue;
                String trimmedWord = word.trim();
                if (sc != null && !sc.isBlank() && !sc.trim().equalsIgnoreCase(trimmedWord)) {
                    shortcuts.put(sc.trim().toLowerCase(Locale.ROOT), trimmedWord);
                } else {
                    terms.add(trimmedWord);
                }
            }
        } catch (Throwable ignored) {
            return Snapshot.EMPTY;
        }
        return Snapshot.of(shortcuts, terms);
    }
}
