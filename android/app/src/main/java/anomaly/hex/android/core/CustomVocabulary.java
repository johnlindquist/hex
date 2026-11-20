package anomaly.hex.android.core;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;
import java.util.regex.*;
import org.json.*;

public final class CustomVocabulary {
    public static final int MAX_TERMS = 100;
    private static final Object LOCK = new Object();
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+(?:['’]+[\\p{L}\\p{N}]+)*");
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]");
    private static final Pattern COMPOUND = Pattern.compile("[\\s\\p{N}-]|\\p{Ll}\\p{Lu}");
    private final SharedPreferences prefs;
    public CustomVocabulary(Context ctx) { prefs = ctx.getSharedPreferences("hex_vocabulary", Context.MODE_PRIVATE); }
    static String key(String s) { return NON_WORD.matcher(s.toLowerCase(Locale.ROOT)).replaceAll(""); }
    static List<String> sanitize(List<String> terms) {
        Set<String> seen = new HashSet<>();
        List<String> out = new ArrayList<>();
        for (String raw : terms) {
            if (raw == null) continue;
            String t = raw.strip();
            if (t.codePointCount(0, t.length()) > 64) continue;
            String k = key(t);
            if (k.codePointCount(0,k.length()) >= 2 && seen.add(k)) out.add(t);
            if (out.size() == MAX_TERMS) break;
        }
        return out;
    }
    public synchronized List<String> terms() {
        List<String> out = new ArrayList<>();
        try {
            JSONArray saved = new JSONArray(prefs.getString("terms", "[]"));
            for (int i = 0; i < saved.length(); i++) out.add(saved.getString(i));
        } catch (JSONException ignored) { return Collections.emptyList(); }
        return sanitize(out);
    }
    public synchronized boolean addTerm(String term) {
        synchronized (LOCK) {
            List<String> list = new ArrayList<>(terms());
            int old = list.size(); list.add(term); list = sanitize(list);
            if (list.size() == old) return false;
            replaceAll(list); return true;
        }
    }
    public synchronized boolean removeTerm(String term) {
        synchronized (LOCK) {
            List<String> list = new ArrayList<>(terms());
            if (term == null || !list.removeIf(t -> key(t).equals(key(term)))) return false;
            replaceAll(list); return true;
        }
    }
    public synchronized void replaceAll(List<String> terms) {
        synchronized (LOCK) { prefs.edit().putString("terms", new JSONArray(sanitize(terms)).toString()).apply(); }
    }
    private record Word(int start, int end, String text) {}
    private static boolean apostrophe(String t) { return t.contains("'") || t.contains("’"); }
    public static String apply(String text, List<String> terms) {
        if (text.isEmpty() || terms == null || terms.isEmpty()) return text;
        Map<String,String> glued = new HashMap<>(), phrases = new HashMap<>();
        for (String term : sanitize(terms)) {
            glued.put(key(term), term);
            List<String> parts = new ArrayList<>();
            for (String s : term.split("[\\s-]+")) if (!key(s).isEmpty()) parts.add(key(s));
            if (parts.size()>1) phrases.put(String.join(" ",parts),term);
        }
        List<Word> words = new ArrayList<>();
        Matcher m = WORD.matcher(text);
        while (m.find()) words.add(new Word(m.start(), m.end(), m.group()));
        StringBuilder out = new StringBuilder(); int copied = 0;
        for (int i=0; i<words.size(); i++) {
            outer: for (int end=Math.min(i+4,words.size()); end>i; end--) {
                List<String> parts = new ArrayList<>();
                boolean shortWord=false, priorApostrophe=false;
                for (int j=i; j<end; j++) {
                    Word word=words.get(j);
                    if (j>i && !text.substring(words.get(j-1).end,word.start).matches("-|[ \\t]+")) continue outer;
                    String k=key(word.text); parts.add(k);
                    shortWord |= k.codePointCount(0,k.length())<2;
                    if (j+1<end) priorApostrophe |= apostrophe(word.text);
                }
                Word last=words.get(end-1);
                String suffix=last.text.endsWith("'s") || last.text.endsWith("’s") ? last.text.substring(last.text.length()-2) : "";
                for (boolean glue : new boolean[]{false,true}) {
                    if (glue && end-i>1 && shortWord) continue;
                    for (int stem=0; stem<=(suffix.isEmpty()?0:1); stem++) {
                        String tail=stem==0?last.text:last.text.substring(0,last.text.length()-2);
                        parts.set(parts.size()-1,key(tail));
                        String term=(glue?glued:phrases).get(String.join(glue?"":" ",parts));
                        if (term==null || ((priorApostrophe || apostrophe(tail)) && !apostrophe(term))) continue;
                        if (glue && end-i>1 && !COMPOUND.matcher(term).find()) continue;
                        out.append(text,copied,words.get(i).start).append(term);
                        if (stem==1) out.append(suffix);
                        copied=last.end; i=end-1; break outer;
                    }
                }
            }
        }
        return out.append(text,copied,text.length()).toString();
    }
}
