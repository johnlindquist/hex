package anomaly.hex.android.keyboard;

import static anomaly.hex.android.keyboard.KeyboardPalette.*;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import anomaly.hex.android.ui.HexTheme;
import java.util.List;
import java.util.function.Consumer;

/** Inline keyboard panel for screen-aware rewrites and custom "Rewrite with prompt" instructions. */
final class RewritePanelView extends LinearLayout {
  private static final String[][] PRESETS = {
      {"Research", "Research the facts on screen using web search and weave them into a helpful reply"},
      {"Playful", "Rewrite in a warm, playful, witty style"},
      {"Executive", "Rewrite in a crisp, confident executive style"},
      {"Shorter", "Make this much shorter and punchier while keeping the key point"},
      {"Bullets", "Rewrite as clear, scannable bullet points"},
      {"Friendly", "Rewrite in a friendly, casual, approachable tone"}
  };

  private final LinearLayout promptBar;
  private final TextView promptText;
  private final LinearLayout resultsContainer;
  private final TextView contextBadge;
  private final LinearLayout body;
  private final ScrollView scroll;
  private final Consumer<KeyboardRewriteClient.Option> onPick;
  private final Consumer<String> onSubmitPrompt;
  private final Runnable onEditPrompt;
  private final Runnable onRetry;
  private final Runnable onClose;
  private final StringBuilder query = new StringBuilder();
  private boolean prompting;

