package anomaly.hex.android.anywhere;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.AtomicFile;
import java.io.*;

/** The sheet owns one capture. A private, timestamp-matched cache survives process recreation. */
public final class TypeAnywhereStore {
  private static TypeAnywhereStore latest;
  public final Bitmap image;
  public final long capturedAt;
  public String text;
  private TypeAnywhereStore(Bitmap image, long time) {
    this.image = image;
    capturedAt = time;
  }
  private static File file(Context c) {
    return new File(c.getCacheDir(), "type_anywhere_last.jpg");
  }
  public static synchronized TypeAnywhereStore put(Context c, Bitmap image) {
    clear(c);
    latest = new TypeAnywhereStore(image, System.currentTimeMillis());
    return latest;
  }
  public static void persist(Context c, TypeAnywhereStore item) {
    if (item.image == null)
      return;
    ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
    try {
      if (!item.image.compress(Bitmap.CompressFormat.JPEG, 85, jpeg))
        return;
    } catch (RuntimeException | OutOfMemoryError e) {
      return;
    }
    ScreenshotHistory.saveJpeg(c, item.capturedAt, jpeg.toByteArray());
    save(c, item, jpeg);
  }
  private static synchronized void save(
      Context c, TypeAnywhereStore item, ByteArrayOutputStream jpeg) {
    if (item != latest)
      return;
    AtomicFile file = new AtomicFile(file(c));
    FileOutputStream out = null;
    try {
      out = file.startWrite();
      jpeg.writeTo(out);
      file.finishWrite(out);
      if (!file.getBaseFile().setLastModified(item.capturedAt))
        file.delete();
    } catch (IOException e) {
      file.failWrite(out);
    }
  }
  static synchronized TypeAnywhereStore get(Context c, long time) {
    if (latest != null && latest.capturedAt == time)
      return latest;
    File f = file(c);
    if (time <= 0 || !f.isFile() || f.lastModified() != time)
      return null;
    return latest =
               new TypeAnywhereStore(android.graphics.BitmapFactory.decodeFile(f.getPath()), time);
  }
  public static synchronized void clear(Context c) {
    latest = null;
    new AtomicFile(file(c)).delete();
  }
  static synchronized void release(Context c, TypeAnywhereStore item) {
    if (latest == item)
      clear(c);
  }
}
