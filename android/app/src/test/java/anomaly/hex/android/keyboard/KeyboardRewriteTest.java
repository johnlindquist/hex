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
    assertFalse(KeyboardRewriteClient.parseSearched(null));
    assertFalse(KeyboardRewriteClient.parseSearched("{}"));
    assertTrue(KeyboardRewriteClient.parseSearched("{\"searched\":true,\"options\":[]}"));
  }

  @Test
  public void rewriteSessionMatchesOriginalAndAppliedAndPreservesOriginalAcrossSwitches() {
    List<KeyboardRewriteClient.Option> opts = List.of(
        new KeyboardRewriteClient.Option("Fix typos", "Let's meet at 3."),
        new KeyboardRewriteClient.Option("Concise", "Meet at 3?"));
    KeyboardRewriteClient.Session session =
        new KeyboardRewriteClient.Session("lets meet at 3", null, opts, true, false, null);

    assertTrue(session.matchesEditorText("lets meet at 3"));
    assertTrue(session.matchesEditorText("Let's meet at 3."));
    assertFalse(session.matchesEditorText("something totally different"));

    KeyboardRewriteClient.Session picked1 = session.withApplied("Let's meet at 3.");
    assertEquals("lets meet at 3", picked1.originalDraft);
    assertEquals("Let's meet at 3.", picked1.appliedText);
    assertTrue(picked1.matchesEditorText("Let's meet at 3."));

    KeyboardRewriteClient.Session picked2 = picked1.withApplied("Meet at 3?");
    assertEquals("lets meet at 3", picked2.originalDraft);
    assertEquals("Meet at 3?", picked2.appliedText);
    assertTrue(picked2.matchesEditorText("Meet at 3?"));
  }

  @Test
  public void typoFixExtractorLearnsTyposAndSurfacesImmediatelyInSuggestions() {
    String original = "We nerd a clean way in case yku oick the wrong one foe typing";
    String fixed = "We need a clean way in case you pick the wrong one for typing";
    List<TypoFixExtractor.FixPair> pairs = TypoFixExtractor.extract(
        original,
        fixed,
        List.of(
            new TypoFixExtractor.FixPair("yku", "you"),
            new TypoFixExtractor.FixPair("oick", "pick"),
            new TypoFixExtractor.FixPair("hallucinated", "word")));

    assertEquals(4, pairs.size());
    assertTrue(pairs.stream().anyMatch(p -> p.from().equals("yku") && p.to().equals("you")));
    assertTrue(pairs.stream().anyMatch(p -> p.from().equals("oick") && p.to().equals("pick")));
    assertTrue(pairs.stream().anyMatch(p -> p.from().equals("nerd") && p.to().equals("need")));
    assertTrue(pairs.stream().anyMatch(p -> p.from().equals("foe") && p.to().equals("for")));
    assertFalse(pairs.stream().anyMatch(p -> p.from().equals("hallucinated")));

    // Feed into PersonalAutocorrectStore + AutocorrectEngine: 1st observation surfaces in suggestions immediately.
    PersonalAutocorrectStore store = PersonalAutocorrectStore.inMemory();
    for (TypoFixExtractor.FixPair p : pairs) store.recordAccepted(p.from(), p.to());
    AutocorrectEngine engine = new AutocorrectEngine(KeyboardLanguage.EN, null, store, true);
    assertTrue(engine.suggestions("yku", List.of()).contains("you"));
    assertTrue(engine.suggestions("oick", List.of()).contains("pick"));
    // 2nd observation promotes non-dictionary typo to automatic space-bar correction.
    store.recordAccepted("yku", "you");
    assertEquals("you", engine.correctOnSpace("yku").caseMatched());
  }
}
