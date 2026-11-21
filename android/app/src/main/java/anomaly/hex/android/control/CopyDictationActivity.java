package anomaly.hex.android.control;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.*;
import anomaly.hex.android.core.*;
import anomaly.hex.android.service.HexBubbleService;
import anomaly.hex.android.speech.*;
import anomaly.hex.android.ui.*;

/** Foreground, copy-only take. No partial output reaches the clipboard or History. */
public final class CopyDictationActivity extends Activity implements SpeechSession.Sink {
  private SpeechSession session;
  private DictationNotification notification;
  private DotScopeView scope;
  private Button done;
  private TextView status;
  private final StringBuilder raw = new StringBuilder(), result = new StringBuilder();
  private TranscriptStyle style;
  private HexSettings settings;
  private long duration;
  private boolean closed, stopping;
  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    if (saved != null) {
      finish();
      return;
    }
    setTitle("Dictate with Hex");
    LinearLayout box = new LinearLayout(this);
    box.setOrientation(1);
    box.setBackgroundColor(HexTheme.SURFACE);
    int pad = HexTheme.dp(this, 16);
    box.setPadding(pad, pad, pad, pad);
    status = new TextView(this);
    status.setText("Starting dictation…");
    box.addView(status);
    scope = new DotScopeView(this);
    box.addView(scope, HexTheme.dp(this, 240), HexTheme.dp(this, 48));
    done = new Button(this);
    done.setText("Finish and copy");
    done.setEnabled(false);
    done.setOnClickListener(v -> stopTake());
    box.addView(done);
    Button cancel = new Button(this);
    cancel.setText("Cancel");
    cancel.setOnClickListener(v -> close(null));
    box.addView(cancel);
    setContentView(box);
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
      startTake();
    else
      requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, 1);
  }
  @Override
  public void onRequestPermissionsResult(int code, String[] perms, int[] grants) {
    super.onRequestPermissionsResult(code, perms, grants);
    if (code != 1 || closed)
      return;
    if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED)
      startTake();
    else
      close("Microphone permission required");
  }
  private void startTake() {
    if (HexBubbleService.active != null && HexBubbleService.active.isBusy()) {
      close("Dictation already active");
      return;
    }
    if (ModelStore.inspect(this).state == ModelStore.State.MISSING) {
      close("Speech model missing");
      return;
    }
    settings = new HexSettings(this);
    style = settings.style();
    var vocab = new CustomVocabulary(this).terms();
    boolean spoken = new HexLabs(this).isEnabled(LabId.SPOKEN_CLEANUP);
    notification = new DictationNotification(this, 7102, this::stopTake, () -> close(null));
    session = new SpeechSession(this, Engine.get(this), null,
        s -> TranscriptFormatter.applyPipeline(s, style, vocab, spoken, ""), this);
    session.start();
  }
  private void stopTake() {
    if (closed || session == null || stopping)
      return;
    stopping = true;
    done.setEnabled(false);
    session.finish();
  }
  @Override
  protected void onNewIntent(Intent i) {
    super.onNewIntent(i);
    stopTake();
  }
  @Override
  protected void onStop() {
    super.onStop();
    if (session != null)
      close(null);
  }
  @Override
  protected void onDestroy() {
    closed = true;
    if (session != null)
      session.cancel();
    if (notification != null)
      notification.dismiss();
    super.onDestroy();
  }
  private void close(String msg) {
    if (closed)
      return;
    closed = true;
    if (session != null)
      session.cancel();
    if (notification != null)
      notification.dismiss();
    if (msg != null)
      Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    finish();
  }
  public void onCheckpoint(byte[] pcm) {}
  public void onError(String code, String detail) {
    runOnUiThread(() -> close(code.replace('_', ' ')));
  }
  public void onMeter(int[] values) {
    runOnUiThread(() -> {
      if (!closed && !stopping)
        scope.update(true, values);
    });
  }
  public void onPhraseReady(String heard, String phrase, long ms) {
    runOnUiThread(() -> {
      if (closed || phrase.isBlank())
        return;
      if (raw.length() + heard.length() > 1000000 || result.length() + phrase.length() > 1000000) {
        close("Dictation too long to copy");
        return;
      }
      if (result.length() > 0) {
        raw.append(' ');
        result.append(' ');
      }
      raw.append(heard);
      result.append(phrase.trim());
      duration += ms;
    });
  }
  public void onStateChanged(String state) {
    runOnUiThread(() -> {
      if (closed || isFinishing())
        return;
      if (state.equals("finished")) {
        if (!stopping || result.length() == 0) {
          close("Nothing copied");
          return;
        }
        String text = result.toString();
        getSystemService(ClipboardManager.class)
            .setPrimaryClip(ClipData.newPlainText("Hex dictation", text));
        new HexHistory(this).record(
            raw.toString(), text, style, duration, settings.historyRetention());
        close("Dictation copied");
      } else if (state.equals("listening") || state.equals("transcribing")) {
        boolean live = state.equals("listening") && !stopping;
        done.setEnabled(live);
        scope.update(live, null);
        status.setText(live ? "Listening" : "Transcribing…");
        notification.show(live);
      } else
        close("Nothing copied");
    });
  }
}
