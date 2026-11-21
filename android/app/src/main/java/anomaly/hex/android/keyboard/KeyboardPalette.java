package anomaly.hex.android.keyboard;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.TextView;

public final class KeyboardPalette {
    private KeyboardPalette() {}
    public static final int BACKDROP = 0xFF1A1B20, KEY_FILL = 0xFF2F3036, KEY_PRESSED = 0xFF45464C,
        MOD_FILL = 0xFF45464C, MOD_PRESSED = 0xFF5A5B62,
        LAYER_PILL_FILL = 0xFFA4ABC1, LAYER_PILL_PRESSED = 0xFFB8BFD4,
        RETURN_PILL_FILL = 0xFFD9E2FF, RETURN_PILL_PRESSED = 0xFFC2CEF5,
        ON_PILL_TEXT = 0xFF1A1B20,
        FUNCTION_FILL = 0xFF45464C, CHIP_FILL = 0xFF2F3036, WELL_FILL = 0xFF25262C,
        SELECT_FILL = 0xFF3E4047, POPUP_SHADOW = 0x6B000000,
        SEPARATOR = 0xFF3C3D41, POPUP_FILL = 0xFF45464C, POPUP_BORDER = 0x24FFFFFF,
        TEXT = 0xFFF1F0F7, TEXT_MUTED = 0xFFB8B7BF, HINT_TEXT = 0xFFB8B7BF,
        ACTION_FILL = 0xFFD9E2FF, ACTION_PRESSED = 0xFFC2CEF5, WARM_CORE = 0xFFFF9500;

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
