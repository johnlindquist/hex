package anomaly.hex.android.speech;

import static org.junit.Assert.*;

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
}
