package anomaly.hex.android.keyboard;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import static anomaly.hex.android.keyboard.KeyboardPalette.*;

/**
 * Option 1 UI: Long-press Spacebar -> Fix Tail panel.
 * Top: 1-tap full-tail phrase corrections.
 * Bottom: tappable chips for the last ~6 words + per-word spatial/real-word neighbor pills.
 */
final class TailFixPanelView extends LinearLayout {
    private final LinearLayout phraseList, wordChipRow, wordCandRow;
    private final TextView subtitle;
    private TailCorrectionEngine.TailAnalysis analysis;
    private int selectedWordIndex = -1;
    private Consumer<TailCorrectionEngine.PhraseFix> onPickPhrase;
    private BiConsumer<Integer, String> onPickWord;

    TailFixPanelView(Context c, Runnable onClose) {
        super(c);
        setOrientation(VERTICAL);
        setBackgroundColor(BACKDROP);
        setPadding(dp(c, 10), dp(c, 8), dp(c, 10), dp(c, 10));

        // Header
        LinearLayout header = new LinearLayout(c);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(c);
        title.setText("Fix recent words");
        title.setTextColor(TEXT);
        title.setTextSize(14f);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.addView(title, new LayoutParams(0, -2, 1f));

        TextView close = new TextView(c);
        close.setText("✕");
        close.setTextColor(TEXT_MUTED);
        close.setTextSize(15f);
        close.setGravity(Gravity.CENTER);
        close.setBackground(pillPress(c, CHIP_FILL));
        close.setContentDescription("Close fix panel");
        close.setOnClickListener(v -> onClose.run());
        header.addView(close, new LayoutParams(dp(c, 32), dp(c, 30)));
        addView(header, new LayoutParams(-1, -2));

        subtitle = new TextView(c);
        subtitle.setTextColor(TEXT_MUTED);
        subtitle.setTextSize(12f);
        subtitle.setPadding(0, dp(c, 2), 0, dp(c, 6));
        addView(subtitle, new LayoutParams(-1, -2));

        // Top: full-phrase instant fixes
        phraseList = new LinearLayout(c);
        phraseList.setOrientation(VERTICAL);
        addView(phraseList, new LayoutParams(-1, -2));

        // Bottom: surgical word chips
        TextView chipLabel = new TextView(c);
        chipLabel.setText("Or tap a word to swap");
        chipLabel.setTextColor(TEXT_MUTED);
        chipLabel.setTextSize(11.5f);
        chipLabel.setPadding(0, dp(c, 8), 0, dp(c, 4));
        addView(chipLabel, new LayoutParams(-1, -2));

        HorizontalScrollView chipScroll = new HorizontalScrollView(c);
        chipScroll.setHorizontalScrollBarEnabled(false);
        wordChipRow = new LinearLayout(c);
        wordChipRow.setOrientation(HORIZONTAL);
        wordChipRow.setGravity(Gravity.CENTER_VERTICAL);
        chipScroll.addView(wordChipRow, new FrameLayout.LayoutParams(-2, -2));
        addView(chipScroll, new LayoutParams(-1, -2));

        // Selected word's candidates
        HorizontalScrollView candScroll = new HorizontalScrollView(c);
        candScroll.setHorizontalScrollBarEnabled(false);
        wordCandRow = new LinearLayout(c);
        wordCandRow.setOrientation(HORIZONTAL);
        wordCandRow.setGravity(Gravity.CENTER_VERTICAL);
        wordCandRow.setPadding(0, dp(c, 6), 0, 0);
        candScroll.addView(wordCandRow, new FrameLayout.LayoutParams(-2, -2));
        addView(candScroll, new LayoutParams(-1, -2));
    }

    void show(TailCorrectionEngine.TailAnalysis analysis,
              Consumer<TailCorrectionEngine.PhraseFix> onPickPhrase,
              BiConsumer<Integer, String> onPickWord) {
        this.analysis = analysis;
        this.onPickPhrase = onPickPhrase;
        this.onPickWord = onPickWord;
        this.selectedWordIndex = analysis == null ? -1 : analysis.defaultFocusIndex();
        render();
    }

