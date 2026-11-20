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
  private boolean live;
  public DotScopeView(Context c) {
    super(c);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
  }
  public void update(boolean recording, int[] targets) {
    live = recording;
    if (targets != null)
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
    float left = (w - 43 * pitch) / 2 + 3, center = h / 2;
    double phase = live ? (SystemClock.uptimeMillis() % 4000) * Math.PI / 1000 : 0;
    ink.setColor(live ? HexTheme.MIC : HexTheme.FAINT);
    c.drawCircle(Math.max(3, left / 2), center, HexTheme.dp(getContext(), 2), ink);
    for (int x = 0; x < 43; x++) {
      double u = x / 42., idx = u * (levels.length - 1);
      int a = (int) idx;
      double level =
          levels[a] + (levels[Math.min(a + 1, levels.length - 1)] - levels[a]) * (idx - a);
      double wave = 3.8 * level * Math.sin(u * 10 - phase) * Math.sin(Math.PI * u);
      for (int y = -4; y <= 4; y++) {
        double dist = Math.min(Math.abs(y - wave), Math.abs(y + wave));
        double light = live ? Math.exp(-2.4 * dist * dist) * (.25 + .75 * level) : 0;
        ink.setColor(HexTheme.PRIMARY);
        ink.setAlpha((int) (33 + 215 * light));
        c.drawCircle(left + x * pitch, center + y * pitch, pitch * .3f, ink);
      }
    }
    if (live && isShown())
      postInvalidateDelayed(33);
  }
}
