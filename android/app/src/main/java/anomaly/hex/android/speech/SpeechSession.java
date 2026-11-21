package anomaly.hex.android.speech;

import android.content.Context;
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;

/** One microphone stream and a separate ordered inference lane. No session-length PCM array. */
public final class SpeechSession {
  static final int RATE = 16000,
      HARD = 10 * RATE,
      CTX = 16000,
      PAUSE = 8960,
      SETTLE = 24000,
      QUEUE_BUDGET = 120 * RATE; // 2 min decode backlog headroom for 10+ min takes.

  public interface Input {
    int read(short[] buf, int offset, int len);

    boolean isSilenced();

    void close();
  }

  public interface Source {
    Input open() throws Exception;
  }

  public interface PostProcessor {
    String process(String raw);
  }

  public interface Sink {
    void onMeter(int[] targets);

    void onPhraseReady(String rawText, String processedText, long durationMs);

    void onStateChanged(String state);

    void onError(String code, String detail);

    void onCheckpoint(byte[] pcmBytes);

    default void onPunctuationReady(String mark) {}
  }

  interface Decode {
    OfflineRecognizerResult run(float[] pcm);
  }

  private final SegmentDecode decode;

  private interface SegmentDecode {
    OfflineRecognizerResult run(short[] left, short[] core, int coreLen, short[] right);
  }
  final Source source;
  final Sink sink;
  final PostProcessor processor;
  private static final ExecutorService decoder = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "hex-decoder");
    t.setDaemon(true);
    return t;
  });
  final MicEnvelope envelope = new MicEnvelope();
  private volatile boolean running;
  private boolean started;
  final PhraseJoin join = new PhraseJoin();
  final AtomicInteger pending = new AtomicInteger(), queuedSamples = new AtomicInteger();
  volatile int peakSamples;
  volatile boolean stop, discard;
  volatile String interruption = "", limit = "";
  volatile long total;
  volatile int emptyPhrases, forced, fallback, peakPending;
  private final short[] pre = new short[3840];
  private int preN;
  private Part current, waiting;
  private short[] nextLeft = EMPTY;
  private volatile boolean probing, inPause;
  volatile int heldPieces, contextResumes;

  boolean finishingSentence() {
    return inPause || heldPieces > 0;
  }

  private static final class Piece {
    String text;
    final int samples;
    int remaining;

    Piece(Part p, String s) {
      remaining = samples = p.n;
      text = s;
    }
  }

  private final ArrayDeque<Piece> staged =
      new ArrayDeque<>(); // Decoder-owned; holds text, never a growing session waveform.
  private final ConcurrentLinkedQueue<String> drumMarks = new ConcurrentLinkedQueue<>();
  private static final short[] EMPTY = new short[0];

  public SpeechSession(
      Context ctx, Engine engine, Source source, PostProcessor processor, Sink sink) {
    this(engine::decodeSegments, source == null ? () -> new Microphone(ctx) : source, processor, sink);
  }

  SpeechSession(Decode decode, Source source, PostProcessor processor, Sink sink) {
    this((left, core, coreLen, right) -> {
      float[] pcm = new float[left.length + coreLen + right.length];
      int j = 0;
      for (short x : left) pcm[j++] = x / 32768f;
      for (int i = 0; i < coreLen; i++) pcm[j++] = core[i] / 32768f;
      for (short x : right) pcm[j++] = x / 32768f;
      return decode.run(pcm);
    }, source, processor, sink);
  }

  private SpeechSession(SegmentDecode decode, Source source, PostProcessor processor, Sink sink) {
    this.decode = decode;
    this.source = source;
    this.processor = processor;
    this.sink = sink;
  }

  public synchronized void start() {
    if (started) return;
    started = running = true;
    new Thread(this::capture, "hex-capture").start();
  }

  public void finish() {
    stop = true;
  }

  /** Seals the live phrase on the capture loop so speech before the tap decodes BEFORE the mark. */
  public void insertPunctuation(String mark) {
    if (running && !stop && !discard && mark != null && !mark.isEmpty()) {
      drumMarks.offer(mark);
    }
  }

  public synchronized void cancel() {
    stop = discard = true;
    interruption = "cancelled";
  }

  public boolean isRunning() {
    return running;
  }

  public JSONObject progressJson() {
    JSONObject j = new JSONObject();
    try {
      j.put("running", running)
          .put("samples", total)
          .put("queuedSamples", queuedSamples.get())
          .put("peakQueuedSamples", peakSamples)
          .put("pending", pending.get())
          .put("peakPending", peakPending)
          .put("emptyPhrases", emptyPhrases)
          .put("contextResumes", contextResumes)
          .put("forcedCuts", forced)
          .put("alignmentFallbacks", fallback)
          .put("finishingSentence", finishingSentence())
          .put("limit", limit);
    } catch (JSONException impossible) {
      throw new IllegalStateException(impossible);
    }
    return j;
  }

  final class Part {
    short[] pcm = new short[2 * RATE];
    short[] left, right = EMPTY;
    short[] block = new short[5120];
    int n, buf, seq, quiet;
    long start;
    boolean hard, voice, natural;
    volatile int stamp = -1, approved = -1;
    int attempted = -1;
    volatile boolean sealed;
    volatile OfflineRecognizerResult guess;
    String drumMark;

    Part(short[] prefix) {
      left = prefix;
    }

    void add(short[] src, int len) throws Exception {
      if (n + len > pcm.length) pcm = Arrays.copyOf(pcm, Math.min(HARD + 640, pcm.length * 2));
      System.arraycopy(src, 0, pcm, n, len);
      n += len;
      for (int i = 0; i < len; i++) {
        block[buf++] = src[i];
        if (buf == block.length || seq == 0 && buf == 640) flush();
      }
    }

    void flush() throws Exception {
      if (buf > 0) {
        byte[] bytes = new byte[buf * 2];
        for (int i = 0; i < buf; i++) {
          bytes[2 * i] = (byte) block[i];
          bytes[2 * i + 1] = (byte) (block[i] >> 8);
        }
        if (!discard) sink.onCheckpoint(bytes);
        seq++;
        buf = 0;
      }
    }
  }

  Part reserve(short[] left) {
    return new Part(left);
  }

  void fail(String code) {
    if (interruption.isEmpty()) {
      interruption = code;
      stop = true;
      sink.onError(code, code);
    }
  }

  void drain(String why) {
    stop = true;
    limit = why;
    sink.onError(why, "Microphone stopped; decoding queued audio");
  }

  void capture() {
    try {
      android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);
    } catch (Throwable ignored) { /* Best effort on this platform. */ }
    Input input = null;
    String failure = "";
    try {
      if (!stop) {
        current = reserve(EMPTY);
        input = source.open();
        if (!stop) sink.onStateChanged("listening");
        short[] frame = new short[640];
        while (!stop) {
          int n = input.read(frame, 0, frame.length);
          if (n == -1) break;
          if (n <= 0 || n > frame.length) throw new IllegalStateException();
          if (input.isSilenced()) {
            failure = "mic_interrupted";
            break;
          }
          if (!interruption.isEmpty()) break;
          double sum = 0;
          for (int i = 0; i < n; i++) {
            double x = frame[i] / 32768.;
            sum += x * x;
          }
          double energy = sum / n;
          envelope.accept(energy);
          sink.onMeter(envelope.targets());
          long from = total;
          total += n;
          try {
            accept(frame, n, from, energy);
            drainDrumMarks();
          } catch (Exception ex) {
            failure = "draft_limit".equals(ex.getMessage()) ? "draft_limit" : "storage_error";
            break;
          }
        }
      }
    } catch (Exception e) {
      failure = "draft_limit".equals(e.getMessage()) ? "draft_limit" : "mic_unavailable";
    } finally {
      if (input != null) input.close();
    }
    if (!failure.isEmpty()) fail(failure);
    if (!discard) sink.onStateChanged("transcribing");
    try {
      if (current != null) current.flush();
    } catch (Exception e) {
      fail("storage_error");
    }
    // The last few milliseconds are also right context for the previous forced cut.
    if (waiting != null) {
      waiting.right =
          current == null ? EMPTY : Arrays.copyOf(current.pcm, Math.min(current.n, CTX));
      submit(waiting);
      waiting = null;
    }
    if (current != null) {
      if (current.n > 0) submit(current);
      current = null;
    }
    decoder.execute(
        () -> {
          try {
            if (interruption.isEmpty()) deliver(true, true);
          } catch (Exception e) {
            fail("model_or_storage_error");
          } finally {
            while (!staged.isEmpty()) {
              Piece s = staged.remove();
              queuedSamples.addAndGet(-s.samples);
            }
            heldPieces = 0;
            join.tail.clear();
            Arrays.fill(pre, (short) 0);
            nextLeft = EMPTY;
            running = false;
            sink.onStateChanged(
                discard ? "cancelled" : interruption.isEmpty() ? "finished" : "error");
          }
        });
  }

  private void drainDrumMarks() throws Exception {
    String mark;
    while ((mark = drumMarks.poll()) != null) {
      if (current != null && current.n >= 1600) {
        Part p = current;
        p.sealed = true;
        p.natural = true;
        p.drumMark = mark;
        p.flush();
        current = null;
        inPause = false;
        if (waiting != null) {
          waiting.right = Arrays.copyOf(p.pcm, Math.min(p.n, CTX));
          submit(waiting);
          waiting = null;
        }
        submit(p);
      } else {
        if (waiting != null) {
          submit(waiting);
          waiting = null;
        }
        final String m = mark;
        decoder.execute(() -> {
          try {
            if (!discard && interruption.isEmpty()) {
              deliver(true, true);
              synchronized (this) {
                if (!discard && interruption.isEmpty()) sink.onPunctuationReady(m);
              }
            }
          } catch (Exception e) {
            fail("model_or_storage_error");
          }
        });
      }
    }
  }

  private void accept(short[] frame, int n, long from, double energy) throws Exception {
    if (current == null) {
      current = reserve(nextLeft);
      nextLeft = EMPTY;
    }
    Part p = current;
    boolean audible = energy > 2.5e-6; // -56 dBFS. Pre-roll preserves the onset.
    if (p.n == 0 && p.left.length == 0 && !audible) {
      int keep = Math.min(preN, pre.length - n);
      System.arraycopy(pre, preN - keep, pre, 0, keep);
      System.arraycopy(frame, 0, pre, keep, n);
      preN = keep + n;
      return;
    }
    if (p.n == 0) {
      p.start = from - preN;
      if (preN > 0) p.add(pre, preN);
      preN = 0;
    }
    p.add(frame, n);
    p.voice |= audible;
    if (audible) {
      if (p.quiet >= PAUSE) contextResumes++;
      p.quiet = 0;
      p.stamp = p.n;
      p.guess = null;
    } else p.quiet += n;
    boolean pausing = p.voice && p.quiet >= PAUSE;
    if (inPause != pausing) {
      inPause = pausing;
    }
    if (waiting != null && p.n >= CTX) {
      waiting.right = Arrays.copyOf(p.pcm, CTX);
      submit(waiting);
      waiting = null;
    }
    // When work is already waiting, group short bursts rather than paying inference overhead per
    // breath.
    int minPhrase = pending.get() >= 2 ? 3 * RATE : 10400;
    if (pausing
        && p.n >= minPhrase
        && p.stamp != p.attempted
        && !probing
        && pending.get() == 0
        && interruption.isEmpty()) probe(p);
    boolean paused = p.quiet >= SETTLE && p.approved >= 0 && p.approved == p.stamp;
    // Retire the context-only tail once after a hard cut; do not decode ten-second
    // windows of silence forever while the user is thinking.
    boolean silentTail = p.left.length > 0 && !p.voice && p.quiet >= SETTLE;
    boolean force = p.n >= HARD;
    if (paused || force || silentTail) {
      p.sealed = true;
      p.natural = paused;
      p.flush();
      current = null;
      inPause = false;
      if (waiting != null) {
        waiting.right = Arrays.copyOf(p.pcm, Math.min(p.n, CTX));
        submit(waiting);
        waiting = null;
      }
      if (force && !paused) {
        forced++;
        p.hard = true;
        waiting = p;
        nextLeft = Arrays.copyOfRange(p.pcm, p.n - CTX, p.n);
      } else submit(p);
      if (queuedSamples.get() + (waiting == null ? 0 : waiting.n) >= QUEUE_BUDGET) drain("backlog");
    }
  }

  // A short hesitation starts a provisional decode, not an editor write. If speech resumes, the
  // snapshot becomes stale and the original samples remain in p for joint recognition.
  private void probe(Part p) {
    int stamp = p.stamp;
    p.attempted = stamp;
    probing = true;
    // Capture only appends, so this prefix stays immutable even if Part grows.
    short[] snapshot = p.pcm;
    int length = p.n;
    decoder.execute(
        () -> {
          try {
            if (stop || p.sealed || p.stamp != stamp) return;
            OfflineRecognizerResult r = decode.run(EMPTY, snapshot, length, EMPTY);
            if (r != null && p.stamp == stamp && !p.sealed && SentenceEnds.complete(r.getText())) {
              p.guess = r;
              p.approved = stamp;
            }
          } catch (RuntimeException | LinkageError ignored) {
            /* Final decode still owns the recording. */
          } finally {
            probing = false;
          }
        });
  }

  private void deliver(boolean flush, boolean terminal) throws Exception {
    if (staged.isEmpty() || !interruption.isEmpty()) return;
    StringBuilder out = new StringBuilder();
    for (Piece piece : staged) {
      if (out.length() > 0) out.append(' ');
      out.append(piece.text);
    }
    String all = out.toString();
    boolean forceLong = staged.size() >= 3;
    int cut = (flush || forceLong) ? all.length() : SentenceEnds.last(all, terminal);
    if (cut == 0) return;
    String send = all.substring(0, cut);
    long durationSamples = 0;
    while (cut > 0 && !staged.isEmpty()) {
      Piece s = staged.peek();
      if (cut < s.text.length()) {
        int portion = (int) ((long) s.remaining * cut / s.text.length());
        durationSamples += portion;
        s.remaining -= portion;
        s.text = s.text.substring(cut).trim();
        break;
      }
      cut -= s.text.length();
      if (cut > 0) cut--;
      staged.remove();
      durationSamples += s.remaining;
      queuedSamples.addAndGet(-s.samples);
    }
    heldPieces = staged.size();
    String processed = processor.process(send);
    synchronized (this) {
      if (!discard && interruption.isEmpty())
        sink.onPhraseReady(send, processed, durationSamples * 1000 / RATE);
    }
  }

  private void submit(Part p) {
    p.sealed = true;
    p.pcm = Arrays.copyOf(p.pcm, p.n);
    p.block = null; // Queued parts no longer need a max-sized recording/checkpoint buffer.
    peakSamples = Math.max(peakSamples, queuedSamples.addAndGet(p.n));
    int k = pending.incrementAndGet();
    peakPending = Math.max(peakPending, k);
    decoder.execute(
        () -> {
          boolean handed = false;
          try {
            if (!interruption.isEmpty()) {
              return;
            }
            OfflineRecognizerResult result =
                p.natural && p.left.length == 0 && p.guess != null ? p.guess : decode.run(p.left, p.pcm, p.n, p.right);
            String s =
                result == null
                    ? ""
                    : join.select(result, p.start, p.n, p.left.length, p.right.length);
            if (s == null) {
              fallback++;
              join.tail.clear();
              OfflineRecognizerResult retry = decode.run(EMPTY, p.pcm, p.n, EMPTY);
              s = retry == null ? "" : retry.getText().trim();
            }
            if (s.isEmpty()) {
              if (p.drumMark != null && !discard && interruption.isEmpty()) {
                deliver(true, true);
                synchronized (this) {
                  if (!discard && interruption.isEmpty()) sink.onPunctuationReady(p.drumMark);
                }
              }
              if (!p.voice || p.n < 2000 || result != null && !result.getText().trim().isEmpty()) {
                return;
              }
              emptyPhrases++;
              return;
            }
            boolean supportedEnd =
                p.natural
                    || (p.hard
                        && result != null
                        && !result.getText().trim().endsWith(s)
                        && p.right.length > 0);
            if (p.hard && !supportedEnd && p.right.length > 0)
              s = SentenceEnds.withoutGuessedPeriod(s);
            if (s.isEmpty()) {
              return;
            }
            if (!interruption.isEmpty()) {
              return;
            }
            staged.add(new Piece(p, s));
            heldPieces = staged.size();
            handed = true;
            if (p.drumMark != null) {
              deliver(true, true);
              synchronized (this) {
                if (!discard && interruption.isEmpty()) sink.onPunctuationReady(p.drumMark);
              }
            } else {
              deliver(false, supportedEnd);
            }
          } catch (Exception | LinkageError e) {
            fail("model_or_storage_error");
          } finally {
            if (!handed) queuedSamples.addAndGet(-p.n);
            pending.decrementAndGet();
          }
        });
  }
}
