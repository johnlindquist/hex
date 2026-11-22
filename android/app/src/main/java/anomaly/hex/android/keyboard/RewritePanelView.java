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

/** Inline keyboard panel that displays screen-aware rewrite/correction options to pick with one tap. */
final class RewritePanelView extends LinearLayout {
  private final TextView contextBadge;
  private final LinearLayout body;
  private final ScrollView scroll;
  private final Consumer<KeyboardRewriteClient.Option> onPick;
  private final Runnable onRetry;
  private final Runnable onClose;

  RewritePanelView(Context c, Consumer<KeyboardRewriteClient.Option> onPick, Runnable onRetry, Runnable onClose) {
    super(c);
    this.onPick = onPick;
    this.onRetry = onRetry;
    this.onClose = onClose;
    setOrientation(VERTICAL);
    setBackgroundColor(BACKDROP);
    setPadding(dp(c, 8), dp(c, 4), dp(c, 8), dp(c, 6));

    // Compact header row: sparkle + title + context pill + retry + close
    LinearLayout header = new LinearLayout(c);
    header.setGravity(Gravity.CENTER_VERTICAL);
    header.setPadding(dp(c, 4), dp(c, 2), dp(c, 2), dp(c, 6));
    addView(header, new LayoutParams(-1, dp(c, 40)));

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

    View refresh = iconBtn(c, "Regenerate options", true, onRetry);
    LayoutParams refLp = new LayoutParams(dp(c, 34), dp(c, 34));
    refLp.rightMargin = dp(c, 4);
    header.addView(refresh, refLp);

    View close = iconBtn(c, "Close rewrite", false, onClose);
    header.addView(close, new LayoutParams(dp(c, 34), dp(c, 34)));

    scroll = new ScrollView(c);
    scroll.setVerticalScrollBarEnabled(true);
    body = new LinearLayout(c);
    body.setOrientation(VERTICAL);
    scroll.addView(body, new FrameLayout.LayoutParams(-1, -2));
    addView(scroll, new LayoutParams(-1, 0, 1));
  }

  private View iconBtn(Context c, String desc, boolean isRefresh, Runnable action) {
    View v = new View(c) {
      @Override protected void onDraw(android.graphics.Canvas canvas) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f, s = dp(c, 18);
        if (isRefresh) KeyIcons.drawRefresh(canvas, cx, cy, s, TEXT_MUTED);
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

  void showLoading(String draft, boolean readingScreen) {
    Context c = getContext();
    contextBadge.setText(readingScreen ? "Reading screen…" : "Working…");
    contextBadge.setVisibility(VISIBLE);
    body.removeAllViews();

    LinearLayout box = new LinearLayout(c);
    box.setOrientation(VERTICAL);
    box.setPadding(dp(c, 14), dp(c, 14), dp(c, 14), dp(c, 14));
    box.setBackground(cardBackground(c));

    TextView msg = label(c, readingScreen ? "Reading screen & crafting options…" : "Crafting rewrite options…", 14);
    msg.setTextColor(TEXT);
    box.addView(msg);

    if (draft != null && !draft.isBlank()) {
      TextView preview = new TextView(c);
      preview.setText("“" + draft.trim() + "”");
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

  void showOptions(List<KeyboardRewriteClient.Option> options, boolean usedScreen) {
    Context c = getContext();
    contextBadge.setText(usedScreen ? "Screen context" : "Draft only");
    contextBadge.setTextColor(usedScreen ? HexTheme.MIC : TEXT_MUTED);
    contextBadge.setVisibility(VISIBLE);
    body.removeAllViews();

    for (int i = 0; i < options.size(); i++) {
      KeyboardRewriteClient.Option opt = options.get(i);
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

      TextView tag = label(c, opt.label, 12);
      tag.setTypeface(android.graphics.Typeface.create("sans-serif-medium", 0));
      tag.setTextColor(HexTheme.MIC);
      topRow.addView(tag);

      View gap = new View(c);
      topRow.addView(gap, new LayoutParams(0, 1, 1));

      TextView hint = label(c, "Tap to apply", 11);
      hint.setTextColor(TEXT_MUTED);
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
      if (i > 0) lp.topMargin = dp(c, 6);
      body.addView(card, lp);
    }
    scroll.scrollTo(0, 0);
  }

  void showError(String error) {
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
