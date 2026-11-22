package anomaly.hex.android.keyboard;

import java.util.*;

/**
 * QWERTY thumb-position autocorrect decoder for fat-thumb typing.
 * Decodes multi-key adjacent-key slips (short and long words, e.g. "tbks" -> "this",
 * "implwmentstion" -> "implementation"), fat-thumb grazed keys ("thiks" -> "this"),
 * and skipped keys ("envronment" -> "environment") across:
 *   1. The 30k English frequency dictionary
 *   2. Words currently visible on screen (editor + active app window)
 *   3. The user's continuously learned personal vocabulary, bigrams, and thumb-slip habits
 * Rejects distant spelling-error edits across the keyboard.
 */
public final class SpatialKeyboardDecoder {
  private static final float ADJACENT_RADIUS_SQ = 1.55f;
  static final int SCREEN_WORD_RANK = 25;
  static final int PERSONAL_WORD_BASE_RANK = 500;

  public record DecodeContext(
      String prevWordLower,
      Map<String, String> screenWords,
      PersonalAutocorrectStore personalStore) {
    public static final DecodeContext EMPTY = new DecodeContext(null, Map.of(), null);
  }

  private SpatialKeyboardDecoder() {}

  public static List<WordDictionary.Candidate> decode(WordDictionary dict, String lower, int limit) {
    return decode(dict, lower, limit, DecodeContext.EMPTY);
  }

  public static List<WordDictionary.Candidate> decode(
      WordDictionary dict, String lower, int limit, DecodeContext ctx) {
    if (lower == null || lower.length() < 3 || lower.length() > 24 || limit <= 0) {
      return List.of();
    }
    DecodeContext dc = ctx == null ? DecodeContext.EMPTY : ctx;
    Map<String, String> screen = dc.screenWords() == null ? Map.of() : dc.screenWords();
    PersonalAutocorrectStore store = dc.personalStore();
    Map<String, String> personal = store == null ? Map.of() : store.personalVocabularySnapshot();
    boolean hasDict = dict != null && dict.size() > 0;
    if (!hasDict && screen.isEmpty() && personal.isEmpty()) return List.of();

    int n = lower.length();
    int maxSlips = n <= 5 ? 2 : (n <= 9 ? 3 : 4);
    Map<String, WordDictionary.Candidate> best = new HashMap<>();

    // 1. Static English dictionary: same-length multi-key adjacent slips & transpositions
    if (hasDict) {
      List<String> sameLen = dict.wordsOfLength(n);
      for (int idx = 0; idx < sameLen.size(); idx++) {
        String w = sameLen.get(idx);
        int cost = spatialSameLengthCost(lower, w, maxSlips, store);
        if (cost > 0) {
          putBest(best, w, effectiveRank(dict.rankOf(w), w, screen, store), cost);
        }
      }

      // 2. Fat-thumb grazed extra key or double-tap deletion (e.g. "thiks" -> "this", "envirionment" -> "environment")
      for (int i = 0; i < n; i++) {
        char ch = lower.charAt(i);
        boolean dup = (i > 0 && ch == lower.charAt(i - 1)) || (i + 1 < n && ch == lower.charAt(i + 1));
        boolean grazed = dup
            || (i > 0 && WordDictionary.isAdjacentKey(ch, lower.charAt(i - 1)))
            || (i + 1 < n && WordDictionary.isAdjacentKey(ch, lower.charAt(i + 1)));
        if (!grazed) continue;
        String cand = lower.substring(0, i) + lower.substring(i + 1);
        int rank = dict.rankOf(cand);
        if (rank >= 0) {
          putBest(best, cand, effectiveRank(rank, cand, screen, store), dup ? 10 : 13);
        }
      }

      // 3. Single skipped key insertion (e.g. "ths" -> "this", "envronment" -> "environment")
      char[] ins = new char[n + 1];
      for (int i = 0; i <= n; i++) {
        for (int j = 0; j < i; j++) ins[j] = lower.charAt(j);
        for (int j = i; j < n; j++) ins[j + 1] = lower.charAt(j);
        for (char ch = 'a'; ch <= 'z'; ch++) {
          ins[i] = ch;
          String cand = new String(ins);
          int rank = dict.rankOf(cand);
          if (rank >= 0) {
            putBest(best, cand, effectiveRank(rank, cand, screen, store), 15);
          }
        }
      }
    }

    // 4. Dynamic words currently on screen (proper nouns, jargon, handles, replies)
    if (!screen.isEmpty()) {
      scanDynamicPool(lower, n, maxSlips, screen.keySet(), SCREEN_WORD_RANK, store, best);
    }

    // 5. User's learned personal vocabulary (all words typed >= 2 times)
    if (!personal.isEmpty()) {
      for (String w : personal.keySet()) {
        int count = store.wordCount(w);
        int rank = Math.max(40, PERSONAL_WORD_BASE_RANK - Math.min(460, count * 25));
        int cost = spatialDynamicCost(lower, w, n, maxSlips, store);
        if (cost > 0) {
          putBest(best, w, screen.containsKey(w) ? Math.min(rank, SCREEN_WORD_RANK) : rank, cost);
        }
      }
    }

    if (best.isEmpty()) return List.of();
    String prevLower = dc.prevWordLower();
    List<WordDictionary.Candidate> sorted = new ArrayList<>(best.values());
    sorted.sort((a, b) -> {
      int scoreA = totalScore(a, prevLower, screen, store);
      int scoreB = totalScore(b, prevLower, screen, store);
      int cmp = Integer.compare(scoreA, scoreB);
      return cmp != 0 ? cmp : Integer.compare(a.rank(), b.rank());
    });
    return sorted.size() > limit ? sorted.subList(0, limit) : sorted;
  }

