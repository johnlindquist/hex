package anomaly.hex.android.keyboard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.transition.Fade;
import android.transition.TransitionManager;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import anomaly.hex.android.core.TranscriptStyle;
import anomaly.hex.android.speech.SpeechLanguage;
import anomaly.hex.android.ui.DotScopeView;

/** Compact keyboard. All recording, editor and persistent settings actions belong to the IME. */
public final class DictationBarView extends LinearLayout {
  public interface Host {
    void onDictateToggle();
    void onStartDictation();
    void onStopDictation();
    void onCancel();
    void onUndo();
    void onOpenApp();
    void onSwitchKeyboard();
    void onSpace();
    void onDelete();
    void onReturn();
    void onStyleSelected(TranscriptStyle style);
    void onLanguageSelected(String id);
    void onKeyFeedback();
  }

  private enum Tray { NONE, STYLE_TRAY, LANG_TRAY }
  private enum Glyph { CANCEL, GLOBE, DELETE, RETURN, MIC, CHEVRON, UNDO }
  private static final int KEY = 0x1BFFFFFF, PRESSED = 0x30FFFFFF, MUTED = 0x8FFFFFFF;
  private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
  private final Host host;
  private final FrameLayout top;
  private final LinearLayout normal, dictate;
  private final View dot, undo, cancel, returnKey, languageTrigger, styleTrigger;
  private final TextView status, language, style, done;
  private final DotScopeView scope;
  private final Stage stage;
  private TranscriptStyle activeStyle = TranscriptStyle.POLISHED;
  private String activeLanguage = SpeechLanguage.AUTO;
  private Tray tray = Tray.NONE;
  private long started;
  private final Runnable clock = this::updateClock;
  private boolean listening, transcribing, undoable;

  public DictationBarView(Context c, Host host) {
    super(c);
    this.host = host;
    setOrientation(VERTICAL);
    setBackgroundColor(KeyboardPalette.BACKDROP);
    setPadding(dp(10), dp(4), dp(10), dp(6));
    top = new FrameLayout(c);
    addView(top, new LayoutParams(-1, dp(26)));
    normal = horizontal();
    top.addView(normal);
    HexMarkView mark = new HexMarkView(c);
    action(mark, "Open Hex", host::onOpenApp, 0, 13);
    normal.addView(mark, cell(22, 26, 0));
    LinearLayout cluster = horizontal();
    normal.addView(cluster, cell(0, 26, 8));
    dot = new View(c);
    cluster.addView(dot, cell(6, 6, 0));
    status = label("Ready", 13, MUTED);
    status.setFontFeatureSettings("tnum");
    cluster.addView(status, cell(-2, 26, 6));
    undo = compound(label("Undo dictation", 13, -1), Glyph.UNDO, true, "Undo dictation", host::onUndo, 0x1CFFFFFF);
    cluster.addView(undo, cell(-2, 26, 0));
    language = label("Auto", 13, MUTED);
    style = label(activeStyle.label(), 13, MUTED);
    languageTrigger = compound(language, Glyph.CHEVRON, false, "Language", () -> show(Tray.LANG_TRAY), 0);
    styleTrigger = compound(style, Glyph.CHEVRON, false, "Style", () -> show(Tray.STYLE_TRAY), 0);
    normal.addView(languageTrigger, cell(-2, 26, 0));
    normal.addView(styleTrigger, cell(-2, 26, 0));

    stage = new Stage(c);
    row(stage, 44);
    stage.setPadding(dp(5), 0, dp(5), 0);
    GradientDrawable well = shape(0x0DFFFFFF, 22);
    well.setStroke(Math.max(1, dp(.5f)), 0x12FFFFFF);
    stage.setBackground(well);
    cancel = icon(Glyph.CANCEL, "Cancel dictation", host::onCancel, 0x16FFFFFF, 17);
    stage.addView(cancel, cell(34, 34, 0));
    scope = new DotScopeView(c);
    stage.addView(scope, cell(0, 34, 0));
    dictate = compound(label("Dictate", 15, -1), Glyph.MIC, true, "Dictate", host::onDictateToggle, 0x17FFFFFF);
    stage.addView(dictate, cell(94, 34, 6));
    done = label("Done", 15, -1);
    action(done, "Finish dictation", host::onStopDictation, 0x26FFFFFF, 17);
    stage.addView(done, cell(66, 34, 6));

    LinearLayout bottom = horizontal();
    row(bottom, 42);
    bottom.addView(icon(Glyph.GLOBE, "Switch keyboard", host::onSwitchKeyboard, KEY, 8.5f), cell(44,42,0));
    TextView space = label("space", 15, MUTED);
    action(space, "Space", host::onSpace, KEY, 8.5f);
    bottom.addView(space, cell(0,42,6));
    View delete = icon(Glyph.DELETE, "Delete", host::onDelete, KEY, 8.5f);
    bottom.addView(delete, cell(52,42,6));
    repeatDelete(delete);
    returnKey = icon(Glyph.RETURN, "Return", host::onReturn, KEY, 8.5f);
    bottom.addView(returnKey, cell(56,42,6));
    refresh();
  }

