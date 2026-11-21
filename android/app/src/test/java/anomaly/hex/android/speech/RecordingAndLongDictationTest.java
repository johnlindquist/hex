package anomaly.hex.android.speech;

import static org.junit.Assert.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;

public class RecordingAndLongDictationTest {
  @Test
  public void wavHeaderEncodesValid16kMonoPcm() {
    long tenMinBytes = 10L * 60L * 16000L * 2L; // 19,200,000 bytes
    byte[] hdr = RecordingStore.wavHeader(tenMinBytes);
    assertEquals(44, hdr.length);
    assertEquals("RIFF", new String(hdr, 0, 4));
    assertEquals("WAVE", new String(hdr, 8, 4));
    assertEquals("fmt ", new String(hdr, 12, 4));
    assertEquals("data", new String(hdr, 36, 4));
    ByteBuffer b = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN);
    assertEquals((int) (tenMinBytes + 36), b.getInt(4));
    assertEquals(16000, b.getInt(24));
    assertEquals(16, b.getShort(34));
    assertEquals((int) tenMinBytes, b.getInt(40));
  }

  @Test
  public void speechSessionQueueBudgetSupportsLongTakes() {
    assertTrue("Queue budget should allow at least 2 minutes of backlog",
        SpeechSession.QUEUE_BUDGET >= 120 * SpeechSession.RATE);
  }

  @Test
  public void repairPartialWavFixesZeroLengthHeaderAfterCrash() throws Exception {
    File dir = java.nio.file.Files.createTempDirectory("hex_rec_test").toFile();
    File partial = new File(dir, "rec_12345.wav.partial");
    try {
      // Simulate crash: 44-byte header with 0 length + 2 seconds (64,000 bytes) of PCM
      try (FileOutputStream out = new FileOutputStream(partial)) {
        out.write(RecordingStore.wavHeader(0));
        out.write(new byte[64000]);
      }
      RecordingStore.Recovered rec = RecordingStore.repairPartialWav(partial);
      assertNotNull(rec);
      assertEquals(2000L, rec.durationMs);
      assertTrue(rec.wavFile.getName().endsWith(".wav"));
      assertFalse(partial.exists());
      byte[] fixedHdr = new byte[44];
      try (FileInputStream in = new FileInputStream(rec.wavFile)) {
        assertEquals(44, in.read(fixedHdr));
      }
      ByteBuffer b = ByteBuffer.wrap(fixedHdr).order(ByteOrder.LITTLE_ENDIAN);
      assertEquals(64036, b.getInt(4));
      assertEquals(64000, b.getInt(40));
      rec.wavFile.delete();
    } finally {
      dir.delete();
    }
  }

  @Test
  public void repairPartialWavDeletesEmptyStub() throws Exception {
    File dir = java.nio.file.Files.createTempDirectory("hex_rec_empty").toFile();
    File partial = new File(dir, "rec_empty.wav.partial");
    try {
      try (FileOutputStream out = new FileOutputStream(partial)) {
        out.write(RecordingStore.wavHeader(0));
        out.write(new byte[100]);
      }
      assertNull(RecordingStore.repairPartialWav(partial));
      assertFalse(partial.exists());
    } finally {
      dir.delete();
    }
  }
}
