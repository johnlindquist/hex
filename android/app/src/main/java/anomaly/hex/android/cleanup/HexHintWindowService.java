package anomaly.hex.android.cleanup;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.os.Build;
import android.os.PowerManager;
import android.view.accessibility.*;
import java.util.List;
import java.util.function.Consumer;

/** Consults focus only for a user-approved capture, never in response to accessibility events. */
public final class HexHintWindowService extends AccessibilityService {
    private static volatile HexHintWindowService active;
    protected void onServiceConnected() { active = this; }
    public void onAccessibilityEvent(AccessibilityEvent ignored) {}
    public void onInterrupt() {}
    public void onDestroy() { if (active == this) active = null; super.onDestroy(); }
    public static boolean isConnected() { return active != null; }

    private int window(String pkg) {
        try {
            if (pkg == null || active != this || getSystemService(KeyguardManager.class).isKeyguardLocked()
                || !getSystemService(PowerManager.class).isInteractive()) return -1;
            AccessibilityNodeInfo n = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (n == null || n.isPassword() || !n.isEditable() || n.getPackageName() == null
                || !pkg.contentEquals(n.getPackageName())) return -1;
            AccessibilityWindowInfo w = n.getWindow();
            return w != null && w.getType() == AccessibilityWindowInfo.TYPE_APPLICATION && w.isFocused() ? w.getId() : -1;
        } catch (RuntimeException unavailable) { return -1; }
    }

    public static void captureHints(String expectedPackage, Consumer<List<String>> onWords, Consumer<String> onError) {
        HexHintWindowService service = active;
        if (service == null || Build.VERSION.SDK_INT < 34) { onError.accept("Window capture unavailable."); return; }
        service.getMainExecutor().execute(() -> {
            int id = service.window(expectedPackage);
            if (id < 0) { onError.accept("Editor unavailable."); return; }
            ScreenWords.read(service, id, new ScreenWords.Result() {
                boolean finished;
                public boolean current() {
                    if (finished) return false;
                    if (service.window(expectedPackage) == id) return true;
                    finished = true; onError.accept("Editor changed."); return false;
                }
                public void done(List<String> words, String note) {
                    if (finished) return;
                    finished = true;
                    if (words == null) onError.accept(note); else onWords.accept(words);
                }
            });
        });
    }
}
