package anomaly.hex.android.anywhere;

import static anomaly.hex.android.ui.HexTheme.*;

import android.content.Context;
import android.graphics.*;
import android.view.*;

/** Ultra-compact 28dp edge-docked accessibility handle for 1-tap Type Anywhere. */
public final class AnywhereHandleView extends View {
  public interface Listener {
    void onTap();
    void onDrag(float rawX, float rawY, boolean done);
  }
  private final int sizePx;
  private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);

  public AnywhereHandleView(Context c, Listener listener) {
    super(c);
    sizePx = dp(c, 28);
    bg.setColor(0xCC1A1B20);
    stroke.setStyle(Paint.Style.STROKE);
    stroke.setStrokeWidth(dp(c, 1));
    stroke.setColor(0x3DFFFFFF);
    setAlpha(0.68f);
    setContentDescription("Type anywhere");
    setElevation(dp(c, 4));
    setOnTouchListener(new OnTouchListener() {
      float downX, downY;
      boolean moved;
      @Override public boolean onTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
          case MotionEvent.ACTION_DOWN -> {
            downX = e.getRawX();
            downY = e.getRawY();
            moved = false;
            setAlpha(1f);
            return true;
          }
          case MotionEvent.ACTION_MOVE -> {
            if (!moved && Math.hypot(e.getRawX() - downX, e.getRawY() - downY)
                > ViewConfiguration.get(c).getScaledTouchSlop()) {
              moved = true;
            }
            if (moved) listener.onDrag(e.getRawX(), e.getRawY(), false);
            return true;
          }
          case MotionEvent.ACTION_UP -> {
            setAlpha(0.68f);
            if (moved) listener.onDrag(e.getRawX(), e.getRawY(), true);
            else listener.onTap();
            return true;
          }
          case MotionEvent.ACTION_CANCEL -> {
            setAlpha(0.68f);
            if (moved) listener.onDrag(e.getRawX(), e.getRawY(), true);
            return true;
          }
          default -> { return false; }
        }
      }
    });
  }

  @Override protected void onMeasure(int w, int h) {
    setMeasuredDimension(sizePx, sizePx);
  }

  @Override protected void onDraw(Canvas c) {
    float r = sizePx * 0.5f;
    c.drawCircle(r, r, r - stroke.getStrokeWidth(), bg);
    c.drawCircle(r, r, r - stroke.getStrokeWidth(), stroke);
    drawHexMark(c, r, r, dp(getContext(), 13), TEXT, MIC);
  }
}
