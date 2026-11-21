package anomaly.hex.android.speech;

import android.content.Context;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** One process-owned transfer. Listeners receive ordered snapshots and must not block. */
public final class ModelDownloader {
  private static final String BASE = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-ultra-int8/resolve/402e1190ce85b9793118ee223db21fb0d12f52db/";
  private static final String[] PINS = {
    "b43072e1b721f18a3df03fcfeb763ff7c4debdf29843598a3aba2f703e2780a3",
    "1fab98fe6c12aded87d2da66272cc9e148d0a0044ce3850a12fe56302ec4a922",
    "8a71aaccfdba3d451775507a889afd2257d2e754463fd81095a8c8f290f924c2",
    "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"};
  private static final long[] SIZES = {652_046_393L, 11_000_000, 6_100_000, 92_000};
  public static final ModelDownloader INSTANCE = new ModelDownloader(null, PINS);
  interface Source { InputStream open(String name) throws IOException; }
  private final Source source;
  private final String[] pins;
  ModelDownloader(Source source, String[] pins) { this.source = source; this.pins = pins.clone(); }

  public static final class Progress {
    public final boolean active, complete;
    public final int fileIndex, totalFiles = 4;
    public final long downloadedBytes, totalBytes;
    public final float fraction;
    public final String detail, error;
    Progress(boolean active, boolean complete, int index, long bytes, long total, String error) {
      this.active = active; this.complete = complete; fileIndex = index;
      downloadedBytes = bytes; totalBytes = total; this.error = error;
      fraction = complete ? 1 : Math.min(1f, bytes / (float)Math.max(1, total));
      detail = "Downloading file " + index + " of 4 · " + (int)(fraction * 100) + "%";
    }
  }
  private Progress progress = new Progress(false, false, 1, 0, 0, null);
  private final Set<Consumer<Progress>> listeners = new LinkedHashSet<>();
  private final AtomicBoolean cancelled = new AtomicBoolean();
  private Runnable abort;
  public synchronized Progress subscribe(Consumer<Progress> listener) {
    listeners.add(listener); return progress;
  }
  public synchronized void unsubscribe(Consumer<Progress> listener) { listeners.remove(listener); }
  private synchronized void publish(Progress next) {
    progress = next;
    for (Consumer<Progress> listener : listeners) listener.accept(next);
  }
  public synchronized void start(Context ctx) {
    if (progress.active || ModelStore.inspect(ctx).state != ModelStore.State.MISSING) return;
    Context app = ctx.getApplicationContext();
    start(ModelStore.externalDir(app), () -> Engine.get(app).prewarmAsync());
  }
  synchronized void start(File dir, Runnable completed) {
    if (progress.active || ModelStore.external(dir) != null) return;
    cancelled.set(false);
    publish(new Progress(true, false, 1, 0, Arrays.stream(SIZES).sum(), null));
    new Thread(() -> run(dir, completed), "hex-model-download").start();
  }
  public synchronized void cancel() {
    if (!progress.active || cancelled.getAndSet(true)) return;
    if (abort != null) new Thread(abort, "hex-model-cancel").start();
  }
  private void check() throws IOException { if (cancelled.get()) throw new IOException("Cancelled"); }
  private synchronized void setAbort(Runnable action) throws IOException { check(); abort = action; }
  private InputStream open(int index, long[] sizes) throws IOException {
    String name = ModelStore.FILES[index];
    if (source != null) return source.open(name);
    URL url = new URL(BASE + name);
    for (int hop = 0; hop < 10; hop++) {
      HttpURLConnection c = (HttpURLConnection)url.openConnection();
      c.setInstanceFollowRedirects(false); c.setConnectTimeout(20_000); c.setReadTimeout(30_000);
      c.setRequestProperty("User-Agent", "Hex-Android/0.1");
      boolean streaming = false;
      try {
        setAbort(c::disconnect);
        int status = c.getResponseCode();
        if (status >= 300 && status < 400) {
          url = new URL(url, c.getHeaderField("Location"));
          if (!"https".equals(url.getProtocol())) throw new IOException("Insecure redirect");
          continue;
        }
        if (status != 200) throw new IOException("HTTP " + status);
        long length = c.getContentLengthLong();
        if (length > 0) sizes[index] = length;
        InputStream in = c.getInputStream(); streaming = true;
        return new FilterInputStream(in) {
          public void close() throws IOException { try { super.close(); } finally { c.disconnect(); } }
        };
      } finally { if (!streaming) c.disconnect(); }
    }
    throw new IOException("Too many redirects");
  }
  private void run(File dir, Runnable completed) {
    long[] sizes = SIZES.clone(); long bytes = 0; int index = 0;
    String error = null; boolean done = false;
    try {
      Files.createDirectories(dir.toPath());
      for (; index < sizes.length; index++) {
        check(); File file = new File(dir, ModelStore.FILES[index]);
        if (file.isFile() && file.canRead() && file.length() > 0) {
          sizes[index] = file.length(); bytes += sizes[index]; continue;
        }
        Path tmp = dir.toPath().resolve(file.getName() + ".tmp");
        try {
          MessageDigest digest = MessageDigest.getInstance("SHA-256"); long count = 0;
          try (InputStream in = open(index, sizes); OutputStream out = Files.newOutputStream(tmp)) {
            if (source != null) setAbort(() -> { try { in.close(); } catch (IOException ignored) {} });
            long total = Arrays.stream(sizes).sum(), last = System.nanoTime();
            publish(new Progress(true, false, index + 1, bytes, total, null));
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) != -1) {
              check(); out.write(buf, 0, n); digest.update(buf, 0, n); count += n;
              if (System.nanoTime() - last >= 100_000_000L) {
                publish(new Progress(true, false, index + 1, bytes + count, total, null));
                last = System.nanoTime();
              }
            }
          }
          StringBuilder hash = new StringBuilder();
          for (byte b : digest.digest()) hash.append(String.format(Locale.ROOT, "%02x", b));
          if (!pins[index].contentEquals(hash)) throw new IOException("Model checksum mismatch");
          synchronized (this) {
            check(); Files.move(tmp, file.toPath(), StandardCopyOption.ATOMIC_MOVE);
            ModelStore.invalidateCache(); sizes[index] = count; bytes += count;
          }
        } finally { Files.deleteIfExists(tmp); }
      }
      done = true;
    } catch (Exception failed) { error = "Download failed. Check your connection and try again."; }
    synchronized (this) {
      abort = null;
      publish(new Progress(false, done, Math.min(4, index + 1), bytes, Arrays.stream(sizes).sum(),
          cancelled.get() ? null : error));
    }
    if (done) completed.run();
  }
}
