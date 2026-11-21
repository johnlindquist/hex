package anomaly.hex.android.speech;

import android.content.Context;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/** Streams 16 kHz mono 16-bit PCM directly to owner-only WAV files (zero session RAM growth)
 *  and supports chunked re-transcription for 10+ minute recordings. */
public final class RecordingStore {
  public static final int SAMPLE_RATE = 16000;
  private static final long MAX_BYTES = 256L * 1024L * 1024L; // 256 MiB disk cap
  private static final int CHUNK_SAMPLES = 10 * SAMPLE_RATE; // 10 s core per chunk
  private static final int CONTEXT_SAMPLES = SAMPLE_RATE; // 1 s overlap context

  private RecordingStore() {}

  public static File dir(Context c) {
    File d = new File(c.getApplicationContext().getFilesDir(), "recordings");
    if (!d.exists()) d.mkdirs();
    return d;
  }

  /** Streaming WAV writer fed by SpeechSession.Sink.onCheckpoint(byte[]). */
  public static final class Writer {
    private final File file;
    private RandomAccessFile raf;
    private long dataBytes;
    private boolean closed;

    public Writer(Context c) throws IOException {
      File d = dir(c);
      file = new File(d, "rec_" + System.currentTimeMillis() + "_" + (System.nanoTime() & 0xFFFF) + ".wav");
      raf = new RandomAccessFile(file, "rw");
      raf.write(wavHeader(0));
    }

    public synchronized void appendPcm(byte[] pcm) {
      if (closed || raf == null || pcm == null || pcm.length == 0) return;
      try {
        raf.write(pcm);
        dataBytes += pcm.length;
      } catch (IOException ignored) {
      }
    }

    public synchronized String finish(Context c, boolean keep) {
      if (closed) return keep && dataBytes > 0 ? file.getAbsolutePath() : null;
      closed = true;
      try {
        if (raf != null) {
          if (keep && dataBytes > 0) {
            raf.seek(0);
            raf.write(wavHeader(dataBytes));
          }
          raf.close();
        }
      } catch (IOException ignored) {
      } finally {
        raf = null;
      }
      if (!keep || dataBytes == 0) {
        file.delete();
        return null;
      }
      pruneCap(c);
      return file.getAbsolutePath();
    }

    public long durationMs() {
      return (dataBytes / 2) * 1000L / SAMPLE_RATE;
    }
  }

  public static byte[] wavHeader(long dataBytes) {
    ByteBuffer b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
    long totalDataLen = dataBytes + 36;
    int byteRate = SAMPLE_RATE * 2;
    b.put(new byte[] {'R', 'I', 'F', 'F'});
    b.putInt((int) Math.min(Integer.MAX_VALUE, totalDataLen));
    b.put(new byte[] {'W', 'A', 'V', 'E'});
    b.put(new byte[] {'f', 'm', 't', ' '});
    b.putInt(16); // PCM chunk size
    b.putShort((short) 1); // PCM format
    b.putShort((short) 1); // mono
    b.putInt(SAMPLE_RATE);
    b.putInt(byteRate);
    b.putShort((short) 2); // block align
    b.putShort((short) 16); // bits per sample
    b.put(new byte[] {'d', 'a', 't', 'a'});
    b.putInt((int) Math.min(Integer.MAX_VALUE, dataBytes));
    return b.array();
  }

  public static void deleteFile(String path) {
    if (path != null && !path.isEmpty()) {
      try { new File(path).delete(); } catch (SecurityException ignored) {}
    }
  }

  public static void deleteAll(Context c) {
    File[] files = dir(c).listFiles();
    if (files == null) return;
    for (File f : files) f.delete();
  }

  public static void pruneCap(Context c) {
    File[] files = dir(c).listFiles((d, name) -> name.endsWith(".wav"));
    if (files == null || files.length == 0) return;
    Arrays.sort(files, Comparator.comparingLong(File::lastModified));
    long total = 0;
    for (File f : files) total += f.length();
    int i = 0;
    while (total > MAX_BYTES && i < files.length - 1) {
      total -= files[i].length();
      files[i].delete();
      i++;
    }
  }

  /** Re-transcribes an arbitrarily long saved WAV in bounded 10-second chunks. */
  public static String retranscribeWav(Context c, File wav) throws IOException {
    if (wav == null || !wav.isFile() || wav.length() <= 44) return "";
    Engine engine = Engine.get(c);
    if (!engine.isAvailable()) throw new IllegalStateException("Speech model not installed");
    StringBuilder out = new StringBuilder();
    short[] prevTail = new short[0];
    byte[] raw = new byte[CHUNK_SAMPLES * 2];
    try (InputStream in = new BufferedInputStream(new FileInputStream(wav))) {
      long skipped = in.skip(44);
      if (skipped < 44) return "";
      while (true) {
        int read = readFully(in, raw);
        if (read < 2) break;
        int samples = read / 2;
        short[] core = new short[samples];
        for (int i = 0; i < samples; i++) {
          core[i] = (short) ((raw[2 * i] & 0xFF) | (raw[2 * i + 1] << 8));
        }
        var res = engine.decodeSegments(prevTail, core, samples, new short[0]);
        if (res != null) {
          String text = res.getText() == null ? "" : res.getText().trim();
          if (!text.isEmpty()) {
            if (out.length() > 0) out.append(' ');
            out.append(text);
          }
        }
        int keep = Math.min(CONTEXT_SAMPLES, samples);
        prevTail = Arrays.copyOfRange(core, samples - keep, samples);
      }
    }
    return out.toString().trim();
  }

  private static int readFully(InputStream in, byte[] buf) throws IOException {
    int total = 0;
    while (total < buf.length) {
      int n = in.read(buf, total, buf.length - total);
      if (n < 0) break;
      total += n;
    }
    return total;
  }
}
