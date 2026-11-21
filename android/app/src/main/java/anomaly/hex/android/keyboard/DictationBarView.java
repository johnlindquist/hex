package anomaly.hex.android.keyboard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.os.SystemClock;
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
  private static final int TEXT_MUTED = 0x8FFFFFFF, CHIP_FILL = 0x1CFFFFFF;
  private final TextView meta, status, done, undo;
  private final View cancel;
  private long started;
  private final Runnable clock = this::updateClock;
  private final LinearLayout dictate;
  private final DotScopeView scope;
  private boolean listening, transcribing, undoable;

  public DictationBarView(Context context, Host host) {
    super(context);
    setOrientation(VERTICAL);
    setBackgroundColor(0xFF17181A);
    setPadding(dp(10), dp(4), dp(10), dp(12));
    LinearLayout top = row(28, 0);
    HexMarkView open = new HexMarkView(context);
    action(open, "Open Hex", host::onOpenApp, 0, 12);
    top.addView(open, cell(22, 24, 0));
    status = label("Ready", 12);
    status.setTextColor(TEXT_MUTED);
    top.addView(status, cell(-2, 28, 6));
    undo = key("Undo dictation", "Undo dictation", host::onUndo, CHIP_FILL, 13);
    top.addView(undo, cell(-2, 26, 6));
    top.addView(new View(context), cell(0, 1, 0));
    meta = key("", "", host::onStyleCycle, 0x17FFFFFF, 13);
    meta.setTextSize(12);
    meta.setPadding(dp(10), 0, dp(10), 0);
    top.addView(meta, cell(-2, 26, 6));
    setMeta("Polished", "Auto");

    LinearLayout stage = row(48, 8);
    stage.setPadding(dp(6), 0, dp(6), 0);
    stage.setBackground(shape(0x0EFFFFFF, 24));
    cancel = icon(Glyph.CANCEL, "Cancel dictation", host::onCancel, 0x17FFFFFF, 18);
    stage.addView(cancel, cell(36, 36, 0));
    scope = new DotScopeView(context);
    stage.addView(scope, cell(0, 38, 0));
    dictate = new LinearLayout(context);
    dictate.setGravity(Gravity.CENTER);
    action(dictate, "Dictate", host::onDictateToggle, KEY, 17);
    View dot = new View(context);
    dot.setBackground(shape(0xFFFF9500, 4));
    dictate.addView(dot, cell(8, 8, 0));
    TextView dictateText = label("Dictate", 15);
    Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    dictateText.setTypeface(medium);
    dictate.addView(dictateText, cell(-2, 34, 6));
    stage.addView(dictate, cell(94, 34, 6));
    done = key("Done", "Finish dictation", host::onDictateToggle, 0xFF007AFF, 17);
    done.setTypeface(medium);
    stage.addView(done, cell(68, 34, 6));

    LinearLayout bottom = row(44, 8);
    bottom.addView(icon(Glyph.GLOBE, "Switch keyboard", host::onSwitchKeyboard, KEY, 8.5f), cell(46, 44, 0));
    TextView space = key("space", "Space", host::onSpace, KEY, 8.5f);
    space.setTextColor(TEXT_MUTED);
    bottom.addView(space, cell(0, 44, 6));
    View delete = icon(Glyph.DELETE, "Delete", host::onDelete, KEY, 8.5f);
    bottom.addView(delete, cell(54, 44, 6));
    installRepeat(delete);
    bottom.addView(icon(Glyph.RETURN, "Return", host::onReturn, KEY, 8.5f), cell(58, 44, 6));
    refresh();
  }

  public void setMeta(String styleLabel, String langLabel) {
    meta.setText(styleLabel + " · " + ("auto".equalsIgnoreCase(langLabel) ? "Auto" : langLabel) + " ▾");
    meta.setContentDescription(meta.getText() + ", cycle style");
  }

  public void setDictationState(String state, int[] meterLevels) {
    boolean live = "listening".equals(state), busy = "transcribing".equals(state);
    scope.update(live, meterLevels);
    if (live == listening && busy == transcribing) return;
    if (live) started = SystemClock.elapsedRealtime();
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
    removeCallbacks(clock);
    if (listening) updateClock();
    else status.setText(transcribing ? "Transcribing…" : "Ready");
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

  private void updateClock() {
    long elapsed = SystemClock.elapsedRealtime() - started, sec = elapsed / 1000;
    status.setText("Listening… " + sec / 60 + ":" + (sec % 60 < 10 ? "0" : "") + sec % 60);
    if (isAttachedToWindow()) postDelayed(clock, 1000 - elapsed % 1000);
  }
  @Override protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    if (listening) updateClock();
  }
  @Override protected void onDetachedFromWindow() {
    removeCallbacks(clock);
    super.onDetachedFromWindow();
  }

  private enum Glyph { CANCEL, GLOBE, DELETE, RETURN }
  private View icon(Glyph glyph, String name, Runnable run, int fill, float radius) {
    View key = new Icon(getContext(), glyph);
    action(key, name, run, fill, radius);
    return key;
  }
  private static final class Icon extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final float density;
    Icon(Context c, Glyph glyph) {
      super(c);
      density = getResources().getDisplayMetrics().density;
      paint.setColor(0xFFFFFFFF); paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(1.6f); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
      switch (glyph) {
        case CANCEL:
          line(-5,-5,5,5); line(-5,5,5,-5); break;
        case GLOBE:
          path.addCircle(0,0,9,Path.Direction.CW); path.addOval(-4,-9,4,9,Path.Direction.CW);
          line(-8,-3,8,-3); line(-8,3,8,3); break;
        case DELETE:
          path.moveTo(-10,0); path.lineTo(-4,-7); path.lineTo(10,-7); path.lineTo(10,7);
          path.lineTo(-4,7); path.close(); line(-1,-3,5,3); line(-1,3,5,-3); break;
        case RETURN:
          path.moveTo(9,-6); path.lineTo(9,2); path.lineTo(-8,2);
          path.moveTo(-3,-3); path.lineTo(-8,2); path.lineTo(-3,7); break;
      }
    }
    private void line(float x, float y, float endX, float endY) { path.moveTo(x,y); path.lineTo(endX,endY); }
    @Override protected void onDraw(Canvas c) {
      c.save(); c.translate(getWidth() / 2f, getHeight() / 2f); c.scale(density, density);
      c.drawPath(path, paint); c.restore();
    }
  }

  private void installRepeat(View key) {
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
    v.setTextColor(0xFFFFFFFF);
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
