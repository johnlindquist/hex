package anomaly.hex.android.service;

import java.util.regex.*;

/** One bounded, confirmed insertion. No editor text is persisted. */
public final class DictationEditTracker {
  public interface FixListener {
    void onWordFix(String heard, String replacement);
  }
  static final int LIMIT = 4096;
  private static final Pattern TOKEN = Pattern.compile("\\p{L}+(?:['’]\\p{L}+)*|[^\\p{L}]+");
  private final FixListener listener;
  private String owner, original;
  public DictationEditTracker(FixListener listener) {
    this.listener = listener;
  }
  public void disarm() {
    owner = original = null;
  }
  public void arm(String targetId, int start, int end, String insertedText) {
    disarm();
    if (targetId == null || targetId.isEmpty() || start < 0 || end < start || insertedText == null
        || insertedText.isBlank() || insertedText.length() > LIMIT
        || insertedText.length() != end - start)
      return;
    owner = targetId;
    original = insertedText;
  }
  public void onTextSnapshot(String targetId, String currentSpanText) {
    if (owner == null)
      return;
    if (!owner.equals(targetId) || currentSpanText == null || currentSpanText.length() > LIMIT) {
      disarm();
      return;
    }
    if (original.equals(currentSpanText))
      return;
    String old = original;
    disarm();
    if (old.codePoints().anyMatch(Character::isDigit)
        || currentSpanText.codePoints().anyMatch(Character::isDigit))
      return;
    Matcher a = TOKEN.matcher(old), b = TOKEN.matcher(currentSpanText);
    String heard = null, replacement = null;
    while (a.find()) {
      if (!b.find())
        return;
      String x = a.group(), y = b.group();
      if (!x.equals(y)) {
        if (heard != null || !Character.isLetter(x.codePointAt(0))
            || !Character.isLetter(y.codePointAt(0)) || y.startsWith(x) || x.startsWith(y))
          return;
        heard = x;
        replacement = y;
      }
    }
    if (!b.find() && heard != null && listener != null)
      listener.onWordFix(heard, replacement);
  }
}