  RewritePanelView(
      Context c,
      Consumer<KeyboardRewriteClient.Option> onPick,
      Consumer<String> onSubmitPrompt,
      Runnable onEditPrompt,
      Runnable onRetry,
      Runnable onClose) {
    super(c);
    this.onPick = onPick;
    this.onSubmitPrompt = onSubmitPrompt;
    this.onEditPrompt = onEditPrompt;
    this.onRetry = onRetry;
    this.onClose = onClose;
    setOrientation(VERTICAL);
    setBackgroundColor(BACKDROP);
    setPadding(dp(c, 8), dp(c, 4), dp(c, 8), dp(c, 4));

    // === 1. Compact 96dp prompt-entry bar (shown above KeyPlaneView) ===
    promptBar = new LinearLayout(c);
    promptBar.setOrientation(VERTICAL);
    addView(promptBar, new LayoutParams(-1, -2));

    // Row 1: quick style/research preset pills + close button
    LinearLayout presetRow = new LinearLayout(c);
    presetRow.setGravity(Gravity.CENTER_VERTICAL);
    promptBar.addView(presetRow, new LayoutParams(-1, dp(c, 36)));

    HorizontalScrollView presetScroll = new HorizontalScrollView(c);
    presetScroll.setHorizontalScrollBarEnabled(false);
    LinearLayout presetStrip = new LinearLayout(c);
    presetStrip.setGravity(Gravity.CENTER_VERTICAL);
    for (String[] preset : PRESETS) {
      TextView chip = label(c, preset[0], 12);
      chip.setTextColor(TEXT);
      chip.setPadding(dp(c, 10), dp(c, 4), dp(c, 10), dp(c, 4));
      chip.setBackground(pressablePill(CHIP_FILL));
      chip.setOnClickListener(v -> {
        query.setLength(0);
        query.append(preset[1]);
        renderQuery();
        submitPrompt();
      });
      LayoutParams clp = new LayoutParams(-2, -2);
      clp.rightMargin = dp(c, 6);
      presetStrip.addView(chip, clp);
    }
    presetScroll.addView(presetStrip);
    presetRow.addView(presetScroll, new LayoutParams(0, -2, 1));

    View promptClose = iconBtn(c, "Close prompt", 2, onClose);
    LayoutParams pclp = new LayoutParams(dp(c, 32), dp(c, 32));
    pclp.leftMargin = dp(c, 6);
    presetRow.addView(promptClose, pclp);

    // Row 2: live typed prompt field + Go button
    LinearLayout inputRow = new LinearLayout(c);
    inputRow.setGravity(Gravity.CENTER_VERTICAL);
    LayoutParams irLp = new LayoutParams(-1, dp(c, 44));
    irLp.topMargin = dp(c, 4);
    promptBar.addView(inputRow, irLp);

    LinearLayout field = new LinearLayout(c);
    field.setGravity(Gravity.CENTER_VERTICAL);
    field.setPadding(dp(c, 10), 0, dp(c, 10), 0);
    field.setBackground(pill(WELL_FILL));

    View pIcon = new View(c) {
      @Override protected void onDraw(android.graphics.Canvas canvas) {
        KeyIcons.drawPromptRewrite(canvas, getWidth() / 2f, getHeight() / 2f, dp(c, 16), HexTheme.MIC);
      }
    };
    field.addView(pIcon, new LayoutParams(dp(c, 20), dp(c, 20)));

    promptText = new TextView(c);
    promptText.setSingleLine(true);
    promptText.setEllipsize(TextUtils.TruncateAt.START);
    promptText.setTextSize(14);
    LayoutParams ptLp = new LayoutParams(0, -2, 1);
    ptLp.leftMargin = dp(c, 8);
    field.addView(promptText, ptLp);

    inputRow.addView(field, new LayoutParams(0, dp(c, 38), 1));

    View goBtn = new View(c) {
      @Override protected void onDraw(android.graphics.Canvas canvas) {
        KeyIcons.drawCheckmark(canvas, getWidth() / 2f, getHeight() / 2f, dp(c, 18), ON_PILL_TEXT);
      }
    };
    goBtn.setContentDescription("Run rewrite prompt");
    goBtn.setBackground(pressablePill(ACTION_FILL));
    goBtn.setOnClickListener(v -> submitPrompt());
    LayoutParams goLp = new LayoutParams(dp(c, 38), dp(c, 38));
    goLp.leftMargin = dp(c, 6);
    inputRow.addView(goBtn, goLp);

    promptBar.setVisibility(GONE);

    // === 2. Full-height results / loading / error container ===
    resultsContainer = new LinearLayout(c);
    resultsContainer.setOrientation(VERTICAL);
    addView(resultsContainer, new LayoutParams(-1, 0, 1));

    LinearLayout header = new LinearLayout(c);
    header.setGravity(Gravity.CENTER_VERTICAL);
    header.setPadding(dp(c, 4), dp(c, 2), dp(c, 2), dp(c, 6));
    resultsContainer.addView(header, new LayoutParams(-1, dp(c, 40)));

    View icon = new View(c) {
      @Override protected void onDraw(android.graphics.Canvas canvas) {
        KeyIcons.drawSparkle(canvas, getWidth() / 2f, getHeight() / 2f, dp(c, 18), HexTheme.MIC);
      }
    };
    header.addView(icon, new LayoutParams(dp(c, 24), dp(c, 24)));

    TextView title = label(c, "Rewrite", 15);
    title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
    title.setTextColor(TEXT);
    LayoutParams titleLp = new LayoutParams(-2, -2);
    titleLp.leftMargin = dp(c, 6);
    header.addView(title, titleLp);

    contextBadge = label(c, "", 11);
    contextBadge.setTextColor(TEXT_MUTED);
    contextBadge.setPadding(dp(c, 8), dp(c, 2), dp(c, 8), dp(c, 2));
    contextBadge.setBackground(pill(CHIP_FILL));
    LayoutParams badgeLp = new LayoutParams(-2, -2);
    badgeLp.leftMargin = dp(c, 8);
    header.addView(contextBadge, badgeLp);
    contextBadge.setVisibility(GONE);

    View spacer = new View(c);
    header.addView(spacer, new LayoutParams(0, 1, 1));

    View editPromptBtn = iconBtn(c, "Edit rewrite prompt", 0, onEditPrompt);
    LayoutParams epLp = new LayoutParams(dp(c, 34), dp(c, 34));
    epLp.rightMargin = dp(c, 4);
    header.addView(editPromptBtn, epLp);

    View refresh = iconBtn(c, "Regenerate options", 1, onRetry);
    LayoutParams refLp = new LayoutParams(dp(c, 34), dp(c, 34));
    refLp.rightMargin = dp(c, 4);
    header.addView(refresh, refLp);

    View close = iconBtn(c, "Close rewrite", 2, onClose);
    header.addView(close, new LayoutParams(dp(c, 34), dp(c, 34)));

    scroll = new ScrollView(c);
    scroll.setVerticalScrollBarEnabled(true);
    body = new LinearLayout(c);
    body.setOrientation(VERTICAL);
    scroll.addView(body, new FrameLayout.LayoutParams(-1, -2));
    resultsContainer.addView(scroll, new LayoutParams(-1, 0, 1));
  }