  public void setMeta(TranscriptStyle activeStyle, String activeLangId, String activeLangLabel) {
    this.activeStyle = activeStyle;
    activeLanguage = activeLangId;
    style.setText(activeStyle.label());
    language.setText(activeLangLabel);
    languageTrigger.setContentDescription("Language: " + activeLangLabel);
    styleTrigger.setContentDescription("Style: " + activeStyle.label());
    show(Tray.NONE);
  }

  void resetGesture() { stage.clear(); }

  public void setReturnAction(boolean primary) { returnKey.setBackground(button(primary ? 0xFF007AFF : KEY, 8.5f)); }

  private void show(Tray next) {
    if (tray == next) return;
    tray = next;
    TransitionManager.beginDelayedTransition(top, new Fade().setDuration(180));
    top.removeAllViews();
    if (next == Tray.NONE) { top.addView(normal); return; }
    LinearLayout strip = horizontal(), choices = horizontal();
    top.addView(strip);
    // Keep the close control reachable in narrow editors and at large font scales.
    HorizontalScrollView scroll = new HorizontalScrollView(getContext());
    scroll.setHorizontalScrollBarEnabled(false);
    scroll.addView(choices);
    strip.addView(scroll, cell(0,26,0));
    if (next == Tray.STYLE_TRAY) {
      for (TranscriptStyle s : TranscriptStyle.values()) chip(choices, s.label(), s == activeStyle, () -> host.onStyleSelected(s));
    } else {
      chip(choices, "Auto", SpeechLanguage.AUTO.equals(activeLanguage), () -> host.onLanguageSelected(SpeechLanguage.AUTO));
      for (String id : SpeechLanguage.supportedIds()) chip(choices, HexInputMethodService.languageLabel(id), id.equals(activeLanguage), () -> host.onLanguageSelected(id));
    }
    strip.addView(icon(Glyph.CANCEL, "Close picker", () -> show(Tray.NONE), 0x12FFFFFF, 13), cell(26,26,4));
  }

  private void chip(LinearLayout choices, String title, boolean selected, Runnable select) {
    TextView chip = label(title, 13, selected ? -1 : MUTED);
    chip.setPadding(dp(10), 0, dp(10), 0);
    chip.setSelected(selected);
    action(chip, title, () -> { select.run(); show(Tray.NONE); }, selected ? 0x29FFFFFF : 0x12FFFFFF, 13);
    choices.addView(chip, cell(-2,26,choices.getChildCount() == 0 ? 0 : 4));
  }

