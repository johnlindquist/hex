package anomaly.hex.android.keyboard;

import java.util.*;
import java.util.regex.*;

/**
 * Extracts safe single-word typo/spelling fix pairs from a screen-aware Quick Fix
 * so PersonalAutocorrectStore can suggest and learn them for future typing.
 */
public final class TypoFixExtractor {
  public record FixPair(String from, String to) {}

  private static final Pattern WORD_TOKEN = Pattern.compile("[\\p{L}][\\p{L}'’]*");
  private static final int MAX_PAIRS = 12;

  private TypoFixExtractor() {}

  public static List<FixPair> extract(String original, String fixed, List<FixPair> modelPairs) {
    if (original == null || fixed == null || original.isBlank() || fixed.isBlank()) {
      return List.of();
    }
    List<String> origTokens = tokenize(original);
    List<String> fixedTokens = tokenize(fixed);
    if (origTokens.isEmpty() || fixedTokens.isEmpty()) return List.of();

    Set<String> origLowerSet = new HashSet<>();
    for (String t : origTokens) origLowerSet.add(t.toLowerCase(Locale.ROOT));
    Set<String> fixedLowerSet = new HashSet<>();
    for (String t : fixedTokens) fixedLowerSet.add(t.toLowerCase(Locale.ROOT));

    LinkedHashMap<String, FixPair> out = new LinkedHashMap<>();

    // 1. Validate model-reported pairs against actual original & fixed text.
    if (modelPairs != null) {
      for (FixPair pair : modelPairs) {
        if (out.size() >= MAX_PAIRS) break;
        FixPair clean = sanitizePair(pair.from(), pair.to());
        if (clean == null) continue;
        String fromLower = clean.from().toLowerCase(Locale.ROOT);
        String toLower = clean.to().toLowerCase(Locale.ROOT);
        if (!origLowerSet.contains(fromLower) || !fixedLowerSet.contains(toLower)) continue;
        out.putIfAbsent(fromLower, clean);
      }
    }

    // 2. Deterministic word-alignment diff when token counts match (catches typos the model omitted).
    if (origTokens.size() == fixedTokens.size()) {
      for (int i = 0; i < origTokens.size() && out.size() < MAX_PAIRS; i++) {
        collectAlignedPair(origTokens.get(i), fixedTokens.get(i), out);
      }
    } else {
      // Also align matching prefix/suffix positions when a spacebar-miss split changed the token count
      // (e.g. "Bow do you want to sknthkd" -> "How do you want to do this" still extracts "bow" -> "how").
      int minLen = Math.min(origTokens.size(), fixedTokens.size());
      int prefix = 0;
      while (prefix < minLen && out.size() < MAX_PAIRS) {
        String a = origTokens.get(prefix);
        String b = fixedTokens.get(prefix);
        if (a.equalsIgnoreCase(b)) {
          prefix++;
        } else if (prefix + 1 < minLen
            && origTokens.get(prefix + 1).equalsIgnoreCase(fixedTokens.get(prefix + 1))) {
          collectAlignedPair(a, b, out);
          prefix += 2;
        } else {
          break;
        }
      }
      int suffix = 0;
      while (suffix < minLen - prefix && out.size() < MAX_PAIRS) {
        String a = origTokens.get(origTokens.size() - 1 - suffix);
        String b = fixedTokens.get(fixedTokens.size() - 1 - suffix);
        if (a.equalsIgnoreCase(b)) {
          suffix++;
        } else if (suffix + 1 < minLen - prefix
            && origTokens.get(origTokens.size() - 2 - suffix)
                .equalsIgnoreCase(fixedTokens.get(fixedTokens.size() - 2 - suffix))) {
          collectAlignedPair(a, b, out);
          suffix += 2;
        } else {
          break;
        }
      }
    }

    return List.copyOf(out.values());
  }

  private static void collectAlignedPair(String a, String b, LinkedHashMap<String, FixPair> out) {
    if (a.equalsIgnoreCase(b) || out.size() >= MAX_PAIRS) return;
    FixPair clean = sanitizePair(a, b);
    if (clean == null) return;
    String fromLower = clean.from().toLowerCase(Locale.ROOT);
    String toLower = clean.to().toLowerCase(Locale.ROOT);
    int maxDist = Math.max(fromLower.length(), toLower.length()) >= 5 ? 3 : 2;
    if (editDistance(fromLower, toLower) <= maxDist) {
      out.putIfAbsent(fromLower, clean);
    }
  }

  private static FixPair sanitizePair(String rawFrom, String rawTo) {
    if (rawFrom == null || rawTo == null) return null;
    String from = rawFrom.trim();
    String to = rawTo.trim();
    if (from.length() < 2 || to.length() < 2) return null;
    if (!isSingleWord(from) || !isSingleWord(to)) return null;
    String fromLower = from.toLowerCase(Locale.ROOT);
    String toLower = to.toLowerCase(Locale.ROOT);
    if (fromLower.equals(toLower)) return null;
    // Skip prefix completions (e.g. "app" -> "application"), which are not typos.
    if (toLower.startsWith(fromLower)) return null;
    // Store lowercase key; preserve capitalization of `to` if it's a proper noun/contraction, else lowercase.
    String normalizedTo = isTitleOnly(from, to) ? toLower : to;
    return new FixPair(fromLower, normalizedTo);
  }

  private static boolean isTitleOnly(String from, String to) {
    if (from.isEmpty() || to.isEmpty()) return false;
    boolean fromCap = Character.isUpperCase(from.charAt(0));
    boolean toCap = Character.isUpperCase(to.charAt(0));
    if (!fromCap || !toCap) return false;
    // Both were sentence-start capitalized and rest of `to` is lowercase: normalize to lowercase.
    for (int i = 1; i < to.length(); i++) {
      if (Character.isUpperCase(to.charAt(i))) return false;
    }
    return true;
  }

  private static boolean isSingleWord(String s) {
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (!(Character.isLetter(c) || c == '\'' || c == '’')) return false;
    }
    return true;
  }

  private static List<String> tokenize(String text) {
    List<String> out = new ArrayList<>();
    Matcher m = WORD_TOKEN.matcher(text);
    while (m.find()) out.add(m.group());
    return out;
  }

  static int editDistance(String a, String b) {
    int n = a.length(), m = b.length();
    int[] prev = new int[m + 1];
    int[] curr = new int[m + 1];
    for (int j = 0; j <= m; j++) prev[j] = j;
    for (int i = 1; i <= n; i++) {
      curr[0] = i;
      for (int j = 1; j <= m; j++) {
        int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
        curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
      }
      int[] tmp = prev; prev = curr; curr = tmp;
    }
    return prev[m];
  }
}
