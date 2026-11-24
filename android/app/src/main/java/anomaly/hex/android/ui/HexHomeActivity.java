package anomaly.hex.android.ui;
import static anomaly.hex.android.ui.HexTheme.*;

import android.Manifest;
import android.graphics.*;
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
import anomaly.hex.android.speech.ModelDownloader;
import java.util.function.Consumer;

public final class HexHomeActivity extends Activity {
  private HexSettings settings;
  private HexLabs labs;
  private HexHistory history;
  private CustomVocabulary vocabulary;
  private LinearLayout root, body, group, footer, nav, header;
  private View footerDivider;
  private ScrollView scroll;
  private String screen = "";
  private final LinearLayout[] items = new LinearLayout[4];
  private int tab;
  private String query = "", scratch = "";
  private EditText practice;
  private final String[] tabs = {"General", "Output", "History", "Labs"};
  private final Handler handler = new Handler(Looper.getMainLooper());
  private boolean modelAvailable, draftPending;
  private final ModelDownloader downloader = ModelDownloader.INSTANCE;
  private ModelDownloader.Progress progress;
  private TextView downloadLabel;
  private ProgressBar downloadBar;
  private final Consumer<ModelDownloader.Progress> listener =
      next -> handler.postAtTime(() -> {
        boolean changed = progress.active != next.active || !Objects.equals(progress.error, next.error);
        progress = next;
        if (next.complete) modelAvailable = true;
        if (changed) render(); else updateDownload();
      }, downloader, SystemClock.uptimeMillis());
  private final Runnable persistDraft = () -> {
    draftPending = false;
    SharedPreferences.Editor edit = getPreferences(MODE_PRIVATE).edit();
    if (scratch.isEmpty())
      edit.remove("scratch_draft");
    else
      edit.putString("scratch_draft", scratch);
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
    getWindow().getInsetsController().setSystemBarsAppearance(
        0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
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
    progress = downloader.subscribe(listener);
    modelAvailable = ModelStore.inspect(this).state != ModelStore.State.MISSING;
    render();
    handler.post(poll);
  }
  @Override
  public void onPause() {
    downloader.unsubscribe(listener);
    handler.removeCallbacksAndMessages(downloader);
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
    TextView heading = text(this, s, 28, TEXT);
    heading.setLetterSpacing(-.015f);
    heading.setAccessibilityHeading(true);
    add(header, heading, 4);
    add(header, text(this, detail, 13, MUTED), 4);
  }
  private void card(String label) {
    if (!label.isEmpty()) {
      TextView h = text(this, label, 13, MUTED);
      h.setAccessibilityHeading(true);
      h.setPadding(d(16), 0, 0, 0);
      add(body, h, 18);
    }
    group = column();
    group.setBackground(shape(this, SURFACE, 18, 0));
    group.setClipToOutline(true);
    add(body, group, label.isEmpty() ? 12 : 6);
  }
  private void note(String s) {
    TextView v = text(this, s, 13, MUTED);
    v.setPadding(d(16), d(8), d(16), d(8));
    add(group, v, 0);
  }
  private void act(String label, boolean primary, Runnable fn) {
    if (primary)
      add(group, button(this, label, true, fn), 8);
    else
      linkRow(label,
          label.equals("Support") || label.equals("Privacy policy")
              || label.equals("Keyboard settings") || label.equals("Configure window access"),
          fn);
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
            int idx = body.indexOfChild(parent);
            View anchor = idx > 0 ? body.getChildAt(idx - 1) : parent;
            scroll.smoothScrollTo(0, Math.max(0, anchor.getTop() - d(8)));
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
    footerDivider = divider(root, 0);
    footer = new LinearLayout(this);
    footer.setGravity(Gravity.CENTER_VERTICAL);
    footer.setPadding(d(16), d(8), d(16), d(8));
    root.addView(footer);
    nav = new LinearLayout(this);
    nav.setBackgroundColor(CANVAS);
    root.addView(nav, -1, d(58));
    for (int i = 0; i < tabs.length; i++) {
      int dest = i;
      LinearLayout item = items[i] = column();
      item.setGravity(Gravity.CENTER);
      item.setBackground(ripple(this, CANVAS, 8));
      item.setContentDescription(tabs[i]);
      item.setFocusable(true);
      item.addView(new TabIcon(this, i), d(20), d(20));
      TextView label = text(this, tabs[i], 11, FAINT);
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
    downloadLabel = null;
    downloadBar = null;
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
    footerDivider.setVisibility(setup ? View.GONE : View.VISIBLE);
    footer.setVisibility(setup ? View.VISIBLE : View.GONE);
    updateNav();
    boolean welcome = setup && settings.onboardingPage() == 0;
    header.setVisibility(welcome ? View.GONE : View.VISIBLE);
    scroll.setFillViewport(welcome);
    body.setGravity(welcome ? Gravity.CENTER_VERTICAL : Gravity.TOP);
    if (setup && settings.onboardingPage() > 0) {
      int p = settings.onboardingPage();
      LinearLayout step = row(header);
      Button back = smallButton(this, "‹", () -> page(p - 1));
      back.setContentDescription("Back");
      back.setBackground(ripple(this, 0, 12));
      back.setTextSize(24);
      step.addView(back, d(44), d(44));
      flexible(step, text(this, (p + 1) + " of 3", 13, FAINT));
    }
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
      icon.invalidate();
      ((TextView) items[i].getChildAt(1)).setTextColor(i == tab ? TEXT : FAINT);
    }
  }
  private void divider(LinearLayout box) {
    if (box != group || box.getChildCount() > 0)
      divider(box, 0);
  }
  private View divider(LinearLayout box, int gap) {
    View line = new View(this);
    line.setBackgroundColor(LINE);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(this, .5f)));
    if (box == group)
      lp.setMarginStart(d(16));
    lp.topMargin = d(gap);
    box.addView(line, lp);
    return line;
  }
  private void footerAction(String label, Runnable back, Runnable forward, boolean enabled) {
    if (back != null)
      footer.addView(button(this, "Back", false, back));
    Button next = button(this, label, true, forward);
    next.setEnabled(enabled);
    if (!enabled) {
      next.setBackground(shape(this, LAYER_02, 999, 0));
      next.setTextColor(FAINT);
    }
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
    lp.leftMargin = back == null ? 0 : d(8);
    footer.addView(next, lp);
  }
  private void tour(int p) {
    if (p == 0) {
      View mark = glyph("mark", 64, TEXT);
      centered(body, mark, 64, 64, 16);
      TextView badge = text(this, "100% on device", 13, MUTED);
      badge.setPadding(d(12), 0, d(12), 0);
      badge.setGravity(Gravity.CENTER);
      badge.setBackground(shape(this, SURFACE, 999, 0));
      centered(body, badge, -2, 28, 16);
      TextView hero = text(this, "Talk instead of type,\nin any app.", 28, TEXT);
      hero.setLetterSpacing(-.015f);
      hero.setGravity(Gravity.CENTER);
      hero.setAccessibilityHeading(true);
      add(body, hero, 16);
      String[] props = {
          "Dictate wherever you type", "Nothing leaves your phone", "Your music keeps playing"};
      String[] icons = {"keyboard", "lock", "wave"};
      for (int i = 0; i < props.length; i++) {
        LinearLayout r = row(body, i == 0 ? 24 : 10);
        View icon = glyph(icons[i], 18, TEXT);
        icon.setBackground(shape(this, SURFACE, 11, 0));
        r.addView(icon, d(38), d(38));
        TextView label = text(this, props[i], 15, TEXT);
        label.setTypeface(Typeface.create("sans-serif-medium", 0));
        r.addView(label, afterControl());
      }
      DotScopeView scope = new DotScopeView(this);
      scope.setDrawPlate(false);
      scope.setDemoMode(true);
      scope.setBackground(shape(this, LAYER_01, 999, 0));
      centered(body, scope, -1, 48, 20);
      footerAction("Get started", null, () -> page(1), true);
    } else if (p == 1) {
      title("Set up Hex", "Three things make local dictation possible.");
      checklist();
      footerAction(
          "Try Hex", null, () -> page(2), mic() && HexBubbleService.active != null && model());
    } else {
      title("Try Hex", "Tap the scratchpad to show the floating mic, then dictate a sentence.");
      scratchpad(false);
      footerAction("Start using Hex", null, () -> {
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
    if (parent == group)
      r.setPadding(d(16), d(8), d(16), d(8));
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
  private void centered(LinearLayout box, View v, int w, int h, int gap) {
    LinearLayout.LayoutParams lp =
        new LinearLayout.LayoutParams(w < 0 ? w : d(w), h < 0 ? h : d(h));
    lp.gravity = Gravity.CENTER_HORIZONTAL;
    lp.topMargin = d(gap);
    box.addView(v, lp);
  }
  private View glyph(String name, int size, int color) {
    return new View(this) {
      final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
      protected void onDraw(Canvas c) {
        float side = d(size);
        if (name.equals("mark")) {
          drawHexMark(c, getWidth() / 2f, getHeight() / 2f, side, TEXT, MIC);
          return;
        }
        c.save();
        c.translate((getWidth() - side) / 2, (getHeight() - side) / 2);
        c.scale(side / 20, side / 20);
        ink.setColor(color);
        ink.setStrokeWidth(1.7f);
        ink.setStrokeCap(Paint.Cap.ROUND);
        ink.setStyle(Paint.Style.STROKE);
        switch (name) {
          case "done":
            ink.setStyle(Paint.Style.FILL);
            c.drawCircle(10, 10, 9, ink);
            ink.setColor(CANVAS);
            ink.setStyle(Paint.Style.STROKE);
          case "check":
            c.drawLine(4, 10, 8, 14, ink);
            c.drawLine(8, 14, 16, 6, ink);
            break;
          case "pending":
            c.drawCircle(10, 10, 8.2f, ink);
            break;
          case "link":
            c.drawLine(7, 4, 13, 10, ink);
            c.drawLine(13, 10, 7, 16, ink);
            break;
          case "external":
            c.drawLine(4, 16, 16, 4, ink);
            c.drawLine(7, 4, 16, 4, ink);
            c.drawLine(16, 4, 16, 13, ink);
            break;
          case "menu":
            c.drawLine(6, 7, 10, 3, ink);
            c.drawLine(10, 3, 14, 7, ink);
            c.drawLine(6, 13, 10, 17, ink);
            c.drawLine(10, 17, 14, 13, ink);
            break;
          case "keyboard":
            c.drawRoundRect(1, 4, 19, 16, 2, 2, ink);
            for (int j = 0; j < 4; j++) c.drawPoint(4 + j * 4, 8, ink);
            c.drawLine(6, 12, 14, 12, ink);
            break;
          case "wave":
            for (int j = 0; j < 5; j++) {
              int h = 8 - 3 * Math.abs(2 - j);
              c.drawLine(2 + j * 4, 10 - h, 2 + j * 4, 10 + h, ink);
            }
            break;
          case "lock":
            c.drawArc(6, 2, 14, 12, 180, 180, false, ink);
            c.drawRoundRect(3, 8, 17, 18, 2, 2, ink);
            break;
        }
        c.restore();
      }
    };
  }
  private LinearLayout labels(String title, String detail) {
    LinearLayout v = column();
    add(v, text(this, title, 15, TEXT), 0);
    if (detail != null)
      add(v, text(this, detail, 12, MUTED), 3);
    return v;
  }
  private void setupRow(String name, String detail, boolean done, String action, Runnable run) {
    divider(group);
    LinearLayout r = row(group);
    r.setMinimumHeight(d(52));
    r.addView(glyph(done ? "done" : "pending", 18, done ? SUCCESS : FAINT), d(18), d(18));
    LinearLayout v = labels(name, detail);
    v.setPadding(d(10), 0, d(6), 0);
    flexible(r, v);
    if (action != null)
      r.addView(smallButton(this, action, run));
  }
  private void checklist() {
    boolean microphone = mic(), service = HexBubbleService.active != null, installed = model();
    readiness = readyKey();
    card(settings.onboardingCompleted() ? "Dictation setup" : "");
    setupRow("Microphone", microphone ? "Allowed" : "Permission needed", microphone,
        microphone ? null : "Allow",
        () -> requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, 1));
    setupRow("Accessibility", service ? "Connected" : "Disconnected", service,
        service ? null : "Settings", this::accessibility);
    if (installed) {
      setupRow("Speech model", "Parakeet TDT · Ready on device", true, null, null);
      return;
    }
    divider(group);
    LinearLayout r = row(group);
    r.setMinimumHeight(d(62));
    r.addView(glyph("pending", 18, FAINT), d(18), d(18));
    LinearLayout stack = labels("Speech model", null);
    stack.setPadding(d(10), d(6), d(6), d(6));
    flexible(r, stack);
    downloadLabel = text(this, progress.error != null ? progress.error :
        "Parakeet TDT (~639 MB) · Downloads once for offline dictation", 12,
        progress.error != null ? DANGER : MUTED);
    add(stack, downloadLabel, 3);
    if (progress.active) {
      downloadBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
      downloadBar.setBackground(shape(this, 0x1cffffff, 2, 0));
      downloadBar.setProgressDrawable(new android.graphics.drawable.ScaleDrawable(
          shape(this, SUCCESS, 2, 0), Gravity.LEFT, 1f, -1f));
      downloadBar.setMax(1000);
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, d(3));
      lp.topMargin = d(4);
      stack.addView(downloadBar, lp);
      updateDownload();
      r.addView(smallButton(this, "Cancel", downloader::cancel));
    } else {
      r.addView(smallButton(this, "Download", () -> downloader.start(getApplicationContext())));
      TextView fallback = text(this, "Import files", 12, TEXT);
      fallback.setOnClickListener(v -> importModel());
      add(stack, fallback, 4);
    }
  }
  private void updateDownload() {
    if (downloadBar == null) return;
    downloadLabel.setText(progress.detail);
    downloadBar.setProgress(Math.round(progress.fraction * 1000));
  }
  private void importModel() {
    startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), 21);
  }
  private void linkRow(String name, boolean external, Runnable run) {
    actionRow(name, null, null, external ? "external" : "link", run);
  }
  private void menuRow(String name, String detail, String value, Runnable run) {
    actionRow(name, detail, value, "menu", run);
  }
  private void actionRow(String name, String detail, String value, String icon, Runnable run) {
    divider(group);
    LinearLayout r = row(group);
    r.setMinimumHeight(d(value == null ? 50 : 48));
    r.setBackground(ripple(this, SURFACE, 0));
    flexible(r, labels(name, detail));
    if (value != null)
      r.addView(text(this, value, 15, MUTED));
    r.addView(glyph(icon, 13, MUTED), afterIcon());
    r.setOnClickListener(v -> run.run());
  }
  private LinearLayout.LayoutParams afterIcon() {
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(d(13), d(16));
    lp.setMarginStart(d(6));
    return lp;
  }
  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request == 22 && result == RESULT_OK && data != null && data.getData() != null) {
      Uri dest = data.getData();
      Context app = getApplicationContext();
      new Thread(() -> {
        anomaly.hex.android.keyboard.TypingTelemetryLog log =
            anomaly.hex.android.keyboard.TypingTelemetryLog.get(app);
        log.flushSync();
        File src = log.file();
        boolean ok = false;
        if (src.isFile()) {
          try (InputStream in = new java.io.FileInputStream(src);
               java.io.OutputStream out = app.getContentResolver().openOutputStream(dest)) {
            if (out != null) {
              byte[] buf = new byte[8192];
              int n;
              while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
              out.flush();
              ok = true;
            }
          } catch (Exception ignored) {}
        }
        final boolean saved = ok;
        runOnUiThread(() -> Toast.makeText(this,
            saved ? "Saved typing telemetry log" : "Nothing to export yet", Toast.LENGTH_SHORT).show());
      }, "hex-telemetry-export").start();
      return;
    }
    if (request != 21 || result != RESULT_OK || data == null)
      return;
    Set<Uri> files = new LinkedHashSet<>();
    if (data.getData() != null)
      files.add(data.getData());
    ClipData clips = data.getClipData();
    if (clips != null)
      for (int i = 0; i < clips.getItemCount(); i++) files.add(clips.getItemAt(i).getUri());
    Context app = getApplicationContext();
    new Thread(() -> {
      int imported = 0;
      for (Uri uri : files) {
        if (uri == null || !"content".equals(uri.getScheme()))
          continue;
        try (Cursor names = app.getContentResolver().query(
                 uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
          if (names == null || !names.moveToFirst())
            continue;
          String name = names.getString(0);
          try (InputStream stream = app.getContentResolver().openInputStream(uri)) {
            if (stream == null)
              continue;
            ModelStore.importModelFile(app, name, stream);
            imported++;
          }
        } catch (IOException | RuntimeException unavailable) { /* Count only successful copies. */
        }
      }
      String message = imported + " imported · " + ModelStore.missingFiles(app).size() + " missing";
      runOnUiThread(() -> {
        Toast.makeText(app, message, Toast.LENGTH_LONG).show();
        if (isDestroyed() || isFinishing())
          return;
        modelAvailable = ModelStore.inspect(this).state != ModelStore.State.MISSING;
        render();
      });
    }, "hex-model-import").start();
  }
  private void copy(String words) {
    getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Hex", words));
  }
  private void inset(View view) {
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.setMargins(d(16), d(10), d(16), d(4));
    group.addView(view, lp);
  }
  private void scratchpad(boolean actions) {
    card("Scratchpad");
    practice = input(this, scratchpadHint());
    practice.setInputType(android.text.InputType.TYPE_CLASS_TEXT
        | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    practice.setGravity(Gravity.TOP | Gravity.START);
    practice.setMinHeight(d(110));
    practice.setText(scratch);
    practice.addTextChangedListener(new android.text.TextWatcher() {
      @Override
      public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
      @Override
      public void onTextChanged(CharSequence s, int start, int before, int count) {}
      @Override
      public void afterTextChanged(android.text.Editable s) {
        scratch = s == null ? "" : s.toString();
        handler.removeCallbacks(persistDraft);
        draftPending = true;
        handler.postDelayed(persistDraft, 400);
      }
    });
    inset(practice);
    if (actions) {
      LinearLayout r = row(group, 10);
      r.addView(smallButton(this, "Rewrite", this::openScratchpadRewrite));
      r.addView(smallButton(this, "Copy", () -> copy(practice.getText().toString())), afterControl());
      r.addView(smallButton(this, "Clear", () -> { practice.setText(""); }), afterControl());
    }
  }
  private void openScratchpadRewrite() {
    String draft = practice == null ? "" : practice.getText().toString().trim();
    if (draft.isEmpty()) {
      Toast.makeText(this, "Type or dictate a message in Scratchpad first", Toast.LENGTH_SHORT).show();
      return;
    }
    ScrollView modalScroll = new ScrollView(this);
    LinearLayout box = new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(d(20), d(18), d(20), d(18));
    modalScroll.addView(box);

    LinearLayout hdr = new LinearLayout(this);
    hdr.setOrientation(LinearLayout.HORIZONTAL);
    hdr.setGravity(Gravity.CENTER_VERTICAL);
    box.addView(hdr, new LinearLayout.LayoutParams(-1, -2));

    TextView title = text(this, "Rewrite", 19, TEXT);
    title.setTypeface(Typeface.create("sans-serif-medium", 0));
    hdr.addView(title);

    TextView statusBadge = text(this, "Working…", 11, MUTED);
    statusBadge.setPadding(d(9), d(3), d(9), d(3));
    statusBadge.setBackground(shape(this, 0x16ffffff, 999, 0));
    LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(-2, -2);
    badgeLp.leftMargin = d(10);
    hdr.addView(statusBadge, badgeLp);

    // Optional prompt row
    LinearLayout promptRow = new LinearLayout(this);
    promptRow.setOrientation(LinearLayout.HORIZONTAL);
    promptRow.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout.LayoutParams prLp = new LinearLayout.LayoutParams(-1, -2);
    prLp.topMargin = d(12);
    box.addView(promptRow, prLp);

    EditText instructionInput = input(this, "Optional instruction (e.g. shorter, friendly)…");
    instructionInput.setSingleLine();
    promptRow.addView(instructionInput, new LinearLayout.LayoutParams(0, -2, 1));

    LinearLayout cardsContainer = new LinearLayout(this);
    cardsContainer.setOrientation(LinearLayout.VERTICAL);
    LinearLayout.LayoutParams ccLp = new LinearLayout.LayoutParams(-1, -2);
    ccLp.topMargin = d(12);
    box.addView(cardsContainer, ccLp);

    AlertDialog dialog = new AlertDialog.Builder(this).setView(modalScroll).create();
    final long[] gen = {0L};
    final Runnable[] runFetch = new Runnable[1];

    Button goBtn = smallButton(this, "Run", () -> {
      if (runFetch[0] != null) runFetch[0].run();
    });
    promptRow.addView(goBtn, afterControl());

    // Quick style chips
    HorizontalScrollView chipScroll = new HorizontalScrollView(this);
    chipScroll.setHorizontalScrollBarEnabled(false);
    LinearLayout chipStrip = new LinearLayout(this);
    chipStrip.setOrientation(LinearLayout.HORIZONTAL);
    String[][] presets = {
        {"Fix & polish", ""},
        {"Shorter", "Make this much shorter and punchier while keeping the key point"},
        {"Friendly", "Rewrite in a friendly, casual, approachable tone"},
        {"Executive", "Rewrite in a crisp, confident executive style"},
        {"Bullets", "Rewrite as clear, scannable bullet points"},
        {"Research", "Research the facts using web search and weave them into a helpful reply"}
    };
    for (String[] preset : presets) {
      TextView chip = text(this, preset[0], 12, TEXT);
      chip.setPadding(d(10), d(5), d(10), d(5));
      chip.setBackground(ripple(this, 0x16ffffff, 999));
      chip.setOnClickListener(v -> {
        instructionInput.setText(preset[1]);
        if (runFetch[0] != null) runFetch[0].run();
      });
      LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
      clp.rightMargin = d(6);
      chipStrip.addView(chip, clp);
    }
    chipScroll.addView(chipStrip);
    LinearLayout.LayoutParams csLp = new LinearLayout.LayoutParams(-1, -2);
    csLp.topMargin = d(8);
    box.addView(chipScroll, 2, csLp);

    runFetch[0] = () -> {
      long ticket = ++gen[0];
      String inst = instructionInput.getText() == null ? "" : instructionInput.getText().toString().trim();
      String effectiveInst = inst.isEmpty() ? null : inst;
      boolean a11yReady = HexBubbleService.isScreenCaptureReady();

      statusBadge.setText(a11yReady ? "Reading screen…" : "Working…");
      statusBadge.setTextColor(MUTED);
      statusBadge.setOnClickListener(null);
      cardsContainer.removeAllViews();

      TextView loading = text(this,
          effectiveInst != null ? "Running rewrite prompt…" : "Crafting rewrite options…",
          14, MUTED);
      loading.setPadding(d(12), d(14), d(12), d(14));
      loading.setBackground(shape(this, SURFACE, 12, 0x1AFFFFFF));
      cardsContainer.addView(loading, new LinearLayout.LayoutParams(-1, -2));

      HexBubbleService.captureScreenForKeyboard(screen -> {
        if (ticket != gen[0] || isFinishing() || isDestroyed() || !dialog.isShowing()) {
          if (screen != null && !screen.isRecycled()) screen.recycle();
          return;
        }
        KeyboardRewriteClient.rewrite(this, draft, effectiveInst, screen, new KeyboardRewriteClient.Callback() {
          @Override
          public void onOptions(List<KeyboardRewriteClient.Option> options, boolean usedScreen, boolean usedWeb) {
            if (ticket != gen[0] || isFinishing() || isDestroyed() || !dialog.isShowing()) return;
            boolean needsA11y = !usedScreen && !HexBubbleService.isScreenCaptureReady();
            String badge = usedScreen
                ? (usedWeb ? "Screen · Web" : "Screen context")
                : (needsA11y
                    ? (usedWeb ? "Web · Enable screen ›" : "Enable screen ›")
                    : (usedWeb ? "Web" : "Draft only"));
            statusBadge.setText(badge);
            statusBadge.setTextColor((usedScreen || usedWeb || needsA11y) ? MIC : MUTED);
            statusBadge.setOnClickListener(needsA11y ? v -> {
              dialog.dismiss();
              accessibility();
            } : null);

            cardsContainer.removeAllViews();
            if (needsA11y) {
              LinearLayout banner = new LinearLayout(HexHomeActivity.this);
              banner.setOrientation(LinearLayout.VERTICAL);
              banner.setPadding(d(12), d(10), d(12), d(10));
              banner.setBackground(ripple(HexHomeActivity.this, SURFACE, 12));
              banner.setOnClickListener(v -> {
                dialog.dismiss();
                accessibility();
              });
              LinearLayout top = new LinearLayout(HexHomeActivity.this);
              top.setGravity(Gravity.CENTER_VERTICAL);
              TextView bt = text(HexHomeActivity.this, "Screen context is off", 13, MIC);
              bt.setTypeface(Typeface.create("sans-serif-medium", 0));
              top.addView(bt, new LinearLayout.LayoutParams(0, -2, 1));
              TextView go = text(HexHomeActivity.this, "Enable ›", 12, TEXT);
              go.setPadding(d(9), d(2), d(9), d(2));
              go.setBackground(shape(HexHomeActivity.this, 0x22ffffff, 999, 0));
              top.addView(go);
              banner.addView(top, new LinearLayout.LayoutParams(-1, -2));
              TextView sub = text(HexHomeActivity.this,
                  "Turn on Hex Accessibility so Rewrite can read the screen.", 12, MUTED);
              LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
              slp.topMargin = d(4);
              banner.addView(sub, slp);
              LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
              blp.bottomMargin = d(8);
              cardsContainer.addView(banner, blp);
            }

            for (int i = 0; i < options.size(); i++) {
              KeyboardRewriteClient.Option opt = options.get(i);
              LinearLayout card = new LinearLayout(HexHomeActivity.this);
              card.setOrientation(LinearLayout.VERTICAL);
              card.setPadding(d(12), d(10), d(12), d(10));
              card.setBackground(ripple(HexHomeActivity.this, SURFACE, 12));
              card.setOnClickListener(v -> {
                if (practice != null) {
                  practice.setText(opt.text);
                  practice.setSelection(opt.text.length());
                }
                Toast.makeText(HexHomeActivity.this, "Applied: " + opt.label, Toast.LENGTH_SHORT).show();
                dialog.dismiss();
              });

              LinearLayout top = new LinearLayout(HexHomeActivity.this);
              top.setGravity(Gravity.CENTER_VERTICAL);
              TextView tag = text(HexHomeActivity.this, opt.label, 12, MIC);
              tag.setTypeface(Typeface.create("sans-serif-medium", 0));
              top.addView(tag, new LinearLayout.LayoutParams(0, -2, 1));
              top.addView(text(HexHomeActivity.this, "Tap to apply", 11, MUTED));
              card.addView(top, new LinearLayout.LayoutParams(-1, -2));

              TextView bodyTxt = text(HexHomeActivity.this, opt.text, 14, TEXT);
              LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
              tlp.topMargin = d(4);
              card.addView(bodyTxt, tlp);

              LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
              if (i > 0) clp.topMargin = d(8);
              cardsContainer.addView(card, clp);
            }
          }

          @Override
          public void onError(String message) {
            if (ticket != gen[0] || isFinishing() || isDestroyed() || !dialog.isShowing()) return;
            statusBadge.setText("Error");
            statusBadge.setTextColor(MUTED);
            cardsContainer.removeAllViews();
            TextView err = text(HexHomeActivity.this,
                message == null || message.isBlank() ? "Couldn’t rewrite right now." : message,
                13, TEXT);
            err.setPadding(d(12), d(12), d(12), d(12));
            err.setBackground(shape(HexHomeActivity.this, SURFACE, 12, 0x1AFFFFFF));
            cardsContainer.addView(err, new LinearLayout.LayoutParams(-1, -2));
          }
        });
      });
    };

    LinearLayout footer = new LinearLayout(this);
    footer.setOrientation(LinearLayout.HORIZONTAL);
    footer.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, -2);
    flp.topMargin = d(16);
    box.addView(footer, flp);

    Button retryBtn = smallButton(this, "Regenerate", () -> {
      if (runFetch[0] != null) runFetch[0].run();
    });
    footer.addView(retryBtn, new LinearLayout.LayoutParams(-2, d(36)));

    View sp = new View(this);
    footer.addView(sp, new LinearLayout.LayoutParams(0, 1, 1));

    Button closeBtn = smallButton(this, "Close", dialog::dismiss);
    footer.addView(closeBtn, new LinearLayout.LayoutParams(-2, d(36)));

    dialog.setOnDismissListener(d -> gen[0]++);
    dialog.show();
    runFetch[0].run();
  }
  private void general() {
    LinearLayout r = row(header);
    r.addView(glyph("mark", 20, TEXT), d(20), d(20));
    TextView name = text(this, "Hex", 20, TEXT);
    name.setPadding(d(8), 0, 0, 0);
    flexible(r, name);
    int steps = (mic() ? 0 : 1) + (model() ? 0 : 1) + (HexBubbleService.active != null ? 0 : 1);
    TextView badge =
        text(this, steps == 0 ? "● Ready" : "● " + steps + (steps == 1 ? " step" : " steps"), 13,
            steps == 0 ? SUCCESS : MIC);
    badge.setPadding(d(10), 0, d(10), 0);
    badge.setGravity(Gravity.CENTER);
    badge.setBackground(shape(this, 0x12ffffff, 999, 0));
    r.addView(badge, -2, d(28));
    checklist();
    scratchpad(true);
    act("Dictate & copy", false,
        () -> startActivity(new Intent(this, CopyDictationActivity.class)));
    card("Audio & language");
    SpeechLanguage language = new SpeechLanguage(this);
    menuRow("Speech language", null, languageLabel(language.selectedId()),
        () -> pickLanguage(language));
    toggleRow("Always use phone microphone", "Keeps Bluetooth music playing and starts faster",
        settings.preferBuiltInMic(), settings::setPreferBuiltInMic);
    toggleRow("Numbers as digits", "Write spoken English numbers like twenty five as 25.",
        settings.numbersAsDigits(), settings::setNumbersAsDigits);
    keyboardCard();
    card("Feedback");
    toggleRow("Haptic feedback", "Vibrate when dictation starts and stops.",
        settings.hapticsEnabled(), settings::setHapticsEnabled);
    if (labs.isEnabled(LabId.RECOVERY_DRAFTS))
      act("Open recovery drafts", false,
          () -> startActivity(new Intent(this, HexRecoveryActivity.class)));
    card("About");
    act("Privacy policy", false, () -> openLink("https://anoma.ly/hex/privacy"));
    act("Support", false, () -> openLink("https://anoma.ly/hex/support"));
    act("Open-source notices", false, this::showNotices);
  }
  private String scratchpadHint() {
    return KeyboardPresence.isHexKeyboardDefault(this)
        ? "Tap here and press the Hex key to test dictation..."
        : "Tap here and press the floating mic to test dictation...";
  }
  private TextView keyboardStatus;
  @Override
  public void onWindowFocusChanged(boolean focused) {
    super.onWindowFocusChanged(focused);
    if (focused && practice != null && practice.isAttachedToWindow())
      practice.setHint(scratchpadHint());
    if (focused && keyboardStatus != null && keyboardStatus.isAttachedToWindow())
      refreshKeyboardStatus();
  }
  private void refreshKeyboardStatus() {
    ComponentName component = new ComponentName(this, HexInputMethodService.class);
    boolean enabled = getSystemService(InputMethodManager.class)
                          .getEnabledInputMethodList()
                          .stream()
                          .anyMatch(i -> component.equals(i.getComponent()));
    keyboardStatus.setText(KeyboardPresence.isHexKeyboardDefault(this) ? "Active"
            : enabled                                                  ? "Enabled"
                      : "Off (using system keyboard)");
  }
  private void keyboardCard() {
    card("Optional Hex Keyboard");
    KeyboardSettings kb = new KeyboardSettings(this);
    InputMethodManager imm = getSystemService(InputMethodManager.class);
    keyboardStatus = text(this, "", 15, MUTED);
    LinearLayout status = row(group);
    flexible(status, text(this, "Status", 15, TEXT));
    status.addView(keyboardStatus);
    refreshKeyboardStatus();
    act("Keyboard settings", false,
        () -> startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
    act("Switch keyboard", false, imm::showInputMethodPicker);
    segmentedRow("Keyboard", new String[] {"Full", "Dictation bar"}, kb.isFullKeyboard() ? 0 : 1,
        i -> kb.setFullKeyboard(i == 0));
    if (!kb.isFullKeyboard())
      return;
    List<String> codes = new ArrayList<>(List.of("auto"));
    for (KeyboardLanguage lang : KeyboardLanguage.values()) codes.add(lang.code());
    String[] labels =
        codes.stream()
            .map(code
                -> code.equals("auto") ? "Automatic"
                                       : Locale.forLanguageTag(code).getDisplayLanguage())
            .toArray(String[] ::new);
    int index = Math.max(0, codes.indexOf(kb.layoutLanguage()));
    menuRow("Keyboard layout", null, labels[index],
        ()
            -> new AlertDialog.Builder(this)
                .setTitle("Keyboard layout")
                .setSingleChoiceItems(labels, index,
                    (dialog, i) -> {
                      kb.setLayoutLanguage(codes.get(i));
                      dialog.dismiss();
                      render();
                    })
                .show());
    toggleRow(
        "Autocorrect", "Fixes typos when you press space", kb.autocorrect(), kb::setAutocorrect);
    toggleRow(
        "Key pop-ups", "Shows the letter above your finger", kb.keyPopups(), kb::setKeyPopups);
    toggleRow(
        "Keyboard haptics", "A light tap as you press Hex's keys", kb.haptics(), kb::setHaptics);
  }
  private void openLink(String url) {
    try {
      startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    } catch (ActivityNotFoundException e) {
      Toast.makeText(this, "No browser available", Toast.LENGTH_SHORT).show();
    }
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
    String[] labels = ids.stream().map(this::languageLabel).toArray(String[] ::new);
    new AlertDialog.Builder(this)
        .setTitle("Speech language")
        .setSingleChoiceItems(labels, ids.indexOf(language.selectedId()),
            (dialog, which) -> {
              language.select(ids.get(which));
              dialog.dismiss();
              render();
            })
        .setNegativeButton("Cancel", null)
        .show();
  }
  private void showNotices() {
    StringBuilder notices = new StringBuilder();
    for (String file : new String[] {"models.txt", "ort-LICENSE.txt", "sherpa-LICENSE.txt"}) {
      notices.append(file).append("\n\n");
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(
               getAssets().open("notices/" + file), java.nio.charset.StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) notices.append(line).append('\n');
      } catch (IOException e) {
        notices.append("Notice unavailable\n");
      }
      notices.append('\n');
    }
    new AlertDialog.Builder(this)
        .setTitle("Open-source notices")
        .setMessage(notices.toString())
        .setPositiveButton("Close", null)
        .show();
  }
  private void toggleRow(
      String label, String summary, boolean value, java.util.function.Consumer<Boolean> changed) {
    divider(group);
    LinearLayout r = row(group);
    r.setMinimumHeight(d(50));
    r.setPadding(d(16), d(10), d(16), d(10));
    LinearLayout labels = column();
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
    lp.setMarginEnd(d(12));
    r.addView(labels, lp);
    add(labels, text(this, label, 15, TEXT), 0);
    add(labels, text(this, summary, 12, MUTED), 3);
    FrameLayout toggle = new FrameLayout(this);
    View thumb = new View(this);
    thumb.setBackground(shape(this, Color.WHITE, 999, 0));
    FrameLayout.LayoutParams thumbParams = new FrameLayout.LayoutParams(d(27), d(27), Gravity.LEFT | Gravity.CENTER_VERTICAL);
    thumbParams.leftMargin = d(2);
    toggle.addView(thumb, thumbParams);
    r.addView(toggle, d(51), d(31));
    Runnable paint = () -> {
      boolean on = toggle.isSelected();
      toggle.setBackground(shape(this, on ? SUCCESS : 0xff393c43, 15.5f, 0));
      r.setStateDescription(on ? "On" : "Off");
    };
    toggle.setSelected(value);
    paint.run();
    thumb.setTranslationX(value ? d(20) : 0);
    r.setContentDescription(label + ". " + summary);
    r.setAccessibilityDelegate(new View.AccessibilityDelegate() {
      @Override public void onInitializeAccessibilityNodeInfo(View v, android.view.accessibility.AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(v, info);
        info.setClassName("android.widget.Switch");
        info.setCheckable(true);
        info.setChecked(toggle.isSelected());
      }
    });
    labels.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    toggle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    r.setFocusable(true);
    r.setOnClickListener(v -> {
      boolean on = !toggle.isSelected();
      toggle.setSelected(on);
      paint.run();
      thumb.animate().translationX(on ? d(20) : 0).setDuration(150).start();
      changed.accept(on);
    });
  }
  private void segmentedRow(
      String title, String[] options, int selected, java.util.function.IntConsumer select) {
    divider(group);
    LinearLayout r = row(group);
    r.setMinimumHeight(d(50));
    flexible(r, text(this, title, 15, TEXT));
    LinearLayout well = new LinearLayout(this);
    well.setPadding(d(2), d(2), d(2), d(2));
    well.setBackground(shape(this, 0x0effffff, 9, 0));
    r.addView(well, -2, d(32));
    for (int i = 0; i < options.length; i++) {
      int index = i;
      Button b = smallButton(this, options[i], () -> {
        select.accept(index);
        render();
      });
      b.setSelected(i == selected);
      b.setBackground(ripple(this, i == selected ? 0x24ffffff : 0, 7));
      well.addView(b, -2, -1);
    }
  }
  private void radioRow(String label, String summary, boolean checked, Runnable select) {
    divider(group);
    LinearLayout r = row(group);
    r.setMinimumHeight(d(50));
    flexible(r, labels(label, summary));
    r.setSelected(checked);
    if (checked)
      r.addView(glyph("check", 16, TEXT), d(16), d(16));
    r.setBackground(ripple(this, SURFACE, 0));
    r.setOnClickListener(v -> {
      select.run();
      render();
    });
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
    toggleRow(
        "Custom vocabulary", "Spell names and terms your way", vocabulary.isEnabled(), enabled -> {
          vocabulary.setEnabled(enabled);
          render();
        });
    if (vocabulary.isEnabled())
      vocabularyEditor();
    card("Dictation learning");
    DictationFixStore fixes = new DictationFixStore(this);
    toggleRow("Learn from dictation fixes", "Remembers single-word edits after two sightings",
        fixes.isEnabled(), enabled -> {
          fixes.setEnabled(enabled);
          render();
        });
    var active = new TreeMap<>(fixes.activeFixes());
    active.forEach((heard, preferred) -> {
      divider(group);
      LinearLayout r = row(group);
      flexible(r, text(this, heard + " → " + preferred, 15, TEXT));
      Button remove = button(this, "Remove", false, () -> {
        fixes.removeFix(heard);
        render();
      });
      remove.setContentDescription("Remove fix for " + heard);
      r.addView(remove);
    });
    if (!active.isEmpty())
      act("Clear learned fixes", false,
          ()
              -> new AlertDialog.Builder(this)
                  .setMessage("Delete all learned fixes?")
                  .setNegativeButton("Cancel", null)
                  .setPositiveButton("Delete",
                      (dialog, which) -> {
                        fixes.clearAll();
                        render();
                      })
                  .show());
  }
  private void vocabularyEditor() {
    divider(group);
    LinearLayout form = row(group, 10);
    ((LinearLayout.LayoutParams) form.getLayoutParams()).bottomMargin = d(8);
    EditText entry = input(this, "New term");
    entry.setSingleLine();
    flexible(form, entry);
    form.addView(smallButton(this, "Add", () -> {
      if (vocabulary.addTerm(entry.getText().toString()))
        render();
      else
        entry.setError("Enter a new term within the vocabulary limit");
    }), afterControl());
    for (String term : vocabulary.terms()) {
      LinearLayout r = row(group, 6);
      r.setMinimumHeight(d(36));
      r.setPadding(d(12), d(4), d(5), d(4));
      r.setBackground(shape(this, LAYER_01, 8.5f, 0));
      LinearLayout.LayoutParams pill = (LinearLayout.LayoutParams) r.getLayoutParams();
      pill.setMargins(d(16), d(6), d(16), d(4));
      flexible(r, text(this, term, 15, TEXT));
      Button remove = smallButton(this, "✕", () -> {
        vocabulary.removeTerm(term);
        render();
      });
      remove.setPadding(0, 0, 0, 0);
      remove.setContentDescription("Remove " + term);
      remove.setBackground(ripple(this, LAYER_02, 999));
      r.addView(remove, d(26), d(26));
    }
  }
  private void history() {
    title("History", "Dictations confirmed in the text field.");
    card("Keep dictations");
    for (HistoryRetention r : HistoryRetention.values())
      radioRow(
          r.label(), null, r == settings.historyRetention(), () -> settings.setHistoryRetention(r));
    history.pruneExpired(settings.historyRetention());
    int recovered = anomaly.hex.android.speech.RecordingStore.recoverInterrupted(this, history, settings.style());
    if (recovered > 0) {
      Toast.makeText(this, "Recovered " + recovered + " interrupted recording" + (recovered > 1 ? "s" : ""), Toast.LENGTH_SHORT).show();
    }
    card("Recent dictations");
    EditText search = input(this, "Search dictations…");
    search.setSingleLine();
    search.setText(query);
    inset(search);
    LinearLayout results = column();
    inset(results);
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
      Button clear = smallButton(this, "Clear history",
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
      LinearLayout actions = row(group);
      actions.addView(clear);
    }
  }
  private void historyResults(LinearLayout results) {
    results.removeAllViews();
    var rows = history.list(query, 100);
    if (rows.isEmpty()) {
      TextView empty = text(this, "No matching dictations.", 15, MUTED);
      empty.setPadding(0, 0, 0, d(12));
      add(results, empty, 8);
    }
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
      String dur = e.durationMs >= 1000 ? " · " + formatDuration(e.durationMs) : "";
      flexible(r, text(this, date + dur + " · " + TranscriptStyle.fromId(e.style).label(), 13, FAINT));
      if (e.audioPath != null && new File(e.audioPath).isFile()) {
        Button retranscribe = smallButton(this, "Re-transcribe", () -> {});
        retranscribe.setOnClickListener(v -> {
          retranscribe.setEnabled(false);
          retranscribe.setText("…");
          new Thread(() -> {
            try {
              String raw = anomaly.hex.android.speech.RecordingStore.retranscribeWav(this, new File(e.audioPath));
              if (raw.isEmpty()) throw new IllegalStateException("No speech detected");
              String lang = SpeechLanguage.resolveLocale(new SpeechLanguage(this).selectedId(), java.util.Locale.getDefault()).toLanguageTag();
              var fixStore = new DictationFixStore(this);
              var fixes = fixStore.isEnabled() ? fixStore.activeFixes() : java.util.Map.<String, String>of();
              String formatted = TranscriptFormatter.applyPipeline(
                  raw, TranscriptStyle.fromId(e.style), new CustomVocabulary(this).terms(),
                  labs.isEnabled(LabId.SPOKEN_CLEANUP), "", fixes, lang, settings.numbersAsDigits()).trim();
              history.updateTranscript(e.id, raw, formatted);
              runOnUiThread(() -> historyResults(results));
            } catch (Exception ex) {
              runOnUiThread(() -> {
                retranscribe.setEnabled(true);
                retranscribe.setText("Re-transcribe");
                Toast.makeText(this, "Re-transcribe failed: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
              });
            }
          }, "hex-retranscribe").start();
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-2, -2);
        rlp.rightMargin = d(6);
        r.addView(retranscribe, rlp);
      }
      r.addView(smallButton(this, "Copy", () -> copy(e.finalText)));
    }
  }
  private static String formatDuration(long ms) {
    long totalSec = Math.max(1, ms / 1000);
    return (totalSec / 60) + ":" + String.format(java.util.Locale.US, "%02d", totalSec % 60);
  }
  private void configureAnywhereKey() {
    SharedPreferences prefs = getSharedPreferences("hex_settings", MODE_PRIVATE);
    String currentGemini = anomaly.hex.android.anywhere.TypeAnywhereAiClient.geminiKey(this);
    String currentProxy = anomaly.hex.android.anywhere.TypeAnywhereAiClient.proxyToken(this);
    String currentModel = anomaly.hex.android.anywhere.TypeAnywhereAiClient.model(this);

    ScrollView scroll = new ScrollView(this);
    LinearLayout box = new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(d(20), d(18), d(20), d(18));
    scroll.addView(box);

    TextView heading = text(this, "Gemini API key", 19, TEXT);
    box.addView(heading);

    TextView note = new TextView(this);
    note.setText("Encrypted in Android Keystore. Screenshots and prompts go directly to Google Gemini — Hex never sees your key or screen.\n\nTip: Restrict the key to Generative Language API and set a monthly budget cap in Google Cloud.");
    note.setTextSize(13f);
    note.setTextColor(MUTED);
    note.setLineSpacing(d(2), 1.15f);
    LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, -2);
    nlp.topMargin = d(8);
    nlp.bottomMargin = d(12);
    box.addView(note, nlp);

    Button getKeyBtn = button(this, "Get API key in Google AI Studio ↗", false, () -> {
      try {
        startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://aistudio.google.com/apikey")));
      } catch (Exception ignored) {}
    });
    LinearLayout.LayoutParams gkp = new LinearLayout.LayoutParams(-1, d(40));
    gkp.bottomMargin = d(12);
    box.addView(getKeyBtn, gkp);

    TextView keyLbl = text(this, "API KEY", 11, MUTED);
    box.addView(keyLbl);
    EditText keyInput = input(this, currentGemini.isEmpty()
        ? "Paste Gemini API key (AIza…)"
        : "Saved: " + anomaly.hex.android.core.SecretStore.mask(currentGemini) + " (leave blank to keep)");
    keyInput.setSingleLine();
    keyInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
    LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(-1, -2);
    klp.topMargin = d(6);
    klp.bottomMargin = d(12);
    box.addView(keyInput, klp);

    TextView modelLbl = text(this, "MODEL", 11, MUTED);
    box.addView(modelLbl);
    EditText modelInput = input(this, anomaly.hex.android.anywhere.GeminiDirectClient.DEFAULT_MODEL);
    modelInput.setSingleLine();
    modelInput.setText(currentModel);
    LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, -2);
    mlp.topMargin = d(6);
    mlp.bottomMargin = d(10);
    box.addView(modelInput, mlp);

    TextView testStatus = new TextView(this);
    testStatus.setTextSize(13f);
    testStatus.setPadding(0, d(6), 0, d(4));
    testStatus.setVisibility(View.GONE);

    final Button[] testBtnHolder = new Button[1];
    Button testBtn = button(this, "Test Gemini key (free)", false, () -> {
      String typed = keyInput.getText().toString().trim();
      String candidate = typed.isEmpty() ? currentGemini : typed;
      String candidateModel = modelInput.getText().toString().trim();
      testBtnHolder[0].setEnabled(false);
      testStatus.setVisibility(View.VISIBLE);
      testStatus.setTextColor(MUTED);
      testStatus.setText("Checking key with Google…");
      new Thread(() -> {
        var res = anomaly.hex.android.anywhere.GeminiDirectClient.testKey(candidate, candidateModel);
        runOnUiThread(() -> {
          testBtnHolder[0].setEnabled(true);
          testStatus.setTextColor(res.ok ? SUCCESS : DANGER);
          testStatus.setText((res.ok ? "✓ " : "✗ ") + res.message);
        });
      }, "hex-key-test").start();
    });
    testBtnHolder[0] = testBtn;
    box.addView(testBtn, new LinearLayout.LayoutParams(-1, d(40)));
    box.addView(testStatus);

    LinearLayout advBox = new LinearLayout(this);
    advBox.setOrientation(LinearLayout.VERTICAL);
    advBox.setVisibility(currentProxy.isEmpty() ? View.GONE : View.VISIBLE);

    TextView advToggle = text(this,
        (advBox.getVisibility() == View.VISIBLE ? "▾ " : "▸ ") + "Advanced: Custom proxy (optional)", 13, MUTED);
    advToggle.setPadding(0, d(14), 0, d(6));
    advToggle.setOnClickListener(v -> {
      boolean show = advBox.getVisibility() != View.VISIBLE;
      advBox.setVisibility(show ? View.VISIBLE : View.GONE);
      advToggle.setText((show ? "▾ " : "▸ ") + "Advanced: Custom proxy (optional)");
    });
    box.addView(advToggle);

    EditText endpoint = input(this, anomaly.hex.android.anywhere.TypeAnywhereAiClient.DEFAULT_ENDPOINT);
    endpoint.setSingleLine();
    endpoint.setText(anomaly.hex.android.anywhere.TypeAnywhereAiClient.customEndpoint(this));
    LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(-1, -2);
    elp.bottomMargin = d(8);
    advBox.addView(endpoint, elp);

    EditText proxyToken = input(this, currentProxy.isEmpty()
        ? "Proxy bearer token (if Gemini key is empty)"
        : "Saved: " + anomaly.hex.android.core.SecretStore.mask(currentProxy));
    proxyToken.setSingleLine();
    proxyToken.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
    advBox.addView(proxyToken, new LinearLayout.LayoutParams(-1, -2));
    box.addView(advBox);

    AlertDialog dialog = new AlertDialog.Builder(this).setView(scroll).create();

    LinearLayout footer = new LinearLayout(this);
    footer.setOrientation(LinearLayout.HORIZONTAL);
    footer.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, -2);
    flp.topMargin = d(18);
    box.addView(footer, flp);

    if (!currentGemini.isEmpty() || !currentProxy.isEmpty()) {
      Button removeBtn = smallButton(this, "Remove", () -> {
        anomaly.hex.android.anywhere.TypeAnywhereAiClient.setGeminiKey(this, "");
        anomaly.hex.android.anywhere.TypeAnywhereAiClient.setProxyToken(this, "");
        dialog.dismiss();
        render();
      });
      removeBtn.setTextColor(DANGER);
      removeBtn.setBackground(ripple(this, DANGER_SURFACE, 999));
      footer.addView(removeBtn, new LinearLayout.LayoutParams(-2, d(36)));
    }

    View spacer = new View(this);
    footer.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1));

    Button cancelBtn = smallButton(this, "Cancel", dialog::dismiss);
    LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, d(36));
    clp.rightMargin = d(8);
    footer.addView(cancelBtn, clp);

    Button saveBtn = button(this, "Save", true, () -> {
      String newKey = keyInput.getText().toString().trim();
      if (!newKey.isEmpty()) {
        anomaly.hex.android.anywhere.TypeAnywhereAiClient.setGeminiKey(this, newKey);
      }
      String newModel = modelInput.getText().toString().trim();
      anomaly.hex.android.anywhere.TypeAnywhereAiClient.setModel(this, newModel);
      String url = endpoint.getText().toString().trim();
      SharedPreferences.Editor ed = prefs.edit();
      if (url.isEmpty()) ed.remove("type_anywhere_endpoint");
      else ed.putString("type_anywhere_endpoint", url);
      ed.apply();
      String newProxy = proxyToken.getText().toString().trim();
      if (!newProxy.isEmpty()) {
        anomaly.hex.android.anywhere.TypeAnywhereAiClient.setProxyToken(this, newProxy);
      }
      dialog.dismiss();
      render();
    });
    saveBtn.setTextSize(14);
    saveBtn.setHeight(d(36));
    footer.addView(saveBtn, new LinearLayout.LayoutParams(-2, d(36)));

    if (dialog.getWindow() != null) {
      dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }
    dialog.show();
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
          if (lab == LabId.EMOJI_KEYBOARD)
            new KeyboardSettings(this).setEmojiEnabled(on);
          HexBubbleService.refreshLabs();
          render();
        }
      });

      if (lab == LabId.TYPE_ANYWHERE && labs.isEnabled(lab)) {
        anywhereSizeRow();
        act("Customize quick prompts", false, this::configureQuickPrompts);
        act("Open accessibility settings", false, this::accessibility);
        act("Gemini API key · " + anomaly.hex.android.anywhere.TypeAnywhereAiClient.connectionSummary(this),
            false, this::configureAnywhereKey);
      }
      if (lab == LabId.SIGNATURE && labs.isEnabled(lab)) {
        String sig = settings.signatureText();
        act("Signature text · " + (sig.isEmpty() ? "(empty)" : sig), false, this::configureSignatureText);
      }
      if (lab == LabId.SPACEBAR_FIX_TAIL && labs.isEnabled(lab)) {
        KeyboardSettings kb = new KeyboardSettings(this);
        act("Gesture mode · " + kb.spacebarFixMode().label, false, this::configureSpacebarFixMode);
      }
      boolean telemetryActive = lab == LabId.TYPING_TELEMETRY
          && (labs.isExplicitlySet(lab)
              ? labs.isEnabled(lab)
              : (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0);
      if (telemetryActive) {
        anomaly.hex.android.keyboard.TypingTelemetryLog log =
            anomaly.hex.android.keyboard.TypingTelemetryLog.get(this);
        log.flushSync();
        String summary = anomaly.hex.android.keyboard.TypingTelemetryLog.formatStats(log.stats());
        act("Share telemetry log · " + summary, false, this::shareTelemetryLog);
        act("Save telemetry log to Files", false, this::saveTelemetryLog);
        act("Clear telemetry log", false, () -> {
          log.clear();
          Toast.makeText(this, "Typing telemetry cleared", Toast.LENGTH_SHORT).show();
          render();
        });
      }
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
  private void configureSignatureText() {
    android.widget.EditText input = new android.widget.EditText(this);
    input.setSingleLine(true);
    input.setText(settings.signatureText());
    input.setHint(anomaly.hex.android.core.DictationSignature.DEFAULT_TEXT);
    int pad = d(16);
    input.setPadding(pad, pad, pad, pad);
    new AlertDialog.Builder(this)
        .setTitle("Dictation signature")
        .setMessage("Added on its own line after each keyboard dictation. Leave blank to add nothing.")
        .setView(input)
        .setPositiveButton("Save", (d, w) -> {
          String val = input.getText() == null ? "" : input.getText().toString().trim();
          var problem = anomaly.hex.android.core.DictationSignature.textProblem(val);
          if (problem == anomaly.hex.android.core.DictationSignature.Problem.LINE) {
            Toast.makeText(this, "Signature must be a single line", Toast.LENGTH_SHORT).show();
            return;
          }
          if (problem == anomaly.hex.android.core.DictationSignature.Problem.LENGTH) {
            Toast.makeText(this, "Signature must be 200 characters or fewer", Toast.LENGTH_SHORT).show();
            return;
          }
          settings.setSignatureText(val);
          render();
        })
        .setNeutralButton("Reset", (d, w) -> {
          settings.setSignatureText(anomaly.hex.android.core.DictationSignature.DEFAULT_TEXT);
          render();
        })
        .setNegativeButton("Cancel", null)
        .show();
  }
  private void configureSpacebarFixMode() {
    KeyboardSettings kb = new KeyboardSettings(this);
    KeyboardSettings.SpacebarFixMode[] modes = KeyboardSettings.SpacebarFixMode.values();
    String[] labels = new String[modes.length];
    int checked = 0;
    KeyboardSettings.SpacebarFixMode current = kb.spacebarFixMode();
    for (int i = 0; i < modes.length; i++) {
      labels[i] = modes[i].label + (modes[i] == KeyboardSettings.SpacebarFixMode.FLICK_SCRUBBER ? " (Default)" : "");
      if (modes[i] == current) checked = i;
    }
    new AlertDialog.Builder(this)
        .setTitle("Spacebar tail fix behavior")
        .setSingleChoiceItems(labels, checked, (d, which) -> {
          kb.setSpacebarFixMode(modes[which]);
          d.dismiss();
          render();
        })
        .setNegativeButton("Cancel", null)
        .show();
  }
  private void shareTelemetryLog() {
    anomaly.hex.android.keyboard.TypingTelemetryLog log =
        anomaly.hex.android.keyboard.TypingTelemetryLog.get(this);
    log.flushSync();
    if (!log.file().isFile() || log.file().length() == 0) {
      Toast.makeText(this, "No typing telemetry recorded yet — type a few words first", Toast.LENGTH_SHORT).show();
      return;
    }
    Intent share = new Intent(Intent.ACTION_SEND)
        .setType("application/x-ndjson")
        .putExtra(Intent.EXTRA_SUBJECT, "Hex typing telemetry log")
        .putExtra(Intent.EXTRA_STREAM, anomaly.hex.android.keyboard.TypingTelemetryProvider.FILE_URI)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    startActivity(Intent.createChooser(share, "Share Hex typing telemetry"));
  }
  private void saveTelemetryLog() {
    anomaly.hex.android.keyboard.TypingTelemetryLog log =
        anomaly.hex.android.keyboard.TypingTelemetryLog.get(this);
    log.flushSync();
    if (!log.file().isFile() || log.file().length() == 0) {
      Toast.makeText(this, "No typing telemetry recorded yet", Toast.LENGTH_SHORT).show();
      return;
    }
    Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT)
        .addCategory(Intent.CATEGORY_OPENABLE)
        .setType("application/x-ndjson")
        .putExtra(Intent.EXTRA_TITLE, anomaly.hex.android.keyboard.TypingTelemetryLog.FILE_NAME);
    startActivityForResult(save, 22);
  }
  private void anywhereSizeRow() {
    divider(group);
    LinearLayout box = new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(d(16), d(12), d(16), d(12));
    LinearLayout head = row(box);
    flexible(head, text(this, "Button size", 15, TEXT));
    int current = settings.anywhereButtonSizeDp();
    TextView value = text(this, current + " dp", 13, MUTED);
    head.addView(value);

    SeekBar slider = new SeekBar(this);
    slider.setMax(HexSettings.ANYWHERE_SIZE_MAX_DP - HexSettings.ANYWHERE_SIZE_MIN_DP);
    slider.setProgress(current - HexSettings.ANYWHERE_SIZE_MIN_DP);
    slider.setThumbTintList(android.content.res.ColorStateList.valueOf(MIC));
    slider.setProgressTintList(android.content.res.ColorStateList.valueOf(MIC));
    slider.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(BORDER));
    slider.setContentDescription("Type anywhere button size");
    slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
      @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
        int dpSize = HexSettings.ANYWHERE_SIZE_MIN_DP + progress;
        value.setText(dpSize + " dp");
        if (fromUser) {
          settings.setAnywhereButtonSizeDp(dpSize);
          HexBubbleService.refreshLabs();
        }
      }
      @Override public void onStartTrackingTouch(SeekBar s) {}
      @Override public void onStopTrackingTouch(SeekBar s) {}
    });
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.topMargin = d(8);
    box.addView(slider, lp);
    group.addView(box);
  }
  private void configureQuickPrompts() {
    var slots = anomaly.hex.android.anywhere.QuickPromptsStore.load(this);
    ScrollView scroll = new ScrollView(this);
    LinearLayout box = new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(d(20), d(18), d(20), d(18));
    scroll.addView(box);

    box.addView(text(this, "Quick prompt slots", 19, TEXT));
    TextView sub = text(this, "Tap any slot to edit or pick a preset.", 13, MUTED);
    LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
    slp.topMargin = d(4);
    slp.bottomMargin = d(14);
    box.addView(sub, slp);

    AlertDialog dialog = new AlertDialog.Builder(this).setView(scroll).create();

    for (int i = 0; i < slots.size(); i++) {
      final int idx = i;
      var s = slots.get(i);
      LinearLayout item = new LinearLayout(this);
      item.setOrientation(LinearLayout.VERTICAL);
      item.setBackground(ripple(this, LAYER_01, 14));
      item.setPadding(d(14), d(12), d(14), d(12));
      item.setOnClickListener(v -> {
        dialog.dismiss();
        editQuickPromptSlot(idx, s);
      });

      LinearLayout topRow = new LinearLayout(this);
      topRow.setOrientation(LinearLayout.HORIZONTAL);
      topRow.setGravity(Gravity.CENTER_VERTICAL);
      TextView badge = text(this, "SLOT " + (i + 1), 11, MIC);
      topRow.addView(badge);
      View sp = new View(this);
      topRow.addView(sp, new LinearLayout.LayoutParams(0, 1, 1));
      topRow.addView(text(this, "Edit ›", 12, MUTED));
      item.addView(topRow, new LinearLayout.LayoutParams(-1, -2));

      TextView lbl = text(this, s.label, 15, TEXT);
      LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, -2);
      llp.topMargin = d(4);
      item.addView(lbl, llp);

      TextView prm = new TextView(this);
      prm.setText(s.prompt);
      prm.setTextSize(12.5f);
      prm.setTextColor(MUTED);
      prm.setMaxLines(2);
      prm.setEllipsize(android.text.TextUtils.TruncateAt.END);
      LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
      plp.topMargin = d(2);
      item.addView(prm, plp);

      LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-1, -2);
      if (i > 0) ilp.topMargin = d(8);
      box.addView(item, ilp);
    }

    LinearLayout footer = new LinearLayout(this);
    footer.setOrientation(LinearLayout.HORIZONTAL);
    footer.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, -2);
    flp.topMargin = d(16);
    box.addView(footer, flp);

    Button resetBtn = smallButton(this, "Reset defaults", () -> {
      anomaly.hex.android.anywhere.QuickPromptsStore.reset(this);
      Toast.makeText(this, "Quick prompts reset", Toast.LENGTH_SHORT).show();
      dialog.dismiss();
      configureQuickPrompts();
    });
    footer.addView(resetBtn, new LinearLayout.LayoutParams(-2, d(36)));

    View fsp = new View(this);
    footer.addView(fsp, new LinearLayout.LayoutParams(0, 1, 1));

    Button doneBtn = button(this, "Done", true, dialog::dismiss);
    doneBtn.setTextSize(14);
    doneBtn.setHeight(d(36));
    footer.addView(doneBtn, new LinearLayout.LayoutParams(-2, d(36)));

    dialog.show();
  }
  private void editQuickPromptSlot(int index, anomaly.hex.android.anywhere.QuickPromptsStore.Slot current) {
    ScrollView scroll = new ScrollView(this);
    LinearLayout box = new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(d(20), d(18), d(20), d(18));
    scroll.addView(box);

    box.addView(text(this, "Slot " + (index + 1), 19, TEXT));

    TextView lTitle = text(this, "SHORT LABEL", 11, MUTED);
    LinearLayout.LayoutParams ltp = new LinearLayout.LayoutParams(-1, -2);
    ltp.topMargin = d(14);
    box.addView(lTitle, ltp);

    EditText label = input(this, "e.g. Fact-check");
    label.setText(current.label);
    label.setSingleLine();
    LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(-1, -2);
    llp.topMargin = d(6);
    llp.bottomMargin = d(12);
    box.addView(label, llp);

    TextView pTitle = text(this, "PROMPT SENT TO GEMINI", 11, MUTED);
    box.addView(pTitle);

    EditText prompt = input(this, "What should Gemini do with the screen?");
    prompt.setText(current.prompt);
    prompt.setMinLines(3);
    prompt.setGravity(Gravity.TOP | Gravity.START);
    LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-1, -2);
    plp.topMargin = d(6);
    box.addView(prompt, plp);

    AlertDialog dialog = new AlertDialog.Builder(this).setView(scroll).create();

    LinearLayout footer = new LinearLayout(this);
    footer.setOrientation(LinearLayout.HORIZONTAL);
    footer.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, -2);
    flp.topMargin = d(18);
    box.addView(footer, flp);

    Button presetsBtn = smallButton(this, "Presets", () -> {
      dialog.dismiss();
      pickQuickPromptPreset(index);
    });
    footer.addView(presetsBtn, new LinearLayout.LayoutParams(-2, d(36)));

    View sp = new View(this);
    footer.addView(sp, new LinearLayout.LayoutParams(0, 1, 1));

    Button cancelBtn = smallButton(this, "Cancel", () -> {
      dialog.dismiss();
      configureQuickPrompts();
    });
    LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, d(36));
    clp.rightMargin = d(8);
    footer.addView(cancelBtn, clp);

    Button saveBtn = button(this, "Save", true, () -> {
      anomaly.hex.android.anywhere.QuickPromptsStore.save(
          this, index, label.getText().toString(), prompt.getText().toString());
      dialog.dismiss();
      configureQuickPrompts();
    });
    saveBtn.setTextSize(14);
    saveBtn.setHeight(d(36));
    footer.addView(saveBtn, new LinearLayout.LayoutParams(-2, d(36)));

    dialog.show();
  }
  private void pickQuickPromptPreset(int index) {
    var presets = anomaly.hex.android.anywhere.QuickPromptsStore.PRESETS;
    ScrollView scroll = new ScrollView(this);
    LinearLayout box = new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(d(20), d(18), d(20), d(18));
    scroll.addView(box);

    box.addView(text(this, "Preset for slot " + (index + 1), 19, TEXT));
    TextView sub = text(this, "Tap a preset to assign it.", 13, MUTED);
    LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
    slp.topMargin = d(4);
    slp.bottomMargin = d(12);
    box.addView(sub, slp);

    AlertDialog dialog = new AlertDialog.Builder(this).setView(scroll).create();

    for (int i = 0; i < presets.size(); i++) {
      var p = presets.get(i);
      LinearLayout item = new LinearLayout(this);
      item.setOrientation(LinearLayout.VERTICAL);
      item.setBackground(ripple(this, LAYER_01, 14));
      item.setPadding(d(14), d(10), d(14), d(10));
      item.setOnClickListener(v -> {
        anomaly.hex.android.anywhere.QuickPromptsStore.save(this, index, p.label, p.prompt);
        dialog.dismiss();
        configureQuickPrompts();
      });
      item.addView(text(this, p.label, 14, TEXT));
      TextView pr = new TextView(this);
      pr.setText(p.prompt);
      pr.setTextSize(12f);
      pr.setTextColor(MUTED);
      pr.setMaxLines(2);
      pr.setEllipsize(android.text.TextUtils.TruncateAt.END);
      LinearLayout.LayoutParams prp = new LinearLayout.LayoutParams(-1, -2);
      prp.topMargin = d(2);
      item.addView(pr, prp);

      LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(-1, -2);
      if (i > 0) ilp.topMargin = d(8);
      box.addView(item, ilp);
    }

    Button cancelBtn = smallButton(this, "Cancel", () -> {
      dialog.dismiss();
      configureQuickPrompts();
    });
    LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, d(36));
    clp.topMargin = d(14);
    clp.gravity = Gravity.END;
    box.addView(cancelBtn, clp);

    dialog.show();
  }
}
