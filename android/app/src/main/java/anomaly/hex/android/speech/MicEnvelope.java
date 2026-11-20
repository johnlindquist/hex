package anomaly.hex.android.speech;

/** Bounded display history, not a second audio recorder. One sample per PCM block (~40 ms). */
public final class MicEnvelope {
  public static final int BARS = 10;
  private final float[] history = new float[BARS];
  private int next;

  synchronized void accept(double meanSquare) {
    // Fixed dBFS mapping keeps pauses quiet rather than normalizing room noise to full height.
    double db = 10 * Math.log10(Math.max(meanSquare, 1e-12));
    // Leave headroom for loud syllables: clipping all of them at -14 dBFS makes a flat fence.
    double normalized = Math.max(0, Math.min(1, (db + 54) / 53));
    history[next] = (float) Math.pow(normalized, 1.65);
    next = (next + 1) % BARS;
  }

  public synchronized void reset() {
    java.util.Arrays.fill(history, 0);
    next = 0;
  }

  public synchronized void push(short[] pcm, int count) {
    if (count <= 0) return;
    double sum = 0;
    for (int i = 0; i < count; i++) {
      double x = pcm[i] / 32768.;
      sum += x * x;
    }
    accept(sum / count);
  }

  /** Oldest to newest, each in percent of display height. */
  public synchronized int[] targets() {
    int[] out = new int[BARS];
    for (int i = 0; i < BARS; i++) out[i] = Math.round(100 * history[(next + i) % BARS]);
    return out;
  }

  public synchronized int peak() {
    int max = 0;
    for (int v : targets()) max = Math.max(max, v);
    return max;
  }

  synchronized void read(float[] dst) {
    for (int i = 0; i < BARS; i++) dst[i] = history[(next + i) % BARS];
  }
}
