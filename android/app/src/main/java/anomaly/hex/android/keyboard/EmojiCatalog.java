package anomaly.hex.android.keyboard;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Paint;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Full Unicode 18.0 + CLDR 49 + Hex chat-alias emoji library and ranked multi-word search.
 * Loaded from assets/keyboard/emoji-index-en.txt (~1,900 emojis, 8 categories, skin tones,
 * weighted word & phrase postings) with Paint.hasGlyph() filtering on Android so no tofu renders.
 */
public final class EmojiCatalog {
    private static final String[] CATEGORY_NAMES = {
        "Smileys & people",
        "Animals & nature",
        "Food & drink",
        "Activity",
        "Travel & places",
        "Objects",
        "Symbols",
        "Flags"
    };

    private record Posting(int entryIdx, float weight) {}

    private static final class Index {
        final List<String> emojis;
        final Map<String, List<String>> categories;
        final Map<String, List<String>> tones;
        final Set<String> allValid;
        final Set<String> stopwords;
        final Map<String, List<Posting>> wordPostings;
        final List<String> sortedWords;
        final Map<String, List<Posting>> phrasePostings;
        final int longestPhrase;

        Index(List<String> emojis, Map<String, List<String>> categories, Map<String, List<String>> tones,
              Set<String> allValid, Set<String> stopwords, Map<String, List<Posting>> wordPostings,
              Map<String, List<Posting>> phrasePostings, int longestPhrase) {
            this.emojis = emojis;
            this.categories = categories;
            this.tones = tones;
            this.allValid = allValid;
            this.stopwords = stopwords;
            this.wordPostings = wordPostings;
            List<String> keys = new ArrayList<>(wordPostings.keySet());
            Collections.sort(keys);
            this.sortedWords = keys;
            this.phrasePostings = phrasePostings;
            this.longestPhrase = longestPhrase;
        }
    }

    private static volatile Index active = buildFallback();
    private static volatile boolean loadedFromAssets;
    private static final Object LOCK = new Object();

    private EmojiCatalog() {}

    /** Loads the full bundled emoji-index-en.txt once on a background thread. */
    public static void load(Context ctx) {
        if (loadedFromAssets || ctx == null) return;
        synchronized (LOCK) {
            if (loadedFromAssets) return;
            try (InputStream in = ctx.getApplicationContext().getAssets().open("keyboard/emoji-index-en.txt")) {
                active = parse(in, true);
                loadedFromAssets = true;
            } catch (Throwable ignored) {
                // Keep fallback index
            }
        }
    }

    /** Loads from a stream (used by JVM unit tests with filterGlyphs=false). */
    public static void load(InputStream in, boolean filterGlyphs) throws IOException {
        active = parse(in, filterGlyphs);
        loadedFromAssets = true;
    }

    public static int size() { return active.emojis.size(); }

    public static boolean isEmoji(String text) {
        return text != null && active.allValid.contains(text);
    }

    public static Map<String, List<String>> categories() { return active.categories; }

    public static List<String> tonesFor(String emoji) {
        return active.tones.getOrDefault(emoji, List.of());
    }

    /** Strong weight threshold matching iOS EmojiSuggester.strongWeight (one-word names & chat aliases). */
    public static final float STRONG_WEIGHT = 0.85f;

    /**
     * Returns strong emoji matches for a single word being typed ("smile" -> 😊, "heart" -> ❤️),
     * matching iOS EmojiSuggester.forWord. Empty for stopwords or weak keyword matches.
     */
    public static List<String> forWord(String word, int limit) {
        if (word == null || limit <= 0) return List.of();
        Index idx = active;
        String w = word.trim().toLowerCase(Locale.ROOT);
        if (w.length() < 2 || idx.stopwords.contains(w)) return List.of();
        List<String> forms = new ArrayList<>();
        forms.add(w);
        forms.addAll(stems(w));
        for (int f = 0; f < forms.size(); f++) {
            float factor = f == 0 ? 1.0f : 0.85f;
            List<Posting> hits = idx.wordPostings.get(forms.get(f));
            if (hits == null || hits.isEmpty()) continue;
            List<Posting> strong = new ArrayList<>();
            for (Posting p : hits) {
                if (p.weight * factor >= STRONG_WEIGHT) strong.add(p);
            }
            if (strong.isEmpty()) continue;
            strong.sort((a, b) -> {
                int cmp = Float.compare(b.weight, a.weight);
                return cmp != 0 ? cmp : Integer.compare(a.entryIdx, b.entryIdx);
            });
            List<String> out = new ArrayList<>(Math.min(limit, strong.size()));
            for (Posting p : strong) {
                String e = idx.emojis.get(p.entryIdx);
                if (!out.contains(e)) out.add(e);
                if (out.size() >= limit) break;
            }
            return out;
        }
        return List.of();
    }

