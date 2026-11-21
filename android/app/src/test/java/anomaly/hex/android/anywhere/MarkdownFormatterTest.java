package anomaly.hex.android.anywhere;

import static org.junit.Assert.*;

import org.junit.Test;

public class MarkdownFormatterTest {
  @Test
  public void stripsBoldItalicCodeAndFormatsBullets() {
    String md = "## Android Settings\n\n* **Battery:** 100% (*charging*)\n  * `Storage`: 63% used\n1. **Tap** to open";
    String plain = MarkdownFormatter.toPlainPreview(md);
    assertEquals(
        "Android Settings\n\n•  Battery: 100% (charging)\n◦  Storage: 63% used\n1. Tap to open",
        plain);
    assertFalse(plain.contains("**"));
    assertFalse(plain.contains("`"));
    assertFalse(plain.contains("##"));
  }
}
