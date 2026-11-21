package anomaly.hex.android.anywhere;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.*;
import java.util.*;

/** Bounded owner-only screenshot history for Type Anywhere (newest first, up to 24 captures). */
public final class ScreenshotHistory {
  public static final int MAX_ITEMS = 24;

  public static final class Item {
    public final long capturedAt;
    public final File file;
    Item(long capturedAt, File file) {
      this.capturedAt = capturedAt;
      this.file = file;
    }
  }

  private ScreenshotHistory() {}

  public static File dir(Context c) {
    File d = new File(c.getApplicationContext().getFilesDir(), "anywhere_history");
    if (!d.exists()) d.mkdirs();
    return d;
  }

  public static synchronized void saveJpeg(Context c, long capturedAt, byte[] jpegBytes) {
    if (jpegBytes == null || jpegBytes.length == 0 || capturedAt <= 0) return;
    File f = new File(dir(c), "shot_" + capturedAt + ".jpg");
    try (FileOutputStream out = new FileOutputStream(f)) {
      out.write(jpegBytes);
      out.getFD().sync();
      f.setLastModified(capturedAt);
    } catch (IOException ignored) {
      f.delete();
      return;
    }
    prune(c);
  }

  public static synchronized List<Item> list(Context c) {
    File[] files = dir(c).listFiles((d, name) -> name.startsWith("shot_") && name.endsWith(".jpg"));
    if (files == null || files.length == 0) return Collections.emptyList();
    List<Item> out = new ArrayList<>(files.length);
    for (File f : files) {
      long ts = parseTimestamp(f.getName(), f.lastModified());
      out.add(new Item(ts, f));
    }
    out.sort((a, b) -> Long.compare(b.capturedAt, a.capturedAt));
    return out;
  }

  public static Bitmap decodeThumb(File file, int maxPx) {
    if (file == null || !file.isFile()) return null;
    BitmapFactory.Options o = new BitmapFactory.Options();
    o.inJustDecodeBounds = true;
    BitmapFactory.decodeFile(file.getPath(), o);
    int sample = 1;
    int maxDim = Math.max(o.outWidth, o.outHeight);
    while (maxDim / sample > maxPx * 2) sample *= 2;
    o.inJustDecodeBounds = false;
    o.inSampleSize = sample;
    return BitmapFactory.decodeFile(file.getPath(), o);
  }

  public static Bitmap decodeFull(File file) {
    if (file == null || !file.isFile()) return null;
    return BitmapFactory.decodeFile(file.getPath());
  }

  public static synchronized boolean delete(File file) {
    return file != null && file.delete();
  }

  public static synchronized int clearAll(Context c) {
    File[] files = dir(c).listFiles((d, name) -> name.startsWith("shot_") && name.endsWith(".jpg"));
    if (files == null) return 0;
    int n = 0;
    for (File f : files) if (f.delete()) n++;
    return n;
  }

  private static void prune(Context c) {
    List<Item> all = list(c);
    for (int i = MAX_ITEMS; i < all.size(); i++) {
      all.get(i).file.delete();
    }
  }

  static long parseTimestamp(String name, long fallback) {
    try {
      String core = name.substring("shot_".length(), name.length() - ".jpg".length());
      return Long.parseLong(core);
    } catch (RuntimeException e) {
      return fallback;
    }
  }
}