  public void setDictationState(String state, int[] meterLevels) {
    boolean live = "listening".equals(state), busy = "transcribing".equals(state);
    scope.update(live, meterLevels);
    if (live == listening && busy == transcribing) return;
    if (live) started = SystemClock.elapsedRealtime();
    listening = live; transcribing = busy;
    refresh();
  }
  public void setUndoVisible(boolean visible) { if (undoable != visible) { undoable = visible; refresh(); } }
  private void refresh() {
    boolean idle = !listening && !transcribing, showUndo = idle && undoable;
    removeCallbacks(clock);
    if (listening) updateClock(); else status.setText(transcribing ? "Transcribing…" : "Ready");
    int size = dp(listening ? 6.5f : 6);
    dot.setLayoutParams(new LayoutParams(size, size));
    dot.setBackground(shape(listening ? 0xFFE54D42 : 0xFFFF9500, 4));
    dot.setVisibility(showUndo || transcribing ? GONE : VISIBLE);
    status.setVisibility(showUndo ? GONE : VISIBLE);
    undo.setVisibility(showUndo ? VISIBLE : GONE);
    cancel.setVisibility(idle ? GONE : VISIBLE);
    dictate.setVisibility(idle ? VISIBLE : GONE);
    done.setVisibility(idle ? GONE : VISIBLE);
    done.setText(transcribing ? "…" : "Done");
    done.setContentDescription(transcribing ? "Transcribing" : "Finish dictation");
    done.setEnabled(!transcribing);
    done.setBackground(button(transcribing ? 0x16FFFFFF : 0x26FFFFFF, 17));
  }
  private void updateClock() {
    long elapsed = SystemClock.elapsedRealtime() - started, sec = elapsed / 1000;
    status.setText(sec / 60 + ":" + (sec % 60 < 10 ? "0" : "") + sec % 60);
    if (isAttachedToWindow()) postDelayed(clock, 1000 - elapsed % 1000);
  }
  @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); if (listening) updateClock(); }
  @Override protected void onDetachedFromWindow() { removeCallbacks(clock); super.onDetachedFromWindow(); }

  /** Own the gesture on the persistent well: the Dictate button disappears when a hold starts. */
  private final class Stage extends LinearLayout {
    private boolean tracking, held;
    private final Runnable hold = () -> { if (tracking) { held = true; host.onStartDictation(); } };
    Stage(Context c) { super(c); }
    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
      if (e.getActionMasked() != MotionEvent.ACTION_DOWN || dictate.getVisibility() != VISIBLE) return false;
      return e.getX() >= dictate.getLeft() && e.getX() < dictate.getRight() && e.getY() >= dictate.getTop() && e.getY() < dictate.getBottom();
    }
    @Override public boolean onTouchEvent(MotionEvent e) {
      switch (e.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
          if (!onInterceptTouchEvent(e)) return false;
          tracking = true; held = false; dictate.setPressed(true); host.onKeyFeedback(); postDelayed(hold, 350); break;
        case MotionEvent.ACTION_MOVE:
          if (tracking && !held && (e.getX() < dictate.getLeft() || e.getX() >= dictate.getRight() || e.getY() < dictate.getTop() || e.getY() >= dictate.getBottom())) clear();
          break;
        case MotionEvent.ACTION_UP:
          if (tracking) { if (!held) dictate.performClick(); else if (listening) host.onStopDictation(); }
          clear(); break;
        case MotionEvent.ACTION_CANCEL:
          if (held && listening) host.onCancel();
          clear(); break;
      }
      return true;
    }
    private void clear() { removeCallbacks(hold); tracking = held = false; dictate.setPressed(false); }
    @Override protected void onDetachedFromWindow() { clear(); super.onDetachedFromWindow(); }
  }

  private void repeatDelete(View key) {
    key.setOnTouchListener(new OnTouchListener() {
      boolean held, repeated;
      final Runnable repeat = new Runnable() { public void run() {
        if (!held || !key.isAttachedToWindow() || !key.isPressed()) return;
        repeated = true; host.onKeyFeedback(); key.performClick(); key.postDelayed(this, 75);
      }};
      public boolean onTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
          case MotionEvent.ACTION_DOWN:
            held = true; repeated = false; host.onKeyFeedback(); key.setPressed(true); key.postDelayed(repeat, 360); break;
          case MotionEvent.ACTION_MOVE:
            if (e.getX() >= 0 && e.getX() < key.getWidth() && e.getY() >= 0 && e.getY() < key.getHeight()) break;
          case MotionEvent.ACTION_CANCEL:
          case MotionEvent.ACTION_UP:
            if (e.getActionMasked() == MotionEvent.ACTION_UP && held && !repeated) key.performClick();
            held = false; key.removeCallbacks(repeat); key.setPressed(false); break;
        }
        return true;
      }
    });
  }

  private final class Icon extends View {
    final Glyph glyph;
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    Icon(Glyph glyph) { super(DictationBarView.this.getContext()); this.glyph = glyph; }
    @Override protected void onDraw(Canvas c) {
      c.save(); c.translate(getWidth()/2f, getHeight()/2f);
      float d = getResources().getDisplayMetrics().density;
      c.scale(d,d);
      p.setStyle(Paint.Style.STROKE); p.setColor(MUTED); p.setStrokeWidth(1.5f); p.setStrokeCap(Paint.Cap.ROUND);
      switch (glyph) {
        case GLOBE -> KeyIcons.drawGlobe(c,0,0,20,-1);
        case DELETE -> KeyIcons.drawDelete(c,0,0,20,-1);
        case RETURN -> KeyIcons.drawReturn(c,0,0,20,-1);
        case CANCEL -> KeyIcons.drawCancel(c,0,0,18,MUTED);
        case CHEVRON -> { c.drawLine(-3,-1.5f,0,1.5f,p); c.drawLine(0,1.5f,3,-1.5f,p); }
        case MIC -> { c.drawRoundRect(-2.3f,-6,2.3f,1.5f,2.3f,2.3f,p); c.drawArc(-5,-5,5,4,0,180,false,p); c.drawLine(0,4,0,6,p); c.drawLine(-2,6,2,6,p); }
        case UNDO -> { c.drawArc(-5,-3,5,5,-90,155,false,p); c.drawLine(0,-3,-5,-3,p); c.drawLine(-5,-3,-2,-6,p); c.drawLine(-5,-3,-2,0,p); }
      }
      c.restore();
    }
  }
  private View icon(Glyph g, String name, Runnable run, int fill, float radius) {
    Icon v = new Icon(g); action(v,name,run,fill,radius); return v;
  }
  private LinearLayout compound(TextView text, Glyph glyph, boolean leading, String name, Runnable run, int fill) {
    LinearLayout v = horizontal(); v.setGravity(Gravity.CENTER); v.setPadding(dp(8),0,dp(8),0);
    if (leading) v.addView(new Icon(glyph), cell(14,16,0));
    v.addView(text, cell(-2,26,leading ? 6 : 0));
    if (!leading) v.addView(new Icon(glyph), cell(8,12,4));
    action(v,name,run,fill,17); return v;
  }
  private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }
  private LayoutParams cell(int w, int h, int gap) {
    LayoutParams lp = new LayoutParams(w < 0 ? w : dp(w), dp(h), w == 0 ? 1f : 0f);
    lp.setMarginStart(dp(gap)); return lp;
  }
  private LinearLayout horizontal() { LinearLayout v = new LinearLayout(getContext()); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
  private void row(LinearLayout v, int height) {
    v.setGravity(Gravity.CENTER_VERTICAL); LayoutParams lp = new LayoutParams(-1,dp(height)); lp.topMargin = dp(8); addView(v,lp);
  }
  private GradientDrawable shape(int fill, float radius) {
    GradientDrawable d = new GradientDrawable(); d.setColor(fill); d.setCornerRadius(radius * getResources().getDisplayMetrics().density); return d;
  }
  private StateListDrawable button(int fill, float radius) {
    StateListDrawable d = new StateListDrawable(); d.addState(new int[]{android.R.attr.state_pressed},shape(PRESSED,radius)); d.addState(new int[]{},shape(fill,radius)); return d;
  }
  private TextView label(String text, int size, int color) {
    TextView v = new TextView(getContext()); v.setGravity(Gravity.CENTER); v.setSingleLine(); v.setEllipsize(TextUtils.TruncateAt.END);
    v.setTextColor(color); v.setTextSize(size); v.setTypeface(MEDIUM); v.setText(text); return v;
  }
  private void action(View v, String name, Runnable run, int fill, float radius) {
    v.setContentDescription(name); v.setBackground(button(fill,radius)); v.setFocusable(true);
    v.setOnClickListener(ignored -> run.run());
    v.setOnTouchListener((key,e) -> { if (e.getActionMasked() == MotionEvent.ACTION_DOWN) host.onKeyFeedback(); return false; });
  }
}