  private static void scanDynamicPool(
      String lower, int n, int maxSlips, Collection<String> pool, int baseRank,
      PersonalAutocorrectStore store, Map<String, WordDictionary.Candidate> best) {
    for (String w : pool) {
      int cost = spatialDynamicCost(lower, w, n, maxSlips, store);
      if (cost > 0) {
        putBest(best, w, baseRank, cost);
      }
    }
  }

  /** Computes spatial cost between `lower` and a dynamic word (same-len, grazed +1 char, or skipped -1 char). */
  private static int spatialDynamicCost(
      String lower, String target, int n, int maxSlips, PersonalAutocorrectStore store) {
    int m = target.length();
    if (m < 3 || m > 24 || Math.abs(m - n) > 1 || lower.equals(target)) return -1;
    if (m == n) {
      return spatialSameLengthCost(lower, target, maxSlips, store);
    }
    if (m == n - 1) {
      // User grazed an extra adjacent/duplicate key while typing `target`
      int i = 0;
      while (i < m && lower.charAt(i) == target.charAt(i)) i++;
      // Check if deleting lower[i] matches target
      if (lower.regionMatches(i + 1, target, i, m - i)) {
        char ch = lower.charAt(i);
        boolean dup = (i > 0 && ch == lower.charAt(i - 1)) || (i + 1 < n && ch == lower.charAt(i + 1));
        boolean grazed = dup
            || (i > 0 && WordDictionary.isAdjacentKey(ch, lower.charAt(i - 1)))
            || (i + 1 < n && WordDictionary.isAdjacentKey(ch, lower.charAt(i + 1)));
        if (grazed) return dup ? 10 : 13;
      }
      return -1;
    }
    // m == n + 1: user skipped one key while typing `target`
    int i = 0;
    while (i < n && lower.charAt(i) == target.charAt(i)) i++;
    if (lower.regionMatches(i, target, i + 1, n - i)) {
      return 15;
    }
    return -1;
  }

  static int spatialSameLengthCost(String typed, String target, int maxSlips) {
    return spatialSameLengthCost(typed, target, maxSlips, null);
  }

  /**
   * Returns >0 spatial edit cost if `typed` and `target` (same length) differ only by
   * adjacent-key slips or adjacent transpositions within `maxSlips`; returns -1 otherwise.
   */
  static int spatialSameLengthCost(String typed, String target, int maxSlips, PersonalAutocorrectStore store) {
    int n = typed.length();
    int slips = 0;
    int cost = 0;
    for (int i = 0; i < n; i++) {
      char a = typed.charAt(i);
      char b = target.charAt(i);
      if (a == b) continue;
      // Adjacent character transposition (e.g. "hte" -> "the", "tpying" -> "typing")
      if (i + 1 < n && a == target.charAt(i + 1) && typed.charAt(i + 1) == b) {
        slips++;
        if (slips > maxSlips) return -1;
        cost += 9;
        i++;
        continue;
      }
      float dSq = WordDictionary.keyDistanceSq(a, b);
      if (dSq > ADJACENT_RADIUS_SQ) return -1;
      slips++;
      if (slips > maxSlips) return -1;
      int base = (int) Math.round(9.0 + dSq * 5.0);
      // Personal thumb-slip adaptation: if the user repeatedly slips `a -> b`, lower its cost.
      if (store != null) {
        int seen = store.slipCount(a, b);
        if (seen >= 2) {
          base = Math.max(6, base - Math.min(4, seen / 2));
        }
      }
      cost += base;
    }
    if (slips == 0) return -1;
    return cost + (slips > 1 ? 3 : 0);
  }

  private static int effectiveRank(
      int dictRank, String lower, Map<String, String> screen, PersonalAutocorrectStore store) {
    int rank = dictRank;
    if (screen.containsKey(lower)) {
      rank = Math.min(rank, SCREEN_WORD_RANK);
    }
    if (store != null) {
      int count = store.wordCount(lower);
      if (count >= 2) {
        int personalRank = Math.max(40, PERSONAL_WORD_BASE_RANK - Math.min(460, count * 25));
        rank = Math.min(rank, personalRank);
      }
    }
    return rank;
  }

  private static int totalScore(
      WordDictionary.Candidate c, String prevLower,
      Map<String, String> screen, PersonalAutocorrectStore store) {
    int score = c.cost() * 1000 + rankPenalty(c.rank());
    // Words literally on screen get a strong contextual prior
    if (screen.containsKey(c.word())) {
      score -= 3200;
    }
    // Continuous usage learning: personal word frequency + personal bigram context
    if (store != null) {
      int usage = store.wordCount(c.word());
      if (usage > 0) {
        score -= Math.min(6500, (int) Math.round(Math.log1p(usage) * 1800.0));
      }
      if (prevLower != null && !prevLower.isEmpty()) {
        int bg = store.bigramCount(prevLower, c.word());
        if (bg > 0) {
          score -= Math.min(7500, (int) Math.round(Math.log1p(bg) * 3200.0));
        }
      }
    }
    return score;
  }

  private static void putBest(Map<String, WordDictionary.Candidate> best, String word, int rank, int cost) {
    WordDictionary.Candidate prev = best.get(word);
    if (prev == null || cost < prev.cost() || (cost == prev.cost() && rank < prev.rank())) {
      best.put(word, new WordDictionary.Candidate(word, rank, cost));
    }
  }

  private static int rankPenalty(int rank) {
    if (rank < 150) return 0;
    if (rank < 600) return 2200;
    if (rank < 2000) return 4800;
    if (rank < 6000) return 7500;
    if (rank < 15000) return 10500;
    return 14000;
  }
}
