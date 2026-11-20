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
    x = prefs.getInt("x", 980);
    y = prefs.getInt("y", 500);
    rev = prefs.getString("rev", "default");
  }
  public JSONObject info() throws JSONException {
    return new JSONObject().put("x", x).put("y", y).put("rev", rev).put("dragging", dragging);
  }
  private Point span(View strip) {
    WindowMetrics m =
        strip.getContext().getSystemService(WindowManager.class).getCurrentWindowMetrics();
    Rect r = m.getBounds();
    Insets i = m.getWindowInsets().getInsetsIgnoringVisibility(
        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
    strip.measure(0, 0);
    return new Point(Math.max(1, r.width() - i.left - i.right - strip.getMeasuredWidth()),
        Math.max(1, r.height() - i.top - i.bottom - strip.getMeasuredHeight()));
  }
  public void layout(View strip, WindowManager.LayoutParams p) {
    Point s = span(strip);
    p.gravity = Gravity.LEFT | Gravity.TOP;
    p.x = Math.round(s.x * x / 1000f);
    p.y = Math.round(s.y * y / 1000f);
  }
  public void begin(float a, float b) {
    startX = x;
    startY = y;
    rawX = a;
    rawY = b;
    dragging = true;
  }
  public void drag(View strip, float a, float b) {
    Point s = span(strip);
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
