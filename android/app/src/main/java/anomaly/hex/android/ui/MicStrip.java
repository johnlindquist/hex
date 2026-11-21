package anomaly.hex.android.ui;
import static anomaly.hex.android.ui.HexTheme.*;

import android.graphics.*;
import android.view.*;
import android.widget.*;
import anomaly.hex.android.service.HexBubbleService;

public final class MicStrip extends LinearLayout {
  private int imeBottom;
  private final HexBubbleService host;
  private final DotScopeView scope;
  private final Tile cancel, action, review, undo;
  private enum Icon { MARK, CANCEL, CHECK, WAIT, UNDO, REVIEW }
  private class Tile extends View {
    Icon icon;
    final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    Tile(Icon type, String label, Runnable run, int size) {
      super(host);
      setIcon(type);
      setContentDescription(label);
      setFocusable(true);
      setOnClickListener(v -> run.run());
      addView(this, dp(host, size), dp(host, size));
    }
    void setIcon(Icon type) {
      if (icon == type)
        return;
      icon = type;
      setBackground(ripple(host,
          type == Icon.MARK        ? 0
              : type == Icon.CHECK ? 0xff007aff
                                   : CONTROL,
          999));
      invalidate();
    }
    protected void onDraw(Canvas c) {
      float x = getWidth() / 2f, y = getHeight() / 2f;
      if (icon == Icon.MARK) {
        drawHexMark(c, x, y, dp(host, 22), TEXT, MIC);
        return;
      }
      c.save();
      c.translate(x, y);
      float scale = getResources().getDisplayMetrics().density;
      c.scale(scale, scale);
      ink.setColor(icon == Icon.WAIT ? MUTED : TEXT);
      ink.setStrokeWidth(1.8f);
      ink.setStrokeCap(Paint.Cap.ROUND);
      ink.setStyle(Paint.Style.STROKE);
      switch (icon) {
        case CANCEL -> {
          c.drawLine(-5, -5, 5, 5, ink);
          c.drawLine(5, -5, -5, 5, ink);
        }
        case CHECK -> {
          c.drawLine(-6, 0, -2, 4, ink);
          c.drawLine(-2, 4, 6, -5, ink);
        }
        case UNDO -> {
          c.drawArc(-6, -4, 6, 7, 235, 232, false, ink);
          c.drawLine(-5, -7, -6, -2, ink);
          c.drawLine(-6, -2, -1, -2, ink);
        }
        case REVIEW -> {
          for (int i = 0; i < 4; i++) {
            c.drawLine(0, -7, 2, -2, ink);
            c.drawLine(2, -2, 7, 0, ink);
            c.rotate(90);
          }
        }
        case WAIT -> {
          ink.setStyle(Paint.Style.FILL);
          for (int i = -1; i <= 1; i++) c.drawCircle(i * 5, 0, 1.5f, ink);
        }
      }
      c.restore();
    }
  }
  public MicStrip(HexBubbleService h) {
    super(h);
    host = h;
    setGravity(Gravity.CENTER_VERTICAL);
    setBackground(shape(h, 0xf217181a, 22, BORDER));
    setElevation(dp(h, 8));
    setPadding(dp(h, 5), dp(h, 5), dp(h, 5), dp(h, 5));
    setOnApplyWindowInsetsListener((view, insets) -> {
      int bottom = insets.getInsets(WindowInsets.Type.ime()).bottom;
      if (bottom != imeBottom) {
        imeBottom = bottom;
        post(host::positionStrip);
      }
      return insets;
    });
    cancel = new Tile(Icon.CANCEL, "Cancel dictation", () -> h.cancel("cancelled"), 34);
    scope = new DotScopeView(h);
    scope.setDrawPlate(false);
    scope.setBackground(shape(h, 0x0effffff, 999, 0));
    addView(scope, dp(h, 56), dp(h, 28));
    action = new Tile(Icon.MARK, "Start dictation", () -> {
      String code = h.isBusy() ? h.stop() : h.start();
      if (!"ok".equals(code))
        Toast.makeText(h, code.replace('_', ' '), Toast.LENGTH_SHORT).show();
    }, 34);
    undo = new Tile(Icon.UNDO, "Undo last dictation", h::undoLast, 32);
    review = new Tile(Icon.REVIEW, "Review cleanup", () -> h.reviewCleanup(h.passageId()), 32);
    dragOrClick(action);
    dragOrClick(scope);
  }
  private void dragOrClick(View v) {
    v.setOnTouchListener(new OnTouchListener() {
      float x, y;
      boolean moved;
      public boolean onTouch(View view, MotionEvent e) {
        switch (e.getActionMasked()) {
          case MotionEvent.ACTION_DOWN:
            x = e.getRawX();
            y = e.getRawY();
            moved = false;
            return true;
          case MotionEvent.ACTION_MOVE:
            if (!moved
                && Math.hypot(e.getRawX() - x, e.getRawY() - y)
                    > ViewConfiguration.get(getContext()).getScaledTouchSlop()) {
              host.placement.begin(MicStrip.this, x, y);
              moved = true;
            }
            if (moved) {
              host.placement.drag(MicStrip.this, e.getRawX(), e.getRawY());
              host.positionStrip();
            }
            return true;
          case MotionEvent.ACTION_UP:
          case MotionEvent.ACTION_CANCEL:
            if (moved) {
              host.placement.end(e.getActionMasked() == MotionEvent.ACTION_UP);
              host.positionStrip();
            } else if (e.getActionMasked() == MotionEvent.ACTION_UP)
              view.performClick();
            return true;
          default:
            return false;
        }
      }
    });
  }
  public void update(String state, int words, boolean canReview) {
    boolean busy = !state.equals("idle"), live = state.equals("listening");
    int width = dp(host, busy ? 92 : 56);
    ViewGroup.LayoutParams lp = scope.getLayoutParams();
    if (lp.width != width) {
      lp.width = width;
      lp.height = dp(host, busy ? 32 : 28);
      scope.setLayoutParams(lp);
    }
    scope.update(live, null);
    cancel.setVisibility(busy ? VISIBLE : GONE);
    action.setIcon(!busy ? Icon.MARK : live ? Icon.CHECK : Icon.WAIT);
    setContentDescription(words > 0 ? words + " confirmed words" : null);
    action.setContentDescription(!busy ? "Start dictation"
            : live                     ? "Finish dictation"
                                       : "Processing dictation");
    action.setEnabled(!busy || live);
    undo.setVisibility(!busy && host.canUndoLast() ? VISIBLE : GONE);
    review.setVisibility(!busy && canReview ? VISIBLE : GONE);
  }
  public void meter(int[] targets) {
    scope.update(true, targets);
  }
}
