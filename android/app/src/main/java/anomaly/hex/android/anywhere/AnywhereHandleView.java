package anomaly.hex.android.anywhere;

import static anomaly.hex.android.ui.HexTheme.*;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import java.util.Collections;

/** Draggable, resizable floating accessibility handle for 1-tap Type Anywhere. */
public final class AnywhereHandleView extends View {
  public interface Listener {
    void onTap();
    void onDragStart();
    void onDragMove(float dx, float dy);
    void onDragEnd();
  }

  private static final int MIN_TOUCH_DP = 36;
  private int visualDp;
  private int visualPx;
  private int windowPx;
  private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);

  public AnywhereHandleView(Context c, int sizeDp, Listener listener) {
    super(c);
    bg.setColor(0xCC1A1B20);
    stroke.setStyle(Paint.Style.STROKE);
    stroke.setStrokeWidth(Math.max(1, dp(c, 1)));
    stroke.setColor(0x44FFFFFF);
    setAlpha(0.72f);
    setContentDescription("Type anywhere");
    setVisualSizeDp(sizeDp);

    final float slop = Math.min(ViewConfiguration.get(c).getScaledTouchSlop(), dp(c, 5));
    setOnTouchListener(new OnTouchListener() {
      float downX, downY;
      boolean moved;

      @Override
      public boolean onTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
          case MotionEvent.ACTION_DOWN -> {
            downX = e.getRawX();
            downY = e.getRawY();
            moved = false;
            setAlpha(1f);
            return true;
          }
          case MotionEvent.ACTION_MOVE -> {
            float dx = e.getRawX() - downX;
            float dy = e.getRawY() - downY;
            if (!moved && Math.hypot(dx, dy) > slop) {
              moved = true;
              setScaleX(1.08f);
              setScaleY(1.08f);
              performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
              listener.onDragStart();
            }
            if (moved) {
              listener.onDragMove(dx, dy);
            }
            return true;
          }
          case MotionEvent.ACTION_UP -> {
            setAlpha(0.72f);
            setScaleX(1f);
            setScaleY(1f);
            if (moved) listener.onDragEnd();
            else listener.onTap();
            return true;
          }
          case MotionEvent.ACTION_CANCEL -> {
            setAlpha(0.72f);
            setScaleX(1f);
            setScaleY(1f);
            if (moved) listener.onDragEnd();
            return true;
          }
          default -> {
            return false;
          }
        }
      }
    });
  }

  public void setVisualSizeDp(int sizeDp) {
    visualDp = sizeDp;
    visualPx = dp(getContext(), sizeDp);
    windowPx = Math.max(visualPx, dp(getContext(), MIN_TOUCH_DP));
    requestLayout();
    invalidate();
  }

  public int visualSizeDp() {
    return visualDp;
  }

  public int windowSizePx() {
    return windowPx;
  }

  @Override
  protected void onMeasure(int w, int h) {
    setMeasuredDimension(windowPx, windowPx);
  }

  @Override
  protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
    super.onLayout(changed, left, top, right, bottom);
    int w = right - left;
    int h = bottom - top;
    if (w > 0 && h > 0) {
      setSystemGestureExclusionRects(Collections.singletonList(new Rect(0, 0, w, h)));
    }
  }

  @Override
  protected void onDraw(Canvas c) {
    float cx = getWidth() * 0.5f;
    float cy = getHeight() * 0.5f;
    float r = visualPx * 0.5f - stroke.getStrokeWidth();
    if (r <= 1f) return;
    c.drawCircle(cx, cy, r, bg);
    c.drawCircle(cx, cy, r, stroke);
    float markSize = Math.max(dp(getContext(), 9), visualPx * 0.48f);
    drawHexMark(c, cx, cy, markSize, TEXT, MIC);
  }
}
