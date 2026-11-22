package anomaly.hex.android.anywhere;

import static org.junit.Assert.*;

import org.junit.Test;

public class TypeAnywhereTest {
  private final String screen =
      "Team meeting at noon.\nBring the budget report.\nLunch after the meeting.\nParking is free.";
  @Test
  public void summarySkipsChromeAndBoundsLines() {
    String settings = "12:52\nS\nSettings\nQ Search settings\nNetwork & internet\nBattery\n100%\nStorage\nSound\nDisplay\nSecurity";
    String s = TypeAnywhereEngine.answerLocally(settings, "Summarize");
    assertFalse(s.contains("12:52"));
    assertFalse(s.contains("Search settings"));
    assertTrue(s.contains("Network & internet"));
    assertTrue(s.contains("Battery"));
  }
  @Test
  public void pairedHeadingAndValuePreserved() {
    String settings = "Settings\nBattery\n100%\nStorage\n38% used";
    assertEquals("Battery — 100%", TypeAnywhereEngine.answerLocally(settings, "battery"));
  }
  @Test
  public void keyPointsAreBullets() {
    assertTrue(TypeAnywhereEngine.answerLocally(screen, "Extract key points")
            .contains("\n• Bring the budget report."));
  }
  @Test
  public void matchesQuestion() {
    assertEquals("Bring the budget report.",
        TypeAnywhereEngine.answerLocally(screen, "What budget report?"));
  }
  @Test
  public void protectedScreenDoesNotInventContent() {
    assertTrue(
        TypeAnywhereEngine.answerLocally(null, "Summarize").contains("No readable screen text"));
  }
  @Test
  public void promptSeparatesQuestionAndScreen() {
    String prompt = TypeAnywhereEngine.buildPrompt("price: $5", "How much?");
    assertTrue(prompt.contains("\"question\":\"How much?\""));
    assertTrue(prompt.contains("\"screen\":\"price: $5\""));
  }
  @Test
  public void parsesAnswerParts() {
    assertEquals("Good morning",
        TypeAnywhereEngine.parseGeminiResponse(
            "{\"candidates\":[{\"content\":{\"parts\":[{\"thought\":true,\"text\":\"ignore\"},{"
            + "\"text\":\"Good \"},{\"text\":\"morning\"}]}}]}"));
  }
  @Test
  public void invalidOrBlockedResponse() {
    for (String json : new String[] {null, "broken", "{}", "{\"candidates\":[]}"})
      assertEquals("", TypeAnywhereEngine.parseGeminiResponse(json));
  }
  @Test
  public void anywhereButtonSizeClampsToRange() {
    assertEquals(anomaly.hex.android.core.HexSettings.ANYWHERE_SIZE_MIN_DP,
        anomaly.hex.android.core.HexSettings.clampAnywhereSize(8));
    assertEquals(36, anomaly.hex.android.core.HexSettings.clampAnywhereSize(36));
    assertEquals(anomaly.hex.android.core.HexSettings.ANYWHERE_SIZE_MAX_DP,
        anomaly.hex.android.core.HexSettings.clampAnywhereSize(120));
  }
  @Test
  public void screenActionsExtractsCalendarMapPhoneEmailAndUrl() {
    String text = "Meet Friday at 3:30 pm at 123 Main Street.\n"
        + "Call (555) 234-5678 or email alex@example.com.\n"
        + "Details: https://anoma.ly/hex";
    var actions = ScreenActions.extract(text);
    assertEquals(5, actions.size());
    assertEquals(ScreenActions.Kind.CALENDAR, actions.get(0).kind);
    assertEquals(ScreenActions.Kind.MAP, actions.get(1).kind);
    assertEquals("123 Main Street.", actions.get(1).value);
    assertEquals(ScreenActions.Kind.PHONE, actions.get(2).kind);
    assertEquals("(555) 234-5678", actions.get(2).value);
    assertEquals(ScreenActions.Kind.EMAIL, actions.get(3).kind);
    assertEquals("alex@example.com", actions.get(3).value);
    assertEquals(ScreenActions.Kind.LINK, actions.get(4).kind);
    assertEquals("https://anoma.ly/hex", actions.get(4).value);
  }
  @Test
  public void screenActionsHandlesEmptyOrPlainText() {
    assertTrue(ScreenActions.extract(null).isEmpty());
    assertTrue(ScreenActions.extract("Just a short note with no actions.").isEmpty());
  }
  @Test
  public void quickPromptsDefaultsAndPresetsAreNonEmpty() {
    assertEquals(4, QuickPromptsStore.DEFAULTS.size());
    assertTrue(QuickPromptsStore.PRESETS.size() >= 8);
  }
}
