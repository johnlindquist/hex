package anomaly.hex.android.ui;
import static anomaly.hex.android.ui.HexTheme.*;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.provider.Settings;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.net.Uri;
import anomaly.hex.android.control.CopyDictationActivity;
import anomaly.hex.android.speech.SpeechLanguage;
import java.io.*;
import java.util.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import anomaly.hex.android.keyboard.*;
import android.widget.*;
import anomaly.hex.android.core.*;
import anomaly.hex.android.service.HexBubbleService;
import anomaly.hex.android.speech.ModelStore;

public final class HexHomeActivity extends Activity {
  private HexSettings settings;
  private HexLabs labs;
  private HexHistory history;
  private CustomVocabulary vocabulary;
  private LinearLayout root, body, group, footer, nav, header;
  private ScrollView scroll;
  private String screen = "";
  private final LinearLayout[] items = new LinearLayout[4];
  private int tab;
  private String query = "", scratch = "";
  private EditText practice;
  private final String[] tabs = {"General", "Output", "History", "Labs"};
  private final Handler handler = new Handler(Looper.getMainLooper());
  private boolean modelAvailable, draftPending;
  private final Runnable persistDraft = () -> {
    draftPending = false;
    SharedPreferences.Editor edit = getPreferences(MODE_PRIVATE).edit();
    if (scratch.isEmpty()) edit.remove("scratch_draft");
    else edit.putString("scratch_draft", scratch);
    edit.apply();
  };
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
    scratch = getPreferences(MODE_PRIVATE).getString("scratch_draft", "");
    if (b != null) {
      tab = b.getInt("tab");
      scratch = b.getString("scratch", "");
    }
    buildShell();
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
    modelAvailable = ModelStore.inspect(this).state != ModelStore.State.MISSING;
    render();
    handler.post(poll);
  }
  @Override
  public void onPause() {
    handler.removeCallbacks(poll);
    if (draftPending) {
      handler.removeCallbacks(persistDraft);
      persistDraft.run();
    }
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
    return modelAvailable;
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
    add(header, heading, 4);
    add(header, text(this, detail, 15, MUTED), 4);
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
  private boolean imeVisible;
  private void updateNav() {
    nav.setVisibility(settings.onboardingCompleted() && !imeVisible ? View.VISIBLE : View.GONE);
  }
  private void buildShell() {
    root = column();
    root.setBackgroundColor(CANVAS);
    root.setOnApplyWindowInsetsListener((v, i) -> {
      android.graphics.Insets pad = i.getInsets(WindowInsets.Type.systemBars()
          | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
      boolean wasIme = imeVisible;
      imeVisible = i.isVisible(WindowInsets.Type.ime());
      body.setPadding(d(20), 0, d(20), d(imeVisible ? 88 : 24));
      updateNav();
      v.setPadding(pad.left, pad.top, pad.right, pad.bottom);
      if (imeVisible && !wasIme && practice != null && practice.hasFocus()) {
        scroll.post(() -> {
          if (practice != null && practice.getParent() instanceof View parent) {
            scroll.smoothScrollTo(0, Math.max(0, parent.getTop() - d(12)));
          }
        });
      }
      return i;
    });
    setContentView(root);
    header = column();
    header.setPadding(d(20), d(12), d(20), d(8));
    root.addView(header);
    scroll = new ScrollView(this);
    body = column();
    body.setPadding(d(20), 0, d(20), d(24));
    scroll.addView(body);
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    divider(root);
    footer = new LinearLayout(this);
    footer.setGravity(Gravity.CENTER_VERTICAL);
    footer.setPadding(d(16), d(8), d(16), d(8));
    root.addView(footer);
    nav = new LinearLayout(this);
    nav.setBackgroundColor(SURFACE);
    root.addView(nav, -1, d(64));
    for (int i = 0; i < tabs.length; i++) {
      int dest = i;
      LinearLayout item = items[i] = column();
      item.setGravity(Gravity.CENTER);
      item.setBackground(ripple(this, SURFACE, 8));
      item.setContentDescription(tabs[i]);
      item.setFocusable(true);
      item.addView(new TabIcon(this, i), d(46), d(26));
      TextView label = text(this, tabs[i], 12, MUTED);
      label.setGravity(Gravity.CENTER_HORIZONTAL);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
      lp.topMargin = d(4);
      item.addView(label, lp);
      item.setOnClickListener(v -> {
        if (tab != dest) {
          tab = dest;
          render();
        }
      });
      nav.addView(item, new LinearLayout.LayoutParams(0, -1, 1));
    }
  }
  private void render() {
    if (practice != null) {
      scratch = practice.getText().toString();
      practice = null;
    }
    header.removeAllViews();
    body.removeAllViews();
    footer.removeAllViews();
    boolean setup = !settings.onboardingCompleted();
    String next = setup ? "tour" + settings.onboardingPage() : tabs[tab];
    if (!screen.equals(next))
      scroll.scrollTo(0, 0);
    screen = next;
    footer.setVisibility(setup ? View.VISIBLE : View.GONE);
    updateNav();
    if (setup)
      add(header, text(this, "Hex   /   " + (settings.onboardingPage() + 1) + " of 3", 12, MUTED),
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
    for (int i = 0; i < items.length; i++) {
      items[i].setSelected(i == tab);
      View icon = items[i].getChildAt(0);
      icon.setSelected(i == tab);
      icon.setBackground(shape(this, i == tab ? CONTROL : SURFACE, 14, 0));
      ((TextView) items[i].getChildAt(1)).setTextColor(i == tab ? TEXT : MUTED);
    }
  }
  private void divider(LinearLayout box) {
    divider(box, box == group && box.getChildCount() == 1 ? 10 : 0);
  }
  private void divider(LinearLayout box, int gap) {
    View line = new View(this);
    line.setBackgroundColor(LINE);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, d(1));
    lp.topMargin = d(gap);
    box.addView(line, lp);
  }
  private void footerAction(String label, Runnable back, Runnable forward, boolean enabled) {
    if (back != null)
      footer.addView(button(this, "Back", false, back));
    Button next = button(this, label, true, forward);
    next.setEnabled(enabled);
    next.setAlpha(enabled ? 1f : .45f);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
    lp.leftMargin = back == null ? 0 : d(8);
    footer.addView(next, lp);
  }
  private void tour(int p) {
    if (p == 0) {
      title("Welcome to Hex", "Speak in any text field. Your voice stays on this device.");
      card("Dictation, where you write");
      DotScopeView scope = new DotScopeView(this);
      scope.setDemoMode(true);
      group.addView(scope, -1, d(70));
      note("Tap the floating mic, speak, then tap the checkmark to insert your words.");
      footerAction("Set up Hex", null, () -> page(1), true);
    } else if (p == 1) {
      title("Set up Hex", "Three things make local dictation possible.");
      checklist();
      footerAction("Try Hex",
          () -> page(0), () -> page(2), mic() && HexBubbleService.active != null && model());
    } else {
      title("Try Hex", "Tap the scratchpad to show the floating mic, then dictate a sentence.");
      scratchpad(false);
      footerAction("Start using Hex", () -> page(1), () -> {
        settings.setOnboardingCompleted(true);
        tab = 0;
        render();
      }, true);
    }
  }
  private LinearLayout row(LinearLayout parent) {
    return row(parent, 0);
  }
  private LinearLayout row(LinearLayout parent, int gap) {
    LinearLayout r = new LinearLayout(this);
    r.setGravity(Gravity.CENTER_VERTICAL);
    add(parent, r, gap);
    return r;
  }
  private void flexible(LinearLayout r, View v) {
    r.addView(v, new LinearLayout.LayoutParams(0, -2, 1));
  }
  private LinearLayout.LayoutParams afterControl() {
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
    lp.setMarginStart(d(8));
    return lp;
  }
  private void status(String name, String value) {
    divider(group);
    LinearLayout r = row(group);
    r.setMinimumHeight(d(38));
    flexible(r, text(this, name, 15, TEXT));
    r.addView(text(this, value, 14, MUTED));
  }
  private void checklist() {
    boolean microphone = mic(), service = HexBubbleService.active != null, installed = model();
    readiness = microphone + ":" + service + ":" + installed;
    boolean ready = microphone && service && installed;
    card("");
    LinearLayout r = row(group);
    flexible(r, text(this, "Dictation setup", 19, TEXT));
    TextView badge = text(this, ready ? "Ready" : "Action needed", 12, ready ? SUCCESS : MIC);
    badge.setPadding(d(10), d(5), d(10), d(5));
    badge.setBackground(shape(this, ready ? 0x2034c759 : 0x20ff9500, 16, 0));
    r.addView(badge);
    status("Microphone", microphone ? "Allowed" : "Permission needed");
    if (!microphone)
      act("Allow microphone", false,
          () -> requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, 1));
    status("Accessibility", service ? "Connected" : "Disconnected");
    if (!service)
      act("Open accessibility settings", false, this::accessibility);
    status("Speech model", installed ? "Parakeet 0.6B" : "Not installed");
    if (!installed)
      note("Select the Parakeet encoder, decoder, joiner and tokens files.");
    act("Import model files", false, () -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), 21));
  }
  @Override protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request != 21 || result != RESULT_OK || data == null) return;
    Set<Uri> files = new LinkedHashSet<>();
    if (data.getData() != null) files.add(data.getData());
    ClipData clips = data.getClipData();
    if (clips != null) for (int i = 0; i < clips.getItemCount(); i++) files.add(clips.getItemAt(i).getUri());
    Context app = getApplicationContext();
    new Thread(() -> {
      int imported = 0;
      for (Uri uri : files) {
        if (uri == null || !"content".equals(uri.getScheme())) continue;
        try (Cursor names = app.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
          if (names == null || !names.moveToFirst()) continue;
          String name = names.getString(0);
          try (InputStream stream = app.getContentResolver().openInputStream(uri)) {
            if (stream == null) continue;
            ModelStore.importModelFile(app, name, stream);
            imported++;
          }
        } catch (IOException | RuntimeException unavailable) { /* Count only successful copies. */ }
      }
      String message = imported + " imported · " + ModelStore.missingFiles(app).size() + " missing";
      runOnUiThread(() -> {
        Toast.makeText(app, message, Toast.LENGTH_LONG).show();
        if (isDestroyed() || isFinishing()) return;
        modelAvailable = ModelStore.inspect(this).state != ModelStore.State.MISSING;
        render();
      });
    }, "hex-model-import").start();
  }
  private void copy(String words) {
    getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Hex", words));
  }
  private void scratchpad(boolean actions) {
    card("Scratchpad");
    practice = input(this, scratchpadHint());
    practice.setGravity(Gravity.TOP | Gravity.START);
    practice.setMinHeight(d(110));
    practice.setText(scratch);
    practice.addTextChangedListener(new android.text.TextWatcher() {
      @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
      @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
      @Override public void afterTextChanged(android.text.Editable s) {
        scratch = s == null ? "" : s.toString();
        handler.removeCallbacks(persistDraft);
        draftPending = true;
        handler.postDelayed(persistDraft, 400);
      }
    });
    add(group, practice, 8);
    if (actions) {
      LinearLayout r = row(group, 10);
      r.addView(button(this, "Copy", false, () -> copy(practice.getText().toString())));
      r.addView(button(this, "Clear", false, () -> {
        practice.setText("");
      }), afterControl());
    }
  }
  private void general() {
    title("General", "Local dictation, ready where you write.");
    checklist();
    scratchpad(true);
    act("Dictate & copy", false, () -> startActivity(new Intent(this, CopyDictationActivity.class)));
    card("Audio & language");
    SpeechLanguage language = new SpeechLanguage(this);
    act("Speech language: " + languageLabel(language.selectedId()), false, () -> pickLanguage(language));
    toggleRow("Always use phone microphone", "Keeps Bluetooth music playing and starts faster",
        settings.preferBuiltInMic(), settings::setPreferBuiltInMic);
    keyboardCard();
    card("Feedback");
    toggleRow("Haptic feedback", "Vibrate when dictation starts and stops.",
        settings.hapticsEnabled(), settings::setHapticsEnabled);
    if (labs.isEnabled(LabId.RECOVERY_DRAFTS))
      act("Recovery drafts", false,
          () -> startActivity(new Intent(this, HexRecoveryActivity.class)));
    card("About");
    act("Privacy Policy", false, () -> openLink("https://anoma.ly/hex/privacy"));
    act("Support", false, () -> openLink("https://anoma.ly/hex/support"));
    act("Open-source notices", false, this::showNotices);
  }
  private String scratchpadHint() {
    return KeyboardPresence.isHexKeyboardDefault(this) ? "Tap here and press the Hex key to test dictation..."
        : "Tap here and press the floating mic to test dictation...";
  }
  private TextView keyboardStatus;
  @Override public void onWindowFocusChanged(boolean focused) {
    super.onWindowFocusChanged(focused);
    if (focused && practice != null && practice.isAttachedToWindow()) practice.setHint(scratchpadHint());
    if (focused && keyboardStatus != null && keyboardStatus.isAttachedToWindow()) refreshKeyboardStatus();
  }
  private void refreshKeyboardStatus() {
    ComponentName component = new ComponentName(this, HexInputMethodService.class);
    boolean enabled = getSystemService(InputMethodManager.class).getEnabledInputMethodList().stream()
        .anyMatch(i -> component.equals(i.getComponent()));
    keyboardStatus.setText(KeyboardPresence.isHexKeyboardDefault(this)
        ? "Active" : enabled ? "Enabled" : "Off (using system keyboard)");
  }
  private void keyboardCard() {
    card("Optional Hex Keyboard");
    KeyboardSettings kb = new KeyboardSettings(this);
    InputMethodManager imm = getSystemService(InputMethodManager.class);
    keyboardStatus = text(this, "", 15, MUTED);
    add(group, keyboardStatus, 8);
    refreshKeyboardStatus();
    act("Keyboard settings", false, () -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
    act("Switch keyboard", false, imm::showInputMethodPicker);
    divider(group, 10);
    radioRowContent("Full keyboard", "QWERTY letters, suggestions, and Hex key", kb.isFullKeyboard(), () -> kb.setFullKeyboard(true));
    radioRow("Dictation bar", "Compact voice stage with space, delete, and return", !kb.isFullKeyboard(), () -> kb.setFullKeyboard(false));
    if (!kb.isFullKeyboard()) return;
    List<String> codes = new ArrayList<>(List.of("auto"));
    for (KeyboardLanguage lang : KeyboardLanguage.values()) codes.add(lang.code());
    String[] labels = codes.stream().map(code -> code.equals("auto") ? "Automatic" : Locale.forLanguageTag(code).getDisplayLanguage()).toArray(String[]::new);
    int index = Math.max(0, codes.indexOf(kb.layoutLanguage()));
    divider(group, 10);
    act("Keyboard layout: " + labels[index], false, () -> new AlertDialog.Builder(this).setTitle("Keyboard layout")
        .setSingleChoiceItems(labels, index, (dialog, i) -> { kb.setLayoutLanguage(codes.get(i)); dialog.dismiss(); render(); }).show());
    toggleRow("Autocorrect", "Fixes typos when you press space", kb.autocorrect(), kb::setAutocorrect);
    toggleRow("Key pop-ups", "Shows the letter above your finger", kb.keyPopups(), kb::setKeyPopups);
    toggleRow("Keyboard haptics", "A light tap as you press Hex's keys", kb.haptics(), kb::setHaptics);
  }
  private void openLink(String url) {
    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
    catch (ActivityNotFoundException e) { Toast.makeText(this, "No browser available", Toast.LENGTH_SHORT).show(); }
  }
  private String languageLabel(String id) {
    String label = SpeechLanguage.resolveLocale(id, Locale.getDefault()).getDisplayLanguage();
    return SpeechLanguage.AUTO.equals(id) ? "Auto (" + label + ")" : label;
  }
  private void pickLanguage(SpeechLanguage language) {
    Set<String> ordered = new LinkedHashSet<>();
    ordered.add(SpeechLanguage.AUTO);
    ordered.addAll(language.recentIds());
    ordered.addAll(SpeechLanguage.supportedIds());
    List<String> ids = new ArrayList<>(ordered);
    String[] labels = ids.stream().map(this::languageLabel).toArray(String[]::new);
    new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("Speech language")
        .setSingleChoiceItems(labels, ids.indexOf(language.selectedId()), (dialog, which) -> {
          language.select(ids.get(which)); dialog.dismiss(); render();
        }).setNegativeButton("Cancel", null).show();
  }
  private void showNotices() {
    StringBuilder notices = new StringBuilder();
    for (String file : new String[]{"models.txt", "ort-LICENSE.txt", "sherpa-LICENSE.txt"}) {
      notices.append(file).append("\n\n");
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(getAssets().open("notices/" + file), java.nio.charset.StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) notices.append(line).append('\n');
      } catch (IOException e) { notices.append("Notice unavailable\n"); }
      notices.append('\n');
    }
    new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("Open-source notices")
        .setMessage(notices.toString()).setPositiveButton("Close", null).show();
  }
  private void toggleRow(
      String label, String summary, boolean value, java.util.function.Consumer<Boolean> changed) {
    divider(group);
    LinearLayout r = row(group);
    r.setPadding(0, d(12), 0, d(12));
    LinearLayout labels = column();
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
    lp.setMarginEnd(d(12));
    r.addView(labels, lp);
    add(labels, text(this, label, 16, TEXT), 0);
    add(labels, text(this, summary, 14, MUTED), 3);
    Switch toggle = new Switch(this);
    toggle.setContentDescription(label + ". " + summary);
    toggle.setChecked(value);
    int[][] states = {{android.R.attr.state_checked}, {}};
    toggle.setThumbTintList(
        new android.content.res.ColorStateList(states, new int[] {PRIMARY, 0xffadb1b8}));
    toggle.setTrackTintList(
        new android.content.res.ColorStateList(states, new int[] {0xff307543, 0xff565b64}));
    toggle.setOnCheckedChangeListener((v, b) -> changed.accept(b));
    r.addView(toggle);
    r.setOnClickListener(v -> toggle.toggle());
  }
  private void radioRow(String label, String summary, boolean checked, Runnable select) {
    divider(group);
    radioRowContent(label, summary, checked, select);
  }
  private void radioRowContent(String label, String summary, boolean checked, Runnable select) {
    LinearLayout r = row(group);
    r.setPadding(0, d(10), 0, d(10));
    r.setMinimumHeight(d(52));
    RadioButton b = new RadioButton(this);
    b.setButtonTintList(android.content.res.ColorStateList.valueOf(PRIMARY));
    b.setChecked(checked);
    b.setContentDescription(summary == null ? label : label + ". " + summary);
    r.addView(b);
    LinearLayout labels = column();
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
    lp.setMarginStart(d(8));
    r.addView(labels, lp);
    add(labels, text(this, label, 16, TEXT), 0);
    if (summary != null)
      add(labels, text(this, summary, 14, MUTED), 2);
    b.setOnClickListener(v -> { select.run(); render(); });
    r.setOnClickListener(v -> b.performClick());
  }
  private void output() {
    title("Output", "Choose how Hex writes your words.");
    card("Transcript style");
    for (TranscriptStyle s : TranscriptStyle.values()) {
      String summary = switch (s) {
        case POLISHED -> "Punctuated and capitalized.";
        case CASUAL -> "Relaxed, conversational text.";
        case NOTES -> "Format speech as notes.";
        case VERBATIM -> "Keep words as recognized.";
      };
      radioRow(s.label(), summary, settings.style() == s, () -> settings.setStyle(s));
    }
    card("Custom vocabulary");
    toggleRow("Custom vocabulary", "Spell names and terms your way", vocabulary.isEnabled(), enabled -> {
      vocabulary.setEnabled(enabled); render();
    });
    if (vocabulary.isEnabled()) vocabularyEditor();
    card("Dictation learning");
    DictationFixStore fixes = new DictationFixStore(this);
    toggleRow("Learn from dictation fixes", "Remembers single-word edits after two sightings", fixes.isEnabled(), enabled -> {
      fixes.setEnabled(enabled); render();
    });
    var active = new TreeMap<>(fixes.activeFixes());
    active.forEach((heard, preferred) -> {
      divider(group);
      LinearLayout r = row(group);
      flexible(r, text(this, heard + " → " + preferred, 15, TEXT));
      Button remove = button(this, "Remove", false, () -> { fixes.removeFix(heard); render(); });
      remove.setContentDescription("Remove fix for " + heard);
      r.addView(remove);
    });
    if (!active.isEmpty()) act("Clear learned fixes", false, () -> new AlertDialog.Builder(this)
        .setMessage("Delete all learned fixes?").setNegativeButton("Cancel", null)
        .setPositiveButton("Delete", (dialog, which) -> { fixes.clearAll(); render(); }).show());
  }
  private void vocabularyEditor() {
    LinearLayout form = row(group, 10);
    EditText entry = input(this, "New term");
    entry.setSingleLine();
    flexible(form, entry);
    form.addView(button(this, "Add", false, () -> {
      if (vocabulary.addTerm(entry.getText().toString()))
        render();
      else
        entry.setError("Enter a new term within the vocabulary limit");
    }), afterControl());
    for (String term : vocabulary.terms()) {
      divider(group, 10);
      LinearLayout r = row(group);
      flexible(r, text(this, term, 15, TEXT));
      Button remove = button(this, "×", false, () -> {
        vocabulary.removeTerm(term);
        render();
      });
      remove.setPadding(0, 0, 0, 0);
      remove.setContentDescription("Remove " + term);
      r.addView(remove, d(48), d(48));
    }
  }
  private void history() {
    title("History", "Dictations confirmed in the text field.");
    card("Keep dictations");
    for (HistoryRetention r : HistoryRetention.values())
      radioRow(r.label(), null, r == settings.historyRetention(), () -> settings.setHistoryRetention(r));
    history.pruneExpired(settings.historyRetention());
    card("Recent dictations");
    EditText search = input(this, "Search dictations…");
    search.setSingleLine();
    search.setText(query);
    add(group, search, 8);
    LinearLayout results = column();
    add(group, results, 8);
    historyResults(results);
    search.addTextChangedListener(new android.text.TextWatcher() {
      public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
      public void onTextChanged(CharSequence s, int start, int before, int count) {
        query = s.toString();
        historyResults(results);
      }
      public void afterTextChanged(android.text.Editable e) {}
    });
    if (history.count() > 0) {
      Button clear = button(this, "Clear history", false,
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
      clear.setTextColor(DANGER);
      add(group, clear, 8);
    }
  }
  private void historyResults(LinearLayout results) {
    results.removeAllViews();
    var rows = history.list(query, 100);
    if (rows.isEmpty())
      add(results, text(this, "No matching dictations.", 15, MUTED), 8);
    for (var e : rows) {
      divider(results, 10);
      TextView words = text(this, e.finalText, 16, TEXT);
      words.setTextIsSelectable(true);
      add(results, words, 10);
      LinearLayout r = row(results, 6);
      ((LinearLayout.LayoutParams) r.getLayoutParams()).bottomMargin = d(4);
      String date = java.text.DateFormat
                        .getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT)
                        .format(new java.util.Date(e.createdAtMs));
      flexible(r, text(this, date + " · " + TranscriptStyle.fromId(e.style).label(), 13, FAINT));
      r.addView(button(this, "Copy", false, () -> copy(e.finalText)));
    }
  }
  private void labs() {
    title("Labs", "Optional features. Each starts off.");
    String last = "";
    for (LabId lab : LabId.values()) {
      if (!lab.group.equals(last)) {
        card(lab.group);
        last = lab.group;
      }
      toggleRow(lab.title, lab.summary, labs.isEnabled(lab), on -> {
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
          if (lab == LabId.EMOJI_KEYBOARD) new KeyboardSettings(this).setEmojiEnabled(on);
          HexBubbleService.refreshLabs();
          render();
        }
      });

      if (lab == LabId.WORDS_ON_SCREEN && labs.isEnabled(lab))
        act("Configure window access", false, this::accessibility);
      if (lab == LabId.RECOVERY_DRAFTS && labs.isEnabled(lab))
        act("Open recovery drafts", false,
            () -> startActivity(new Intent(this, HexRecoveryActivity.class)));
    }
    card("Setup");
    act("Run setup again", false, () -> {
      settings.setOnboardingCompleted(false);
      page(0);
    });
  }
}
