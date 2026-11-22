package anomaly.hex.android.anywhere;

import static anomaly.hex.android.ui.HexTheme.*;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import java.util.Collections;

/** Draggable, resizable floating handle: tap to open, drag to move, hold to speak-and-ask. */
public final class AnywhereHandleView extends View {
  public interface Listener {
    void onTap();
    void onHoldStart();
    void onHoldEnd();
    void onDragStart();
    void onDragMove(float dx, float dy);
    void onDragEnd();
  }

  private static final int MIN_TOUCH_DP = 36;
  private static final long HOLD_DELAY_MS = 320L;
  private int visualDp;
  private int visualPx;
  private int windowPx;
  private boolean listening;
  private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);

  public AnywhereHandleView(Context c, int sizeDp, Listener listener) {
    super(c);
    bg.setColor(0xCC1A1B20);
    stroke.setStyle(Paint.Style.STROKE);
    stroke.setStrokeWidth(Math.max(1, dp(c, 1)));
    stroke.setColor(0x44FFFFFF);
    ring.setStyle(Paint.Style.STROKE);
    ring.setStrokeWidth(Math.max(2, dp(c, 2)));
    ring.setColor(MIC);
    setAlpha(0.72f);
    setContentDescription("Type anywhere");
    setVisualSizeDp(sizeDp);

    final float slop = Math.min(ViewConfiguration.get(c).getScaledTouchSlop(), dp(c, 5));
    setOnTouchListener(new OnTouchListener() {
      float downX, downY;
      boolean moved, holding;
      final Runnable triggerHold = () -> {
        if (!moved) {
          holding = true;
          setListening(true);
          setScaleX(1.22f);
          setScaleY(1.22f);
          performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
          listener.onHoldStart();
        }
      };

      @Override
      public boolean onTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
          case MotionEvent.ACTION_DOWN -> {
            downX = e.getRawX();
            downY = e.getRawY();
            moved = false;
            holding = false;
            setAlpha(1f);
            removeCallbacks(triggerHold);
            postDelayed(triggerHold, HOLD_DELAY_MS);
            return true;
          }
          case MotionEvent.ACTION_MOVE -> {
            if (holding) return true;
            float dx = e.getRawX() - downX;
            float dy = e.getRawY() - downY;
            if (!moved && Math.hypot(dx, dy) > slop) {
              moved = true;
              removeCallbacks(triggerHold);
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
            removeCallbacks(triggerHold);
            setAlpha(0.72f);
            setScaleX(1f);
            setScaleY(1f);
            if (holding) {
              holding = false;
              setListening(false);
              listener.onHoldEnd();
            } else if (moved) {
              listener.onDragEnd();
            } else {
              listener.onTap();
            }
            return true;
          }
          case MotionEvent.ACTION_CANCEL -> {
            removeCallbacks(triggerHold);
            setAlpha(0.72f);
            setScaleX(1f);
            setScaleY(1f);
            if (holding) {
              holding = false;
              setListening(false);
              listener.onHoldEnd();
            } else if (moved) {
              listener.onDragEnd();
            }
            return true;
          }
          default -> {
            return false;
          }
        }
      }
    });
  }

  public void setListening(boolean active) {
    if (listening != active) {
      listening = active;
      invalidate();
    }
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
    float r = visualPx * 0.5f - (listening ? ring.getStrokeWidth() : stroke.getStrokeWidth());
    if (r <= 1f) return;
    c.drawCircle(cx, cy, r, bg);
    c.drawCircle(cx, cy, r, listening ? ring : stroke);
    float markSize = Math.max(dp(getContext(), 9), visualPx * 0.48f);
    drawHexMark(c, cx, cy, markSize, listening ? MIC : TEXT, MIC);
  }
}
