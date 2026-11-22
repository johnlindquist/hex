package anomaly.hex.android.anywhere;

import static org.junit.Assert.*;

import anomaly.hex.android.core.SecretStore;
import anomaly.hex.android.keyboard.KeyboardRewriteClient;
import java.io.IOException;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

public class GeminiDirectClientTest {

  @Test
  public void normalizesModelAndBuildsUrls() {
    assertEquals("gemini-4-hydrogen", GeminiDirectClient.normalizeModel(null));
    assertEquals("gemini-4-hydrogen", GeminiDirectClient.normalizeModel("  "));
    assertEquals("gemini-4-hydrogen", GeminiDirectClient.normalizeModel("models/gemini-4-hydrogen"));
    assertEquals("Gemini 4 Hydrogen", GeminiDirectClient.modelDisplayLabel("gemini-4-hydrogen"));
    assertEquals(
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-4-hydrogen:generateContent",
        GeminiDirectClient.generateContentUrl("gemini-4-hydrogen"));
    assertEquals(
        "https://generativelanguage.googleapis.com/v1beta/models/gemini-4-hydrogen",
        GeminiDirectClient.modelInfoUrl("models/gemini-4-hydrogen"));
  }

  @Test
  public void rejectsNonHttpsEndpoints() {
    assertThrows(IOException.class, () -> GeminiDirectClient.validateHttpsUrl("http://example.com/api"));
    assertThrows(IOException.class, () -> GeminiDirectClient.validateHttpsUrl(""));
    assertThrows(IOException.class, () -> GeminiDirectClient.validateHttpsUrl(null));
  }

  @Test
  public void buildsAskAndRewriteBodiesCorrectly() throws Exception {
    JSONObject ask = GeminiDirectClient.buildAskBody(
        "Order #42", "What order?", "abc123", null,
        List.of(new TypeAnywhereAiClient.Turn("user", "hi"), new TypeAnywhereAiClient.Turn("model", "hello")));
    assertEquals(3, ask.getJSONArray("contents").length());
    assertTrue(ask.has("tools"));

    JSONObject rwDefault = GeminiDirectClient.buildRewriteBody("pls fix this", null, "imgdata");
    assertFalse(rwDefault.has("tools"));
    assertEquals("application/json", rwDefault.getJSONObject("generationConfig").getString("responseMimeType"));

    JSONObject rwPrompt = GeminiDirectClient.buildRewriteBody("hey", "cite today's weather", null);
    assertTrue(rwPrompt.has("tools"));
    assertFalse(rwPrompt.getJSONObject("generationConfig").has("responseMimeType"));
  }

  @Test
  public void parsesGeminiResponseSkippingThoughtsAndExtractingSources() {
    String json = "{"
        + "\"candidates\":[{"
        + "  \"finishReason\":\"STOP\","
        + "  \"content\":{\"parts\":["
        + "    {\"thought\":true,\"text\":\"internal reasoning\"},"
        + "    {\"text\":\"The answer is 42.\"}"
        + "  ]},"
        + "  \"groundingMetadata\":{"
        + "    \"webSearchQueries\":[\"answer to everything\"],"
        + "    \"groundingChunks\":["
        + "      {\"web\":{\"uri\":\"https://example.com/a\",\"title\":\"Example A\"}},"
        + "      {\"web\":{\"uri\":\"https://example.com/a\",\"title\":\"Duplicate\"}},"
        + "      {\"web\":{\"uri\":\"https://example.com/b\",\"title\":\"Example B\"}}"
        + "    ]"
        + "  }"
        + "}]}";
    GeminiDirectClient.ParsedGemini parsed = GeminiDirectClient.parseGeminiPayload(json);
    assertEquals("The answer is 42.", parsed.text);
    assertTrue(parsed.searched);
    assertEquals(2, parsed.sources.size());
    assertEquals("Example A", parsed.sources.get(0).title);
    assertEquals("https://example.com/b", parsed.sources.get(1).uri);
  }

  @Test
  public void parsesRewriteOptionsFromFencedJsonAndDeduplicates() {
    String fenced = "```json\n{\"options\":["
        + "{\"label\":\"Fix typos\",\"text\":\"Sounds good!\"},"
        + "{\"label\":\"Duplicate\",\"text\":\"Sounds good!\"},"
        + "{\"label\":\"Concise\",\"text\":\"Sounds good.\"}"
        + "]}\n```";
    List<KeyboardRewriteClient.Option> opts = GeminiDirectClient.parseRewriteOptions(fenced);
    assertEquals(2, opts.size());
    assertEquals("Fix typos", opts.get(0).label);
    assertEquals("Concise", opts.get(1).label);
  }

  @Test
  public void masksSecretsSafely() {
    assertEquals("", SecretStore.mask(""));
    assertEquals("••••", SecretStore.mask("short"));
    assertEquals("AIza••••3x9Q", SecretStore.mask("AIzaSyD1234567890abcdef3x9Q"));
  }
}
