package anomaly.hex.android.anywhere;

import static anomaly.hex.android.keyboard.KeyboardPalette.*;

import android.app.Activity;
import android.content.*;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import anomaly.hex.android.core.*;
import anomaly.hex.android.ui.HexTheme;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.*;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

public final class TypeAnywhereActivity extends Activity {
  private TypeAnywhereStore capture;
  private TextView status, badge, answer;
  private Button copy;
  private LinearLayout card;
  private EditText input;
  private boolean reading, busy;
  private String pending;
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
  @Override
  protected void onNewIntent(Intent i) {
    super.onNewIntent(i);
    setIntent(i);
    open();
  }
  @Override
  protected void onDestroy() {
    generation++;
    if (isFinishing())
      TypeAnywhereStore.release(this, capture);
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
        var imm = getSystemService(android.view.inputmethod.InputMethodManager.class);
        if (imm != null) imm.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
      };
      input.post(showIme);
      input.postDelayed(showIme, 150);
    }
  }
  private int dp(int n) {
    return HexTheme.dp(this, n);
  }
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
  private Button button(String s, Runnable action) {
    Button b = HexTheme.smallButton(this, s, action);
    b.setHeight(dp(44));
    return b;
  }
  private void pad(View v, int x, int y) {
    v.setPadding(dp(x), dp(y), dp(x), dp(y));
  }
  private void open() {
    if (!new HexLabs(this).isEnabled(LabId.TYPE_ANYWHERE)) {
      finish();
      return;
    }
    int ticket = ++generation;
    reading = busy = false;
    pending = null;
    capture = TypeAnywhereStore.get(this, getIntent().getLongExtra("capture", 0));
    FrameLayout root = new FrameLayout(this);
    root.setFitsSystemWindows(true);
    root.setOnClickListener(v -> finish());
    LinearLayout sheet = column();
    sheet.setClickable(true);
    pad(sheet, 16, 12);
    sheet.setBackground(HexTheme.shape(this, BACKDROP, 22, 0));
    root.addView(sheet, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
    setContentView(root);
    LinearLayout header = row();
    sheet.addView(header);
    ImageView thumb = new ImageView(this);
    thumb.setContentDescription("Captured screen preview");
    thumb.setBackground(HexTheme.shape(this, KEY_FILL, 8, 0));
    thumb.setClipToOutline(true);
    thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
    header.addView(thumb, dp(44), dp(60));
    if (capture != null)
      thumb.setImageBitmap(capture.image);
    LinearLayout labels = column();
    pad(labels, 12, 0);
    header.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
    TextView title = text("Type anywhere", 20, TEXT);
    title.setTypeface(null, Typeface.BOLD);
    labels.addView(title);
    status = text("Reading screen…", 13, TEXT_MUTED);
    labels.addView(status);
    Button close = button("✕", this::finish);
    close.setContentDescription("Close Type anywhere");
    header.addView(close);
    HorizontalScrollView scroll = new HorizontalScrollView(this);
    scroll.setHorizontalScrollBarEnabled(false);
    sheet.addView(scroll);
    LinearLayout chips = row();
    pad(chips, 0, 8);
    scroll.addView(chips);
    for (String label : new String[] {"Summarize", "Explain", "Key points", "Draft reply"}) {
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
      lp.rightMargin = dp(5);
      chips.addView(button(label, () -> ask(label)), lp);
    }
    card = column();
    card.setBackground(HexTheme.shape(this, KEY_FILL, 14, 0));
    pad(card, 12, 8);
    LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
    cardLp.bottomMargin = dp(6);
    sheet.addView(card, cardLp);
    card.setVisibility(View.GONE);
    LinearLayout bar = row();
    card.addView(bar);
    badge = text("", 13, TEXT_MUTED);
    bar.addView(badge, new LinearLayout.LayoutParams(0, -2, 1));
    copy = button("Copy", () -> {
      getSystemService(ClipboardManager.class)
          .setPrimaryClip(ClipData.newPlainText("Answer", answer.getText()));
      copy.setText("Copied");
      copy.postDelayed(() -> copy.setText("Copy"), 1500);
    });
    bar.addView(copy);
    ScrollView body = new ScrollView(this) {
      @Override protected void onMeasure(int w, int h) {
        super.onMeasure(w, MeasureSpec.makeMeasureSpec(dp(120), MeasureSpec.AT_MOST));
      }
    };
    answer = text("", 15, TEXT);
    answer.setTextIsSelectable(true);
    body.addView(answer);
    card.addView(body, -1, -2);
    if (!TypeAnywhereAiClient.key(this).isEmpty())
      sheet.addView(text("Ask sends this screen and question to Gemini 4 Hydrogen via Hex API.", 13, TEXT_MUTED));
    LinearLayout composer = row();
    pad(composer, 0, 4);
    sheet.addView(composer);
    input = new EditText(this);
    input.setSingleLine();
    input.setTextSize(15);
    input.setTextColor(TEXT);
    input.setHintTextColor(HINT_TEXT);
    input.setHint("Ask anything about this screen…");
    pad(input, 14, 4);
    input.setBackground(HexTheme.shape(this, KEY_FILL, 20, 0));
    composer.addView(input, new LinearLayout.LayoutParams(0, dp(48), 1));
    input.setImeOptions(EditorInfo.IME_ACTION_SEND);
    input.setOnEditorActionListener((v, id, event) -> {
      if (id != EditorInfo.IME_ACTION_SEND)
        return false;
      ask(input.getText().toString());
      return true;
    });
    Button send = button("Ask", () -> ask(input.getText().toString()));
    send.setTypeface(null, Typeface.BOLD);
    send.setBackground(HexTheme.shape(this, ACTION_FILL, 30, 0));
    send.setTextColor(ON_PILL_TEXT);
    LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(-2, dp(48));
    sendParams.leftMargin = dp(8);
    composer.addView(send, sendParams);
    input.requestFocus();
    if (capture == null || capture.image == null) {
      String note = getIntent().getStringExtra("note");
      status.setText(note == null ? "Screen unavailable — type your question" : note);
      return;
    }
    if (capture.text != null) {
      showCount();
      return;
    }
    reading = true;
    TextRecognizer ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    try {
      ocr.process(InputImage.fromBitmap(capture.image, 0)).addOnCompleteListener(task -> {
        ocr.close();
        if (ticket != generation || isFinishing())
          return;
        readDone(task.isSuccessful() ? task.getResult().getText() : null);
      });
    } catch (RuntimeException e) {
      ocr.close();
      readDone(null);
    }
  }
  private void showCount() {
    String s = capture.text.trim();
    status.setText("Screen captured · " + (s.isEmpty() ? 0 : s.split("\\s+").length) + " words");
  }
  private void readDone(String s) {
    reading = false;
    capture.text = s;
    if (s != null)
      showCount();
    else
      status.setText("Screen captured · text unavailable");
    if (pending != null) {
      String q = pending;
      pending = null;
      ask(q);
    }
  }
  private void ask(String question) {
    if (busy || question.isBlank())
      return;
    if (!new HexLabs(this).isEnabled(LabId.TYPE_ANYWHERE)) {
      finish();
      return;
    }
    input.setText("");
    card.setVisibility(View.VISIBLE);
    copy.setVisibility(View.GONE);
    answer.setText("");
    if (reading) {
      pending = question;
      badge.setText("Reading screen…");
      return;
    }
    busy = true;
    badge.setText("Answering…");
    int ticket = generation;
    TypeAnywhereAiClient.ask(this, capture == null ? null : capture.image,
        capture == null ? null : capture.text, question, new TypeAnywhereAiClient.Callback() {
          public void onAnswer(String source, String response) {
            if (ticket != generation || isFinishing())
              return;
            busy = false;
            badge.setText(source);
            answer.setText(response);
            copy.setVisibility(View.VISIBLE);
          }
          public void onError(String message) {
            if (ticket != generation || isFinishing())
              return;
            busy = false;
            badge.setText("Answer unavailable");
            answer.setText(message);
          }
        });
  }
}
