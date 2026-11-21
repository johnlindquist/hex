package anomaly.hex.android.ui;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.widget.*;

public final class HexTheme {
  private HexTheme() {}
  public static final int CANVAS = 0xff0a0b0d, SURFACE = 0xff111316, CARD = SURFACE,
                          LAYER_01 = 0xff17191d, LAYER_02 = 0xff202329, CONTROL = 0x17ffffff,
                          TEXT = 0xf0ffffff, MUTED = 0x8fffffff, FAINT = 0x5cffffff,
                          LINE = 0x0fffffff, BORDER = 0x1cffffff, PRIMARY = 0xf5ffffff,
                          DANGER = 0xffe54d42, DANGER_SURFACE = 0x24e54d42, MIC = 0xffff9500,
                          SUCCESS = 0xff34c759, WARM_PEARL = 0xfffaf5eb,
                          WARM_PEARL_BASE = 0xffdbd4c4;
  public static int dp(Context c, float v) {
    return Math.round(c.getResources().getDisplayMetrics().density * v);
  }
  public static GradientDrawable shape(Context c, int color, int radius, int border) {
    return shape(c, color, (float) radius, border);
  }
  public static GradientDrawable shape(Context c, int color, float radius, int border) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(c, radius));
    if (border != 0)
      d.setStroke(Math.max(1, dp(c, .5f)), border);
    return d;
  }
  static android.graphics.drawable.Drawable ripple(Context c, int color, int radius) {
    return new android.graphics.drawable.RippleDrawable(
        android.content.res.ColorStateList.valueOf(BORDER), shape(c, color, radius, 0), null);
  }
  static EditText input(Context c, String hint) {
    EditText v = new EditText(c);
    v.setBackground(shape(c, LAYER_01, 12, 0));
    v.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
    v.setTextColor(TEXT);
    v.setHintTextColor(FAINT);
    v.setTextSize(15);
    v.setHint(hint);
    return v;
  }
  static TextView text(Context c, String s, int size, int color) {
    TextView v = new TextView(c);
    v.setText(s);
    v.setTextSize(size);
    v.setTextColor(color);
    v.setIncludeFontPadding(false);
    if (size >= 18 || size == 11 || size == 13)
      v.setTypeface(Typeface.create("sans-serif-medium", 0));
    return v;
  }
  static Button button(Context c, String label, boolean primary, Runnable action) {
    Button b = new Button(c);
    b.setAllCaps(false);
    b.setText(label);
    b.setTextSize(primary ? 17 : 15);
    b.setTypeface(Typeface.create("sans-serif-medium", 0));
    b.setMinimumHeight(0);
    b.setMinWidth(0);
    b.setMinimumWidth(0);
    b.setPadding(dp(c, 16), 0, dp(c, 16), 0);
    b.setHeight(dp(c, primary ? 50 : 42));
    b.setStateListAnimator(null);
    b.setTextColor(primary ? CANVAS : TEXT);
    b.setBackground(ripple(c, primary ? PRIMARY : CONTROL, 999));
    b.setOnClickListener(v -> action.run());
    return b;
  }
  public static Button smallButton(Context c, String label, Runnable action) {
    Button b = button(c, label, false, action);
    b.setTextSize(13);
    b.setMinimumHeight(0);
    b.setHeight(dp(c, 28));
    b.setPadding(dp(c, 12), 0, dp(c, 12), 0);
    return b;
  }
  private static Path hex(float top, float right, float upper, float lower, float bottom) {
    Path p = new Path();
    p.moveTo(32, top);
    p.lineTo(right, upper);
    p.lineTo(right, lower);
    p.lineTo(32, bottom);
    p.lineTo(64 - right, lower);
    p.lineTo(64 - right, upper);
    p.close();
    return p;
  }
  private static final Path OUTER = hex(4, 57, 18.5f, 45.5f, 60),
                            MIDDLE = hex(15, 48, 24.25f, 39.75f, 49),
                            CORE = hex(24, 39.5f, 28.25f, 36.75f, 41);
  private static final ThreadLocal<Paint> MARK_PAINT =
      ThreadLocal.withInitial(() -> new Paint(Paint.ANTI_ALIAS_FLAG));
  public static void drawHexMark(
      Canvas c, float cx, float cy, float sidePx, int strokeColor, int coreColor) {
    if (sidePx <= 0)
      return;
    float s = sidePx / 64f;
    c.save();
    c.translate(cx - sidePx / 2, cy - sidePx / 2);
    c.scale(s, s);
    Paint ink = MARK_PAINT.get();
    ink.setColor(strokeColor);
    ink.setStyle(Paint.Style.STROKE);
    ink.setStrokeJoin(Paint.Join.ROUND);
    ink.setStrokeWidth(Math.max(1.15f * c.getDensity() / 160f / s, 4.5f));
    c.drawPath(OUTER, ink);
    c.drawPath(MIDDLE, ink);
    ink.setStyle(Paint.Style.FILL);
    ink.setColor(coreColor);
    c.drawPath(CORE, ink);
    c.restore();
  }
}
