package anomaly.hex.android.anywhere;

import static org.junit.Assert.*;

import org.junit.Test;

public class TypeAnywhereTest {
  private final String screen =
      "Team meeting at noon.\nBring the budget report.\nLunch after the meeting.\nParking is free.";
  @Test
  public void summaryIsBoundedToThreeLines() {
    assertEquals("Team meeting at noon. Bring the budget report. Lunch after the meeting.",
        TypeAnywhereEngine.answerLocally(screen, "Summarize"));
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
}
