package anomaly.hex.android.speech;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Punctuation is a hypothesis, not an endpoint by itself. The capture lane supplies pause/context
 * evidence.
 */
final class SentenceEnds {
  private static final Pattern ABBREV =
      Pattern.compile(
          "(?i)(?:mr|mrs|ms|dr|prof|sr|jr|vs|etc|st|no|fig|i\\.e|e\\.g|(?:[a-z]\\.)+[a-z]|[A-Z])",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern HANGING =
      Pattern.compile(
          "(?i)(?:a|an|the|to|because|if|until|with|and|or|but|of|for|that|which|whose|although|unless)");

  static boolean mark(char c) {
    return ".!?。！？".indexOf(c) >= 0;
  }

  static int last(String t, boolean allowEnd) {
    int cut = 0;
    for (int i = 0; i < t.length(); i++) {
      char ch = t.charAt(i);
      if (!mark(ch)) continue;
      int j = i + 1;
      while (j < t.length() && mark(t.charAt(j))) j++;
      boolean ellipsis = t.substring(i, j).contains("..");
      int after = j;
      while (after < t.length() && "\"'”’»)]}".indexOf(t.charAt(after)) >= 0) after++;
      boolean delimiter =
          after == t.length()
              ? allowEnd
              : (Character.isWhitespace(t.charAt(after)) || "。！？".indexOf(ch) >= 0);
      if (delimiter && !ellipsis) {
        int start = i;
        while (start > 0 && (Character.isLetter(t.charAt(start - 1)) || t.charAt(start - 1) == '.'))
          start--;
        String token = t.substring(start, i);
        if (ch != '.'
            || (!ABBREV.matcher(token).matches()
                && !HANGING.matcher(token.toLowerCase(Locale.ROOT)).matches())) cut = after;
      }
      i = j - 1;
    }
    return cut;
  }

  static boolean complete(String text) {
    String t = text.trim();
    return !t.isEmpty() && last(t, true) == t.length();
  }

  static String withoutGuessedPeriod(String text) {
    if (text.endsWith(".") && !text.endsWith("..")) {
      int i = text.length() - 1, start = i;
      while (start > 0
          && (Character.isLetter(text.charAt(start - 1)) || text.charAt(start - 1) == '.')) start--;
      if (!ABBREV.matcher(text.substring(start, i)).matches()) return text.substring(0, i).trim();
    }
    return text;
  }
}
