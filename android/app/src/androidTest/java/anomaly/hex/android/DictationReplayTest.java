package anomaly.hex.android;

import android.app.*;
import android.os.*;
import android.view.accessibility.AccessibilityNodeInfo;
import anomaly.hex.android.service.HexBubbleService;
import anomaly.hex.android.speech.SpeechSession;
import java.io.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Instrumentation-only emulator replay. Focus an empty Hex scratchpad and provide
 * files/qualification.pcm.
 */
public final class DictationReplayTest extends Instrumentation {
  private Bundle args;
  @Override
  public void onCreate(Bundle b) {
    super.onCreate(b);
    args = b;
    start();
  }
  private String field(UiAutomation ui) {
    AccessibilityNodeInfo root = ui.getRootInActiveWindow();
    AccessibilityNodeInfo f =
        root == null ? null : root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
    if (f == null || !f.isEditable()
        || !getTargetContext().getPackageName().contentEquals(f.getPackageName()))
      return null;
    return f.getText() == null || f.isShowingHintText() ? "" : f.getText().toString();
  }
  @Override
  public void onStart() {
    Bundle out = new Bundle();
    try {
      if (!Build.FINGERPRINT.contains("generic") && !Build.PRODUCT.startsWith("sdk_gphone"))
        throw new IllegalStateException("Emulator only");
      File pcm = new File(getTargetContext().getFilesDir(), "qualification.pcm");
      if (!pcm.isFile() || pcm.length() < 8000 || pcm.length() > 32000L * 360
          || pcm.length() % 2 != 0)
        throw new IOException("Supply bounded 16 kHz mono PCM16LE");
      String expected = args.getString("expected", "").trim();
      if (expected.isEmpty())
        throw new IllegalArgumentException("Supply -e expected 'expected text'");
      UiAutomation ui = getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
      long until = SystemClock.elapsedRealtime() + 60000;
      while (!"".equals(field(ui)) && SystemClock.elapsedRealtime() < until) SystemClock.sleep(100);
      if (!"".equals(field(ui)))
        throw new IllegalStateException("Focus an empty Hex scratchpad");
      AtomicReference<String> code = new AtomicReference<>();
      runOnMainSync(() -> {
        HexBubbleService h = HexBubbleService.active;
        if (h != null && h.getInputMethod() != null
            && h.getInputMethod().getCurrentInputEditorInfo() != null
            && getTargetContext().getPackageName().equals(
                h.getInputMethod().getCurrentInputEditorInfo().packageName))
          code.set(h.start(() -> new Fixture(pcm)));
      });
      if (!"ok".equals(code.get()))
        throw new IllegalStateException("Start failed: " + code.get());
      until = SystemClock.elapsedRealtime() + pcm.length() / 32 + 75000;
      do {
        SystemClock.sleep(150);
        runOnMainSync(()
                          -> code.set(HexBubbleService.active == null ? "lost"
                                  : HexBubbleService.active.isBusy()  ? "busy"
                                                                      : "done"));
      } while ("busy".equals(code.get()) && SystemClock.elapsedRealtime() < until);
      if (!"done".equals(code.get()) || !expected.equals(field(ui)))
        throw new AssertionError("Editor did not confirm expected text");
      out.putString("result", "confirmed");
      finish(Activity.RESULT_OK, out);
    } catch (Throwable t) {
      out.putString("error", t.toString());
      finish(Activity.RESULT_CANCELED, out);
    }
  }
  static final class Fixture implements SpeechSession.Input {
    final InputStream in;
    final byte[] bytes = new byte[4096];
    long samples, at = SystemClock.elapsedRealtime();
    Fixture(File f) throws IOException {
      in = new BufferedInputStream(new FileInputStream(f));
    }
    public int read(short[] b, int off, int n) {
      try {
        int k = in.read(bytes, 0, Math.min(bytes.length, n * 2));
        if (k < 0)
          return -1;
        for (int i = 0; i < k / 2; i++)
          b[off + i] = (short) ((bytes[2 * i] & 255) | (bytes[2 * i + 1] << 8));
        samples += k / 2;
        SystemClock.sleep(Math.max(0, at + samples / 16 - SystemClock.elapsedRealtime()));
        return k / 2;
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
    }
    public boolean isSilenced() {
      return false;
    }
    public void close() {
      try {
        in.close();
      } catch (IOException ignored) {
      }
    }
  }
}
