package anomaly.hex.android.speech;

import static org.junit.Assert.*;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class ModelDownloaderTest {
  @Rule public TemporaryFolder folder = new TemporaryFolder();
  // Published SHA-256 test vector for "abc".
  private static final String PIN = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
  private ModelDownloader downloader(ModelDownloader.Source source) {
    return new ModelDownloader(source, new String[]{PIN, PIN, PIN, PIN});
  }
  private static InputStream good() { return new ByteArrayInputStream(new byte[]{97,98,99}); }
  private ModelDownloader.Progress run(ModelDownloader d) throws Exception {
    BlockingQueue<ModelDownloader.Progress> terminal = new LinkedBlockingQueue<>();
    d.subscribe(p -> { if (!p.active) terminal.add(p); });
    d.start(folder.getRoot(), () -> {});
    ModelDownloader.Progress p = terminal.poll(5, TimeUnit.SECONDS);
    assertNotNull(p); return p;
  }
  @Test public void verifiesAndPromotesAllFour() throws Exception {
    ModelDownloader d = downloader(name -> {
      assertFalse(new File(folder.getRoot(), name).exists()); return good();
    });
    assertTrue(run(d).complete);
    assertEquals(4, folder.getRoot().list().length);
    for (String name : ModelStore.FILES)
      assertArrayEquals(new byte[]{97,98,99}, Files.readAllBytes(new File(folder.getRoot(), name).toPath()));
  }
  @Test public void rejectsMismatchAndRemovesTemp() throws Exception {
    assertNotNull(run(downloader(name -> new ByteArrayInputStream(new byte[]{0}))).error);
    assertEquals(0, folder.getRoot().list().length);
  }
  @Test public void retriesOnlyMissingFiles() throws Exception {
    assertNotNull(run(downloader(name -> {
      if (name.equals(ModelStore.FILES[1])) throw new IOException("connection lost");
      return good();
    })).error);
    List<String> requested = new ArrayList<>();
    assertTrue(run(downloader(name -> { requested.add(name); return good(); })).complete);
    assertEquals(Arrays.asList(ModelStore.FILES).subList(1,4), requested);
  }
  @Test public void cancelClosesBlockedReadAndRemovesPartial() throws Exception {
    CountDownLatch reading = new CountDownLatch(1), closed = new CountDownLatch(1);
    ModelDownloader d = downloader(name -> new InputStream() {
      public int read() throws IOException {
        reading.countDown();
        try { if (!closed.await(4, TimeUnit.SECONDS)) throw new IOException("not closed"); }
        catch (InterruptedException e) { throw new IOException(e); }
        return -1;
      }
      public void close() { closed.countDown(); }
    });
    BlockingQueue<ModelDownloader.Progress> terminal = new LinkedBlockingQueue<>();
    d.subscribe(p -> { if (!p.active) terminal.add(p); });
    d.start(folder.getRoot(), () -> {});
    assertTrue(reading.await(2, TimeUnit.SECONDS)); d.cancel();
    ModelDownloader.Progress p = terminal.poll(2, TimeUnit.SECONDS);
    assertNotNull(p); assertFalse(p.complete); assertNull(p.error);
    assertEquals(0, folder.getRoot().list().length);
  }
}
