package anomaly.hex.android.speech;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.SystemClock;
import com.k2fsa.sherpa.onnx.*;
import java.io.File;

public final class Engine {
  private static Engine shared;
  private final Context ctx;
  private long prepMs;

  private Engine(Context ctx) {
    this.ctx = ctx.getApplicationContext();
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
    float[] pcm = new float[length];
    for (int i = 0; i < length; i++) pcm[i] = samples[i] / 32768f;
    OfflineRecognizerResult r = decode(pcm);
    return r == null ? "" : r.getText().trim();
  }

  private OfflineRecognizer recognizer;

  synchronized OfflineRecognizerResult decode(float[] pcm) {
    float peak = 0;
    for (float x : pcm) peak = Math.max(peak, Math.abs(x));
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
      c.getModelConfig().setNumThreads(2);
      recognizer = new OfflineRecognizer(assets, c);
      prepMs = SystemClock.elapsedRealtime() - start;
    }
  }
}
