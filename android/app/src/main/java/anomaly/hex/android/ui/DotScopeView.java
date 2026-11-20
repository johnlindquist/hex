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
  private static final int COLS = 43, ROWS = 9, BUCKETS = 6;
  // Beyond squared distance four, even full brightness adds less than one alpha step.
  private static final float[] EXP_LUT = new float[128], PHASE = new float[2000];
  static {
    for (int i = 0; i < EXP_LUT.length; i++)
      EXP_LUT[i] = (float) Math.exp(-2.4 * i * 4 / (EXP_LUT.length - 1));
    for (int i = 0; i < PHASE.length; i++)
      PHASE[i] = (float) Math.sin(i * Math.PI / 1000);
  }
  private float pitch, spriteHalf;
  private Bitmap basePlate;
  private final Bitmap[] dotSprites = new Bitmap[BUCKETS];
  private final float[] xCoords = new float[COLS], yCoords = new float[ROWS];
  private final float[] sinPiU = new float[COLS];
  // Spatial sine and cosine pairs for sin(k*u +/- phase) by angle addition.
  private final float[] u8 = new float[COLS], cos8 = new float[COLS], u10 = new float[COLS],
      cos10 = new float[COLS], u4 = new float[COLS], cos4 = new float[COLS];
  private final float[] levels = new float[MicEnvelope.BARS], mix = new float[COLS];
  private final int[] bar = new int[COLS];
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
    for (int i = 1; i < BUCKETS; i++) {
      if (dotSprites[i] != null) dotSprites[i].recycle();
      dotSprites[i] = null;
    }
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
    pitch = Math.min((w - HexTheme.dp(getContext(), 14)) / (float) COLS, h / 11f);
    if (pitch <= 0) return;
    float radius = pitch * .3f;
    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    p.setColor(HexTheme.PRIMARY);
    int size = (int) Math.ceil(radius * 2 + 2);
    spriteHalf = size * .5f;
    for (int i = 1; i < BUCKETS; i++) {
      Bitmap sprite = dotSprites[i] = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
      sprite.setDensity(Bitmap.DENSITY_NONE);
      p.setAlpha(55 + 195 * i / (BUCKETS - 1));
      new Canvas(sprite).drawCircle(spriteHalf, spriteHalf, radius, p);
    }
    basePlate = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
    basePlate.setDensity(Bitmap.DENSITY_NONE);
    Canvas plate = new Canvas(basePlate);
    p.setAlpha(55);
    for (int y = 0; y < ROWS; y++) yCoords[y] = h * .5f + (y - 4) * pitch;
    for (int x = 0; x < COLS; x++) {
      xCoords[x] = (w - (COLS - 1) * pitch) * .5f + x * pitch;
      float u = x / (float) (COLS - 1);
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
    if (pitch <= 0) return;
    if (basePlate == null) buildPlate(getWidth(), getHeight());
    c.drawBitmap(basePlate, 0f, 0f, null);
    if (!live && !demo) return;
    int t = (int) (SystemClock.uptimeMillis() % PHASE.length);
    float sin = PHASE[t], cos = PHASE[(t + 500) % PHASE.length];
    for (int x = 0; x < COLS; x++) {
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
        int bucket = (int) (EXP_LUT[index] * intensity + .5f);
        if (bucket > 0)
          c.drawBitmap(dotSprites[bucket], xCoords[x] - spriteHalf, yCoords[y] - spriteHalf, null);
      }
    }
  }
}
