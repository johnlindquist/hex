package anomaly.hex.android;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.SystemClock;
import anomaly.hex.android.speech.Engine;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * Repeatable on-device speed and memory benchmark for Hex for Android.
 * Invoked via: am instrument -w -e mode perf anomaly.hex.android.test/anomaly.hex.android.DictationReplayTest
 */
final class PerfBenchTest {
  private static short[] readPcm(File f) throws IOException {
    byte[] raw = new byte[(int) f.length()];
    try (FileInputStream in = new FileInputStream(f)) {
      int off = 0;
      while (off < raw.length) {
        int n = in.read(raw, off, raw.length - off);
        if (n < 0) break;
        off += n;
      }
    }
    short[] pcm = new short[raw.length / 2];
    for (int i = 0; i < pcm.length; i++) {
      pcm[i] = (short) ((raw[2 * i] & 0xff) | (raw[2 * i + 1] << 8));
    }
    return pcm;
  }

  private static void recordMem(Bundle out, String prefix) {
    Runtime.getRuntime().gc();
    SystemClock.sleep(200);
    Debug.MemoryInfo mi = new Debug.MemoryInfo();
    Debug.getMemoryInfo(mi);
    out.putInt(prefix + "_total_pss_kb", mi.getTotalPss());
    out.putInt(prefix + "_native_pss_kb", mi.nativePss);
    out.putInt(prefix + "_dalvik_pss_kb", mi.dalvikPss);
    out.putInt(prefix + "_other_pss_kb", mi.otherPss);
    out.putInt(prefix + "_private_dirty_kb", mi.getTotalPrivateDirty());
  }

  static void run(Instrumentation inst) {
    Bundle out = new Bundle();
    try {
      if (!Build.FINGERPRINT.contains("generic") && !Build.PRODUCT.startsWith("sdk_gphone"))
        throw new IllegalStateException("Emulator only");
      Context targetCtx = inst.getTargetContext();
      File pcmFile = new File(targetCtx.getFilesDir(), "qualification.pcm");
      if (!pcmFile.isFile() || pcmFile.length() < 8000)
        throw new IOException("Missing files/qualification.pcm");
      short[] pcm = readPcm(pcmFile);
      long audioMs = pcm.length * 1000L / 16000L;
      out.putLong("audio_ms", audioMs);

      recordMem(out, "before_load");

      Engine engine = Engine.get(targetCtx);
      long t0 = SystemClock.elapsedRealtime();
      String text0 = engine.transcribe(pcm, pcm.length);
      long coldTotalMs = SystemClock.elapsedRealtime() - t0;
      long coldPrepMs = engine.lastPrepMs();
      long coldDecodeMs = Math.max(0, coldTotalMs - coldPrepMs);

      out.putLong("cold_prep_ms", coldPrepMs);
      out.putLong("cold_decode_ms", coldDecodeMs);
      out.putLong("cold_total_ms", coldTotalMs);
      out.putString("transcript", text0);

      long[] warm = new long[3];
      for (int i = 0; i < warm.length; i++) {
        long s = SystemClock.elapsedRealtime();
        String txt = engine.transcribe(pcm, pcm.length);
        warm[i] = SystemClock.elapsedRealtime() - s;
        if (txt.isEmpty()) throw new AssertionError("Empty warm transcript on run " + i);
      }
      long[] sorted = Arrays.copyOf(warm, warm.length);
      Arrays.sort(sorted);
      long warmMedianMs = sorted[1];

      out.putLong("warm_run1_ms", warm[0]);
      out.putLong("warm_run2_ms", warm[1]);
      out.putLong("warm_run3_ms", warm[2]);
      out.putLong("warm_median_ms", warmMedianMs);
      out.putString("warm_rtf", String.format(java.util.Locale.US, "%.4f", warmMedianMs / (double) audioMs));

      recordMem(out, "after_decode");

      // Trigger background memory trim to measure whether model/arena memory can be reclaimed
      inst.runOnMainSync(() -> {
        Context app = targetCtx.getApplicationContext();
        if (app instanceof Application)
          ((Application) app).onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND);
      });
      try {
        java.lang.reflect.Method release = Engine.class.getDeclaredMethod("releaseIfIdleForTest");
        release.invoke(engine);
      } catch (NoSuchMethodException ignored) {
      }
      SystemClock.sleep(300);
      recordMem(out, "after_trim");

      out.putString("result", "ok");
      inst.finish(Activity.RESULT_OK, out);
    } catch (Throwable t) {
      out.putString("error", t.toString());
      inst.finish(Activity.RESULT_CANCELED, out);
    }
  }
}
