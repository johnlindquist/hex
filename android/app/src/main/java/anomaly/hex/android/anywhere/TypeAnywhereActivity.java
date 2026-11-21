package anomaly.hex.android.anywhere;

import static anomaly.hex.android.keyboard.KeyboardPalette.*;

import android.app.Activity;
import android.content.*;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import anomaly.hex.android.core.*;
import anomaly.hex.android.ui.HexTheme;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.*;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

/** Minimalist bottom-sheet composer for Type Anywhere: thumbnail, chips, formatted answer, input. */
public final class TypeAnywhereActivity extends Activity {
  public static volatile boolean visible;
  private TypeAnywhereStore capture;
  private TextView status, badge, answer;
  private Button copy;
  private View chipsScroll;
  private LinearLayout card;
  private EditText input;
  private boolean reading, busy;
  private String pending, rawAnswer = "";
  private int generation;

  public static Intent intent(Context c, long capture, String note) {
    return new Intent(c, TypeAnywhereActivity.class)
        .putExtra("capture", capture)
        .putExtra("note", note)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
            | Intent.FLAG_ACTIVITY_CLEAR_TOP);
  }

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    open();
  }

  @Override protected void onResume() { super.onResume(); visible = true; anomaly.hex.android.service.HexBubbleService.refreshLabs(); }
  @Override protected void onPause() { visible = false; anomaly.hex.android.service.HexBubbleService.refreshLabs(); super.onPause(); }

  @Override
  protected void onNewIntent(Intent i) {
    super.onNewIntent(i);
    setIntent(i);
    open();
  }

  @Override
  protected void onDestroy() {
    visible = false;
    generation++;
    if (isFinishing()) TypeAnywhereStore.release(this, capture);
    super.onDestroy();
  }

  @Override
  public void onWindowFocusChanged(boolean focus) {
    super.onWindowFocusChanged(focus);
    if (focus && input != null) {
      input.requestFocus();
      Runnable showIme = () -> {
        if (isFinishing() || input == null) return;
        getWindow().getInsetsController().show(WindowInsets.Type.ime());
        var imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
      };
      input.post(showIme);
      input.postDelayed(showIme, 150);
    }
  }

  private int dp(int n) { return HexTheme.dp(this, n); }

  private TextView text(String s, int size, int color) {
    TextView v = new TextView(this);
    v.setText(s);
    v.setTextSize(size);
    v.setTextColor(color);
    return v;
  }

  private LinearLayout column() {
    LinearLayout v = new LinearLayout(this);
    v.setOrientation(LinearLayout.VERTICAL);
    return v;
  }

  private LinearLayout row() {
    LinearLayout v = new LinearLayout(this);
    v.setGravity(Gravity.CENTER_VERTICAL);
    return v;
  }

  private Button chipButton(String s, Runnable action) {
    Button b = new Button(this);
    b.setAllCaps(false);
    b.setText(s);
    b.setTextSize(13);
    b.setTextColor(TEXT);
    b.setMinWidth(0);
    b.setMinimumWidth(0);
    b.setMinHeight(0);
    b.setMinimumHeight(0);
    b.setPadding(dp(12), dp(6), dp(12), dp(6));
    b.setBackground(HexTheme.shape(this, KEY_FILL, 16, 0x22FFFFFF));
    b.setOnClickListener(v -> action.run());
    return b;
  }

  private void pad(View v, int x, int y) { v.setPadding(dp(x), dp(y), dp(x), dp(y)); }

  private void open() {
    if (!new HexLabs(this).isEnabled(LabId.TYPE_ANYWHERE)) {
      finish();
      return;
    }
    int ticket = ++generation;
    reading = busy = false;
    pending = null;
    rawAnswer = "";
    capture = TypeAnywhereStore.get(this, getIntent().getLongExtra("capture", 0));

    FrameLayout root = new FrameLayout(this);
    root.setFitsSystemWindows(true);
    root.setOnClickListener(v -> finish());

    LinearLayout sheet = column();
    sheet.setClickable(true);
    pad(sheet, 14, 10);
    sheet.setBackground(HexTheme.shape(this, BACKDROP, 20, 0x1FFFFFFF));
    root.addView(sheet, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
    setContentView(root);

    // Compact top strip: small screenshot thumbnail + subtle status pill + icon-only close
    LinearLayout top = row();
    sheet.addView(top);

    ImageView thumb = new ImageView(this);
    thumb.setContentDescription("Captured screen preview");
    thumb.setBackground(HexTheme.shape(this, KEY_FILL, 8, 0x22FFFFFF));
    thumb.setClipToOutline(true);
    thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
    top.addView(thumb, dp(34), dp(46));
    if (capture != null && capture.image != null) thumb.setImageBitmap(capture.image);

    status = text("Reading…", 12, TEXT_MUTED);
    LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(0, -2, 1);
    statusLp.leftMargin = dp(10);
    top.addView(status, statusLp);

    Button close = chipButton("✕", this::finish);
    close.setContentDescription("Close Type anywhere");
    close.setTextSize(14);
    close.setTextColor(TEXT_MUTED);
    close.setBackground(HexTheme.shape(this, KEY_FILL, 999, 0));
    top.addView(close, dp(32), dp(32));

    // Compact quick-prompt chips (collapse once an answer is shown)
    HorizontalScrollView scroll = new HorizontalScrollView(this);
    scroll.setHorizontalScrollBarEnabled(false);
    chipsScroll = scroll;
    LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(-1, -2);
    scrollLp.topMargin = dp(8);
    scrollLp.bottomMargin = dp(6);
    sheet.addView(scroll, scrollLp);
    LinearLayout chips = row();
    scroll.addView(chips);
    for (String label : new String[] {"Summarize", "Explain", "Key points", "Reply"}) {
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(32));
      lp.rightMargin = dp(6);
      chips.addView(chipButton(label, () -> ask(label.equals("Reply") ? "Draft reply" : label)), lp);
    }

    // Markdown-formatted answer card
    card = column();
    card.setBackground(HexTheme.shape(this, KEY_FILL, 14, 0x1AFFFFFF));
    pad(card, 12, 8);
    LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
    cardLp.topMargin = dp(8);
    cardLp.bottomMargin = dp(8);
    sheet.addView(card, cardLp);
    card.setVisibility(View.GONE);

    LinearLayout bar = row();
    card.addView(bar);
    badge = text("", 11, TEXT_MUTED);
    bar.addView(badge, new LinearLayout.LayoutParams(0, -2, 1));
    copy = chipButton("Copy", () -> {
      getSystemService(ClipboardManager.class)
          .setPrimaryClip(ClipData.newPlainText("Answer", rawAnswer.isEmpty() ? answer.getText() : rawAnswer));
      copy.setText("Copied");
      copy.postDelayed(() -> copy.setText("Copy"), 1400);
    });
    copy.setTextSize(11);
    copy.setPadding(dp(10), dp(4), dp(10), dp(4));
    bar.addView(copy, new LinearLayout.LayoutParams(-2, dp(26)));

    ScrollView body = new ScrollView(this) {
      @Override protected void onMeasure(int w, int h) {
        super.onMeasure(w, MeasureSpec.makeMeasureSpec(dp(220), MeasureSpec.AT_MOST));
      }
    };
    answer = text("", 14, TEXT);
    answer.setLineSpacing(dp(3), 1.08f);
    answer.setTextIsSelectable(true);
    LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(-1, -2);
    bodyLp.topMargin = dp(4);
    body.addView(answer);
    card.addView(body, bodyLp);

    // Sleek bottom composer pill
    LinearLayout composer = row();
    LinearLayout.LayoutParams compLp = new LinearLayout.LayoutParams(-1, -2);
    compLp.topMargin = dp(2);
    sheet.addView(composer, compLp);

    input = new EditText(this);
    input.setSingleLine();
    input.setTextSize(15);
    input.setTextColor(TEXT);
    input.setHintTextColor(HINT_TEXT);
    input.setHint("Ask…");
    pad(input, 14, 4);
    input.setBackground(HexTheme.shape(this, KEY_FILL, 22, 0x1FFFFFFF));
    composer.addView(input, new LinearLayout.LayoutParams(0, dp(42), 1));
    input.setImeOptions(EditorInfo.IME_ACTION_SEND);
    input.setOnEditorActionListener((v, id, event) -> {
      if (id != EditorInfo.IME_ACTION_SEND) return false;
      ask(input.getText().toString());
      return true;
    });

    Button send = chipButton("↑", () -> ask(input.getText().toString()));
    send.setContentDescription("Ask");
    send.setTextSize(18);
    send.setTypeface(null, Typeface.BOLD);
    send.setBackground(HexTheme.shape(this, ACTION_FILL, 999, 0));
    send.setTextColor(ON_PILL_TEXT);
    send.setPadding(0, 0, 0, 0);
    LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(dp(42), dp(42));
    sendParams.leftMargin = dp(8);
    composer.addView(send, sendParams);
    input.requestFocus();

    if (capture == null || capture.image == null) {
      String note = getIntent().getStringExtra("note");
      status.setText(note == null ? "No screen" : note);
      return;
    }
    if (capture.text != null) {
      status.setText("");
      return;
    }
    reading = true;
    TextRecognizer ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    try {
      ocr.process(InputImage.fromBitmap(capture.image, 0)).addOnCompleteListener(task -> {
        ocr.close();
        if (ticket != generation || isFinishing()) return;
        readDone(task.isSuccessful() ? task.getResult().getText() : null);
      });
    } catch (RuntimeException e) {
      ocr.close();
      readDone(null);
    }
  }

  private void readDone(String s) {
    reading = false;
    capture.text = s;
    status.setText(s != null ? "" : "No text");
    if (pending != null) {
      String q = pending;
      pending = null;
      ask(q);
    }
  }

  private void ask(String question) {
    if (busy || question.isBlank()) return;
    if (!new HexLabs(this).isEnabled(LabId.TYPE_ANYWHERE)) {
      finish();
      return;
    }
    input.setText("");
    chipsScroll.setVisibility(View.GONE);
    card.setVisibility(View.VISIBLE);
    copy.setVisibility(View.GONE);
    answer.setText("");
    rawAnswer = "";
    if (reading) {
      pending = question;
      badge.setText("Reading…");
      return;
    }
    busy = true;
    badge.setText("Thinking…");
    int ticket = generation;
    float density = getResources().getDisplayMetrics().density;
    TypeAnywhereAiClient.ask(this, capture == null ? null : capture.image,
        capture == null ? null : capture.text, question, new TypeAnywhereAiClient.Callback() {
          public void onAnswer(String source, String response) {
            if (ticket != generation || isFinishing()) return;
            busy = false;
            rawAnswer = response;
            badge.setText(source);
            answer.setText(MarkdownFormatter.format(response, density));
            copy.setVisibility(View.VISIBLE);
          }
          public void onError(String message) {
            if (ticket != generation || isFinishing()) return;
            busy = false;
            badge.setText("Error");
            answer.setText(message);
          }
        });
  }
}