  // kind: 0 = prompt, 1 = refresh, 2 = close
  private View iconBtn(Context c, String desc, int kind, Runnable action) {
    View v = new View(c) {
      @Override protected void onDraw(android.graphics.Canvas canvas) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f, s = dp(c, 18);
        if (kind == 0) KeyIcons.drawPromptRewrite(canvas, cx, cy, s, TEXT_MUTED);
        else if (kind == 1) KeyIcons.drawRefresh(canvas, cx, cy, s, TEXT_MUTED);
        else KeyIcons.drawCancel(canvas, cx, cy, s, TEXT_MUTED);
      }
    };
    v.setContentDescription(desc);
    v.setBackground(pressablePill(CHIP_FILL));
    v.setOnClickListener(view -> action.run());
    return v;
  }

  private static StateListDrawable pressablePill(int fill) {
    StateListDrawable s = new StateListDrawable();
    s.addState(new int[] {android.R.attr.state_pressed}, pill(KEY_PRESSED));
    s.addState(new int[0], pill(fill));
    return s;
  }

  private static StateListDrawable cardBackground(Context c) {
    GradientDrawable normal = new GradientDrawable();
    normal.setColor(KEY_FILL);
    normal.setCornerRadius(dp(c, 12));
    normal.setStroke(Math.max(1, dp(c, 1)), 0x1AFFFFFF);

    GradientDrawable pressed = new GradientDrawable();
    pressed.setColor(KEY_PRESSED);
    pressed.setCornerRadius(dp(c, 12));
    pressed.setStroke(Math.max(1, dp(c, 1)), HexTheme.MIC);

    StateListDrawable s = new StateListDrawable();
    s.addState(new int[] {android.R.attr.state_pressed}, pressed);
    s.addState(new int[0], normal);
    return s;
  }

  boolean prompting() { return prompting; }

  void openPrompt(String initial) {
    prompting = true;
    query.setLength(0);
    if (initial != null && !initial.isBlank()) query.append(initial.trim());
    renderQuery();
    promptBar.setVisibility(VISIBLE);
    resultsContainer.setVisibility(GONE);
  }

  void append(String s) {
    if (!prompting || s == null) return;
    if (query.length() + s.length() <= 400) {
      query.append(s);
      renderQuery();
    }
  }

  void erase() {
    if (!prompting || query.length() == 0) return;
    int end = query.length();
    int start = Character.offsetByCodePoints(query, end, -1);
    query.delete(start, end);
    renderQuery();
  }

  void eraseWord() {
    if (!prompting || query.length() == 0) return;
    int i = query.length();
    while (i > 0 && Character.isWhitespace(query.charAt(i - 1))) i--;
    while (i > 0 && !Character.isWhitespace(query.charAt(i - 1))) i--;
    query.delete(i, query.length());
    renderQuery();
  }

  void submitPrompt() {
    if (!prompting) return;
    String instruction = query.toString().trim();
    if (instruction.isEmpty()) return;
    prompting = false;
    promptBar.setVisibility(GONE);
    resultsContainer.setVisibility(VISIBLE);
    onSubmitPrompt.accept(instruction);
  }

  private void renderQuery() {
    if (query.length() == 0) {
      promptText.setText("Research or pick a style…");
      promptText.setTextColor(HINT_TEXT);
    } else {
      promptText.setText(query + "|");
      promptText.setTextColor(TEXT);
    }
  }

  void showLoading(String draft, String instruction, boolean readingScreen) {
    prompting = false;
    promptBar.setVisibility(GONE);
    resultsContainer.setVisibility(VISIBLE);

    Context c = getContext();
    contextBadge.setText(readingScreen ? "Reading screen…" : "Working…");
    contextBadge.setTextColor(TEXT_MUTED);
    contextBadge.setVisibility(VISIBLE);
    body.removeAllViews();

    LinearLayout box = new LinearLayout(c);
    box.setOrientation(VERTICAL);
    box.setPadding(dp(c, 14), dp(c, 14), dp(c, 14), dp(c, 14));
    box.setBackground(cardBackground(c));

    String heading = (instruction != null && !instruction.isBlank())
        ? (readingScreen ? "Reading screen & running prompt…" : "Running rewrite prompt…")
        : (readingScreen ? "Reading screen & crafting options…" : "Crafting rewrite options…");
    TextView msg = label(c, heading, 14);
    msg.setTextColor(TEXT);
    box.addView(msg);

    String previewStr = (instruction != null && !instruction.isBlank())
        ? instruction.trim()
        : (draft == null ? "" : draft.trim());
    if (!previewStr.isEmpty()) {
      TextView preview = new TextView(c);
      preview.setText("“" + previewStr + "”");
      preview.setTextSize(13);
      preview.setTextColor(TEXT_MUTED);
      preview.setMaxLines(3);
      preview.setEllipsize(TextUtils.TruncateAt.END);
      LayoutParams pLp = new LayoutParams(-1, -2);
      pLp.topMargin = dp(c, 6);
      box.addView(preview, pLp);
    }
    body.addView(box, new LayoutParams(-1, -2));
    scroll.scrollTo(0, 0);
  }

  void showOptions(List<KeyboardRewriteClient.Option> options, boolean usedScreen, boolean usedWeb,
      String originalDraft, String appliedText) {
    prompting = false;
    promptBar.setVisibility(GONE);
    resultsContainer.setVisibility(VISIBLE);

    Context c = getContext();
    String badgeText = usedScreen
        ? (usedWeb ? "Screen · Web" : "Screen context")
        : (usedWeb ? "Web" : "Draft only");
    contextBadge.setText(badgeText);
    contextBadge.setTextColor((usedScreen || usedWeb) ? HexTheme.MIC : TEXT_MUTED);
    contextBadge.setVisibility(VISIBLE);
    body.removeAllViews();

    for (int i = 0; i < options.size(); i++) {
      KeyboardRewriteClient.Option opt = options.get(i);
      boolean selected = appliedText != null && appliedText.equals(opt.text);
      addOptionCard(c, opt, selected, i > 0);
    }

    if (originalDraft != null && !originalDraft.isBlank()) {
      boolean alreadyListed = false;
      for (KeyboardRewriteClient.Option opt : options) {
        if (originalDraft.equals(opt.text)) { alreadyListed = true; break; }
      }
      if (!alreadyListed) {
        boolean isCurrent = appliedText == null || appliedText.equals(originalDraft);
        addOptionCard(c, new KeyboardRewriteClient.Option("Original", originalDraft), isCurrent, !options.isEmpty());
      }
    }
    scroll.scrollTo(0, 0);
  }

  private void addOptionCard(Context c, KeyboardRewriteClient.Option opt, boolean selected, boolean topGap) {
    LinearLayout card = new LinearLayout(c);
    card.setOrientation(VERTICAL);
    card.setPadding(dp(c, 12), dp(c, 9), dp(c, 12), dp(c, 10));
    card.setBackground(cardBackground(c));
    card.setClickable(true);
    card.setContentDescription(opt.label + ": " + opt.text);
    card.setOnClickListener(v -> onPick.accept(opt));

    LinearLayout topRow = new LinearLayout(c);
    topRow.setGravity(Gravity.CENTER_VERTICAL);
    card.addView(topRow, new LayoutParams(-1, -2));

    boolean isOriginal = "Original".equals(opt.label);
    TextView tag = label(c, opt.label, 12);
    tag.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
    tag.setTextColor(isOriginal ? TEXT_MUTED : HexTheme.MIC);
    topRow.addView(tag);

    View gap = new View(c);
    topRow.addView(gap, new LayoutParams(0, 1, 1));

    TextView hint = label(c, selected ? "Selected" : (isOriginal ? "Restore" : "Tap to apply"), 11);
    hint.setTextColor(selected ? HexTheme.MIC : TEXT_MUTED);
    topRow.addView(hint);

    TextView content = new TextView(c);
    content.setText(opt.text);
    content.setTextSize(14);
    content.setTextColor(TEXT);
    content.setLineSpacing(dp(c, 2), 1.08f);
    LayoutParams textLp = new LayoutParams(-1, -2);
    textLp.topMargin = dp(c, 4);
    card.addView(content, textLp);

    LayoutParams lp = new LayoutParams(-1, -2);
    if (topGap) lp.topMargin = dp(c, 6);
    body.addView(card, lp);
  }

  void showError(String error) {
    prompting = false;
    promptBar.setVisibility(GONE);
    resultsContainer.setVisibility(VISIBLE);

    Context c = getContext();
    contextBadge.setVisibility(GONE);
    body.removeAllViews();

    LinearLayout box = new LinearLayout(c);
    box.setOrientation(VERTICAL);
    box.setPadding(dp(c, 14), dp(c, 14), dp(c, 14), dp(c, 14));
    box.setBackground(cardBackground(c));

    TextView msg = new TextView(c);
    msg.setText(error == null || error.isBlank() ? "Rewrite failed." : error);
    msg.setTextSize(14);
    msg.setTextColor(TEXT);
    box.addView(msg);

    TextView retry = label(c, "Try again", 14);
    retry.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
    retry.setTextColor(ON_PILL_TEXT);
    retry.setBackground(pill(RETURN_PILL_FILL));
    retry.setPadding(dp(c, 14), dp(c, 6), dp(c, 14), dp(c, 6));
    retry.setOnClickListener(v -> onRetry.run());
    LayoutParams rLp = new LayoutParams(-2, -2);
    rLp.topMargin = dp(c, 10);
    box.addView(retry, rLp);

    body.addView(box, new LayoutParams(-1, -2));
    scroll.scrollTo(0, 0);
  }
}
