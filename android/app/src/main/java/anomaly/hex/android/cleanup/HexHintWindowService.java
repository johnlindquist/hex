package anomaly.hex.android.cleanup;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.os.Build;
import android.os.PowerManager;
import android.view.accessibility.*;
import anomaly.hex.android.keyboard.ScreenContextVocabulary;
import java.util.ArrayDeque;
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

    /**
     * Fast, bounded, password-safe extraction of visible words from the active app window's
     * accessibility node tree for the keyboard's session corrective dictionary.
     */
    public static void readVisibleWords(String expectedPackage, Consumer<List<String>> onWords) {
        HexHintWindowService service = active;
        if (service == null || expectedPackage == null || onWords == null) return;
        service.getMainExecutor().execute(() -> {
            try {
                if (active != service
                    || service.getSystemService(KeyguardManager.class).isKeyguardLocked()
                    || !service.getSystemService(PowerManager.class).isInteractive()) return;
                AccessibilityNodeInfo root = null;
                AccessibilityNodeInfo focused = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if (focused != null) {
                    if (focused.isPassword() || focused.getPackageName() == null
                        || !expectedPackage.contentEquals(focused.getPackageName())) return;
                    AccessibilityWindowInfo win = focused.getWindow();
                    if (win != null && win.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) {
                        root = win.getRoot();
                    }
                }
                if (root == null) {
                    AccessibilityNodeInfo activeRoot = service.getRootInActiveWindow();
                    if (activeRoot != null && activeRoot.getPackageName() != null
                        && expectedPackage.contentEquals(activeRoot.getPackageName())) {
                        root = activeRoot;
                    }
                }
                if (root == null) return;
                StringBuilder buf = new StringBuilder(2048);
                ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
                queue.add(root);
                int visited = 0;
                while (!queue.isEmpty() && visited < 400 && buf.length() < 12000) {
                    AccessibilityNodeInfo node = queue.poll();
                    if (node == null) continue;
                    visited++;
                    if (node.isPassword()) continue;
                    CharSequence text = node.getText();
                    if (text != null && text.length() > 0) buf.append(text).append('\n');
                    CharSequence desc = node.getContentDescription();
                    if (desc != null && desc.length() > 0 && (text == null || !desc.toString().contentEquals(text))) {
                        buf.append(desc).append('\n');
                    }
                    int count = node.getChildCount();
                    for (int i = 0; i < count && visited + queue.size() < 450; i++) {
                        AccessibilityNodeInfo child = node.getChild(i);
                        if (child != null) queue.add(child);
                    }
                }
                List<String> words = ScreenContextVocabulary.extractWords(buf, 250);
                if (!words.isEmpty()) onWords.accept(words);
            } catch (RuntimeException ignored) {
                // Accessibility tree may disappear mid-traversal if user switches apps.
            }
        });
    }
}
