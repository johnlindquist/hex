package anomaly.hex.android.anywhere;

import android.content.Context;
import android.content.SharedPreferences;
import anomaly.hex.android.anywhere.AnywhereIcons.Glyph;
import java.util.*;

/** Persists the 4 customizable quick-action icon slots in Type Anywhere. */
public final class QuickPromptsStore {
  public static final class Slot {
    public final Glyph glyph;
    public final String label;
    public final String prompt;
    public Slot(Glyph glyph, String label, String prompt) {
      this.glyph = glyph;
      this.label = label;
      this.prompt = prompt;
    }
  }

  public static final List<Slot> DEFAULTS = List.of(
      new Slot(Glyph.SUMMARIZE, "Summarize", "Summarize"),
      new Slot(Glyph.EXPLAIN, "Explain", "Explain"),
      new Slot(Glyph.KEY_POINTS, "Key points", "Key points"),
      new Slot(Glyph.REPLY, "Draft reply", "Draft reply"));

  public static final List<Slot> PRESETS = List.of(
      new Slot(Glyph.SUMMARIZE, "Summarize", "Summarize"),
      new Slot(Glyph.EXPLAIN, "Explain", "Explain"),
      new Slot(Glyph.KEY_POINTS, "Key points", "Key points"),
      new Slot(Glyph.REPLY, "Draft reply", "Draft reply"),
      new Slot(Glyph.SUMMARIZE, "Fact-check", "Fact-check the main claim on this screen using web search"),
      new Slot(Glyph.EXPLAIN, "Translate", "Translate the main text on this screen into natural English"),
      new Slot(Glyph.EXPLAIN, "ELI5", "Explain what is on this screen simply in 2-3 sentences"),
      new Slot(Glyph.KEY_POINTS, "Extract data", "Extract any key numbers, dates, prices, or code cleanly"));

  private QuickPromptsStore() {}

  public static List<Slot> load(Context c) {
    SharedPreferences p = c.getSharedPreferences("hex_settings", Context.MODE_PRIVATE);
    List<Slot> out = new ArrayList<>(4);
    for (int i = 0; i < 4; i++) {
      Slot def = DEFAULTS.get(i);
      String label = p.getString("anywhere_slot_label_" + i, def.label);
      String prompt = p.getString("anywhere_slot_prompt_" + i, def.prompt);
      if (label == null || label.isBlank()) label = def.label;
      if (prompt == null || prompt.isBlank()) prompt = def.prompt;
      out.add(new Slot(def.glyph, label.trim(), prompt.trim()));
    }
    return out;
  }

  public static void save(Context c, int index, String label, String prompt) {
    if (index < 0 || index >= 4) return;
    SharedPreferences p = c.getSharedPreferences("hex_settings", Context.MODE_PRIVATE);
    p.edit()
        .putString("anywhere_slot_label_" + index, label == null ? "" : label.trim())
        .putString("anywhere_slot_prompt_" + index, prompt == null ? "" : prompt.trim())
        .apply();
  }

  public static void reset(Context c) {
    SharedPreferences.Editor ed = c.getSharedPreferences("hex_settings", Context.MODE_PRIVATE).edit();
    for (int i = 0; i < 4; i++) {
      ed.remove("anywhere_slot_label_" + i);
      ed.remove("anywhere_slot_prompt_" + i);
    }
    ed.apply();
  }
}
