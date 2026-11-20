package anomaly.hex.android.ui;
import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.view.View;
import anomaly.hex.android.speech.MicEnvelope;
import java.util.Arrays;

/** A still phosphor plate with two opposing traces crossing a shared envelope. */
public final class DotScopeView extends View {
  private static final int COLS = 43, ROWS = 9, BUCKETS = 6, COORDS = COLS * ROWS * 2;
  // Beyond squared distance four, even full brightness adds less than one alpha step.
  private static final float[] EXP_LUT = new float[128], PHASE = new float[2000];
  static {
    for (int i = 0; i < EXP_LUT.length; i++)
      EXP_LUT[i] = (float) Math.exp(-2.4 * i * 4 / (EXP_LUT.length - 1));
    for (int i = 0; i < PHASE.length; i++)
      PHASE[i] = (float) Math.sin(i * Math.PI / 1000);
  }
  private float pitch, radius;
  private final float[] xCoords = new float[COLS], yCoords = new float[ROWS], idle = new float[COORDS];
  private final float[] uVals = new float[COLS], sinPiU = new float[COLS];
  // Spatial sine and cosine pairs for sin(k*u +/- phase) by angle addition.
  private final float[] u8 = new float[COLS], cos8 = new float[COLS], u10 = new float[COLS],
      cos10 = new float[COLS], u4 = new float[COLS], cos4 = new float[COLS];
  private final float[] levels = new float[MicEnvelope.BARS], mix = new float[COLS];
  private final int[] bar = new int[COLS], counts = new int[BUCKETS];
  private final Paint[] paints = new Paint[BUCKETS];
  private final float[][] points = new float[BUCKETS][COORDS];
  private boolean live, demo;
  void setDemoMode(boolean enabled) {
    demo = enabled;
    invalidate();
  }
  public DotScopeView(Context c) {
    super(c);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    for (int i = 0; i < BUCKETS; i++) {
      Paint p = paints[i] = new Paint(Paint.ANTI_ALIAS_FLAG);
      p.setColor(HexTheme.PRIMARY);
      p.setAlpha(55 + 195 * i / (BUCKETS - 1));
      p.setStrokeCap(Paint.Cap.ROUND);
    }
  }
  public void update(boolean recording, int[] targets) {
    live = recording;
    if (!recording)
      Arrays.fill(levels, 0f);
    else if (targets != null)
      for (int i = 0; i < levels.length; i++)
        levels[i] = i < targets.length ? Math.min(1f, Math.max(0, targets[i]) / 100f) : 0;
    invalidate();
  }
  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    pitch = Math.min((w - HexTheme.dp(getContext(), 14)) / (float) COLS, h / 11f);
    radius = pitch * .3f;
    if (pitch <= 0) return;
    for (Paint p : paints) p.setStrokeWidth(radius * 2);
    for (int y = 0; y < ROWS; y++) yCoords[y] = h * .5f + (y - 4) * pitch;
    int n = 0;
    for (int x = 0; x < COLS; x++) {
      xCoords[x] = (w - (COLS - 1) * pitch) * .5f + x * pitch;
      float u = uVals[x] = x / (float) (COLS - 1);
      sinPiU[x] = (float) Math.sin(Math.PI * u);
      u4[x] = (float) Math.sin(4 * u); cos4[x] = (float) Math.cos(4 * u);
      u8[x] = (float) Math.sin(8 * u); cos8[x] = (float) Math.cos(8 * u);
      u10[x] = (float) Math.sin(10 * u); cos10[x] = (float) Math.cos(10 * u);
      float idx = u * (levels.length - 1);
      bar[x] = Math.min((int) idx, levels.length - 2);
      mix[x] = idx - bar[x];
      for (float y : yCoords) {
        idle[n++] = xCoords[x];
        idle[n++] = y;
      }
    }
  }
  @Override
  protected void onDraw(Canvas c) {
    if (pitch <= 0) return;
    if (!live && !demo) {
      c.drawPoints(idle, 0, COORDS, paints[0]);
      return;
    }
    Arrays.fill(counts, 0);
    int t = (int) (SystemClock.uptimeMillis() % PHASE.length);
    float sin = PHASE[t], cos = PHASE[(t + 500) % PHASE.length];
    for (int x = 0; x < COLS; x++) {
      int a = bar[x];
      float level = demo ? .48f + .22f * (u4[x] * cos + cos4[x] * sin)
          : levels[a] + (levels[a + 1] - levels[a]) * mix[x];
      float second = -3.2f * level * (u8[x] * cos + cos8[x] * sin) * sinPiU[x];
      float wave = 3.8f * level * (u10[x] * cos - cos10[x] * sin) * sinPiU[x];
      float intensity = (.25f + .75f * level) * (BUCKETS - 1);
      for (int y = 0; y < ROWS; y++) {
        float dist = Math.min(Math.abs(y - 4 - wave), Math.abs(y - 4 - second));
        int index = Math.min(127, (int) (dist * dist * (127f / 4) + .5f));
        int bucket = (int) (EXP_LUT[index] * intensity + .5f);
        int n = counts[bucket];
        points[bucket][n] = xCoords[x];
        points[bucket][n + 1] = yCoords[y];
        counts[bucket] = n + 2;
      }
    }
    for (int i = 0; i < BUCKETS; i++)
      if (counts[i] > 0) c.drawPoints(points[i], 0, counts[i], paints[i]);
    if (isShown()) postInvalidateOnAnimation();
  }
}
