package anomaly.hex.android;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Termux-like raw TYPE_NULL terminal view: ignores deleteSurroundingText, handles KEYCODE_DEL/ENTER. */
public final class TerminalImeTestActivity extends Activity {
  private final StringBuilder buffer = new StringBuilder();
  private int delEvents, enterEvents, ignoredDeleteCalls;
  private TextView screen, stats;

  @Override protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(0xFF0D1117);
    root.setPadding(36, 96, 36, 24);

    TextView header = new TextView(this);
    header.setText("HEX TERMINAL TEST (InputType.TYPE_NULL · BaseInputConnection)");
    header.setTextColor(0xFF8B949E);
    header.setTextSize(12);
    header.setTypeface(Typeface.MONOSPACE);
    root.addView(header);

    stats = new TextView(this);
    stats.setTextColor(0xFF58A6FF);
    stats.setTextSize(13);
    stats.setTypeface(Typeface.MONOSPACE);
    stats.setPadding(0, 12, 0, 20);
    root.addView(stats);

    screen = new RawTerminalView(this);
    screen.setTextColor(0xFFE6EDF3);
    screen.setTextSize(20);
    screen.setTypeface(Typeface.MONOSPACE);
    screen.setFocusable(true);
    screen.setFocusableInTouchMode(true);
    screen.setBackgroundColor(0xFF161B22);
    screen.setPadding(24, 24, 24, 24);
    screen.setMinHeight(360);
    root.addView(screen, new LinearLayout.LayoutParams(-1, -2));

    setContentView(root);
    render();
    screen.requestFocus();
    screen.postDelayed(() -> {
      InputMethodManager imm = getSystemService(InputMethodManager.class);
      if (imm != null) imm.showSoftInput(screen, InputMethodManager.SHOW_IMPLICIT);
    }, 300);
  }

  private void render() {
    screen.setText("$ " + buffer + "█");
    stats.setText("KEYCODE_DEL=" + delEvents + "  KEYCODE_ENTER=" + enterEvents + "  ignoredDeleteSurrounding=" + ignoredDeleteCalls);
  }

  private final class RawTerminalView extends TextView {
    RawTerminalView(Context c) { super(c); }
    @Override public boolean onCheckIsTextEditor() { return true; }
    @Override public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
      outAttrs.inputType = InputType.TYPE_NULL;
      outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_ACTION_NONE;
      return new BaseInputConnection(this, false) {
        @Override public boolean commitText(CharSequence text, int newCursorPosition) {
          if (text != null) { buffer.append(text); render(); }
          return true;
        }
        @Override public boolean deleteSurroundingText(int beforeLength, int afterLength) {
          ignoredDeleteCalls++;
          render();
          return true; // Termux/PTY silently ignores Editable deletes!
        }
        @Override public boolean deleteSurroundingTextInCodePoints(int beforeLength, int afterLength) {
          ignoredDeleteCalls++;
          render();
          return true;
        }
        @Override public boolean sendKeyEvent(KeyEvent event) {
          if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_DEL) {
              delEvents++;
              if (buffer.length() > 0) buffer.deleteCharAt(buffer.length() - 1);
              render();
              return true;
            }
            if (event.getKeyCode() == KeyEvent.KEYCODE_ENTER) {
              enterEvents++;
              buffer.append("\n$ ");
              render();
              return true;
            }
          }
          return super.sendKeyEvent(event);
        }
      };
    }
  }
}
