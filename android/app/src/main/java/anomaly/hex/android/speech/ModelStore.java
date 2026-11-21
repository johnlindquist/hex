package anomaly.hex.android.speech;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class ModelStore {
  public enum State {
    READY_EXTERNAL,
    READY_BUNDLED,
    MISSING
  }

  static final String ID = "parakeet-tdt-0.6b-ultra-int8";
  static final String[] FILES = {
    "encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"
  };

  public static final class Info {
    public final State state;
    public final File dir;
    public final long totalBytes;
    public final String modelId = ID;

    Info(State state, File dir, long bytes) {
      this.state = state;
      this.dir = dir;
      totalBytes = bytes;
    }
  }

  public static File externalDir(Context ctx) {
    return new File(ctx.getNoBackupFilesDir(), "models/" + ID);
  }

  public static boolean isExpectedModelFile(String fileName) {
    return Arrays.asList(FILES).contains(fileName);
  }

  public static final class ImportResult {
    public final List<String> missingFiles;
    public final boolean ready;

    ImportResult(List<String> missing) {
      missingFiles = Collections.unmodifiableList(missing);
      ready = missing.isEmpty();
    }
  }

  /** Files still needed for a complete external import, independent of bundled assets. */
  public static List<String> missingFiles(Context ctx) {
    return missingFiles(externalDir(ctx));
  }

  static List<String> missingFiles(File dir) {
    List<String> missing = new ArrayList<>();
    for (String name : FILES) {
      if (!present(new File(dir, name))) missing.add(name);
    }
    return missing;
  }

  private static boolean present(File file) {
    return file.isFile() && file.canRead() && file.length() > 0;
  }

  /** Copy off the UI thread. The caller owns the stream. Never replace a working file on error. */
  public static ImportResult importModelFile(Context ctx, String fileName, InputStream in)
      throws IOException {
    return importModelFile(externalDir(ctx), fileName, in);
  }

  static ImportResult importModelFile(File dir, String fileName, InputStream in) throws IOException {
    if (!isExpectedModelFile(fileName)) throw new IOException("Unexpected model filename");
    Path folder = Files.createDirectories(dir.toPath());
    Path tmp = Files.createTempFile(folder, fileName, ".tmp");
    try {
      if (Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING) == 0) {
        throw new IOException("Model file is empty");
      }
      synchronized (ModelStore.class) {
        Files.move(tmp, folder.resolve(fileName), StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
        cached = null;
        return new ImportResult(missingFiles(dir));
      }
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  static Info external(File dir) {
    long bytes = 0;
    for (String name : FILES) {
      File f = new File(dir, name);
      if (!present(f)) return null;
      bytes += f.length();
    }
    return new Info(State.READY_EXTERNAL, dir, bytes);
  }

  public static synchronized void invalidateCache() { cached = null; }

  private static Info cached;
  private static File cachedDir;
  private static long cachedAt, cachedModified;

  public static synchronized Info inspect(Context ctx) {
    File dir = externalDir(ctx);
    long modified = dir.lastModified(), now = android.os.SystemClock.elapsedRealtime();
    if (cached != null && cached.state != State.MISSING && dir.equals(cachedDir)
        && modified == cachedModified && now - cachedAt < 5000) return cached;
    cached = inspectUncached(ctx, dir);
    cachedDir = dir;
    cachedAt = now;
    cachedModified = modified;
    return cached;
  }

  private static Info inspectUncached(Context ctx, File dir) {
    Info info = external(dir);
    if (info != null) return info;
    try {
      List<String> names = Arrays.asList(ctx.getAssets().list(""));
      long bytes = 0;
      for (String name : FILES) {
        if (!names.contains(name)) return new Info(State.MISSING, dir, 0);
        long size;
        try (AssetFileDescriptor fd = ctx.getAssets().openFd(name)) {
          size = fd.getLength();
        } catch (IOException compressed) {
          try (InputStream in = ctx.getAssets().open(name)) {
            size = in.available();
          }
        }
        if (size <= 0) return new Info(State.MISSING, dir, 0);
        bytes += size;
      }
      return new Info(State.READY_BUNDLED, null, bytes);
    } catch (IOException e) {
      return new Info(State.MISSING, dir, 0);
    }
  }
}
