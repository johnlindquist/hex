package anomaly.hex.android.anywhere;

import static anomaly.hex.android.keyboard.KeyboardPalette.*;

import android.app.Activity;
import android.content.*;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.CalendarContract;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import anomaly.hex.android.anywhere.AnywhereIcons.Glyph;
import anomaly.hex.android.anywhere.AnywhereIcons.IconButton;
import anomaly.hex.android.core.*;
import anomaly.hex.android.service.HexBubbleService;
import anomaly.hex.android.ui.HexTheme;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.*;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;
import java.util.*;

/** Icon-first minimalist bottom sheet for Type Anywhere: annotate, hold-to-speak, multi-turn,
 *  compare screenshots, 1-tap insert, web sources, screen actions, and custom quick slots. */
public final class TypeAnywhereActivity extends Activity {
  public static volatile boolean visible;
  private TypeAnywhereStore capture;
  private Bitmap activeImage;
  private Bitmap secondImage;
  private ImageView thumbView, secondThumbView;
  private TextView status, badge, answer;
  private IconButton copy, insertBtn, historyBtn;
  private LinearLayout card, historyTray, extrasBar;
  private ScrollView answerScroll;
  private EditText input;
  private boolean reading, busy, historyOpen;
  private String pending, rawAnswer = "";
  private final List<TypeAnywhereAiClient.Turn> conversation = new ArrayList<>();
  private int generation;

  public static Intent intent(Context c, long capture, String note) {
    return intent(c, capture, note, null);
  }

