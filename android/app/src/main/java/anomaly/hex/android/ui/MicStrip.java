package anomaly.hex.android.ui;
import static anomaly.hex.android.ui.HexTheme.*;

import android.view.*;
import android.widget.*;
import anomaly.hex.android.service.HexBubbleService;

public final class MicStrip extends LinearLayout {
  private int imeBottom;
  private final HexBubbleService host;
  private final DotScopeView scope;
  private final TextView cancel, action, count, review;
  public MicStrip(HexBubbleService h) {
    super(h);
    host = h;
    setGravity(Gravity.CENTER_VERTICAL);
    setBackground(shape(h, SURFACE, 28, BORDER));
    setElevation(dp(h, 6));
    setOnApplyWindowInsetsListener((view, insets) -> {
      int bottom = insets.getInsets(WindowInsets.Type.ime()).bottom;
      if (bottom != imeBottom) {
        imeBottom = bottom;
        post(host::positionStrip);
      }
      return insets;
    });
    cancel = tile("×", "Cancel dictation", () -> h.cancel("cancelled"));
    scope = new DotScopeView(h);
    addView(scope, dp(h, 86), dp(h, 32));
    count = text(h, "", 12, MUTED);
    count.setPadding(dp(h, 6), 0, dp(h, 2), 0);
    addView(count);
    action = tile("✓", "Finish dictation", () -> {
      String code = h.isBusy() ? h.stop() : h.start();
      if (!"ok".equals(code))
        Toast.makeText(h, code.replace('_', ' '), Toast.LENGTH_SHORT).show();
    });
    review = tile("✦", "Review cleanup", () -> h.reviewCleanup(h.passageId()));
    dragOrClick(action);
    dragOrClick(scope);
  }
  private TextView tile(String title, String label, Runnable run) {
    TextView v = text(getContext(), title, 20, TEXT);
    v.setGravity(Gravity.CENTER);
    v.setContentDescription(label);
    v.setOnClickListener(t -> run.run());
    addView(v, dp(getContext(), 48), dp(getContext(), 48));
    return v;
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
    setPadding(dp(host, busy ? 4 : 12), dp(host, 3), dp(host, 4), dp(host, 3));
    scope.update(live, null);
    cancel.setVisibility(busy ? VISIBLE : GONE);
    count.setVisibility(words > 0 ? VISIBLE : GONE);
    count.setText(Integer.toString(words));
    count.setContentDescription(words + " confirmed words");
    action.setText(!busy ? "●" : live ? "✓" : "⋯");
    action.setTextColor(busy ? TEXT : MIC);
    action.setContentDescription(!busy ? "Start dictation"
            : live                     ? "Finish dictation"
                                       : "Processing dictation");
    action.setEnabled(!busy || live);
    review.setVisibility(!busy && canReview ? VISIBLE : GONE);
  }
  public void meter(int[] targets) {
    scope.update(true, targets);
  }
}
