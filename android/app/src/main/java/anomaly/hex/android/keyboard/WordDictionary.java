package anomaly.hex.android.keyboard;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Fast offline frequency dictionary with QWERTY spatial-proximity 1-edit lookup (<0.2ms). */
public final class WordDictionary {
    public record Candidate(String word, int rank, int cost) {}

    private static volatile WordDictionary sharedEn;
    private static final Object LOCK = new Object();
    private static final float[] KEY_X = new float[26];
    private static final float[] KEY_Y = new float[26];

    static {
        String[] rows = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};
        float[] offsets = {0f, 0.5f, 1.5f};
        for (int r = 0; r < rows.length; r++) {
            for (int c = 0; c < rows[r].length(); c++) {
                int idx = rows[r].charAt(c) - 'a';
                KEY_X[idx] = c + offsets[r];
                KEY_Y[idx] = r;
            }
        }
    }

    private final HashMap<String, Integer> ranks;
    private final List<String> words;
    private final Map<Integer, List<String>> wordsByLength;

    public WordDictionary(List<String> frequencyOrderedWords) {
        HashMap<String, Integer> map = new HashMap<>(frequencyOrderedWords.size() * 2);
        List<String> list = new ArrayList<>(frequencyOrderedWords.size());
        Map<Integer, List<String>> byLen = new HashMap<>();
        for (int i = 0; i < frequencyOrderedWords.size(); i++) {
            String w = frequencyOrderedWords.get(i).trim().toLowerCase(Locale.ROOT);
            if (!w.isEmpty() && !map.containsKey(w)) {
                map.put(w, list.size());
                list.add(w);
                if (w.length() >= 2 && w.length() <= 20) {
                    byLen.computeIfAbsent(w.length(), k -> new ArrayList<>()).add(w);
                }
            }
        }
        this.ranks = map;
        this.words = list;
        this.wordsByLength = byLen;
    }

    public static WordDictionary loadEnglish(Context ctx) {
        WordDictionary d = sharedEn;
        if (d != null) return d;
        synchronized (LOCK) {
            if (sharedEn != null) return sharedEn;
            try (InputStream in = ctx.getApplicationContext().getAssets().open("keyboard/en_freq.txt")) {
                sharedEn = load(in);
            } catch (IOException e) {
                sharedEn = new WordDictionary(List.of());
            }
            return sharedEn;
        }
    }

    public static WordDictionary load(InputStream in) throws IOException {
        List<String> list = new ArrayList<>(30050);
        try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.isEmpty()) list.add(line);
            }
        }
        return new WordDictionary(list);
    }

    public boolean contains(String lower) { return ranks.containsKey(lower); }
    public int rankOf(String lower) { Integer r = ranks.get(lower); return r == null ? -1 : r; }
    public int size() { return words.size(); }
    public List<String> wordsOfLength(int length) {
        List<String> bucket = wordsByLength.get(length);
        return bucket == null ? List.of() : bucket;
    }

    private static final String[] COMMON_TWO_LETTER = {
        "to", "of", "in", "it", "is", "be", "as", "at", "so", "we",
        "he", "by", "or", "on", "do", "if", "me", "my", "up", "an",
        "go", "no", "us", "am"
    };

    /** Fixes 2-letter non-words one transposition or adjacent key away from a common 2-letter word ("fo"->"of", "tk"->"to"). */
    public String bestTwoLetterFix(String lower) {
        if (lower == null || lower.length() != 2 || contains(lower)) return null;
        char a = lower.charAt(0), b = lower.charAt(1);
        // 1. Prefer exact transposition ("ot" -> "to", "fo" -> "of", "si" -> "is")
        for (String w : COMMON_TWO_LETTER) {
            if (a == w.charAt(1) && b == w.charAt(0)) return w;
        }
        // 2. Single adjacent-key substitution ("tk" -> "to")
        for (String w : COMMON_TWO_LETTER) {
            char wa = w.charAt(0), wb = w.charAt(1);
            if ((a == wa && isAdjacentKey(b, wb)) || (b == wb && isAdjacentKey(a, wa))) return w;
        }
        return null;
    }

    /** Splits a missed-space compound ("upto"->"up to", "nevermind"->"never mind", "alot"->"a lot") into two common words. */
    public String splitMissedSpace(String lower) {
        if (lower == null || lower.length() < 4 || lower.length() > 18 || contains(lower) || ranks.isEmpty()) return null;
        String best = null;
        int bestScore = Integer.MAX_VALUE;
        for (int i = 1; i < lower.length(); i++) {
            String left = lower.substring(0, i);
            String right = lower.substring(i);
            int rl = splitWordRank(left);
            int rr = splitWordRank(right);
            if (rl < 0 || rr < 0) continue;
            // Both halves must be high-frequency words (top 4000) so obscure splits never fire.
            if (rl > 4000 || rr > 4000) continue;
            int score = rl + rr;
            if (score < bestScore) {
                bestScore = score;
                best = left + " " + right;
            }
        }
        return best;
    }

    private int splitWordRank(String part) {
        if (part.length() == 1) return ("a".equals(part) || "i".equals(part)) ? 10 : -1;
        return rankOf(part);
    }

    /** Returns up to `limit` best 1-edit candidates ordered by (cost, frequencyRank). */
    public List<Candidate> bestEdits(String lower, int limit) {
        if (lower == null || lower.length() < 3 || ranks.isEmpty()) return List.of();
        Map<String, Candidate> best = new HashMap<>();
        int n = lower.length();

        // 1. Adjacent transpositions (cost 10) — e.g. "tpying" -> "typing", "wroking" -> "working"
        char[] buf = lower.toCharArray();
        for (int i = 0; i < n - 1; i++) {
            if (buf[i] == buf[i + 1]) continue;
            char tmp = buf[i]; buf[i] = buf[i + 1]; buf[i + 1] = tmp;
            consider(best, new String(buf), 10);
            buf[i + 1] = buf[i]; buf[i] = tmp;
        }

        // 2. Single deletions (cost 10 for duplicate char like "helllo"->"hello", else 14)
        for (int i = 0; i < n; i++) {
            boolean dup = (i > 0 && lower.charAt(i) == lower.charAt(i - 1))
                    || (i + 1 < n && lower.charAt(i) == lower.charAt(i + 1));
            String cand = lower.substring(0, i) + lower.substring(i + 1);
            consider(best, cand, dup ? 10 : 14);
        }

        // 3. Single substitutions (cost 11 for adjacent QWERTY key like "reolacements"->"replacements", else 22)
        for (int i = 0; i < n; i++) {
            char orig = lower.charAt(i);
            for (char ch = 'a'; ch <= 'z'; ch++) {
                if (ch == orig) continue;
                buf[i] = ch;
                String cand = new String(buf);
                Integer rank = ranks.get(cand);
                if (rank != null) {
                    int cost = isAdjacentKey(orig, ch) ? 11 : 22;
                    putBest(best, cand, rank, cost);
                }
            }
            buf[i] = orig;
        }

        // 4. Single insertions (cost 13 — e.g. "keboard"->"keyboard", "mesage"->"message")
        char[] ins = new char[n + 1];
        for (int i = 0; i <= n; i++) {
            for (int j = 0; j < i; j++) ins[j] = lower.charAt(j);
            for (int j = i; j < n; j++) ins[j + 1] = lower.charAt(j);
            for (char ch = 'a'; ch <= 'z'; ch++) {
                ins[i] = ch;
                consider(best, new String(ins), 13);
            }
        }

        if (best.isEmpty()) return List.of();
        List<Candidate> sorted = new ArrayList<>(best.values());
        sorted.sort((a, b) -> {
            // Combine spatial edit cost with log-rank preference so very common words win cleanly
            int scoreA = a.cost * 1000 + rankPenalty(a.rank);
            int scoreB = b.cost * 1000 + rankPenalty(b.rank);
            int cmp = Integer.compare(scoreA, scoreB);
            return cmp != 0 ? cmp : Integer.compare(a.rank, b.rank);
        });
        return sorted.size() > limit ? sorted.subList(0, limit) : sorted;
    }

    /** Returns up to `limit` high-frequency prefix completions for `lower` (length >= 2). */
    public List<String> prefixCompletions(String lower, int limit) {
        if (lower == null || lower.length() < 2 || words.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(limit);
        int cap = Math.min(words.size(), 8000);
        for (int i = 0; i < cap && out.size() < limit; i++) {
            String w = words.get(i);
            if (w.length() > lower.length() && w.startsWith(lower)) out.add(w);
        }
        return out;
    }

    private void consider(Map<String, Candidate> best, String cand, int cost) {
        Integer rank = ranks.get(cand);
        if (rank != null) putBest(best, cand, rank, cost);
    }

    private static void putBest(Map<String, Candidate> best, String cand, int rank, int cost) {
        Candidate prev = best.get(cand);
        if (prev == null || cost < prev.cost) best.put(cand, new Candidate(cand, rank, cost));
    }

    private static int rankPenalty(int rank) {
        if (rank < 200) return 0;
        if (rank < 1000) return 1500;
        if (rank < 4000) return 3500;
        if (rank < 12000) return 6000;
        return 9500;
    }

    public static float keyDistanceSq(char a, char b) {
        if (a < 'a' || a > 'z' || b < 'a' || b > 'z') return Float.MAX_VALUE;
        float dx = KEY_X[a - 'a'] - KEY_X[b - 'a'];
        float dy = KEY_Y[a - 'a'] - KEY_Y[b - 'a'];
        return dx * dx + dy * dy;
    }

    public static boolean isAdjacentKey(char a, char b) {
        return keyDistanceSq(a, b) <= 1.55f;
    }
}
