package anomaly.hex.android.keyboard;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

public class KeyboardRewriteTest {
  @Test
  public void rewriteEndpointDerivesFromAskEndpoint() {
    assertEquals(
        "https://hex-api-five.vercel.app/api/rewrite",
        KeyboardRewriteClient.rewriteEndpoint("https://hex-api-five.vercel.app/api/ask"));
    assertEquals(
        "https://custom.example.com/api/rewrite",
        KeyboardRewriteClient.rewriteEndpoint("https://custom.example.com/api/ask"));
    assertEquals(
        KeyboardRewriteClient.DEFAULT_REWRITE_ENDPOINT,
        KeyboardRewriteClient.rewriteEndpoint(""));
  }

  @Test
  public void parseResponseExtractsValidOptionsAndSkipsBlanks() {
    String json = "{\"options\":["
        + "{\"label\":\"Fix typos\",\"text\":\"I would love a rewrite feature on the keyboard.\"},"
        + "{\"label\":\"Concise\",\"text\":\"Add a keyboard rewrite feature.\"},"
        + "{\"label\":\"\",\"text\":\"ignored\"},"
        + "{\"label\":\"Empty\",\"text\":\"   \"}"
        + "],\"model\":\"gemini-4-hydrogen\"}";
    List<KeyboardRewriteClient.Option> opts = KeyboardRewriteClient.parseResponse(json);
    assertEquals(2, opts.size());
    assertEquals("Fix typos", opts.get(0).label);
    assertEquals("I would love a rewrite feature on the keyboard.", opts.get(0).text);
    assertEquals("Concise", opts.get(1).label);
  }

  @Test
  public void parseResponseHandlesInvalidJson() {
    assertTrue(KeyboardRewriteClient.parseResponse(null).isEmpty());
    assertTrue(KeyboardRewriteClient.parseResponse("not json").isEmpty());
    assertTrue(KeyboardRewriteClient.parseResponse("{}").isEmpty());
  }
}
