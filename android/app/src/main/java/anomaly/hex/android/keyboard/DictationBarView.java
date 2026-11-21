package anomaly.hex.android.keyboard;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import anomaly.hex.android.ui.DotScopeView;

/** The compact three-row keyboard; the input method owns all editor and speech actions. */
public final class DictationBarView extends LinearLayout {
  public interface Host {
    void onDictateToggle();
    void onCancel();
    void onUndo();
    void onOpenApp();
    void onSwitchKeyboard();
    void onSpace();
    void onDelete();
    void onReturn();
    void onStyleCycle();
  }

  private static final int KEY = 0x1BFFFFFF, PRESSED = 0x30FFFFFF;
  private final TextView meta, status, cancel, done, undo;
  private final LinearLayout dictate;
  private final DotScopeView scope;
  private boolean listening, transcribing, undoable;

  public DictationBarView(Context context, Host host) {
    super(context);
    setOrientation(VERTICAL);
    setBackgroundColor(0xFF17181A);
    setPadding(dp(10), dp(4), dp(10), dp(6));
    LinearLayout top = row(28, 0);
    TextView open = key("Hex", "Open Hex", host::onOpenApp, 0, 12);
    top.addView(open, cell(42, 28, 0));
    meta = key("", "", host::onStyleCycle, KEY, 14);
    top.addView(meta, cell(0, 26, 6));
    setMeta("Polished", "EN");
    status = label("Ready", 12);
    status.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
    status.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
    top.addView(status, cell(-2, 28, 8));
    undo = key("Undo dictation", "Undo dictation", host::onUndo, KEY, 14);
    top.addView(undo, cell(-2, 26, 6));

    LinearLayout stage = row(48, 8);
    stage.setPadding(dp(6), 0, dp(6), 0);
    stage.setBackground(shape(0x0EFFFFFF, 24));
    cancel = key("×", "Cancel dictation", host::onCancel, 0x17FFFFFF, 18);
    cancel.setTextSize(22);
    stage.addView(cancel, cell(36, 36, 0));
    scope = new DotScopeView(context);
    stage.addView(scope, cell(0, 38, 0));
    dictate = new LinearLayout(context);
    dictate.setGravity(Gravity.CENTER);
    action(dictate, "Dictate", host::onDictateToggle, KEY, 17);
    View dot = new View(context);
    dot.setBackground(shape(0xFFFF9500, 4));
    dictate.addView(dot, cell(8, 8, 0));
    dictate.addView(label("Dictate", 15), cell(-2, 34, 6));
    stage.addView(dictate, cell(94, 34, 6));
    done = key("Done", "Finish dictation", host::onDictateToggle, 0xFF007AFF, 17);
    stage.addView(done, cell(68, 34, 6));

    LinearLayout bottom = row(44, 8);
    bottom.addView(key("🌐", "Switch keyboard", host::onSwitchKeyboard, KEY, 8.5f), cell(46, 44, 0));
    bottom.addView(key("space", "Space", host::onSpace, KEY, 8.5f), cell(0, 44, 6));
    TextView delete = key("⌫", "Delete", host::onDelete, KEY, 8.5f);
    bottom.addView(delete, cell(54, 44, 6));
    installRepeat(delete);
    bottom.addView(key("↵", "Return", host::onReturn, KEY, 8.5f), cell(58, 44, 6));
    refresh();
  }

  public void setMeta(String styleLabel, String langLabel) {
    meta.setText(styleLabel + " · " + langLabel);
    meta.setContentDescription(meta.getText() + ", cycle style");
  }

  public void setDictationState(String state, int[] meterLevels) {
    boolean live = "listening".equals(state), busy = "transcribing".equals(state);
    scope.update(live, meterLevels);
    if (live == listening && busy == transcribing) return;
    listening = live;
    transcribing = busy;
    refresh();
  }

  public void setUndoVisible(boolean visible) {
    if (visible == undoable) return;
    undoable = visible;
    refresh();
  }

  private void refresh() {
    boolean idle = !listening && !transcribing;
    status.setText(listening ? "Listening…" : transcribing ? "Transcribing…" : "Ready");
    status.setVisibility(idle && undoable ? GONE : VISIBLE);
    undo.setVisibility(idle && undoable ? VISIBLE : GONE);
    cancel.setVisibility(idle ? GONE : VISIBLE);
    dictate.setVisibility(idle ? VISIBLE : GONE);
    done.setVisibility(idle ? GONE : VISIBLE);
    done.setText(transcribing ? "…" : "Done");
    done.setContentDescription(transcribing ? "Transcribing" : "Finish dictation");
    done.setEnabled(!transcribing);
    done.setBackground(button(transcribing ? KEY : 0xFF007AFF, 17));
  }

  private void installRepeat(TextView key) {
    key.setOnTouchListener(new OnTouchListener() {
      boolean held, repeated;
      final Runnable repeat = new Runnable() {
        public void run() {
          if (!held || !key.isAttachedToWindow() || !key.isPressed()) return;
          repeated = true;
          key.performClick();
          key.postDelayed(this, 75);
        }
      };
      public boolean onTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
          case MotionEvent.ACTION_DOWN:
            held = true;
            repeated = false;
            key.setPressed(true);
            key.postDelayed(repeat, 360);
            break;
          case MotionEvent.ACTION_MOVE:
            if (e.getX() >= 0 && e.getX() < key.getWidth() && e.getY() >= 0 && e.getY() < key.getHeight()) break;
            // Leaving the key cancels this gesture.
          case MotionEvent.ACTION_CANCEL:
          case MotionEvent.ACTION_UP:
            boolean tap = e.getActionMasked() == MotionEvent.ACTION_UP && held && !repeated;
            held = false;
            key.removeCallbacks(repeat);
            key.setPressed(false);
            if (tap) key.performClick();
            break;
        }
        return true;
      }
    });
  }

  private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }
  private LayoutParams cell(int w, int h, int gap) {
    LayoutParams lp = new LayoutParams(w < 0 ? w : dp(w), dp(h), w == 0 ? 1f : 0f);
    lp.setMarginStart(dp(gap));
    return lp;
  }
  private LinearLayout row(int height, int gap) {
    LinearLayout row = new LinearLayout(getContext());
    row.setGravity(Gravity.CENTER_VERTICAL);
    LayoutParams lp = new LayoutParams(-1, dp(height));
    lp.topMargin = dp(gap);
    addView(row, lp);
    return row;
  }
  private GradientDrawable shape(int fill, float radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(fill);
    d.setCornerRadius(dp(radius));
    return d;
  }
  private StateListDrawable button(int fill, float radius) {
    StateListDrawable d = new StateListDrawable();
    d.addState(new int[]{android.R.attr.state_pressed}, shape(PRESSED, radius));
    d.addState(new int[]{}, shape(fill, radius));
    return d;
  }
  private TextView label(String text, int size) {
    TextView v = new TextView(getContext());
    v.setGravity(Gravity.CENTER);
    v.setSingleLine();
    v.setEllipsize(TextUtils.TruncateAt.END);
    v.setTextColor(0xFFF2F2F7);
    v.setTextSize(size);
    v.setText(text);
    return v;
  }
  private void action(View v, String name, Runnable run, int fill, float radius) {
    v.setContentDescription(name);
    v.setBackground(button(fill, radius));
    v.setOnClickListener(ignored -> run.run());
    v.setFocusable(true);
  }
  private TextView key(String text, String name, Runnable run, int fill, float radius) {
    TextView key = label(text, 15);
    key.setPadding(dp(5), 0, dp(5), 0);
    action(key, name, run, fill, radius);
    return key;
  }
}
