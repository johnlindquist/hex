package anomaly.hex.android.keyboard;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.StateListDrawable;
import android.view.*;
import android.widget.*;
import static anomaly.hex.android.keyboard.KeyboardPalette.*;

/** Occupies only the key rows, leaving the recording bar and its controls untouched. */
final class PunctuationDrumsOverlay extends LinearLayout {
    PunctuationDrumsOverlay(Context c, FullKeyboardView.Host host) {
        super(c); setOrientation(VERTICAL); setBackgroundColor(BACKDROP);
        setClickable(true); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setPadding(0, dp(c, 4), 0, dp(c, 6));
        setVisibility(GONE);
        TextView hint = label(c, "Tap punctuation while speaking", 13);
        hint.setTextColor(TEXT_MUTED);
        addView(hint, -1, dp(c, 26));
        String[] marks = {",", ".", "?", "!", "\n", "\n\n"};
        String[] glyphs = {",", ".", "?", "!", null, "¶"};
        String[] names = {"Comma", "Period", "Question", "Exclamation", "New line", "Paragraph"};
        for (int r = 0; r < 2; r++) {
            LinearLayout row = new LinearLayout(c);
            row.setGravity(Gravity.CENTER);
            row.setPadding(dp(c, 6), 0, dp(c, 6), 0);
            addView(row, new LayoutParams(-1, 0, 1));
            for (int col = 0; col < 3; col++) {
                int i = 3 * r + col;
                String mark = marks[i];
                boolean accent = i >= 4;
                DrumPad pad = new DrumPad(c, glyphs[i], names[i], accent);
                pad.setContentDescription(names[i]);
                var states = new StateListDrawable();
                for (boolean down : new boolean[]{true, false}) {
                    var shape = pill(accent ? (down ? RETURN_PILL_PRESSED : RETURN_PILL_FILL)
                        : (down ? KEY_PRESSED : KEY_FILL));
                    shape.setCornerRadius(dp(c, 12));
                    states.addState(down ? new int[]{android.R.attr.state_pressed} : new int[0], shape);
                }
                pad.setBackground(states);
                pad.setOnClickListener(v -> host.onLivePunctuation(mark));
                var p = new LayoutParams(0, -1, 1);
                int m = dp(c, 4);
                p.setMargins(m, m, m, m);
                row.addView(pad, p);
            }
        }
    }

    void update(boolean live, int[] levels) { setVisibility(live ? VISIBLE : GONE); }

    private static final class DrumPad extends View {
        private final String glyph, sub;
        private final boolean accent;
        private final Paint glyphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Rect bounds = new Rect();
        private final float iconSize, subGap;

        DrumPad(Context c, String glyph, String sub, boolean accent) {
            super(c);
            this.glyph = glyph;
            this.sub = sub;
            this.accent = accent;
            float sp = c.getResources().getDisplayMetrics().scaledDensity;
            glyphPaint.setColor(accent ? ON_PILL_TEXT : TEXT);
            glyphPaint.setTextAlign(Paint.Align.CENTER);
            glyphPaint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
            glyphPaint.setTextSize(34f * sp);
            subPaint.setColor(accent ? 0xB31A1B20 : TEXT_MUTED);
            subPaint.setTextAlign(Paint.Align.CENTER);
            subPaint.setTextSize(11f * sp);
            iconSize = dp(c, 22);
            subGap = dp(c, 18);
            setClickable(true);
            setFocusable(true);
        }

        @Override protected void onDraw(Canvas canvas) {
            float cx = getWidth() * 0.5f;
            float glyphCy = getHeight() * 0.42f;
            if (glyph == null) {
                KeyIcons.drawReturn(canvas, cx, glyphCy, iconSize, accent ? ON_PILL_TEXT : TEXT);
            } else {
                glyphPaint.getTextBounds(glyph, 0, glyph.length(), bounds);
                canvas.drawText(glyph, cx, glyphCy - bounds.exactCenterY(), glyphPaint);
            }
            float subY = getHeight() * 0.76f - (subPaint.ascent() + subPaint.descent()) * 0.5f;
            canvas.drawText(sub, cx, subY, subPaint);
        }
    }
}