  public static Intent intent(Context c, long capture, String note, String autoQuestion) {
    Intent i = new Intent(c, TypeAnywhereActivity.class)
        .putExtra("capture", capture)
        .putExtra("note", note)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
            | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    if (autoQuestion != null && !autoQuestion.isBlank()) {
      i.putExtra("auto_question", autoQuestion);
    }
    return i;
  }

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    open();
  }

  @Override protected void onResume() { super.onResume(); visible = true; HexBubbleService.refreshLabs(); }
  @Override protected void onPause() { visible = false; HexBubbleService.refreshLabs(); super.onPause(); }

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
    secondImage = null;
    conversation.clear();
    capture = TypeAnywhereStore.get(this, getIntent().getLongExtra("capture", 0));
    activeImage = capture != null ? capture.image : null;

    FrameLayout root = new FrameLayout(this);
    root.setFitsSystemWindows(true);
    root.setOnClickListener(v -> finish());

    LinearLayout sheet = column();
    sheet.setClickable(true);
    pad(sheet, 12, 10);
    sheet.setBackground(HexTheme.shape(this, BACKDROP, 20, 0x1FFFFFFF));
    root.addView(sheet, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
    setContentView(root);

    // Single compact top action bar: thumbnail (tap to annotate) + optional 2nd compare thumb + quick icons + history + close
    LinearLayout top = row();
    sheet.addView(top);

    ImageView thumb = new ImageView(this);
    thumbView = thumb;
    thumb.setContentDescription("Captured screen");
    thumb.setTooltipText("Tap to circle or highlight");
    thumb.setBackground(HexTheme.shape(this, KEY_FILL, 8, 0x22FFFFFF));
    thumb.setClipToOutline(true);
    thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
    thumb.setOnClickListener(v -> {
      if (activeImage == null) return;
      AnnotateImageDialog.show(this, activeImage, annotated -> {
        activeImage = annotated;
        thumbView.setImageBitmap(annotated);
        thumbView.setBackground(HexTheme.shape(this, KEY_FILL, 8, HexTheme.MIC));
      });
    });
    top.addView(thumb, dp(30), dp(40));
    if (activeImage != null) thumb.setImageBitmap(activeImage);

    secondThumbView = new ImageView(this);
    secondThumbView.setContentDescription("Second screenshot for comparison");
    secondThumbView.setTooltipText("Tap to remove second screenshot");
    secondThumbView.setBackground(HexTheme.shape(this, KEY_FILL, 8, HexTheme.MIC));
    secondThumbView.setClipToOutline(true);
    secondThumbView.setScaleType(ImageView.ScaleType.CENTER_CROP);
    secondThumbView.setVisibility(View.GONE);
    secondThumbView.setOnClickListener(v -> {
      secondImage = null;
      secondThumbView.setVisibility(View.GONE);
    });
    LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(dp(24), dp(34));
    stLp.leftMargin = dp(4);
    top.addView(secondThumbView, stLp);

    LinearLayout actions = row();
    LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(-2, -2);
    actionsLp.leftMargin = dp(8);
    top.addView(actions, actionsLp);

    for (QuickPromptsStore.Slot slot : QuickPromptsStore.load(this)) {
      addQuickIcon(actions, slot.glyph, slot.label, slot.prompt);
    }

    status = text("", 11, TEXT_MUTED);
    status.setSingleLine();
    LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(0, -2, 1);
    statusLp.leftMargin = dp(6);
    statusLp.rightMargin = dp(4);
    top.addView(status, statusLp);

    historyOpen = false;
    historyBtn = AnywhereIcons.button(
        this, Glyph.HISTORY, "Screenshot history", 34, 16, KEY_FILL, TEXT_MUTED, 0, this::toggleHistory);
    LinearLayout.LayoutParams histLp = new LinearLayout.LayoutParams(dp(34), dp(34));
    histLp.rightMargin = dp(6);
    top.addView(historyBtn, histLp);

    IconButton close = AnywhereIcons.button(
        this, Glyph.CLOSE, "Close", 34, 15, KEY_FILL, TEXT_MUTED, 0, this::finish);
    top.addView(close, new LinearLayout.LayoutParams(dp(34), dp(34)));

    // Collapsible horizontal screenshot history tray (tap to load, + to compare, long-press to delete)
    historyTray = column();
    LinearLayout.LayoutParams trayLp = new LinearLayout.LayoutParams(-1, -2);
    trayLp.topMargin = dp(8);
    sheet.addView(historyTray, trayLp);
    historyTray.setVisibility(View.GONE);

    // Markdown-formatted answer card (supports multi-turn + Insert + Copy + web sources + screen actions)
    card = column();
    card.setBackground(HexTheme.shape(this, KEY_FILL, 14, 0x1AFFFFFF));
    pad(card, 12, 8);
    LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(-1, -2);
    cardLp.topMargin = dp(8);
    sheet.addView(card, cardLp);
    card.setVisibility(View.GONE);

    LinearLayout bar = row();
    card.addView(bar);
    badge = text("", 11, TEXT_MUTED);
    bar.addView(badge, new LinearLayout.LayoutParams(0, -2, 1));

    insertBtn = AnywhereIcons.button(
        this, Glyph.INSERT, "Insert into app", 28, 14, 0x18FFFFFF, TEXT_MUTED, 0, () -> {
          String plain = answer.getText().toString().trim();
          if (plain.isEmpty()) return;
          getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Answer", plain));
          finish();
          HexBubbleService.insertAnywhereText(plain);
        });
    LinearLayout.LayoutParams insLp = new LinearLayout.LayoutParams(dp(28), dp(28));
    insLp.rightMargin = dp(6);
    bar.addView(insertBtn, insLp);

    copy = AnywhereIcons.button(
        this, Glyph.COPY, "Copy", 28, 14, 0x18FFFFFF, TEXT_MUTED, 0, () -> {
          getSystemService(ClipboardManager.class).setPrimaryClip(
              ClipData.newPlainText("Answer", rawAnswer.isEmpty() ? answer.getText() : rawAnswer));
          copy.setGlyph(Glyph.CHECK, HexTheme.SUCCESS, "Copied");
          copy.postDelayed(() -> copy.setGlyph(Glyph.COPY, TEXT_MUTED, "Copy"), 1400);
        });
    bar.addView(copy, new LinearLayout.LayoutParams(dp(28), dp(28)));

    answerScroll = new ScrollView(this) {
      @Override protected void onMeasure(int w, int h) {
        super.onMeasure(w, MeasureSpec.makeMeasureSpec(dp(210), MeasureSpec.AT_MOST));
      }
    };
    answer = text("", 14, TEXT);
    answer.setLineSpacing(dp(3), 1.08f);
    answer.setTextIsSelectable(true);
    LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(-1, -2);
    bodyLp.topMargin = dp(4);
    answerScroll.addView(answer);
    card.addView(answerScroll, bodyLp);

    // Compact horizontal bar for clickable web sources + detected screen actions
    extrasBar = row();
    HorizontalScrollView extrasScroll = new HorizontalScrollView(this);
    extrasScroll.setHorizontalScrollBarEnabled(false);
    extrasScroll.addView(extrasBar);
    LinearLayout.LayoutParams exLp = new LinearLayout.LayoutParams(-1, -2);
    exLp.topMargin = dp(6);
    card.addView(extrasScroll, exLp);
    extrasBar.setVisibility(View.GONE);

    // Compact bottom composer pill
    LinearLayout composer = row();
    LinearLayout.LayoutParams compLp = new LinearLayout.LayoutParams(-1, -2);
    compLp.topMargin = dp(8);
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

    IconButton send = AnywhereIcons.button(
        this, Glyph.SEND, "Send", 42, 18, ACTION_FILL, ON_PILL_TEXT, 0,
        () -> ask(input.getText().toString()));
    LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(dp(42), dp(42));
    sendParams.leftMargin = dp(8);
    composer.addView(send, sendParams);
    input.requestFocus();

    String autoQ = getIntent().getStringExtra("auto_question");

    if (capture == null || activeImage == null) {
      String note = getIntent().getStringExtra("note");
      status.setText(note == null ? "No screen" : note);
      if (autoQ != null && !autoQ.isBlank()) ask(autoQ);
      return;
    }
    if (capture.text != null) {
      status.setText("");
      if (autoQ != null && !autoQ.isBlank()) ask(autoQ);
      return;
    }
    reading = true;
    status.setText("…");
    if (autoQ != null && !autoQ.isBlank()) pending = autoQ;
    TextRecognizer ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    try {
      ocr.process(InputImage.fromBitmap(activeImage, 0)).addOnCompleteListener(task -> {
        ocr.close();
        if (ticket != generation || isFinishing()) return;
        readDone(task.isSuccessful() ? task.getResult().getText() : null);
      });
    } catch (RuntimeException e) {
      ocr.close();
      readDone(null);
    }
  }

  private void addQuickIcon(LinearLayout parent, Glyph glyph, String tooltip, String prompt) {
    IconButton b = AnywhereIcons.button(
        this, glyph, tooltip, 36, 17, KEY_FILL, TEXT, 0x1FFFFFFF, () -> ask(prompt));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(36));
    lp.rightMargin = dp(6);
    parent.addView(b, lp);
  }

  private void toggleHistory() {
    historyOpen = !historyOpen;
    historyBtn.setGlyph(Glyph.HISTORY, historyOpen ? HexTheme.MIC : TEXT_MUTED, "Screenshot history");
    if (!historyOpen) {
      historyTray.setVisibility(View.GONE);
      return;
    }
    populateHistoryTray();
    historyTray.setVisibility(View.VISIBLE);
  }

  private void populateHistoryTray() {
    historyTray.removeAllViews();
    var items = ScreenshotHistory.list(this);
    if (items.isEmpty()) {
      TextView empty = text("No saved screenshots yet.", 12, TEXT_MUTED);
      empty.setPadding(dp(4), dp(6), dp(4), dp(6));
      historyTray.addView(empty);
      return;
    }
    HorizontalScrollView scroll = new HorizontalScrollView(this);
    scroll.setHorizontalScrollBarEnabled(false);
    LinearLayout strip = row();
    scroll.addView(strip);
    long now = System.currentTimeMillis();
    for (var item : items) {
      FrameLayout tile = new FrameLayout(this);
      tile.setBackground(HexTheme.shape(this, KEY_FILL, 10, 0x22FFFFFF));
      tile.setClipToOutline(true);
      ImageView iv = new ImageView(this);
      iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
      var bm = ScreenshotHistory.decodeThumb(item.file, dp(56));
      if (bm != null) iv.setImageBitmap(bm);
      tile.addView(iv, new FrameLayout.LayoutParams(dp(48), dp(66)));

      TextView age = text(relativeAge(now - item.capturedAt), 9, TEXT);
      age.setBackgroundColor(0xAA000000);
      age.setPadding(dp(4), dp(1), dp(4), dp(1));
      tile.addView(age, new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START));

      // Tiny '+' compare icon in top-right corner to attach as 2nd screenshot
      IconButton cmp = AnywhereIcons.button(
          this, Glyph.PLUS, "Compare with current screenshot", 20, 11, 0xCC1A1B20, HexTheme.MIC, 0x33FFFFFF,
          () -> attachSecondScreenshot(item));
      FrameLayout.LayoutParams cmpLp = new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.TOP | Gravity.END);
      cmpLp.topMargin = dp(2);
      cmpLp.rightMargin = dp(2);
      tile.addView(cmp, cmpLp);

      tile.setContentDescription("Load screenshot from " + relativeAge(now - item.capturedAt));
      tile.setTooltipText("Tap to load · + to compare · long-press to delete");
      tile.setOnClickListener(v -> loadHistoricalScreenshot(item));
      tile.setOnLongClickListener(v -> {
        ScreenshotHistory.delete(item.file);
        populateHistoryTray();
        return true;
      });
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(48), dp(66));
      lp.rightMargin = dp(8);
      strip.addView(tile, lp);
    }
    IconButton clearAll = AnywhereIcons.button(
        this, Glyph.TRASH, "Clear screenshot history", 34, 15, KEY_FILL, HexTheme.DANGER, 0x22FFFFFF, () -> {
          ScreenshotHistory.clearAll(this);
          populateHistoryTray();
        });
    strip.addView(clearAll, new LinearLayout.LayoutParams(dp(34), dp(34)));
    historyTray.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
  }

  private void attachSecondScreenshot(ScreenshotHistory.Item item) {
    Bitmap bmp = ScreenshotHistory.decodeFull(item.file);
    if (bmp == null) return;
    secondImage = bmp;
    secondThumbView.setImageBitmap(bmp);
    secondThumbView.setVisibility(View.VISIBLE);
    historyOpen = false;
    historyBtn.setGlyph(Glyph.HISTORY, TEXT_MUTED, "Screenshot history");
    historyTray.setVisibility(View.GONE);
    input.setHint("Compare screenshots…");
  }

  private void loadHistoricalScreenshot(ScreenshotHistory.Item item) {
    var bmp = ScreenshotHistory.decodeFull(item.file);
    if (bmp == null) return;
    int ticket = ++generation;
    capture = TypeAnywhereStore.put(this, bmp);
    activeImage = bmp;
    secondImage = null;
    conversation.clear();
    if (thumbView != null) {
      thumbView.setImageBitmap(bmp);
      thumbView.setBackground(HexTheme.shape(this, KEY_FILL, 8, 0x22FFFFFF));
    }
    if (secondThumbView != null) secondThumbView.setVisibility(View.GONE);
    historyOpen = false;
    historyBtn.setGlyph(Glyph.HISTORY, TEXT_MUTED, "Screenshot history");
    historyTray.setVisibility(View.GONE);
    card.setVisibility(View.GONE);
    busy = false;
    reading = true;
    status.setText("…");
    TextRecognizer ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
    try {
      ocr.process(InputImage.fromBitmap(bmp, 0)).addOnCompleteListener(task -> {
        ocr.close();
        if (ticket != generation || isFinishing()) return;
        readDone(task.isSuccessful() ? task.getResult().getText() : null);
      });
    } catch (RuntimeException e) {
      ocr.close();
      readDone(null);
    }
  }

  private static String relativeAge(long diffMs) {
    long sec = Math.max(0, diffMs / 1000);
    if (sec < 60) return "now";
    long min = sec / 60;
    if (min < 60) return min + "m";
    long hr = min / 60;
    if (hr < 24) return hr + "h";
    return (hr / 24) + "d";
  }

  private void readDone(String s) {
    reading = false;
    if (capture != null) capture.text = s;
    status.setText("");
    if (pending != null) {
      String q = pending;
      pending = null;
      ask(q);
    }
  }

  private void populateExtras(List<TypeAnywhereAiClient.Source> sources, String combinedText) {
    extrasBar.removeAllViews();
    int added = 0;
    if (sources != null) {
      for (var src : sources) {
        Button pill = new Button(this);
        pill.setAllCaps(false);
        String label = src.title.length() > 22 ? src.title.substring(0, 21) + "…" : src.title;
        pill.setText("↗ " + label);
        pill.setTextSize(11);
        pill.setTextColor(TEXT_MUTED);
        pill.setMinWidth(0);
        pill.setMinimumWidth(0);
        pill.setMinHeight(0);
        pill.setMinimumHeight(0);
        pill.setPadding(dp(9), dp(3), dp(9), dp(3));
        pill.setBackground(HexTheme.shape(this, 0x1AFFFFFF, 999, 0x22FFFFFF));
        pill.setTooltipText(src.uri);
        pill.setOnClickListener(v -> launchSafe(new Intent(Intent.ACTION_VIEW, Uri.parse(src.uri))));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(26));
        lp.rightMargin = dp(6);
        extrasBar.addView(pill, lp);
        added++;
      }
    }
    for (ScreenActions.Action act : ScreenActions.extract(combinedText)) {
      Glyph g = switch (act.kind) {
        case CALENDAR -> Glyph.CALENDAR;
        case MAP -> Glyph.MAP;
        case PHONE -> Glyph.PHONE;
        case EMAIL -> Glyph.EMAIL;
        case LINK -> Glyph.LINK;
      };
      IconButton btn = AnywhereIcons.button(
          this, g, act.label, 28, 14, 0x1AFFFFFF, HexTheme.MIC, 0x22FFFFFF, () -> triggerAction(act));
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(28), dp(28));
      lp.rightMargin = dp(6);
      extrasBar.addView(btn, lp);
      added++;
    }
    extrasBar.setVisibility(added > 0 ? View.VISIBLE : View.GONE);
  }

  private void triggerAction(ScreenActions.Action act) {
    Intent i = switch (act.kind) {
      case CALENDAR -> new Intent(Intent.ACTION_INSERT)
          .setData(CalendarContract.Events.CONTENT_URI)
          .putExtra(CalendarContract.Events.TITLE, act.value);
      case MAP -> new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(act.value)));
      case PHONE -> new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(act.value)));
      case EMAIL -> new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(act.value)));
      case LINK -> new Intent(Intent.ACTION_VIEW, Uri.parse(act.value));
    };
    launchSafe(i);
  }

  private void launchSafe(Intent i) {
    try {
      i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      startActivity(i);
    } catch (ActivityNotFoundException ignored) {}
  }

  private void ask(String question) {
    if (busy || question.isBlank()) return;
    if (!new HexLabs(this).isEnabled(LabId.TYPE_ANYWHERE)) {
      finish();
      return;
    }
    input.setText("");
    card.setVisibility(View.VISIBLE);
    copy.setVisibility(View.GONE);
    insertBtn.setVisibility(View.GONE);
    extrasBar.setVisibility(View.GONE);
    copy.setGlyph(Glyph.COPY, TEXT_MUTED, "Copy");
    if (reading) {
      pending = question;
      badge.setText("…");
      return;
    }
    busy = true;
    badge.setText("…");
    int ticket = generation;
    float density = getResources().getDisplayMetrics().density;
    String screenText = capture == null ? null : capture.text;
    TypeAnywhereAiClient.ask(this, activeImage, secondImage, screenText, question, conversation,
        new TypeAnywhereAiClient.Callback() {
          public void onAnswer(String source, String response, List<TypeAnywhereAiClient.Source> sources) {
            if (ticket != generation || isFinishing()) return;
            busy = false;
            rawAnswer = response;
            conversation.add(new TypeAnywhereAiClient.Turn("user", question));
            conversation.add(new TypeAnywhereAiClient.Turn("model", response));
            while (conversation.size() > 8) conversation.remove(0);
            badge.setText(secondImage != null ? source + " · 2 screens" : source);
            answer.setText(MarkdownFormatter.format(response, density));
            answerScroll.post(() -> answerScroll.scrollTo(0, 0));
            copy.setVisibility(View.VISIBLE);
            insertBtn.setVisibility(View.VISIBLE);
            input.setHint("Follow up…");
            populateExtras(sources, (screenText == null ? "" : screenText) + "\n" + response);
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
