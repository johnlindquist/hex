package anomaly.hex.android.ui;
import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;
import anomaly.hex.android.speech.MicEnvelope;
import java.util.Arrays;

/** A still phosphor plate with two opposing traces crossing a shared envelope. */
public final class DotScopeView extends View {
  private static final int ROWS = 9, BUCKETS = 6;
  // Beyond squared distance four, even full brightness adds less than one alpha step.
  private static final float[] EXP_LUT = new float[128], PHASE = new float[2000];
  static {
    for (int i = 0; i < EXP_LUT.length; i++)
      EXP_LUT[i] = (float) Math.exp(-2.4 * i * 4 / (EXP_LUT.length - 1));
    for (int i = 0; i < PHASE.length; i++)
      PHASE[i] = (float) Math.sin(i * Math.PI / 1000);
  }
  private Bitmap basePlate;
  private final Paint dots = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final float[][] points = new float[BUCKETS][];
  private final int[] counts = new int[BUCKETS];
  private final float[] yCoords = new float[ROWS], levels = new float[MicEnvelope.BARS];
  private float[] xCoords, sinPiU, u4, cos4, u8, cos8, u10, cos10, mix, edgeAlpha;
  private int[] bar;
  private boolean live, demo, ticking;
  private long lastFrameNanos;
  private final Choreographer.FrameCallback tick = new Choreographer.FrameCallback() {
    @Override public void doFrame(long now) {
      if (!ticking) return;
      if (now - lastFrameNanos >= 30_000_000L) {
        lastFrameNanos = now;
        invalidate();
      }
      Choreographer.getInstance().postFrameCallback(this);
    }
  };
  private void setTicking(boolean enabled) {
    if (enabled == ticking) return;
    ticking = enabled;
    if (enabled) {
      lastFrameNanos = 0;
      Choreographer.getInstance().postFrameCallback(tick);
    } else {
      Choreographer.getInstance().removeFrameCallback(tick);
    }
  }
  @Override public void onVisibilityAggregated(boolean visible) {
    super.onVisibilityAggregated(visible);
    setTicking(visible && (live || demo));
  }
  private void modeChanged() {
    setTicking(isAttachedToWindow() && isShown() && getWindowVisibility() == VISIBLE && (live || demo));
    if (!live && !demo) invalidate();
  }
  void setDemoMode(boolean enabled) {
    demo = enabled;
    modeChanged();
  }
  public DotScopeView(Context c) {
    super(c);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
  }
  @Override protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    if (basePlate == null) buildPlate(getWidth(), getHeight());
    modeChanged();
  }
  @Override protected void onDetachedFromWindow() {
    setTicking(false);
    releasePlate();
    super.onDetachedFromWindow();
  }
  private void releasePlate() {
    if (basePlate != null) basePlate.recycle();
    basePlate = null;

  }
  public void update(boolean recording, int[] targets) {
    boolean changed = live != recording;
    live = recording;
    if (!recording)
      Arrays.fill(levels, 0f);
    else if (targets != null)
      for (int i = 0; i < levels.length; i++)
        levels[i] = i < targets.length ? Math.min(1f, Math.max(0, targets[i]) / 100f) : 0;
    if (changed) modeChanged();
  }
  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    super.onSizeChanged(w, h, oldw, oldh);
    releasePlate();
    buildPlate(w, h);
  }
  private void buildPlate(int w, int h) {
    if (w <= 0 || h <= 0) return;
    float dp = getResources().getDisplayMetrics().density;
    float pitch = Math.max(3 * dp, Math.min(4 * dp, h / 11f));
    int cols = Math.max(15, (int) Math.floor((w - 16 * dp) / pitch));
    xCoords = new float[cols]; sinPiU = new float[cols]; edgeAlpha = new float[cols];
    u4 = new float[cols]; cos4 = new float[cols]; u8 = new float[cols]; cos8 = new float[cols];
    u10 = new float[cols]; cos10 = new float[cols]; mix = new float[cols]; bar = new int[cols];
    for (int i = 1; i < BUCKETS; i++) points[i] = new float[2 * ROWS * cols];
    float radius = pitch * .3f;
    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    p.setColor(HexTheme.PRIMARY);
    dots.setColor(HexTheme.PRIMARY);
    dots.setStrokeWidth(2 * radius);
    dots.setStrokeCap(Paint.Cap.ROUND);
    basePlate = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
    basePlate.setDensity(Bitmap.DENSITY_NONE);
    Canvas plate = new Canvas(basePlate);
    float fade = 24 * dp;
    for (int y = 0; y < ROWS; y++) yCoords[y] = h * .5f + (y - 4) * pitch;
    for (int x = 0; x < cols; x++) {
      float px = xCoords[x] = (w - (cols - 1) * pitch) * .5f + x * pitch;
      edgeAlpha[x] = Math.max(0f, Math.min(1f, Math.min(px, w - px) / fade));
      p.setAlpha(Math.round(55 * edgeAlpha[x]));
      float u = x / (float) (cols - 1);
      sinPiU[x] = (float) Math.sin(Math.PI * u);
      u4[x] = (float) Math.sin(4 * u); cos4[x] = (float) Math.cos(4 * u);
      u8[x] = (float) Math.sin(8 * u); cos8[x] = (float) Math.cos(8 * u);
      u10[x] = (float) Math.sin(10 * u); cos10[x] = (float) Math.cos(10 * u);
      float idx = u * (levels.length - 1);
      bar[x] = Math.min((int) idx, levels.length - 2);
      mix[x] = idx - bar[x];
      for (float y : yCoords) plate.drawCircle(xCoords[x], y, radius, p);
    }
  }
  @Override
  protected void onDraw(Canvas c) {
    if (basePlate == null) return;
    c.drawBitmap(basePlate, 0f, 0f, null);
    if (!live && !demo) return;
    int t = (int) (SystemClock.uptimeMillis() % PHASE.length);
    float sin = PHASE[t], cos = PHASE[(t + 500) % PHASE.length];
    Arrays.fill(counts, 0);
    for (int x = 0; x < xCoords.length; x++) {
      int a = bar[x];
      float level = demo ? .48f + .22f * (u4[x] * cos + cos4[x] * sin)
          : levels[a] + (levels[a + 1] - levels[a]) * mix[x];
      float second = -3.2f * level * (u8[x] * cos + cos8[x] * sin) * sinPiU[x];
      float wave = 3.8f * level * (u10[x] * cos - cos10[x] * sin) * sinPiU[x];
      float intensity = (.25f + .75f * level) * (BUCKETS - 1);
      int first = Math.max(0, (int) Math.ceil(4 + Math.min(wave, second) - 2));
      int last = Math.min(ROWS - 1, (int) Math.floor(4 + Math.max(wave, second) + 2));
      for (int y = first; y <= last; y++) {
        float dist = Math.min(Math.abs(y - 4 - wave), Math.abs(y - 4 - second));
        int index = Math.min(127, (int) (dist * dist * (127f / 4) + .5f));
        int bucket = Math.round(EXP_LUT[index] * intensity * edgeAlpha[x]);
        if (bucket > 0) {
          int n = counts[bucket];
          points[bucket][n] = xCoords[x]; points[bucket][n + 1] = yCoords[y];
          counts[bucket] = n + 2;
        }
      }
    }
    for (int i = 1; i < BUCKETS; i++) {
      if (counts[i] == 0) continue;
      dots.setAlpha(250 * i / (BUCKETS - 1));
      c.drawPoints(points[i], 0, counts[i], dots);
    }
  }
}
