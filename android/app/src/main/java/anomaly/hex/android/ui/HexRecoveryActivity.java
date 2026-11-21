package anomaly.hex.android.ui;
import static anomaly.hex.android.ui.HexTheme.*;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import anomaly.hex.android.core.*;
import anomaly.hex.android.recovery.Drafts;
import anomaly.hex.android.speech.Engine;

public final class HexRecoveryActivity extends Activity {
  private HexLabs labs;
  private Drafts store;
  private boolean working;
  @Override
  public void onCreate(Bundle b) {
    super.onCreate(b);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    labs = new HexLabs(this);
  }
  private boolean allowed() {
    return labs.isEnabled(LabId.RECOVERY_DRAFTS)
        && !getSystemService(KeyguardManager.class).isKeyguardLocked();
  }
  @Override
  public void onResume() {
    super.onResume();
    draw();
  }
  private void draw() {
    if (!allowed()) {
      finish();
      return;
    }
    if (store == null)
      store = new Drafts(this);
    store.purgeExpired();
    ScrollView scroll = new ScrollView(this);
    scroll.setBackgroundColor(CANVAS);
    scroll.setOnApplyWindowInsetsListener((v, i) -> {
      android.graphics.Insets p = i.getInsets(WindowInsets.Type.systemBars());
      v.setPadding(p.left, p.top, p.right, p.bottom);
      return i;
    });
    LinearLayout rows = new LinearLayout(this);
    rows.setOrientation(1);
    int d = dp(this, 20);
    rows.setPadding(d, d, d, d);
    scroll.addView(rows);
    setContentView(scroll);
    rows.addView(button(this, "Back", false, this::finish));
    rows.addView(text(this, "Recovery drafts", 30, TEXT));
    rows.addView(
        text(this, "Interrupted dictations stay on this device until they expire.", 15, MUTED));
    java.util.List<Drafts.Draft> entries = store.list();
    if (entries.isEmpty())
      rows.addView(text(this, "No drafts to recover.", 17, TEXT));
    for (Drafts.Draft item : entries) {
      LinearLayout card = new LinearLayout(this);
      card.setOrientation(1);
      card.setPadding(d, d, d, d);
      card.setBackground(shape(this, SURFACE, 18, 0));
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
      p.topMargin = d;
      rows.addView(card, p);
      card.addView(text(this,
          java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(item.createdAtMs)),
          15, MUTED));
      boolean hasText = item.text != null && !item.text.isEmpty();
      if (hasText)
        card.addView(text(this, item.text, 16, TEXT));
      Button recover = button(this, hasText ? "Copy text" : "Transcribe draft", false, () -> {
        if (!fresh(item))
          return;
        if (hasText) {
          getSystemService(ClipboardManager.class)
              .setPrimaryClip(ClipData.newPlainText("Hex draft", item.text));
        } else
          retry(item);
      });
      recover.setEnabled(!working);
      card.addView(recover);
      card.addView(button(this, "Delete draft", false,
          ()
              -> new AlertDialog.Builder(this)
                  .setMessage("Permanently delete this draft?")
                  .setNegativeButton("Keep", null)
                  .setPositiveButton("Delete",
                      (v, n) -> {
                        if (allowed())
                          store.delete(item.id, item.rev);
                        draw();
                      })
                  .show()));
    }
  }
  private boolean fresh(Drafts.Draft item) {
    if (!allowed())
      return false;
    Drafts.Draft current = store.get(item.id);
    return current != null && current.rev == item.rev;
  }
  private void retry(Drafts.Draft item) {
    working = true;
    draw();
    new Thread(() -> {
      String result = "";
      try {
        if (fresh(item)) {
          byte[] b = store.readAudio(item.id);
          if (b != null) {
            short[] audio = new short[b.length / 2];
            for (int i = 0; i < audio.length; i++)
              audio[i] = (short) ((b[2 * i] & 255) | (b[2 * i + 1] << 8));
            result = Engine.get(this).transcribe(audio, audio.length);
          }
        }
      } catch (RuntimeException ignored) {
      }
      String finalText = result;
      runOnUiThread(() -> {
        working = false;
        if (isDestroyed() || isFinishing())
          return;
        if (fresh(item) && finalText != null && !finalText.trim().isEmpty())
          store.markText(item.id, finalText);
        else
          Toast.makeText(this, "Could not recover text", Toast.LENGTH_SHORT).show();
        draw();
      });
    }, "hex-recover").start();
  }
}
