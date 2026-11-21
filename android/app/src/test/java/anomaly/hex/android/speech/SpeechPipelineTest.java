package anomaly.hex.android.speech;

import static org.junit.Assert.*;

import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SpeechPipelineTest {
  @Test
  public void recognizerThreadCountReservesACoreWithinBounds() {
    assertEquals(2, Engine.optimalThreads(1));
    assertEquals(2, Engine.optimalThreads(2));
    assertEquals(3, Engine.optimalThreads(4));
    assertEquals(4, Engine.optimalThreads(8));
  }

  @Test
  public void formattingLocaleUsesSupportedDeviceLanguageOrEnglish() {
    assertEquals(Locale.CANADA_FRENCH, SpeechLanguage.resolveLocale("auto", Locale.CANADA_FRENCH));
    assertEquals(Locale.ENGLISH, SpeechLanguage.resolveLocale("auto", Locale.JAPAN));
    assertEquals(Locale.GERMAN, SpeechLanguage.resolveLocale("de", Locale.CANADA_FRENCH));
  }

  @Test
  public void recentsAreExplicitDistinctNewestFirstAndCappedAtFive() {
    List<String> recent = Arrays.asList("en", "de", "es", "pl", "fr");
    recent = SpeechLanguage.orderedRecent("sv", recent);
    assertEquals(Arrays.asList("sv", "en", "de", "es", "pl"), recent);
    recent = SpeechLanguage.orderedRecent("de", recent);
    assertEquals(Arrays.asList("de", "sv", "en", "es", "pl"), recent);
    assertEquals(recent, SpeechLanguage.orderedRecent("auto", recent));
  }

  @Test
  public void importsOnlyAcceptExactKnownBasenames() {
    for (String name : ModelStore.FILES) assertTrue(ModelStore.isExpectedModelFile(name));
    for (String name : Arrays.asList(null, "", "encoder.onnx", "../tokens.txt", "/tokens.txt",
        "sub/tokens.txt", "..\\tokens.txt", "TOKENS.txt")) assertFalse(ModelStore.isExpectedModelFile(name));
  }

  @Test
  public void importsReportProgressAndPreserveExistingFileOnFailure() throws Exception {
    File dir = tmp.newFolder();
    int count = ModelStore.FILES.length;
    for (String name : ModelStore.FILES) {
      ModelStore.ImportResult result = ModelStore.importModelFile(dir, name,
          new ByteArrayInputStream(new byte[] {42}));
      assertEquals(--count, result.missingFiles.size());
      assertFalse(result.missingFiles.contains(name));
      assertEquals(count == 0, result.ready);
    }
    try {
      ModelStore.importModelFile(dir, "tokens.txt", new ByteArrayInputStream(new byte[0]));
      fail("Empty replacement must fail");
    } catch (IOException expected) {
      assertArrayEquals(new byte[] {42}, Files.readAllBytes(new File(dir, "tokens.txt").toPath()));
      assertEquals(4, dir.list().length);
    }
  }

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  @Test
  public void partialExternalModelIsNeverReady() throws Exception {
    File dir = tmp.newFolder();
    for (String name : ModelStore.FILES) {
      assertNull(ModelStore.external(dir));
      Files.write(new File(dir, name).toPath(), new byte[] {1});
    }
    assertEquals(ModelStore.State.READY_EXTERNAL, ModelStore.external(dir).state);
    assertEquals(4, ModelStore.external(dir).totalBytes);
    Files.write(new File(dir, "tokens.txt").toPath(), new byte[0]);
    assertNull(ModelStore.external(dir));
  }

  @Test
  public void meterRollsOffAndResets() {
    MicEnvelope e = new MicEnvelope();
    short[] block = {Short.MAX_VALUE};
    e.push(block, 1);
    assertEquals(100, e.peak());
    assertEquals(100, e.targets()[9]);
    for (int n = 0; n < 10; n++) e.push(new short[1], 1);
    assertEquals(0, e.peak());
    e.push(block, 1);
    e.reset();
    assertEquals(0, e.peak());
  }

  @Test
  public void endpointsNeedEvidence() {
    assertFalse(SentenceEnds.complete("Meet Dr."));
    assertFalse(SentenceEnds.complete("and."));
    assertFalse(SentenceEnds.complete("Maybe..."));
    assertEquals(5, SentenceEnds.last("Done. Next", false));
    assertTrue(SentenceEnds.complete("Ready?”"));
    assertEquals("Ready", SentenceEnds.withoutGuessedPeriod("Ready."));
  }

  @Test
  public void streamFlushesPartialSentenceAndCheckpointsOriginalPcm() throws Exception {
    java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
    java.io.ByteArrayOutputStream audio = new java.io.ByteArrayOutputStream();
    java.util.List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
    SpeechSession session =
        new SpeechSession(
            pcm -> words("unfinished thought", 0, .1f),
            () ->
                new SpeechSession.Input() {
                  int blocks = 5;

                  public int read(short[] b, int offset, int len) {
                    if (blocks-- == 0) return -1;
                    java.util.Arrays.fill(b, (short) 0x1234);
                    return len;
                  }

                  public boolean isSilenced() {
                    return false;
                  }

                  public void close() {
                    events.add("close");
                  }
                },
            raw -> raw + "!",
            new SpeechSession.Sink() {
              public void onStateChanged(String s) {
                events.add(s);
                if (s.equals("finished") || s.equals("error")) done.countDown();
              }

              public void onMeter(int[] bars) {}

              public void onError(String code, String detail) {
                events.add(code);
              }

              public void onCheckpoint(byte[] bytes) {
                audio.write(bytes, 0, bytes.length);
              }

              public void onPhraseReady(String raw, String processed, long duration) {
                events.add(raw + "/" + processed + "/" + duration);
              }
            });
    session.start();
    assertTrue(done.await(3, java.util.concurrent.TimeUnit.SECONDS));
    assertEquals(
        java.util.Arrays.asList(
            "listening",
            "close",
            "transcribing",
            "unfinished thought/unfinished thought!/200",
            "finished"),
        events);
    assertEquals(6400, audio.size());
    assertEquals(0x34, audio.toByteArray()[0]);
    assertEquals(0x12, audio.toByteArray()[1]);
    assertFalse(session.isRunning());
  }

  static OfflineRecognizerResult words(String text, float... times) {
    return new OfflineRecognizerResult(
        text, text.split(" "), times, "", "", "", new float[0], new int[0]);
  }

  @Test
  public void overlapOnlyDropsSameSound() {
    PhraseJoin join = new PhraseJoin();
    assertEquals("go", join.select(words("go go", .9f, 1.2f), 0, 16000, 0, 16000));
    assertEquals("go", join.select(words("go go", .95f, 1.4f), 16000, 16000, 16000, 0));
    assertEquals("go go", join.select(words("go go", 0, .2f), 32000, 16000, 0, 0));
    assertNull(join.select(words("broken mapping", .1f), 0, 16000, 0, 16000));
  }
}
