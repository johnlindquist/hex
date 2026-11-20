package anomaly.hex.android.ui;
import android.content.*;
import android.graphics.*;
import android.view.*;
import java.util.UUID;
import org.json.*;

/** Coordinates within the usable rectangle. All controls remain on screen. */
public final class Placement {
  private final SharedPreferences prefs;
  private int x, y, startX, startY;
  private float rawX, rawY;
  private boolean dragging;
  private String rev;
  public Placement(Context c) {
    prefs = c.getSharedPreferences("mic_position", 0);
    x = prefs.getInt("x", 955);
    y = prefs.getInt("y", 915);
    rev = prefs.getString("rev", "default");
  }
  public JSONObject info() throws JSONException {
    return new JSONObject().put("x", x).put("y", y).put("rev", rev).put("dragging", dragging);
  }
  public int currentImeBottom(Context c, View strip) {
    WindowMetrics m = c.getSystemService(WindowManager.class).getCurrentWindowMetrics();
    int ime = m.getWindowInsets().getInsets(WindowInsets.Type.ime()).bottom;
    WindowInsets attached = strip == null ? null : strip.getRootWindowInsets();
    if (attached != null)
      ime = Math.max(ime, attached.getInsets(WindowInsets.Type.ime()).bottom);
    return ime;
  }
  private record Span(int x, int y, boolean imeVisible) {}
  private Span span(View strip) {
    WindowMetrics m =
        strip.getContext().getSystemService(WindowManager.class).getCurrentWindowMetrics();
    Rect r = m.getBounds();
    Insets i = m.getWindowInsets().getInsetsIgnoringVisibility(
        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
    int ime = currentImeBottom(strip.getContext(), strip);
    if (strip.isLayoutRequested() || strip.getMeasuredWidth() <= 0 || strip.getMeasuredHeight() <= 0)
      strip.measure(0, 0);
    return new Span(Math.max(1, r.width() - i.left - i.right - strip.getMeasuredWidth()),
        Math.max(1, r.height() - i.top - Math.max(i.bottom, ime) - strip.getMeasuredHeight()), ime > i.bottom);
  }
  public void layout(View strip, WindowManager.LayoutParams p) {
    Span s = span(strip);
    p.gravity = Gravity.LEFT | Gravity.TOP;
    p.x = Math.round(s.x * x / 1000f);
    // Leave the bottom action row reachable until the user explicitly places the strip.
    int parked = Math.max(0, s.y - HexTheme.dp(strip.getContext(), s.imeVisible ? 12 : 76));
    int effectiveY = !dragging && "default".equals(rev) ? parked : Math.round(s.y * y / 1000f);
    p.y = effectiveY;
  }
  public void begin(View strip, float a, float b) {
    startX = x;
    WindowManager.LayoutParams p = (WindowManager.LayoutParams) strip.getLayoutParams();
    startY = "default".equals(rev) ? Math.round(1000f * p.y / span(strip).y) : y;
    rawX = a;
    rawY = b;
    dragging = true;
  }
  public void drag(View strip, float a, float b) {
    Span s = span(strip);
    x = Math.max(0, Math.min(1000, startX + Math.round((a - rawX) * 1000 / s.x)));
    y = Math.max(0, Math.min(1000, startY + Math.round((b - rawY) * 1000 / s.y)));
  }
  public void end(boolean keep) {
    if (!dragging)
      return;
    dragging = false;
    if (keep)
      save();
    else {
      x = startX;
      y = startY;
    }
  }
  private void save() {
    rev = UUID.randomUUID().toString();
    prefs.edit().putInt("x", x).putInt("y", y).putString("rev", rev).apply();
  }
  public String set(String expected, int a, int b) {
    if (dragging)
      return "busy";
    if (!rev.equals(expected))
      return "stale_position";
    if (a < 0 || b < 0 || a > 1000 || b > 1000)
      return "invalid_position";
    x = a;
    y = b;
    save();
    return "ok";
  }
}
