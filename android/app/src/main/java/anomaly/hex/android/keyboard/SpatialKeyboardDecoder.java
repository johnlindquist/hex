package anomaly.hex.android.keyboard;

import java.util.*;

/**
 * QWERTY thumb-position autocorrect decoder for fat-thumb typing.
 * Decodes multi-key adjacent-key slips (e.g. "tbks" -> "this", "wjaf" -> "what", "wpyld" -> "would")
 * and fat-thumb grazed keys ("thiks" -> "this") purely by physical key proximity + word frequency,
 * rejecting distant spelling-error edits across the keyboard.
 */
public final class SpatialKeyboardDecoder {
  private static final float ADJACENT_RADIUS_SQ = 1.55f;

  private SpatialKeyboardDecoder() {}

  public static List<WordDictionary.Candidate> decode(WordDictionary dict, String lower, int limit) {
    if (dict == null || lower == null || lower.length() < 3 || lower.length() > 16 || dict.size() == 0) {
      return List.of();
    }
    int n = lower.length();
    Map<String, WordDictionary.Candidate> best = new HashMap<>();

    // 1. Same-length multi-key adjacent thumb slips & adjacent transpositions
    int maxSlips = n <= 5 ? 2 : Math.min(3, n / 2);
    List<String> sameLen = dict.wordsOfLength(n);
    for (int idx = 0; idx < sameLen.size(); idx++) {
      String w = sameLen.get(idx);
      int cost = spatialSameLengthCost(lower, w, maxSlips);
      if (cost > 0) {
        int rank = dict.rankOf(w);
        putBest(best, w, rank, cost);
      }
    }

    // 2. Fat-thumb grazed extra key or double-tap deletion (e.g. "thiks" -> "this", "tthis" -> "this")
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
        putBest(best, cand, rank, dup ? 10 : 13);
      }
    }

    // 3. Single skipped key insertion (e.g. "ths" -> "this", "keboard" -> "keyboard")
    char[] ins = new char[n + 1];
    for (int i = 0; i <= n; i++) {
      for (int j = 0; j < i; j++) ins[j] = lower.charAt(j);
      for (int j = i; j < n; j++) ins[j + 1] = lower.charAt(j);
      for (char ch = 'a'; ch <= 'z'; ch++) {
        ins[i] = ch;
        String cand = new String(ins);
        int rank = dict.rankOf(cand);
        if (rank >= 0) {
          putBest(best, cand, rank, 15);
        }
      }
    }

    if (best.isEmpty()) return List.of();
    List<WordDictionary.Candidate> sorted = new ArrayList<>(best.values());
    sorted.sort((a, b) -> {
      int scoreA = a.cost() * 1000 + rankPenalty(a.rank());
      int scoreB = b.cost() * 1000 + rankPenalty(b.rank());
      int cmp = Integer.compare(scoreA, scoreB);
      return cmp != 0 ? cmp : Integer.compare(a.rank(), b.rank());
    });
    return sorted.size() > limit ? sorted.subList(0, limit) : sorted;
  }

  /**
   * Returns >0 spatial edit cost if `typed` and `target` (same length) differ only by
   * adjacent-key slips or adjacent transpositions within `maxSlips`; returns -1 otherwise.
   */
  static int spatialSameLengthCost(String typed, String target, int maxSlips) {
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
      cost += (int) Math.round(9.0 + dSq * 5.0);
    }
    if (slips == 0) return -1;
    return cost + (slips > 1 ? 3 : 0);
  }

  private static void putBest(Map<String, WordDictionary.Candidate> best, String word, int rank, int cost) {
    WordDictionary.Candidate prev = best.get(word);
    if (prev == null || cost < prev.cost()) {
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
