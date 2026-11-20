package anomaly.hex.android.speech;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.res.Configuration;
import java.util.concurrent.atomic.AtomicBoolean;
import android.content.res.AssetManager;
import android.os.SystemClock;
import com.k2fsa.sherpa.onnx.*;
import java.io.File;

public final class Engine {
  private static Engine shared;
  private final Context ctx;
  private long prepMs;
  private static final short[] EMPTY = new short[0];
  private float[] scratch;
  private final AtomicBoolean warming = new AtomicBoolean();

  private Engine(Context ctx) {
    this.ctx = ctx.getApplicationContext();
    this.ctx.registerComponentCallbacks(new ComponentCallbacks2() {
      public void onConfigurationChanged(Configuration config) {}
      public void onLowMemory() { releaseIfIdle(); }
      public void onTrimMemory(int level) {
        if (level >= TRIM_MEMORY_BACKGROUND || level == TRIM_MEMORY_RUNNING_CRITICAL
            || level == TRIM_MEMORY_RUNNING_LOW) releaseIfIdle();
      }
    });
  }

  public static synchronized Engine get(Context ctx) {
    if (shared == null) shared = new Engine(ctx);
    return shared;
  }

  public synchronized boolean isAvailable() {
    return recognizer != null || ModelStore.inspect(ctx).state != ModelStore.State.MISSING;
  }

  public synchronized long lastPrepMs() {
    return prepMs;
  }

  public synchronized String transcribe(short[] samples, int length) {
    OfflineRecognizerResult r = decodeSegments(EMPTY, samples, length, EMPTY);
    return r == null ? "" : r.getText().trim();
  }

  synchronized OfflineRecognizerResult decodeSegments(short[] left, short[] core, int coreLen, short[] right) {
    int length = left.length + coreLen + right.length;
    if (length < 2000) return null;
    // sherpa accepts the whole array, never a slice: do not feed a stale tail.
    if (scratch == null || scratch.length != length) scratch = new float[length];
    float peak = 0;
    int coreEnd = left.length + coreLen;
    for (int i = 0; i < length; i++) {
      short sample = i < left.length ? left[i] : i < coreEnd ? core[i - left.length] : right[i - coreEnd];
      float x = sample / 32768f;
      scratch[i] = x;
      peak = Math.max(peak, Math.abs(x));
    }
    return decode(scratch, peak);
  }

  private volatile OfflineRecognizer recognizer;

  public void prewarmAsync() {
    if (recognizer != null || !warming.compareAndSet(false, true)) return;
    Thread thread = new Thread(() -> {
      try {
        synchronized (this) {
          if (recognizer == null && isAvailable()) load();
        }
      } catch (RuntimeException e) {
        android.util.Log.w("HexEngine", "Model prewarm failed", e);
      } finally {
        warming.set(false);
      }
    }, "hex-model-warm");
    thread.setDaemon(true);
    thread.start();
  }

  public synchronized boolean releaseIfIdle() {
    scratch = null;
    prepMs = 0;
    if (recognizer == null) return false;
    recognizer.release();
    recognizer = null;
    return true;
  }

  public void releaseIfIdleForTest() { releaseIfIdle(); }

  synchronized OfflineRecognizerResult decode(float[] pcm) {
    float peak = 0;
    for (float x : pcm) peak = Math.max(peak, Math.abs(x));
    return decode(pcm, peak);
  }

  private OfflineRecognizerResult decode(float[] pcm, float peak) {
    if (pcm.length < 2000 || peak < .0005f) return null;
    if (peak < .25f) {
      float gain = Math.min(12, .5f / peak);
      for (int i = 0; i < pcm.length; i++) pcm[i] *= gain;
    }
    load();
    OfflineStream s = recognizer.createStream();
    try {
      s.acceptWaveform(pcm, 16000);
      recognizer.decode(s);
      return recognizer.getResult(s);
    } finally {
      s.release();
    }
  }

  static int optimalThreads(int availableProcessors) {
    return Math.max(2, Math.min(4, availableProcessors - 1));
  }

  private void load() {
    if (recognizer == null) {
      long start = SystemClock.elapsedRealtime();
      ModelStore.Info info = ModelStore.inspect(ctx);
      if (info.state == ModelStore.State.MISSING) throw new IllegalStateException("Model missing");
      AssetManager assets = info.state == ModelStore.State.READY_BUNDLED ? ctx.getAssets() : null;
      String prefix = assets == null ? info.dir.getAbsolutePath() + File.separator : "";
      OfflineTransducerModelConfig t = new OfflineTransducerModelConfig();
      t.setEncoder(prefix + "encoder.int8.onnx");
      t.setDecoder(prefix + "decoder.int8.onnx");
      t.setJoiner(prefix + "joiner.int8.onnx");
      OfflineRecognizerConfig c = new OfflineRecognizerConfig();
      c.getFeatConfig().setSampleRate(16000);
      c.getFeatConfig().setFeatureDim(128);
      c.getModelConfig().setTransducer(t);
      c.getModelConfig().setModelType("nemo_transducer");
      c.getModelConfig().setTokens(prefix + "tokens.txt");
      c.getModelConfig().setNumThreads(optimalThreads(Runtime.getRuntime().availableProcessors()));
      recognizer = new OfflineRecognizer(assets, c);
      prepMs = SystemClock.elapsedRealtime() - start;
    }
  }
}
