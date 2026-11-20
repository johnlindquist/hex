package anomaly.hex.android.ui;
import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.view.View;
import anomaly.hex.android.speech.MicEnvelope;

/** A still phosphor plate with two opposing traces crossing a shared envelope. */
public final class DotScopeView extends View {
  private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final float[] levels = new float[MicEnvelope.BARS];
  private boolean live, demo;
  void setDemoMode(boolean enabled) {
    demo = enabled;
    invalidate();
  }
  public DotScopeView(Context c) {
    super(c);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
  }
  public void update(boolean recording, int[] targets) {
    live = recording;
    if (!recording)
      java.util.Arrays.fill(levels, 0f);
    else if (targets != null)
      for (int i = 0; i < levels.length; i++)
        levels[i] = i < targets.length ? Math.min(1f, Math.max(0, targets[i]) / 100f) : 0;
    invalidate();
  }
  @Override
  protected void onDraw(Canvas c) {
    float w = getWidth(), h = getHeight(),
          pitch = Math.min((w - HexTheme.dp(getContext(), 14)) / 43, h / 11);
    if (pitch <= 0)
      return;
    float left = (w - 42 * pitch) / 2, center = h / 2;
    boolean animated = live || demo;
    double phase = animated ? (SystemClock.uptimeMillis() % 4000) * Math.PI / 1000 : 0;

    for (int x = 0; x < 43; x++) {
      double u = x / 42., idx = u * (levels.length - 1);
      int a = (int) idx;
      double level =
          levels[a] + (levels[Math.min(a + 1, levels.length - 1)] - levels[a]) * (idx - a);
      if (demo)
        level = .48 + .22 * Math.sin(phase + 4 * u);
      double second = -3.2 * level * Math.sin(u * 8 + phase) * Math.sin(Math.PI * u);
      double wave = 3.8 * level * Math.sin(u * 10 - phase) * Math.sin(Math.PI * u);
      for (int y = -4; y <= 4; y++) {
        double dist = Math.min(Math.abs(y - wave), Math.abs(y - second));
        double light = animated ? Math.exp(-2.4 * dist * dist) * (.25 + .75 * level) : 0;
        ink.setColor(HexTheme.PRIMARY);
        ink.setAlpha((int) (55 + 195 * light));
        c.drawCircle(left + x * pitch, center + y * pitch, pitch * .3f, ink);
      }
    }
    if (animated && isShown())
      postInvalidateDelayed(33);
  }
}
