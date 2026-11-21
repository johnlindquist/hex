package anomaly.hex.android.core;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;
import java.util.regex.*;
import org.json.*;

/** Opt-in, local substitutions. A competing spelling permanently contests a candidate until removed. */
public final class DictationFixStore {
    private static final Object LOCK = new Object();
    private static final Pattern PHRASE = Pattern.compile("[\\p{L}\\p{N}]+(?:['’][\\p{L}\\p{N}]+)*(?: [\\p{L}\\p{N}]+(?:['’][\\p{L}\\p{N}]+)*){0,2}");
    private final SharedPreferences prefs;

    public DictationFixStore(Context context) {
        prefs = context.getSharedPreferences("hex_dictation_fixes", Context.MODE_PRIVATE);
    }
    public boolean isEnabled() { return prefs.getBoolean("enabled", false); }
    public void setEnabled(boolean enabled) {
        synchronized (LOCK) { prefs.edit().putBoolean("enabled", enabled).apply(); }
    }
    private static String phrase(String s) { return s == null ? "" : s.strip().replaceAll("[ \\t]+", " "); }
    private static String key(String s) { return phrase(s).replace('’', '\'').toLowerCase(Locale.ROOT); }
    private JSONObject records() {
        try { return new JSONObject(prefs.getString("records", "{}")); }
        catch (JSONException ignored) { return new JSONObject(); }
    }
    private void save(JSONObject records) { prefs.edit().putString("records", records.toString()).apply(); }

    public void recordSighting(String heard, String preferred) {
        String source = key(heard), target = phrase(preferred);
        if (source.length() > 128 || target.length() > 128 || !PHRASE.matcher(source).matches()
                || target.isEmpty() || phrase(heard).equals(target)) return;
        synchronized (LOCK) {
            if (!isEnabled()) return;
            JSONObject all = records(), row = all.optJSONObject(source);
            if (row == null) row = new JSONObject();
            try {
                String existing = row.optString("preferred", target);
                if (!key(existing).equals(key(target))) row.put("contested", true);
                else {
                    if (target.equals(target.toLowerCase(Locale.ROOT))) target = existing;
                    row.put("preferred", target).put("count", Math.min(2, row.optInt("count") + 1));
                }
                all.put(source, row);
                save(all);
            } catch (JSONException invalid) { throw new IllegalStateException(invalid); }
        }
    }
    public Map<String,String> activeFixes() {
        synchronized (LOCK) {
            if (!isEnabled()) return Collections.emptyMap();
            Map<String,String> result = new HashMap<>();
            JSONObject all = records();
            for (Iterator<String> it = all.keys(); it.hasNext();) {
                String source = it.next();
                JSONObject row = all.optJSONObject(source);
                if (row != null && row.optInt("count") >= 2 && !row.optBoolean("contested"))
                    result.put(source, row.optString("preferred"));
            }
            return result;
        }
    }
    public void removeFix(String heard) {
        synchronized (LOCK) { JSONObject all = records(); all.remove(key(heard)); save(all); }
    }
    public void clearAll() { synchronized (LOCK) { save(new JSONObject()); } }
    public String apply(String text, List<String> customVocab) { return applyRules(text, customVocab, activeFixes()); }

    static String applyRules(String raw, List<String> vocab, Map<String,String> fixes) {
        String text = CustomVocabulary.apply(raw, vocab);
        if (fixes == null || fixes.isEmpty() || text.isEmpty()) return text;
        Map<String,String> rules = new HashMap<>();
        fixes.forEach((source, target) -> {
            if (PHRASE.matcher(key(source)).matches() && target != null && !target.isBlank()) rules.put(key(source), target);
        });
        if (rules.isEmpty()) return text;
        BitSet protectedChars = new BitSet();
        if (vocab != null) for (String term : CustomVocabulary.sanitize(vocab)) {
            Matcher m = Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])" + Pattern.quote(term) + "(?![\\p{L}\\p{N}])").matcher(text);
            while (m.find()) protectedChars.set(m.start(), m.end());
        }
        List<String> sources = new ArrayList<>(rules.keySet());
        sources.sort(Comparator.comparingInt(String::length).reversed());
        List<String> alternatives = new ArrayList<>();
        for (String source : sources) {
            List<String> words = new ArrayList<>();
            for (String word : source.split(" ")) words.add(Pattern.quote(word).replace("'", "\\E['’]\\Q"));
            alternatives.add(String.join("[ \\t]+", words));
        }
        String joined = "-\\p{L}\\p{M}\\p{N}_/@#&+=~*\\\\";
        Matcher m = Pattern.compile("(?iu)(?<![\\p{L}\\p{N}]['’])(?<![" + joined + ".])(?:" + String.join("|", alternatives)
                + ")(?![" + joined + "]|\\.(?=[^\\s.\"”’')\\]])|['’](?!s\\b)[\\p{L}])").matcher(text);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            int protectedAt = protectedChars.nextSetBit(m.start());
            if (protectedAt >= 0 && protectedAt < m.end()) continue;
            String source = m.group(), target = rules.get(key(source));
            if (target == null) continue;
            if (source.codePoints().filter(Character::isLetter).count() >= 2 && source.equals(source.toUpperCase(Locale.ROOT)))
                target = target.toUpperCase(Locale.ROOT);
            else if (Character.isUpperCase(source.codePointAt(0)) && target.equals(target.toLowerCase(Locale.ROOT))) {
                int first = Character.charCount(target.codePointAt(0));
                target = target.substring(0, first).toUpperCase(Locale.ROOT) + target.substring(first);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(target));
        }
        return m.appendTail(out).toString();
    }
}
