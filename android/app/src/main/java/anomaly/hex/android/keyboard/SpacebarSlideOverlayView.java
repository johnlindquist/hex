package anomaly.hex.android.keyboard;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import static anomaly.hex.android.keyboard.KeyboardPalette.*;

/**
 * Option 2 UI: Hold spacebar + slide up & release (zero-second-tap floating picker).
 * Renders up to 3 phrase fixes stacked above the spacebar (index 0 at bottom, closest to thumb).
 */
final class SpacebarSlideOverlayView extends LinearLayout {
    private final List<TextView> rows = new ArrayList<>();
    private final TextView hint;
    private List<TailCorrectionEngine.PhraseFix> options = List.of();
    private int highlighted = -1;

    SpacebarSlideOverlayView(Context c) {
        super(c);
        setOrientation(VERTICAL);
        setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        setPadding(dp(c, 16), dp(c, 8), dp(c, 16), dp(c, 58));
        setClickable(false);
        setFocusable(false);

        hint = new TextView(c);
        hint.setTextColor(TEXT_MUTED);
        hint.setTextSize(12f);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(c, 10), dp(c, 4), dp(c, 10), dp(c, 4));
        GradientDrawable hintBg = new GradientDrawable();
        hintBg.setColor(0xE614161A);
        hintBg.setCornerRadius(dp(c, 999));
        hint.setBackground(hintBg);
    }

    void show(List<TailCorrectionEngine.PhraseFix> phrases) {
        options = phrases == null ? List.of() : phrases;
        highlighted = options.isEmpty() ? -1 : 0;
        rebuild();
    }

    /** Updates highlighted option based on upward thumb slide distance in dp. */
    boolean updateSlide(float dyUpDp) {
        int prev = highlighted;
        if (options.isEmpty() || dyUpDp < 20f) {
            highlighted = -1;
        } else {
            int idx = (int) ((dyUpDp - 20f) / 42f);
            highlighted = Math.max(0, Math.min(options.size() - 1, idx));
        }
        if (highlighted != prev) {
            styleRows();
            return true;
        }
        return false;
    }

    TailCorrectionEngine.PhraseFix currentPick() {
        if (highlighted < 0 || highlighted >= options.size()) return null;
        return options.get(highlighted);
    }

    private void rebuild() {
        Context c = getContext();
        removeAllViews();
        rows.clear();
        // Stack top-to-bottom so index 0 is closest to the spacebar
        for (int i = options.size() - 1; i >= 0; i--) {
            TextView tv = new TextView(c);
            tv.setText(options.get(i).label());
            tv.setTextSize(14.5f);
            tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            tv.setSingleLine(true);
            tv.setEllipsize(TextUtils.TruncateAt.START);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 10));
            LayoutParams lp = new LayoutParams(-1, -2);
            lp.bottomMargin = dp(c, 6);
            addView(tv, lp);
            rows.add(0, tv);
        }
        addView(hint, new LayoutParams(-2, -2));
        styleRows();
    }

    private void styleRows() {
        Context c = getContext();
        for (int i = 0; i < rows.size(); i++) {
            boolean active = (i == highlighted);
            TextView tv = rows.get(i);
            tv.setTextColor(active ? ON_PILL_TEXT : TEXT);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(active ? RETURN_PILL_FILL : 0xF01C1F24);
            bg.setCornerRadius(dp(c, 12));
            bg.setStroke(dp(c, 1), active ? RETURN_PILL_FILL : 0x33FFFFFF);
            tv.setBackground(bg);
        }
        if (options.isEmpty()) {
            hint.setText("No fixes found — release to cancel");
        } else if (highlighted < 0) {
            hint.setText("Slide up to pick · release to cancel");
        } else {
            hint.setText("Release to apply fix");
        }
    }
}
