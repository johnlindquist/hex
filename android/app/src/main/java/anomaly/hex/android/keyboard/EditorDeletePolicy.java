package anomaly.hex.android.keyboard;

import android.text.InputType;

/** Raw input connections may acknowledge a surrounding-text deletion without sending a key. */
final class EditorDeletePolicy {
  enum Delete { SELECTION, KEY_EVENT, SURROUNDING_TEXT }

  static boolean raw(int inputType) {
    return (inputType & InputType.TYPE_MASK_CLASS) == InputType.TYPE_NULL;
  }

  static Delete deletion(int inputType, CharSequence selected, CharSequence before) {
    if (selected != null && selected.length() > 0) return Delete.SELECTION;
    if (raw(inputType) || before == null || before.length() == 0) return Delete.KEY_EVENT;
    return Delete.SURROUNDING_TEXT;
  }

  static boolean sendEnter(int inputType, String actionLabel) {
    return raw(inputType) && actionLabel.isEmpty();
  }
}
