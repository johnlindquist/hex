package anomaly.hex.android.keyboard;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.TextView;

public final class KeyboardPalette {
    private KeyboardPalette() {}
    public static final int BACKDROP = 0xFF17181A, KEY_FILL = 0x29FFFFFF, KEY_PRESSED = 0x47FFFFFF,
        FUNCTION_FILL = 0x17FFFFFF, CHIP_FILL = 0x1CFFFFFF, WELL_FILL = 0x0EFFFFFF,
        SELECT_FILL = 0x2EFFFFFF, POPUP_SHADOW = 0x6B000000,
        SEPARATOR = 0x1FFFFFFF, POPUP_FILL = 0xFF4E5056, POPUP_BORDER = 0x24FFFFFF,
        TEXT = 0xFFFFFFFF, TEXT_MUTED = 0x8FFFFFFF, ACTION_FILL = 0xFF007AFF,
        ACTION_PRESSED = 0xFF0062CC, WARM_CORE = 0xFFFF9500;

    static int dp(Context c, float value) { return Math.round(value * c.getResources().getDisplayMetrics().density); }
    static GradientDrawable pill(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(1000);
        return shape;
    }
    static TextView label(Context c, String text, int size) {
        TextView view = new TextView(c);
        view.setText(text);
        view.setTextSize(size);
        view.setGravity(Gravity.CENTER);
        view.setTextColor(TEXT);
        view.setSingleLine();
        view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return view;
    }
}
