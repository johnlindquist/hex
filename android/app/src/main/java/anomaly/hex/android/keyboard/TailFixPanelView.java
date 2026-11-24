package anomaly.hex.android.keyboard;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.view.Gravity;
import android.widget.*;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import static anomaly.hex.android.keyboard.KeyboardPalette.*;

/**
 * Full-keyboard replacement UI for choosing from fully corrected sentence options
 * (triggered by swiping up from the spacebar, or long-press in panel mode).
 * Displays scrollable multi-line sentence cards with changed words highlighted, plus
 * optional surgical per-word chips below.
 */
final class TailFixPanelView extends LinearLayout {
    private final LinearLayout phraseList, wordChipRow, wordCandRow;
    private final TextView titleView, subtitle;
    private final ScrollView scroll;
    private TailCorrectionEngine.TailAnalysis analysis;
    private int selectedWordIndex = -1;
    private final Set<Integer> markedWords = new LinkedHashSet<>();
    private final Map<Integer, String> pinnedWords = new HashMap<>();
    private Consumer<TailCorrectionEngine.PhraseFix> onPickPhrase;
    private BiConsumer<Integer, String> onPickWord;

    TailFixPanelView(Context c, Runnable onClose) {
        super(c);
        setOrientation(VERTICAL);
        setBackgroundColor(BACKDROP);
        setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 10));

        // Fixed Header
        LinearLayout header = new LinearLayout(c);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        titleView = new TextView(c);
        titleView.setText("Choose a corrected sentence");
        titleView.setTextColor(TEXT);
        titleView.setTextSize(14.5f);
        titleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        header.addView(titleView, new LayoutParams(0, -2, 1f));

        TextView close = new TextView(c);
        close.setText("✕");
        close.setTextColor(TEXT_MUTED);
        close.setTextSize(15f);
        close.setGravity(Gravity.CENTER);
        close.setBackground(pillPress(c, CHIP_FILL));
        close.setContentDescription("Close sentence picker");
        close.setOnClickListener(v -> onClose.run());
        header.addView(close, new LayoutParams(dp(c, 32), dp(c, 30)));
        addView(header, new LayoutParams(-1, -2));

        subtitle = new TextView(c);
        subtitle.setTextColor(TEXT_MUTED);
        subtitle.setTextSize(12f);
        subtitle.setMaxLines(2);
        subtitle.setPadding(0, dp(c, 2), 0, dp(c, 8));
        addView(subtitle, new LayoutParams(-1, -2));

        // Scrollable body filling the entire keyboard area
        scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(c);
        body.setOrientation(VERTICAL);

        // Interactive word-marking row: tap words to mark/unmark bad sections
        TextView chipLabel = new TextView(c);
        chipLabel.setText("Tap words to mark what needs fixing");
        chipLabel.setTextColor(TEXT_MUTED);
        chipLabel.setTextSize(11.5f);
        chipLabel.setPadding(0, 0, 0, dp(c, 5));
        body.addView(chipLabel, new LayoutParams(-1, -2));

        HorizontalScrollView chipScroll = new HorizontalScrollView(c);
        chipScroll.setHorizontalScrollBarEnabled(false);
        wordChipRow = new LinearLayout(c);
        wordChipRow.setOrientation(HORIZONTAL);
        wordChipRow.setGravity(Gravity.CENTER_VERTICAL);
        chipScroll.addView(wordChipRow, new FrameLayout.LayoutParams(-2, -2));
        body.addView(chipScroll, new LayoutParams(-1, -2));

        HorizontalScrollView candScroll = new HorizontalScrollView(c);
        candScroll.setHorizontalScrollBarEnabled(false);
        wordCandRow = new LinearLayout(c);
        wordCandRow.setOrientation(HORIZONTAL);
        wordCandRow.setGravity(Gravity.CENTER_VERTICAL);
        wordCandRow.setPadding(0, dp(c, 6), 0, dp(c, 8));
        candScroll.addView(wordCandRow, new FrameLayout.LayoutParams(-2, -2));
        body.addView(candScroll, new LayoutParams(-1, -2));

        // Full corrected sentence cards (live-updated from marked words)
        phraseList = new LinearLayout(c);
        phraseList.setOrientation(VERTICAL);
        body.addView(phraseList, new LayoutParams(-1, -2));

        scroll.addView(body, new FrameLayout.LayoutParams(-1, -2));
        addView(scroll, new LayoutParams(-1, 0, 1f));
    }

    void show(TailCorrectionEngine.TailAnalysis analysis,
              Consumer<TailCorrectionEngine.PhraseFix> onPickPhrase,
              BiConsumer<Integer, String> onPickWord) {
        this.analysis = analysis;
        this.onPickPhrase = onPickPhrase;
        this.onPickWord = onPickWord;
        this.markedWords.clear();
        this.pinnedWords.clear();
        if (analysis != null) {
            for (int i = 0; i < analysis.words().size(); i++) {
                if (analysis.words().get(i).suspicious()) {
                    markedWords.add(i);
                }
            }
        }
        this.selectedWordIndex = analysis == null ? -1 : analysis.defaultFocusIndex();
        scroll.scrollTo(0, 0);
        render();
    }

    private void render() {
        Context c = getContext();
        phraseList.removeAllViews();
        wordChipRow.removeAllViews();
        wordCandRow.removeAllViews();

        if (analysis == null || analysis.isEmpty()) {
            subtitle.setText("Type a sentence first, then swipe up from the spacebar.");
            return;
        }
        String origTrim = analysis.originalTail().trim();
        subtitle.setText("Typed: “" + origTrim + "”");

        List<TailCorrectionEngine.WordSlot> words = analysis.words();
        for (int i = 0; i < words.size(); i++) {
            final int idx = i;
            TailCorrectionEngine.WordSlot slot = words.get(i);
            boolean marked = markedWords.contains(i);
            String pinned = pinnedWords.get(i);
            TextView chip = new TextView(c);
            chip.setText(pinned != null ? "✓ " + pinned : (marked ? "• " + slot.original() : slot.original()));
            chip.setTextColor(marked ? ON_PILL_TEXT : TEXT);
            chip.setTextSize(13.5f);
            chip.setPadding(dp(c, 11), dp(c, 6), dp(c, 11), dp(c, 6));
            chip.setBackground(pillPress(c, marked ? ACTION_FILL : CHIP_FILL));
            chip.setOnClickListener(v -> {
                if (markedWords.contains(idx)) {
                    markedWords.remove(idx);
                    pinnedWords.remove(idx);
                    if (selectedWordIndex == idx) {
                        selectedWordIndex = markedWords.isEmpty() ? -1 : markedWords.iterator().next();
                    }
                } else {
                    markedWords.add(idx);
                    selectedWordIndex = idx;
                }
                render();
            });
            LayoutParams lp = new LayoutParams(-2, -2);
            lp.rightMargin = dp(c, 6);
            wordChipRow.addView(chip, lp);
        }

        if (selectedWordIndex >= 0 && selectedWordIndex < words.size() && markedWords.contains(selectedWordIndex)) {
            TailCorrectionEngine.WordSlot slot = words.get(selectedWordIndex);
            String activePin = pinnedWords.get(selectedWordIndex);
            if (slot.candidates().isEmpty()) {
                TextView empty = new TextView(c);
                empty.setText("No close neighbors for “" + slot.original() + "”");
                empty.setTextColor(TEXT_MUTED);
                empty.setTextSize(12.5f);
                wordCandRow.addView(empty);
            } else {
                for (String cand : slot.candidates()) {
                    boolean isPinned = cand.equals(activePin);
                    TextView pill = new TextView(c);
                    pill.setText(cand);
                    pill.setTextColor(isPinned ? ON_PILL_TEXT : TEXT);
                    pill.setTextSize(13.5f);
                    pill.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                    pill.setPadding(dp(c, 12), dp(c, 6), dp(c, 12), dp(c, 6));
                    pill.setBackground(pillPress(c, isPinned ? RETURN_PILL_FILL : KEY_FILL));
                    pill.setOnClickListener(v -> {
                        if (cand.equals(pinnedWords.get(selectedWordIndex))) {
                            pinnedWords.remove(selectedWordIndex);
                        } else {
                            pinnedWords.put(selectedWordIndex, cand);
                        }
                        render();
                    });
                    LayoutParams lp = new LayoutParams(-2, -2);
                    lp.rightMargin = dp(c, 6);
                    wordCandRow.addView(pill, lp);
                }
            }
        }

        List<TailCorrectionEngine.PhraseFix> phrases = markedWords.isEmpty()
                ? List.of()
                : TailCorrectionEngine.recomputePhrases(analysis, markedWords, pinnedWords, selectedWordIndex, 4);
        if (phrases.isEmpty()) {
            TextView none = new TextView(c);
            none.setText(markedWords.isEmpty()
                    ? "Tap any word above to mark it for correction."
                    : "No close replacements for the marked words.");
            none.setTextColor(TEXT_MUTED);
            none.setTextSize(13f);
            none.setPadding(0, dp(c, 4), 0, dp(c, 6));
            phraseList.addView(none);
        } else {
            for (int i = 0; i < phrases.size(); i++) {
                TailCorrectionEngine.PhraseFix pf = phrases.get(i);
                TextView row = new TextView(c);
                row.setText(highlightChanges(origTrim, pf.label()));
                row.setTextColor(i == 0 ? ON_PILL_TEXT : TEXT);
                row.setTextSize(15f);
                row.setLineSpacing(dp(c, 2), 1.0f);
                row.setPadding(dp(c, 14), dp(c, 11), dp(c, 14), dp(c, 11));
                row.setBackground(cardPress(c, i == 0 ? RETURN_PILL_FILL : CHIP_FILL));
                row.setOnClickListener(v -> {
                    if (onPickPhrase != null) onPickPhrase.accept(pf);
                });
                LayoutParams lp = new LayoutParams(-1, -2);
                lp.bottomMargin = dp(c, 7);
                phraseList.addView(row, lp);
            }
        }
    }

    /** Bolds and underlines words in {@code candidate} that differ from {@code original}. */
    static CharSequence highlightChanges(String original, String candidate) {
        if (original == null || candidate == null || original.equals(candidate)) return candidate == null ? "" : candidate;
        Set<String> origTokens = new HashSet<>();
        for (String t : original.split("\\s+")) {
            if (!t.isEmpty()) origTokens.add(t.toLowerCase(Locale.ROOT));
        }
        SpannableStringBuilder ssb = new SpannableStringBuilder(candidate);
        int i = 0, n = candidate.length();
        while (i < n) {
            while (i < n && Character.isWhitespace(candidate.charAt(i))) i++;
            int start = i;
            while (i < n && !Character.isWhitespace(candidate.charAt(i))) i++;
            if (i > start) {
                String tok = candidate.substring(start, i);
                if (!origTokens.contains(tok.toLowerCase(Locale.ROOT))) {
                    ssb.setSpan(new StyleSpan(Typeface.BOLD), start, i, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    ssb.setSpan(new UnderlineSpan(), start, i, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
        }
        return ssb;
    }

    private static StateListDrawable cardPress(Context c, int fill) {
        StateListDrawable st = new StateListDrawable();
        st.addState(new int[]{android.R.attr.state_pressed}, rounded(c, KEY_PRESSED, 12));
        st.addState(new int[0], rounded(c, fill, 12));
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
