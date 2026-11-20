package anomaly.hex.android.ui;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.widget.*;

public final class HexTheme {
  private HexTheme() {}
  public static final int CANVAS = 0xff0a0b0d, SURFACE = 0xff111316, CARD = 0xff17191d,
                          CONTROL = 0xff202329, TEXT = 0xf0ffffff, MUTED = 0x8fffffff,
                          FAINT = 0x5cffffff, LINE = 0x0fffffff, BORDER = 0x1cffffff,
                          PRIMARY = 0xfff0f2f5, DANGER = 0xffe54d42, MIC = 0xffff9500,
                          SUCCESS = 0xff34c759;
  public static int dp(Context c, float v) {
    return Math.round(c.getResources().getDisplayMetrics().density * v);
  }
  public static GradientDrawable shape(Context c, int color, int radius, int border) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(c, radius));
    d.setStroke(dp(c, 1), border);
    return d;
  }
  static TextView text(Context c, String s, int size, int color) {
    TextView v = new TextView(c);
    v.setText(s);
    v.setTextSize(size);
    v.setTextColor(color);
    v.setIncludeFontPadding(false);
    if (size >= 20)
      v.setTypeface(Typeface.create("sans-serif-medium", 0));
    return v;
  }
  static Button button(Context c, String label, boolean primary, Runnable action) {
    Button b = new Button(c);
    b.setAllCaps(false);
    b.setText(label);
    b.setTextSize(15);
    b.setMinHeight(dp(c, 48));
    b.setTextColor(primary ? CANVAS : TEXT);
    b.setBackground(shape(c, primary ? PRIMARY : CONTROL, 10, BORDER));
    b.setOnClickListener(v -> action.run());
    return b;
  }
}
