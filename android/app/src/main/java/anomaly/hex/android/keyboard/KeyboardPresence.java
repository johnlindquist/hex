package anomaly.hex.android.keyboard;

/** Process-local seam between the keyboard and the independent accessibility overlay. */
public final class KeyboardPresence {
  private static volatile boolean imeVisible;
  private static volatile Runnable onVisibilityChanged;

  private KeyboardPresence() {}

  public static void setImeVisible(boolean visible) {
    imeVisible = visible;
    Runnable listener = onVisibilityChanged;
    if (listener != null) listener.run();
  }

  public static boolean isImeVisible() { return imeVisible; }

  public static void setOnVisibilityChanged(Runnable listener) { onVisibilityChanged = listener; }
}
