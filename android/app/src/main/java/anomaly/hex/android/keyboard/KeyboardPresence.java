package anomaly.hex.android.keyboard;

import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;

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

  public static boolean isHexKeyboardDefault(Context context) {
    try {
      String id = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
      return id != null && new ComponentName(context, HexInputMethodService.class).equals(ComponentName.unflattenFromString(id));
    } catch (SecurityException e) {
      return false;
    }
  }

  public static boolean isImeVisible() { return imeVisible; }

  public static void setOnVisibilityChanged(Runnable listener) { onVisibilityChanged = listener; }
}