    /** Exact strong emoji matches for a shortcode name ("fire", "heart", "sweat smile"). */
    public static List<String> exactMatches(String lookupName, int limit) {
        if (lookupName == null || lookupName.isBlank() || limit <= 0) return List.of();
        Index idx = active;
        String key = lookupName.trim().toLowerCase(Locale.ROOT);
        List<Posting> hits = key.contains(" ") ? idx.phrasePostings.get(key) : idx.wordPostings.get(key);
        if (hits == null || hits.isEmpty()) return List.of();
        List<Posting> strong = new ArrayList<>();
        for (Posting p : hits) {
            if (p.weight >= STRONG_WEIGHT) strong.add(p);
        }
        if (strong.isEmpty()) return List.of();
        strong.sort((a, b) -> {
            int cmp = Float.compare(b.weight, a.weight);
            return cmp != 0 ? cmp : Integer.compare(a.entryIdx, b.entryIdx);
        });
        List<String> out = new ArrayList<>(Math.min(limit, strong.size()));
        for (Posting p : strong) {
            String e = idx.emojis.get(p.entryIdx);
            if (!out.contains(e)) out.add(e);
            if (out.size() >= limit) break;
        }
        return out;
    }

    /** Multi-word & prefix ranked emoji search (Gboard / iOS EmojiLibrary parity). */
    public static List<String> search(String query) {
        if (query == null || query.isBlank()) return List.of();
        Index idx = active;
        List<String> terms = tokenize(query);
        if (terms.isEmpty()) return List.of();

        Map<Integer, Float> scores = new HashMap<>();
        Map<Integer, Integer> matchedTerms = new HashMap<>();

        // 1. Exact full phrase match
        if (terms.size() >= 2) {
            String fullPhrase = String.join(" ", terms);
            List<Posting> pHits = idx.phrasePostings.get(fullPhrase);
            if (pHits != null) {
                for (Posting p : pHits) {
                    scores.merge(p.entryIdx, p.weight * 3.0f, Float::sum);
                    matchedTerms.put(p.entryIdx, terms.size());
                }
            }
        }

        // 2. Per-term exact & prefix postings
        for (String term : terms) {
            Map<Integer, Float> termBest = new HashMap<>();
            List<Posting> exact = idx.wordPostings.get(term);
            if (exact != null) {
                for (Posting p : exact) termBest.merge(p.entryIdx, p.weight * 2.0f, Math::max);
            }
            // Also try singular/stemmed form for exact hit ("hearts" -> "heart", "dogs" -> "dog")
            for (String stem : stems(term)) {
                List<Posting> stemHits = idx.wordPostings.get(stem);
                if (stemHits != null) {
                    for (Posting p : stemHits) termBest.merge(p.entryIdx, p.weight * 1.7f, Math::max);
                }
            }
            // Prefix binary search over sortedWords
            int pos = Collections.binarySearch(idx.sortedWords, term);
            if (pos < 0) pos = -pos - 1;
            int scanned = 0;
            for (int i = pos; i < idx.sortedWords.size() && scanned < 64; i++) {
                String w = idx.sortedWords.get(i);
                if (!w.startsWith(term)) break;
                if (!w.equals(term)) {
                    float ratio = (float) term.length() / (float) w.length();
                    float scale = 0.85f + 0.35f * ratio;
                    for (Posting p : idx.wordPostings.get(w)) {
                        termBest.merge(p.entryIdx, p.weight * scale, Math::max);
                    }
                }
                scanned++;
            }
            for (var e : termBest.entrySet()) {
                scores.merge(e.getKey(), e.getValue(), Float::sum);
                matchedTerms.merge(e.getKey(), 1, Integer::sum);
            }
        }

        if (scores.isEmpty()) return List.of();
        int totalTerms = terms.size();
        List<Map.Entry<Integer, Float>> ranked = new ArrayList<>(scores.entrySet());
        ranked.sort((a, b) -> {
            float sa = a.getValue() * (totalTerms > 1 && matchedTerms.getOrDefault(a.getKey(), 0) >= totalTerms ? 2.2f : 1f);
            float sb = b.getValue() * (totalTerms > 1 && matchedTerms.getOrDefault(b.getKey(), 0) >= totalTerms ? 2.2f : 1f);
            int cmp = Float.compare(sb, sa);
            return cmp != 0 ? cmp : Integer.compare(a.getKey(), b.getKey());
        });

        int limit = Math.min(60, ranked.size());
        List<String> out = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) out.add(idx.emojis.get(ranked.get(i).getKey()));
        return List.copyOf(out);
    }

    /** Contextual emoji suggestion from recent text (phrases + weighted recent non-stopword tokens). */
    public static String suggestForContext(String recentText) {
        if (recentText == null || recentText.isBlank()) return null;
        Index idx = active;
        String tail = recentText.length() > 240 ? recentText.substring(recentText.length() - 240) : recentText;
        List<String> tokens = tokenize(tail);
        if (tokens.isEmpty()) return null;
        if (tokens.size() > 24) tokens = tokens.subList(tokens.size() - 24, tokens.size());

        Map<Integer, Float> total = new HashMap<>();
        Map<Integer, Float> strongest = new HashMap<>();

        // 1. Multi-word phrases (2..4 words) near the end of recent text ("good luck", "happy birthday")
        if (tokens.size() >= 2 && idx.longestPhrase >= 2) {
            int maxLen = Math.min(idx.longestPhrase, 4);
            int minEnd = Math.max(1, tokens.size() - 4);
            for (int end = tokens.size(); end >= minEnd; end--) {
                float recency = (float) Math.pow(0.85, tokens.size() - end);
                for (int len = 2; len <= maxLen && end - len >= 0; len++) {
                    String phrase = String.join(" ", tokens.subList(end - len, end));
                    List<Posting> hits = idx.phrasePostings.get(phrase);
                    if (hits != null) {
                        for (Posting p : hits) {
                            float val = p.weight * 1.25f * recency;
                            total.merge(p.entryIdx, val, Float::sum);
                            strongest.merge(p.entryIdx, val, Math::max);
                        }
                    }
                }
            }
        }

        // 2. Last non-stopword word(s), newest weighted highest
        int checked = 0;
        for (int i = tokens.size() - 1; i >= 0 && checked < 3; i--) {
            String tok = tokens.get(i);
            if (tok.length() < 2 || idx.stopwords.contains(tok)) continue;
            float recency = (float) Math.pow(0.80, checked);
            checked++;
            List<Posting> hits = idx.wordPostings.get(tok);
            if (hits == null) {
                for (String stem : stems(tok)) {
                    hits = idx.wordPostings.get(stem);
                    if (hits != null) break;
                }
            }
            if (hits != null) {
                for (Posting p : hits) {
                    float val = p.weight * recency;
                    total.merge(p.entryIdx, val, Float::sum);
                    strongest.merge(p.entryIdx, val, Math::max);
                }
            }
        }

        int bestIdx = -1;
        float bestScore = 0.25f; // minimum threshold so weak/noisy matches stay quiet
        for (var e : total.entrySet()) {
            int id = e.getKey();
            float s = strongest.getOrDefault(id, 0f);
            float combined = s + (e.getValue() - s) * 0.33f;
            if (combined > bestScore) {
                bestScore = combined;
                bestIdx = id;
            }
        }
        return bestIdx >= 0 ? idx.emojis.get(bestIdx) : null;
    }

    private static List<String> tokenize(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                cur.append(Character.toLowerCase(c));
            } else if (cur.length() > 0) {
                out.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private static List<String> stems(String word) {
        if (word.length() < 4) return List.of();
        List<String> out = new ArrayList<>(2);
        if (word.endsWith("ies") && word.length() > 4) out.add(word.substring(0, word.length() - 3) + "y");
        if (word.endsWith("es") && word.length() > 4) out.add(word.substring(0, word.length() - 2));
        if (word.endsWith("s") && !word.endsWith("ss")) out.add(word.substring(0, word.length() - 1));
        if (word.endsWith("ing") && word.length() > 5) {
            out.add(word.substring(0, word.length() - 3));
            out.add(word.substring(0, word.length() - 3) + "e");
        }
        return out;
    }

    private static Index parse(InputStream in, boolean filterGlyphs) throws IOException {
        Paint glyphPaint = filterGlyphs ? new Paint() : null;
        Set<String> stopwords = new HashSet<>();
        List<String> emojis = new ArrayList<>(2000);
        List<List<String>> catLists = new ArrayList<>(CATEGORY_NAMES.length);
        for (int i = 0; i < CATEGORY_NAMES.length; i++) catLists.add(new ArrayList<>());
        Map<String, List<String>> tonesMap = new HashMap<>();
        Set<String> allValid = new HashSet<>(4096);
        int[] remap = new int[4096];
        Arrays.fill(remap, -1);
        int rawEntryIdx = 0;
        Map<String, List<Posting>> wordPostings = new HashMap<>(4096);
        Map<String, List<Posting>> phrasePostings = new HashMap<>(2048);
        int longestPhrase = 0;

        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8), 32768)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                char kind = line.charAt(0);
                if (kind == 'S' && line.length() > 2 && line.charAt(1) == '\t') {
                    for (String w : line.substring(2).split(" ")) {
                        if (!w.isEmpty()) stopwords.add(w);
                    }
                } else if (kind == 'E' && line.length() > 2 && line.charAt(1) == '\t') {
                    String[] cols = line.split("\t", -1);
                    if (cols.length >= 3) {
                        if (rawEntryIdx >= remap.length) {
                            int oldLen = remap.length;
                            remap = Arrays.copyOf(remap, oldLen * 2);
                            Arrays.fill(remap, oldLen, remap.length, -1);
                        }
                        String emoji = cols[1];
                        int cat = Integer.parseInt(cols[2]);
                        boolean renderable = !filterGlyphs || canRender(glyphPaint, emoji);
                        if (renderable && cat >= 0 && cat < CATEGORY_NAMES.length) {
                            int mapped = emojis.size();
                            remap[rawEntryIdx] = mapped;
                            emojis.add(emoji);
                            catLists.get(cat).add(emoji);
                            allValid.add(emoji);
                            if (cols.length > 4 && !cols[4].isEmpty()) {
                                List<String> tList = new ArrayList<>();
                                for (String t : cols[4].split(" ")) {
                                    if (!t.isEmpty() && (!filterGlyphs || canRender(glyphPaint, t))) {
                                        tList.add(t);
                                        allValid.add(t);
                                    }
                                }
                                if (!tList.isEmpty()) tonesMap.put(emoji, List.copyOf(tList));
                            }
                        }
                        rawEntryIdx++;
                    }
                } else if ((kind == 'W' || kind == 'P') && line.length() > 2 && line.charAt(1) == '\t') {
                    int tab2 = line.indexOf('\t', 2);
                    if (tab2 > 2 && tab2 + 1 < line.length()) {
                        String key = line.substring(2, tab2);
                        String rawList = line.substring(tab2 + 1);
                        List<Posting> postings = parsePostings(rawList, remap);
                        if (!postings.isEmpty()) {
                            if (kind == 'W') {
                                wordPostings.put(key, postings);
                            } else {
                                phrasePostings.put(key, postings);
                                int spaces = 1;
                                for (int i = 0; i < key.length(); i++) if (key.charAt(i) == ' ') spaces++;
                                if (spaces > longestPhrase) longestPhrase = spaces;
                            }
                        }
                    }
                }
            }
        }

        Map<String, List<String>> categories = new LinkedHashMap<>();
        for (int i = 0; i < CATEGORY_NAMES.length; i++) {
            categories.put(CATEGORY_NAMES[i], List.copyOf(catLists.get(i)));
        }
        return new Index(emojis, Collections.unmodifiableMap(categories), tonesMap,
                allValid, stopwords, wordPostings, phrasePostings, longestPhrase);
    }

    private static boolean canRender(Paint paint, String emoji) {
        try {
            return paint.hasGlyph(emoji);
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static List<Posting> parsePostings(String raw, int[] remap) {
        List<Posting> out = new ArrayList<>();
        int start = 0;
        int len = raw.length();
        while (start < len) {
            int comma = raw.indexOf(',', start);
            int end = comma < 0 ? len : comma;
            int colon = raw.indexOf(':', start);
            if (colon > start && colon < end) {
                int rawId = parseIntFast(raw, start, colon);
                if (rawId >= 0 && rawId < remap.length && remap[rawId] >= 0) {
                    float w = Float.parseFloat(raw.substring(colon + 1, end));
                    out.add(new Posting(remap[rawId], w));
                }
            }
            if (comma < 0) break;
            start = comma + 1;
        }
        return out;
    }

    private static int parseIntFast(String s, int start, int end) {
        int val = 0;
        for (int i = start; i < end; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return -1;
            val = val * 10 + (c - '0');
        }
        return val;
    }

    /** Minimal fallback used only before assets finish loading or in contextless unit tests. */
    private static Index buildFallback() {
        String[][] groups = {
            {"Smileys & people", "😀:smile,happy,grin 😂:lol,laugh,tears 🤣:rofl,laugh 😊:blush,smile 🥰:love,adore 😍:heart,eyes 😘:kiss 😎:cool 🤔:think 🤗:hug 😭:cry 😡:angry 🥳:party 🙏:thanks,thank,please 👏:clap 👍:yes,like 👎:no 👋:hello,bye 💪:strong 🤞:luck"},
            {"Animals & nature", "🐶:dog,puppy 🐱:cat,kitten 🦊:fox 🐻:bear 🐼:panda 🦁:lion 🐸:frog 🦋:butterfly 🌹:rose 🔥:fire,hot 🌈:rainbow ☀️:sun"},
            {"Food & drink", "🍕:pizza 🍎:apple 🍔:burger 🍟:fries 🌮:taco 🍣:sushi 🍰:cake 🎂:birthday ☕:coffee 🍺:beer 🍷:wine"},
            {"Activity", "⚽:soccer,football 🏀:basketball 🎾:tennis 🎮:game 🎯:target 🏆:trophy,win 🎸:guitar 🎨:art"},
            {"Travel & places", "🚗:car 🚌:bus 🚲:bike ✈️:plane,flight 🚀:rocket 🏠:home ⛰️:mountain 🏖️:beach 🌍:earth"},
            {"Objects", "💡:idea,light 📱:phone 💻:laptop,computer ⌨️:keyboard 📷:camera 🔑:key 🔒:lock 📝:note 🎁:gift 🎈:balloon 🎉:celebrate,congratulations"},
            {"Symbols", "❤️:heart,love 💔:brokenheart 💯:hundred ✅:check,done ❌:cross ⚠️:warning ⭐:star ✨:sparkles 🎵:music"},
            {"Flags", "🏁:finish 🚩:flag 🏳️‍🌈:pride 🇺🇸:usa,america 🇬🇧:uk 🇨🇦:canada 🇯🇵:japan"}
        };
        List<String> emojis = new ArrayList<>();
        Map<String, List<String>> cats = new LinkedHashMap<>();
        Set<String> valid = new HashSet<>();
        Map<String, List<Posting>> wp = new HashMap<>();
        for (String[] g : groups) {
            List<String> list = new ArrayList<>();
            for (String entry : g[1].split(" ")) {
                String[] pair = entry.split(":");
                int idx = emojis.size();
                emojis.add(pair[0]);
                list.add(pair[0]);
                valid.add(pair[0]);
                for (String w : pair[1].split(",")) {
                    wp.computeIfAbsent(w, k -> new ArrayList<>()).add(new Posting(idx, 1.0f));
                }
            }
            cats.put(g[0], List.copyOf(list));
        }
        return new Index(emojis, Collections.unmodifiableMap(cats), Map.of(), valid,
                Set.of("the", "and", "for", "with", "this", "that"), wp, Map.of(), 0);
    }

    /** Ranked by use count; a fresh use wins ties. Only emoji selections are persisted. */
    public static final class FrequentlyUsed {
        private final SharedPreferences prefs;
        public FrequentlyUsed(Context context) { prefs = context.getSharedPreferences("hex_emoji_usage", Context.MODE_PRIVATE); }
        private record Entry(String emoji, int count) {}
        private List<Entry> entries() {
            List<Entry> result = new ArrayList<>();
            for (String item : prefs.getString("ranked", "").split(" ")) {
                String[] pair = item.split(":", 2);
                if (pair.length == 2) try { result.add(new Entry(pair[0], Integer.parseInt(pair[1]))); } catch (NumberFormatException ignored) {}
            }
            return result;
        }
        public void record(String emoji) {
            if (!active.allValid.contains(emoji)) return;
            synchronized (prefs) {
                List<Entry> entries = entries();
                int count = 1;
                for (Entry e : entries) if (e.emoji.equals(emoji)) { count = e.count == Integer.MAX_VALUE ? e.count : e.count + 1; break; }
                entries.removeIf(e -> e.emoji.equals(emoji));
                entries.add(0, new Entry(emoji, count));
                entries.sort((a, b) -> Integer.compare(b.count, a.count));
                StringBuilder value = new StringBuilder();
                for (Entry e : entries.subList(0, Math.min(120, entries.size()))) value.append(e.emoji).append(':').append(e.count).append(' ');
                prefs.edit().putString("ranked", value.toString()).apply();
            }
        }
        public List<String> list() {
            List<String> result = new ArrayList<>();
            for (Entry e : entries()) result.add(e.emoji);
            return List.copyOf(result);
        }
    }
}
