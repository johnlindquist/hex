package anomaly.hex.android.speech;

import android.content.Context;
import anomaly.hex.android.core.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Streams 16 kHz mono 16-bit PCM directly to crash-safe owner-only WAV files
 *  (zero session RAM growth), repairs interrupted .wav.partial files after crashes/kills,
 *  and supports chunked re-transcription for 10+ minute recordings. */
public final class RecordingStore {
  public static final int SAMPLE_RATE = 16000;
  private static final long MAX_BYTES = 256L * 1024L * 1024L; // 256 MiB disk cap
  private static final int CHUNK_SAMPLES = 10 * SAMPLE_RATE; // 10 s core per chunk
  private static final int CONTEXT_SAMPLES = SAMPLE_RATE; // 1 s overlap context
  private static final long MIN_RECOVERABLE_PCM_BYTES = 3200L; // 0.1 s
  private static final long SYNC_INTERVAL_BYTES = 64L * 1024L; // ~2 s of audio
  private static final Set<String> ACTIVE_PATHS = ConcurrentHashMap.newKeySet();

  private RecordingStore() {}

  public static File dir(Context c) {
    File d = new File(c.getApplicationContext().getFilesDir(), "recordings");
    if (!d.exists()) d.mkdirs();
    return d;
  }

  /** Streaming WAV writer fed by SpeechSession.Sink.onCheckpoint(byte[]). */
  public static final class Writer {
    private final File partialFile;
    private final File finalFile;
    private RandomAccessFile raf;
    private long dataBytes;
    private long unsyncedBytes;
    private boolean closed;

    public Writer(Context c) throws IOException {
      File d = dir(c);
      String base = "rec_" + System.currentTimeMillis() + "_" + (System.nanoTime() & 0xFFFF);
      partialFile = new File(d, base + ".wav.partial");
      finalFile = new File(d, base + ".wav");
      ACTIVE_PATHS.add(partialFile.getAbsolutePath());
      try {
        raf = new RandomAccessFile(partialFile, "rw");
        raf.write(wavHeader(0));
      } catch (IOException e) {
        ACTIVE_PATHS.remove(partialFile.getAbsolutePath());
        throw e;
      }
    }

    public synchronized void appendPcm(byte[] pcm) {
      if (closed || raf == null || pcm == null || pcm.length == 0) return;
      try {
        raf.write(pcm);
        dataBytes += pcm.length;
        unsyncedBytes += pcm.length;
        if (unsyncedBytes >= SYNC_INTERVAL_BYTES) {
          raf.getFD().sync();
          unsyncedBytes = 0;
        }
      } catch (IOException ignored) {
      }
    }

    public synchronized String finish(Context c, boolean keep) {
      if (closed) return keep && dataBytes > 0 && finalFile.isFile() ? finalFile.getAbsolutePath() : null;
      closed = true;
      try {
        if (raf != null) {
          if (keep && dataBytes > 0) {
            raf.seek(0);
            raf.write(wavHeader(dataBytes));
            raf.getFD().sync();
          }
          raf.close();
        }
      } catch (IOException ignored) {
      } finally {
        raf = null;
        ACTIVE_PATHS.remove(partialFile.getAbsolutePath());
      }
      if (!keep || dataBytes == 0) {
        partialFile.delete();
        return null;
      }
      File target = partialFile.renameTo(finalFile) ? finalFile : partialFile;
      pruneCap(c);
      return target.getAbsolutePath();
    }

    public long durationMs() {
      return (dataBytes / 2) * 1000L / SAMPLE_RATE;
    }
  }

  public static final class Recovered {
    public final File wavFile;
    public final long durationMs;
    Recovered(File wavFile, long durationMs) {
      this.wavFile = wavFile;
      this.durationMs = durationMs;
    }
  }

  /** Repairs a crashed/orphaned .wav.partial file by patching its 44-byte header from actual
   *  disk length and renaming to .wav. Returns null (and deletes) if shorter than 0.1 s. */
  public static Recovered repairPartialWav(File partial) {
    if (partial == null || !partial.isFile()) return null;
    long len = partial.length();
    long dataBytes = len - 44L;
    if (dataBytes < MIN_RECOVERABLE_PCM_BYTES) {
      partial.delete();
      return null;
    }
    // Ensure even byte count for 16-bit PCM frames.
    dataBytes = (dataBytes / 2L) * 2L;
    try (RandomAccessFile raf = new RandomAccessFile(partial, "rw")) {
      raf.setLength(44L + dataBytes);
      raf.seek(0);
      raf.write(wavHeader(dataBytes));
      raf.getFD().sync();
    } catch (IOException e) {
      return null;
    }
    String name = partial.getName();
    File target = name.endsWith(".wav.partial")
        ? new File(partial.getParentFile(), name.substring(0, name.length() - ".partial".length()))
        : partial;
    File finalWav = (target.equals(partial) || partial.renameTo(target)) ? target : partial;
    long durationMs = (dataBytes / 2L) * 1000L / SAMPLE_RATE;
    return new Recovered(finalWav, durationMs);
  }

  /** Scans files/recordings/ for any orphaned .wav.partial left behind by a crash or process kill,
   *  repairs each one into a valid WAV, and records it in HexHistory so the user can re-transcribe. */
  public static int recoverInterrupted(Context c, HexHistory history, TranscriptStyle style) {
    File[] partials = dir(c).listFiles((d, name) -> name.endsWith(".wav.partial"));
    if (partials == null || partials.length == 0) return 0;
    int recoveredCount = 0;
    HexSettings settings = new HexSettings(c);
    HistoryRetention retention = settings.historyRetention();
    for (File partial : partials) {
      if (ACTIVE_PATHS.contains(partial.getAbsolutePath())) continue;
      Recovered rec = repairPartialWav(partial);
      if (rec == null) continue;
      if (retention == HistoryRetention.OFF) {
        rec.wavFile.delete();
        continue;
      }
      history.record(
          "",
          "(Interrupted recording — tap Re-transcribe)",
          style == null ? TranscriptStyle.fromId(null) : style,
          rec.durationMs,
          retention,
          rec.wavFile.getAbsolutePath());
      recoveredCount++;
    }
    if (recoveredCount > 0) pruneCap(c);
    return recoveredCount;
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
    for (File f : files) {
      if (!ACTIVE_PATHS.contains(f.getAbsolutePath())) f.delete();
    }
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
