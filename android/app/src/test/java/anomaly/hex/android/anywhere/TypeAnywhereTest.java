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
}
