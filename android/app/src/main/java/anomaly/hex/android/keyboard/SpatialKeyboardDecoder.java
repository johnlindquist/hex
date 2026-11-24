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

  /**
   * Detects when a bottom-row key directly above the spacebar (c, v, b, n, m) was accidentally
   * hit instead of the spacebar between two valid words (e.g. "wantbto" -> "want to",
   * "inmthe" -> "in the", "gotvthere" -> "got there").
   */
  public static String splitSpacebarMiss(WordDictionary dict, String lower, DecodeContext ctx) {
    if (dict == null || lower == null || lower.length() < 4 || lower.length() > 22) return null;
    if (dict.contains(lower)) return null;
    DecodeContext dc = ctx == null ? DecodeContext.EMPTY : ctx;
    Map<String, String> screen = dc.screenWords() == null ? Map.of() : dc.screenWords();
    PersonalAutocorrectStore store = dc.personalStore();
    if (screen.containsKey(lower) || (store != null && store.isPersonalWord(lower))) return null;

    String best = null;
    int bestScore = Integer.MAX_VALUE;
    int n = lower.length();
    for (int i = 1; i < n - 1; i++) {
      char mid = lower.charAt(i);
      if (mid != 'c' && mid != 'v' && mid != 'b' && mid != 'n' && mid != 'm') continue;
      String left = lower.substring(0, i);
      String right = lower.substring(i + 1);
      int rLeft = splitWordRank(dict, left, screen, store);
      if (rLeft < 0) continue;
      int rRight = splitWordRank(dict, right, screen, store);
      if (rRight < 0) continue;
      // Avoid splitting very short obscure fragments unless both are very common words
      if ((left.length() == 1 || right.length() == 1) && (rLeft > 1200 || rRight > 1200)) continue;
      int score = rLeft + rRight;
      // Slight preference for b/v/n which sit dead-center above the spacebar
      if (mid == 'c' || mid == 'm') score += 400;
      if (score < bestScore) {
        bestScore = score;
        String leftOut = left.equals("i") ? "I" : left;
        String rightOut = right.equals("i") ? "I" : right;
        best = leftOut + " " + rightOut;
      }
    }
    return best;
  }

  /**
   * Returns ranked two-word split candidates for a bottom-row spacebar miss (c/v/b/n/m),
   * optionally allowing spatial QWERTY decoding of each half (e.g. "hisbwekk" -> "how well", "his well").
   */
  public static List<String> splitSpacebarMissCandidates(
      WordDictionary dict, String lower, DecodeContext ctx, String nextWordLower, int limit) {
    if (dict == null || lower == null || lower.length() < 4 || lower.length() > 20
        || limit <= 0 || dict.contains(lower)) {
      return List.of();
    }
    DecodeContext dc = ctx == null ? DecodeContext.EMPTY : ctx;
    Map<String, String> screen = dc.screenWords() == null ? Map.of() : dc.screenWords();
    PersonalAutocorrectStore store = dc.personalStore();
    if (screen.containsKey(lower) || (store != null && store.isPersonalWord(lower))) {
      return List.of();
    }

    String prevLower = dc.prevWordLower();
    DecodeContext leftCtx = new DecodeContext(prevLower, screen, store);
    DecodeContext rightCtx = new DecodeContext(null, screen, store);
    Map<String, Integer> scored = new HashMap<>();
    int n = lower.length();

    for (int i = 1; i < n - 1; i++) {
      char mid = lower.charAt(i);
      if (mid != 'c' && mid != 'v' && mid != 'b' && mid != 'n' && mid != 'm') continue;
      String left = lower.substring(0, i);
      String right = lower.substring(i + 1);

      List<WordDictionary.Candidate> leftCands = splitHalfCandidates(dict, left, screen, store, leftCtx);
      if (leftCands.isEmpty()) continue;
      List<WordDictionary.Candidate> rightCands = splitHalfCandidates(dict, right, screen, store, rightCtx);
      if (rightCands.isEmpty()) continue;

      int midPenalty = (mid == 'c' || mid == 'm') ? 2000 : 0;
      for (WordDictionary.Candidate lc : leftCands) {
        for (WordDictionary.Candidate rc : rightCands) {
          if ((left.length() == 1 || right.length() == 1) && (lc.rank() > 1200 || rc.rank() > 1200)) continue;
          if (lc.cost() > 0 && rc.cost() > 0 && lc.cost() + rc.cost() > 75) continue;

          int score = (lc.cost() + rc.cost()) * 1000
              + rankPenalty(lc.rank())
              + rankPenalty(rc.rank())
              + midPenalty;
          if (CommonBigrams.isCommon(lc.word(), rc.word())) score -= 28000;
          if (CommonBigrams.isCommon(prevLower, lc.word())) score -= 22000;
          if (CommonBigrams.isCommon(rc.word(), nextWordLower)) score -= 12000;
          if (store != null) {
            int bgMid = store.bigramCount(lc.word(), rc.word());
            if (bgMid > 0) score -= Math.min(18000, (int) Math.round(Math.log1p(bgMid) * 6000.0));
            if (prevLower != null && !prevLower.isEmpty()) {
              int bgPrev = store.bigramCount(prevLower, lc.word());
              if (bgPrev > 0) score -= Math.min(12000, (int) Math.round(Math.log1p(bgPrev) * 4500.0));
            }
          }

          String leftOut = lc.word().equals("i") ? "I" : lc.word();
          String rightOut = rc.word().equals("i") ? "I" : rc.word();
          String phrase = leftOut + " " + rightOut;
          Integer existing = scored.get(phrase);
          if (existing == null || score < existing) {
            scored.put(phrase, score);
          }
        }
      }
    }

    if (scored.isEmpty()) return List.of();
    List<Map.Entry<String, Integer>> entries = new ArrayList<>(scored.entrySet());
    entries.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
        .thenComparing(Map.Entry::getKey));
    List<String> out = new ArrayList<>(Math.min(limit, entries.size()));
    for (int i = 0; i < entries.size() && out.size() < limit; i++) {
      out.add(entries.get(i).getKey());
    }
    return out;
  }

  private static List<WordDictionary.Candidate> splitHalfCandidates(
      WordDictionary dict, String part, Map<String, String> screen,
      PersonalAutocorrectStore store, DecodeContext halfCtx) {
    List<WordDictionary.Candidate> out = new ArrayList<>(3);
    int exactRank = splitWordRank(dict, part, screen, store);
    if (exactRank >= 0) {
      out.add(new WordDictionary.Candidate(part, exactRank, 0));
    }
    if (part.length() == 2) {
      List<WordDictionary.Candidate> twoCands = dict.twoLetterCandidates(part);
      int added = 0;
      for (WordDictionary.Candidate c : twoCands) {
        out.add(c);
        if (++added >= 4) break;
      }
    } else if (part.length() >= 3) {
      List<WordDictionary.Candidate> decoded = decode(dict, part, 8, halfCtx);
      int added = 0;
      for (WordDictionary.Candidate c : decoded) {
        if (c.word().equals(part) || c.rank() >= 4000) continue;
        out.add(c);
        if (++added >= 5) break;
      }
    }
    return out;
  }

  private static int splitWordRank(
      WordDictionary dict, String w, Map<String, String> screen, PersonalAutocorrectStore store) {
    if (w.equals("i") || w.equals("a")) return 5;
    if (w.length() < 2) return -1;
    if (screen.containsKey(w)) return 25;
    if (store != null && store.isPersonalWord(w)) return 150;
    int r = dict.rankOf(w);
    if (r >= 0 && r < 10000) return r;
    return -1;
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
    if (CommonBigrams.isCommon(prevLower, c.word())) {
      score -= 8000;
    }
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