    private void render() {
        Context c = getContext();
        phraseList.removeAllViews();
        wordChipRow.removeAllViews();
        wordCandRow.removeAllViews();

        if (analysis == null || analysis.isEmpty()) {
            subtitle.setText("Type a few words first.");
            return;
        }
        subtitle.setText("“" + analysis.originalTail().trim() + "”");

        List<TailCorrectionEngine.PhraseFix> phrases = analysis.phraseFixes();
        if (phrases.isEmpty()) {
            TextView none = new TextView(c);
            none.setText("No phrase fixes found — tap a word below to inspect neighbors.");
            none.setTextColor(TEXT_MUTED);
            none.setTextSize(12.5f);
            none.setPadding(0, dp(c, 2), 0, dp(c, 4));
            phraseList.addView(none);
        } else {
            for (int i = 0; i < phrases.size(); i++) {
                TailCorrectionEngine.PhraseFix pf = phrases.get(i);
                TextView row = new TextView(c);
                row.setText(pf.label());
                row.setTextColor(i == 0 ? ON_PILL_TEXT : TEXT);
                row.setTextSize(14f);
                row.setSingleLine(true);
                row.setEllipsize(TextUtils.TruncateAt.START);
                row.setPadding(dp(c, 12), dp(c, 9), dp(c, 12), dp(c, 9));
                row.setBackground(cardPress(c, i == 0 ? RETURN_PILL_FILL : CHIP_FILL));
                row.setOnClickListener(v -> {
                    if (onPickPhrase != null) onPickPhrase.accept(pf);
                });
                LayoutParams lp = new LayoutParams(-1, -2);
                lp.bottomMargin = dp(c, 5);
                phraseList.addView(row, lp);
            }
        }

        List<TailCorrectionEngine.WordSlot> words = analysis.words();
        for (int i = 0; i < words.size(); i++) {
            final int idx = i;
            TailCorrectionEngine.WordSlot slot = words.get(i);
            boolean selected = (i == selectedWordIndex);
            TextView chip = new TextView(c);
            chip.setText(slot.suspicious() ? "• " + slot.original() : slot.original());
            chip.setTextColor(selected ? ON_PILL_TEXT : TEXT);
            chip.setTextSize(13.5f);
            chip.setPadding(dp(c, 11), dp(c, 6), dp(c, 11), dp(c, 6));
            chip.setBackground(pillPress(c, selected ? ACTION_FILL : (slot.suspicious() ? KEY_FILL : CHIP_FILL)));
            chip.setOnClickListener(v -> {
                selectedWordIndex = idx;
                render();
            });
            LayoutParams lp = new LayoutParams(-2, -2);
            lp.rightMargin = dp(c, 6);
            wordChipRow.addView(chip, lp);
        }

        if (selectedWordIndex >= 0 && selectedWordIndex < words.size()) {
            TailCorrectionEngine.WordSlot slot = words.get(selectedWordIndex);
            if (slot.candidates().isEmpty()) {
                TextView empty = new TextView(c);
                empty.setText("No close neighbors for “" + slot.original() + "”");
                empty.setTextColor(TEXT_MUTED);
                empty.setTextSize(12.5f);
                wordCandRow.addView(empty);
            } else {
                for (String cand : slot.candidates()) {
                    TextView pill = new TextView(c);
                    pill.setText(cand);
                    pill.setTextColor(TEXT);
                    pill.setTextSize(14f);
                    pill.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                    pill.setPadding(dp(c, 13), dp(c, 7), dp(c, 13), dp(c, 7));
                    pill.setBackground(pillPress(c, KEY_FILL));
                    pill.setOnClickListener(v -> {
                        if (onPickWord != null) onPickWord.accept(selectedWordIndex, cand);
                    });
                    LayoutParams lp = new LayoutParams(-2, -2);
                    lp.rightMargin = dp(c, 6);
                    wordCandRow.addView(pill, lp);
                }
            }
        }
    }

    private static StateListDrawable cardPress(Context c, int fill) {
        StateListDrawable st = new StateListDrawable();
        st.addState(new int[]{android.R.attr.state_pressed}, rounded(c, KEY_PRESSED, 11));
        st.addState(new int[0], rounded(c, fill, 11));
        return st;
    }

    private static StateListDrawable pillPress(Context c, int fill) {
        StateListDrawable st = new StateListDrawable();
        st.addState(new int[]{android.R.attr.state_pressed}, pill(KEY_PRESSED));
        st.addState(new int[0], pill(fill));
        return st;
    }

    private static GradientDrawable rounded(Context c, int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }
}
