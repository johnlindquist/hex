package anomaly.hex.android.ui;
import static anomaly.hex.android.ui.HexTheme.*;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import anomaly.hex.android.core.*;
import anomaly.hex.android.service.HexBubbleService;
import anomaly.hex.android.speech.ModelStore;

public final class HexHomeActivity extends Activity {
  private HexSettings settings;
  private HexLabs labs;
  private HexHistory history;
  private CustomVocabulary vocabulary;
  private LinearLayout root, body, group;
  private int tab;
  private String query = "", scratch = "";
  private EditText practice;
  private final String[] tabs = {"General", "Output", "History", "Labs"};
  private final Handler handler = new Handler(Looper.getMainLooper());
  private String readiness = "";
  private final Runnable poll = new Runnable() {
    public void run() {
      if (!settings.onboardingCompleted() && settings.onboardingPage() == 1
          && !readyKey().equals(readiness))
        render();
      handler.postDelayed(this, 1000);
    }
  };
  @Override
  public void onCreate(Bundle b) {
    super.onCreate(b);
    settings = new HexSettings(this);
    labs = new HexLabs(this);
    history = new HexHistory(this);
    vocabulary = new CustomVocabulary(this);
    if (b != null) {
      tab = b.getInt("tab");
      scratch = b.getString("scratch", "");
    }
  }
  @Override
  public void onSaveInstanceState(Bundle b) {
    super.onSaveInstanceState(b);
    b.putInt("tab", tab);
    b.putString("scratch", practice == null ? scratch : practice.getText().toString());
  }
  @Override
  public void onResume() {
    super.onResume();
    render();
    handler.post(poll);
  }
  @Override
  public void onPause() {
    handler.removeCallbacks(poll);
    super.onPause();
  }
  @Override
  public void onRequestPermissionsResult(int n, String[] p, int[] g) {
    super.onRequestPermissionsResult(n, p, g);
    render();
  }
  private boolean mic() {
    return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
        == PackageManager.PERMISSION_GRANTED;
  }
  private boolean model() {
    return ModelStore.inspect(this).state != ModelStore.State.MISSING;
  }
  private String readyKey() {
    return mic() + ":" + (HexBubbleService.active != null) + ":" + model();
  }
  private void accessibility() {
    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
  }
  private void page(int p) {
    settings.setOnboardingPage(p);
    render();
  }
  private int d(int n) {
    return dp(this, n);
  }
  private void add(LinearLayout box, View v, int gap) {
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.topMargin = d(gap);
    box.addView(v, p);
  }
  private LinearLayout column() {
    LinearLayout v = new LinearLayout(this);
    v.setOrientation(1);
    return v;
  }
  private void title(String s, String detail) {
    TextView heading = text(this, s, 32, TEXT);
    heading.setAccessibilityHeading(true);
    add(body, heading, 14);
    add(body, text(this, detail, 16, MUTED), 8);
  }
  private void card(String label) {
    group = column();
    group.setPadding(d(16), d(14), d(16), d(16));
    group.setBackground(shape(this, CARD, 18, LINE));
    add(body, group, 18);
    if (!label.isEmpty()) {
      TextView h = text(this, label, 19, TEXT);
      h.setAccessibilityHeading(true);
      add(group, h, 0);
    }
  }
  private void note(String s) {
    add(group, text(this, s, 15, MUTED), 8);
  }
  private void act(String label, boolean primary, Runnable fn) {
    add(group, button(this, label, primary, fn), 8);
  }
  private void render() {
    if (practice != null) {
      scratch = practice.getText().toString();
      practice = null;
    }
    root = column();
    root.setBackgroundColor(CANVAS);
    root.setOnApplyWindowInsetsListener((v, i) -> {
      android.graphics.Insets pad = i.getInsets(WindowInsets.Type.systemBars()
          | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
      v.setPadding(pad.left, pad.top, pad.right, pad.bottom);
      return i;
    });
    setContentView(root);
    ScrollView scroll = new ScrollView(this);
    body = column();
    body.setPadding(d(20), d(22), d(20), d(24));
    scroll.addView(body);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    boolean setup = !settings.onboardingCompleted();
    add(body,
        text(this,
            setup ? "Hex   /   " + (settings.onboardingPage() + 1) + " of 3" : "Hex   /   Settings",
            13, MUTED),
        0);
    if (setup) {
      tour(settings.onboardingPage());
      return;
    }
    switch (tab) {
      case 0:
        general();
        break;
      case 1:
        output();
        break;
      case 2:
        history();
        break;
      default:
        labs();
    }
    LinearLayout nav = new LinearLayout(this);
    nav.setBackgroundColor(SURFACE);
    root.addView(nav);
    for (int i = 0; i < tabs.length; i++) {
      final int dest = i;
      TextView item = text(this, tabs[i], 14, i == tab ? TEXT : MUTED);
      item.setSelected(i == tab);
      item.setGravity(Gravity.CENTER);
      item.setOnClickListener(v -> {
        tab = dest;
        render();
      });
      nav.addView(item, new LinearLayout.LayoutParams(0, d(56), 1));
    }
  }
  private void tour(int p) {
    if (p == 0) {
      title("Welcome to Hex", "Speak in any text field. Your voice stays on this device.");
      card("Dictation, where you write");
      DotScopeView scope = new DotScopeView(this);
      group.addView(scope, -1, d(70));
      note("Tap the floating mic, speak, then tap the checkmark to insert your words.");
      act("Set up Hex", true, () -> page(1));
    } else if (p == 1) {
      title("Set up Hex", "Three things make local dictation possible.");
      checklist();
      Button next = button(this, "Try Hex", true, () -> page(2));
      next.setEnabled(mic() && HexBubbleService.active != null && model());
      next.setAlpha(next.isEnabled() ? 1 : .45f);
      add(group, next, 12);
      act("Back", false, () -> page(0));
    } else {
      title("Try Hex", "Tap the scratchpad to show the floating mic, then dictate a sentence.");
      card("Scratchpad");
      practice = new EditText(this);
      practice.setGravity(Gravity.TOP);
      practice.setMinLines(4);
      practice.setTextColor(TEXT);
      practice.setHintTextColor(FAINT);
      practice.setHint("Your words appear here");
      practice.setText(scratch);
      add(group, practice, 8);
      act("Start using Hex", true, () -> {
        settings.setOnboardingCompleted(true);
        render();
      });
      act("Back", false, () -> page(1));
    }
  }
  private void checklist() {
    readiness = readyKey();
    card("Dictation setup");
    note(mic() ? "✓  Microphone allowed" : "Microphone permission is needed only while recording.");
    if (!mic())
      act("Allow microphone", false,
          () -> requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, 1));
    note(HexBubbleService.active != null
            ? "✓  Dictation service connected"
            : "Enable Hex dictation under Accessibility to insert into the active text field.");
    if (HexBubbleService.active == null)
      act("Open accessibility settings", false, this::accessibility);
    note(model() ? "✓  Speech model installed"
                 : "Install the offline speech model in " + ModelStore.externalDir(this));
  }
  private void general() {
    title("General", "Microphone, model and setup.");
    checklist();
    card("Feedback");
    toggle("Haptic feedback", settings.hapticsEnabled(), settings::setHapticsEnabled);
    act("Run setup again", false, () -> {
      settings.setOnboardingCompleted(false);
      page(0);
    });
    if (labs.isEnabled(LabId.RECOVERY_DRAFTS))
      act("Recovery drafts", false,
          () -> startActivity(new Intent(this, HexRecoveryActivity.class)));
  }
  private void toggle(String label, boolean value, java.util.function.Consumer<Boolean> changed) {
    Switch s = new Switch(this);
    s.setText(label);
    s.setTextColor(TEXT);
    s.setMinHeight(d(48));
    s.setChecked(value);
    s.setOnCheckedChangeListener((v, b) -> changed.accept(b));
    add(group, s, 4);
  }
  private void output() {
    title("Output", "Choose how Hex writes your words.");
    card("Transcript style");
    for (TranscriptStyle s : TranscriptStyle.values()) {
      RadioButton b = new RadioButton(this);
      b.setText(s.label());
      b.setTextColor(TEXT);
      b.setChecked(settings.style() == s);
      b.setOnClickListener(v -> {
        settings.setStyle(s);
        render();
      });
      add(group, b, 0);
    }
    card("Custom vocabulary");
    note("Add names or terms you want Hex to recognize.");
    EditText entry = new EditText(this);
    entry.setSingleLine();
    entry.setHint("New term");
    add(group, entry, 4);
    act("Add term", false, () -> {
      if (vocabulary.addTerm(entry.getText().toString()))
        render();
      else
        entry.setError("Enter a new term within the vocabulary limit");
    });
    for (String term : vocabulary.terms())
      act("Remove “" + term + "”", false, () -> {
        vocabulary.removeTerm(term);
        render();
      });
  }
  private void history() {
    title("History", "Dictations confirmed in the text field.");
    card("Keep dictations");
    for (HistoryRetention r : HistoryRetention.values()) {
      RadioButton b = new RadioButton(this);
      b.setText(r.label());
      b.setTextColor(TEXT);
      b.setChecked(r == settings.historyRetention());
      b.setOnClickListener(v -> {
        settings.setHistoryRetention(r);
        render();
      });
      add(group, b, 0);
    }
    history.pruneExpired(settings.historyRetention());
    card("Recent dictations");
    EditText search = new EditText(this);
    search.setSingleLine();
    search.setHint("Search history");
    search.setText(query);
    add(group, search, 4);
    act("Search", false, () -> {
      query = search.getText().toString();
      render();
    });
    java.util.List<HexHistory.Entry> rows = history.list(query, 100);
    if (rows.isEmpty())
      note("No matching dictations.");
    for (HexHistory.Entry e : rows) {
      TextView words = text(this, e.finalText, 16, TEXT);
      words.setTextIsSelectable(true);
      add(group, words, 14);
    }
    if (history.count() > 0)
      act("Clear history", false,
          ()
              -> new AlertDialog.Builder(this)
                  .setMessage("Delete all retained dictations?")
                  .setNegativeButton("Cancel", null)
                  .setPositiveButton("Delete",
                      (v, n) -> {
                        history.clearAll();
                        render();
                      })
                  .show());
  }
  private void labs() {
    title("Labs", "Optional features. Each starts off.");
    String last = "";
    for (LabId lab : LabId.values()) {
      if (!lab.group.equals(last)) {
        card(lab.group);
        last = lab.group;
      }
      toggle(lab.title, labs.isEnabled(lab), on -> {
        if (lab == LabId.RECOVERY_DRAFTS && !on) {
          new AlertDialog.Builder(this)
              .setMessage("Turn off recovery and delete all saved drafts?")
              .setNegativeButton("Keep drafts", (d, n) -> render())
              .setOnCancelListener(d -> render())
              .setPositiveButton("Turn off and delete",
                  (d, n) -> {
                    labs.setEnabledUnchecked(lab, false);
                    HexBubbleService.refreshLabs();
                    new anomaly.hex.android.recovery.Drafts(this).purgeAll();
                    render();
                  })
              .show();
        } else {
          labs.setEnabledUnchecked(lab, on);
          HexBubbleService.refreshLabs();
          render();
        }
      });
      note(lab.summary);
      if (lab == LabId.WORDS_ON_SCREEN && labs.isEnabled(lab))
        act("Configure window access", false, this::accessibility);
      if (lab == LabId.RECOVERY_DRAFTS && labs.isEnabled(lab))
        act("Open recovery drafts", false,
            () -> startActivity(new Intent(this, HexRecoveryActivity.class)));
    }
  }
}
