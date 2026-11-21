package anomaly.hex.android.service;
import android.Manifest;
import android.accessibilityservice.AccessibilityButtonController;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import anomaly.hex.android.anywhere.*;
import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.InputMethod;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.graphics.PixelFormat;
import android.provider.Settings;
import android.media.*;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.view.accessibility.AccessibilityEvent;
import android.view.inputmethod.*;
import android.widget.*;
import anomaly.hex.android.cleanup.*;
import anomaly.hex.android.control.DictationNotification;
import anomaly.hex.android.core.*;
import anomaly.hex.android.keyboard.KeyboardPresence;
import anomaly.hex.android.recovery.Drafts;
import anomaly.hex.android.speech.*;
import anomaly.hex.android.ui.*;
import java.util.*;
import org.json.*;

/** Editor access goes only through the accessibility IME, never a node tree or clipboard. */
public final class HexBubbleService extends AccessibilityService {
  public static volatile HexBubbleService active;
  public Placement placement;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ContentObserver defaultImeObserver = new ContentObserver(main) {
    @Override public void onChange(boolean selfChange) { main.post(HexBubbleService.this::render); }
  };
  private HexSettings settings;
  private DictationFixStore fixStore;
  private HexLabs labs;
  private HexHistory history;
  private Drafts drafts;
  private Cleanup cleanup;
  private boolean connected, editor;
  private String target = "", pkg = "", state = "idle", outcome = "none";
  private int words, selectionA, selectionB;
  private MicStrip strip;
  private WindowManager.LayoutParams params;
  private AnywhereHandleView anywhereHandle;
  private WindowManager.LayoutParams anywhereParams;
  private AlertDialog review;
  private Take take;
  private SpeechSession last;
  private final ArrayDeque<Runnable> pending = new ArrayDeque<>();
  private boolean confirming;
  private DictationNotification notification;
  private String undoTarget, undoText, editPrefix, editSuffix;
  private int undoStart, undoEnd;
  private boolean undoInvalid;
  private DictationEditTracker tracker;
  public void setFixListener(DictationEditTracker.FixListener listener) {
    disarmEdits();
    tracker = listener == null ? null : new DictationEditTracker(listener);
  }
  private void disarmEdits() {
    main.removeCallbacks(expireEdits);
    main.removeCallbacks(observeEdits);
    editPrefix = editSuffix = null;
    if (tracker != null) tracker.disarm();
  }
  private final Runnable expireEdits = this::settleEdits;
  private void settleEdits() { observeEdits.run(); disarmEdits(); }
  private final Runnable observeEdits = () -> {
    if (editSuffix == null || tracker == null || isBusy()) return;
    if (!fixStore.isEnabled()) { disarmEdits(); return; }
    SurroundingText s = spanContext();
    if (s == null) { disarmEdits(); return; }
    String text = s.getText().toString();
    int start = undoStart - s.getOffset();
    // Require a complete right edge, not a cursor-relative or truncated guess.
    if (start < editPrefix.length() || !text.regionMatches(start - editPrefix.length(), editPrefix, 0, editPrefix.length()) || text.length()-s.getSelectionEnd() >= DictationEditTracker.LIMIT || !text.endsWith(editSuffix)
        || start > text.length()-editSuffix.length()) { disarmEdits(); return; }
    tracker.onTextSnapshot(target, text.substring(start, text.length()-editSuffix.length()));
  };
  private void clearUndo() { undoTarget = undoText = null; disarmEdits(); }
  private SurroundingText spanContext() {
    if (undoTarget == null || !undoTarget.equals(target)) return null;
    InputMethod.AccessibilityInputConnection ic = connection();
    SurroundingText s = ic == null ? null : ic.getSurroundingText(DictationEditTracker.LIMIT, DictationEditTracker.LIMIT, 0);
    return s != null && s.getOffset() >= 0 ? s : null;
  }
  public boolean canUndoLast() {
    if (undoInvalid || isBusy() || review != null || applying) return false;
    SurroundingText s = spanContext();
    if (s == null) return false;
    int a = undoStart-s.getOffset(), b = undoEnd-s.getOffset();
    if (a < 0) return false;
    if (b > s.getText().length()) {
      if (a <= s.getText().length() && s.getText().length() - s.getSelectionEnd() < DictationEditTracker.LIMIT) undoInvalid = true;
      return false;
    }
    undoInvalid = !undoText.contentEquals(s.getText().subSequence(a,b));
    return !undoInvalid;
  }
  public String undoLast() {
    if (!canUndoLast()) return "stale_passage";
    InputMethod.AccessibilityInputConnection ic = connection();
    if (ic == null) return "no_editor";
    ic.setSelection(undoStart, undoEnd);
    SurroundingText s = ic.getSurroundingText(1, 1, 0);
    if (s == null || s.getOffset() < 0 || absolute(s,true) != undoStart || absolute(s,false) != undoEnd
        || !undoText.contentEquals(s.getText().subSequence(s.getSelectionStart(),s.getSelectionEnd()))) return "stale_passage";
    ic.commitText("",1,null);
    clearUndo(); invalidateCleanup(); render();
    return "ok";
  }
  private final class Take {
    final String id = UUID.randomUUID().toString(), owner = target;
    final TranscriptStyle style = settings.style();
    SpeechSession session;
    volatile String draft;
    boolean finished;
    int a, b;
  }
  private final BroadcastReceiver screen = new BroadcastReceiver() {
    public void onReceive(Context c, Intent i) {
      loseEditor();
    }
  };
  private final AudioManager.OnModeChangedListener mode = m -> {
    if (calling()) {
      invalidateCleanup();
      cancel("call_interrupted");
    }
    render();
  };
  @Override
  public void onCreate() {
    super.onCreate();
    settings = new HexSettings(this);
    fixStore = new DictationFixStore(this);
    setFixListener((heard, replacement) -> {
      if (fixStore.isEnabled()) fixStore.recordSighting(heard, replacement);
    });
    labs = new HexLabs(this);
    history = new HexHistory(this);
    placement = new Placement(this);
  }
  public static boolean eligible(EditorInfo e) {
    if (e == null || e.packageName == null
        || (e.inputType & InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT
        || (e.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0)
      return false;
    int v = e.inputType & InputType.TYPE_MASK_VARIATION;
    return v != InputType.TYPE_TEXT_VARIATION_PASSWORD
        && v != InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        && v != InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD;
  }
  @Override
  public InputMethod onCreateInputMethod() {
    return new InputMethod(this) {
      public void onStartInput(EditorInfo e, boolean restart) {
        super.onStartInput(e, restart);
        loseEditor();
        editor = eligible(e);
        if (editor) {
          Engine.get(HexBubbleService.this).prewarmAsync();
          target = UUID.randomUUID().toString();
          pkg = e.packageName;
          selectionA = e.initialSelStart;
          selectionB = e.initialSelEnd;
        }
        render();
      }
      public void onFinishInput() {
        loseEditor();
        super.onFinishInput();
      }
      public void onUpdateSelection(int os, int oe, int ns, int ne, int cs, int ce) {
        super.onUpdateSelection(os, oe, ns, ne, cs, ce);
        selectionA = ns;
        selectionB = ne;
        if (!isBusy() && undoTarget != null) {
          render();
          if (editSuffix != null) { main.removeCallbacks(observeEdits); main.postDelayed(observeEdits, 1200); }
        }
        if (os != ns || oe != ne) {
          if (take != null && (ns != take.a || ne != take.b))
            cancel("cursor_changed");
          if (cleanup != null && cleanup.current() != null
              && (!applying && (ns != cleanup.current().end || ne != ns)))
            invalidateCleanup();
        }
      }
    };
  }
  private final AccessibilityButtonController.AccessibilityButtonCallback anywhereButton =
      new AccessibilityButtonController.AccessibilityButtonCallback() {
        @Override public void onClicked(AccessibilityButtonController button) {
          if (labs.isEnabled(LabId.TYPE_ANYWHERE))
            launchTypeAnywhere();
        }
      };
  private int captureRequest;
  public void launchTypeAnywhere() {
    if (!connected || !unlocked() || !labs.isEnabled(LabId.TYPE_ANYWHERE))
      return;
    if (anywhereHandle != null) anywhereHandle.setVisibility(View.INVISIBLE);
    int request = ++captureRequest;
    TypeAnywhereStore.clear(this);
    try {
      takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
        public void onFailure(int code) {
          openAnywhere(request, null,
              code == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW ? "Protected" : "No screen");
        }
        public void onSuccess(ScreenshotResult result) {
          Bitmap pixels = null;
          try (HardwareBuffer buffer = result.getHardwareBuffer()) {
            if (request != captureRequest)
              return;
            Bitmap wrapped = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
            if (wrapped != null)
              try {
                pixels = wrapped.copy(Bitmap.Config.ARGB_8888, false);
                if (pixels.getWidth() > 1080) {
                  Bitmap original = pixels;
                  pixels = Bitmap.createScaledBitmap(original, 1080,
                      Math.max(1, Math.round(original.getHeight() * 1080f / original.getWidth())),
                      true);
                  original.recycle();
                }
              } finally {
                wrapped.recycle();
              }
          } catch (RuntimeException | OutOfMemoryError e) {
            pixels = null;
          }
          openAnywhere(request, pixels, "Screen unavailable — type your question");
        }
      });
    } catch (RuntimeException e) {
      openAnywhere(request, null, "Screen unavailable — type your question");
    }
  }
  private void openAnywhere(int request, Bitmap pixels, String note) {
    if (request != captureRequest || !connected || !unlocked()
        || !labs.isEnabled(LabId.TYPE_ANYWHERE))
      return;
    TypeAnywhereStore capture = TypeAnywhereStore.put(this, pixels);
    startActivity(
        TypeAnywhereActivity.intent(this, capture.capturedAt, pixels == null ? note : null));
    if (pixels != null)
      new Thread(() -> TypeAnywhereStore.persist(this, capture), "ScreenHandoff").start();
  }
  @Override
  protected void onServiceConnected() {
    if (!connected) {
      getAccessibilityButtonController().registerAccessibilityButtonCallback(anywhereButton, main);
      getContentResolver().registerContentObserver(
          Settings.Secure.getUriFor(Settings.Secure.DEFAULT_INPUT_METHOD), false,
          defaultImeObserver);
      registerReceiver(screen, new IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED);
      getSystemService(AudioManager.class).addOnModeChangedListener(getMainExecutor(), mode);
    }
    Engine.get(this).prewarmAsync();
    connected = true;
    active = this;
    KeyboardPresence.setOnVisibilityChanged(() -> main.post(this::render));
    render();
  }
  @Override
  public void onAccessibilityEvent(AccessibilityEvent e) {}
  @Override
  public void onInterrupt() {
    invalidateCleanup();
    cancel("interrupted");
  }
  private void loseEditor() {
    clearUndo();
    editor = false;
    target = "";
    pkg = "";
    invalidateCleanup();
    cancel("editor_changed");
    render();
  }
  private boolean unlocked() {
    return !getSystemService(KeyguardManager.class).isKeyguardLocked()
        && getSystemService(PowerManager.class).isInteractive();
  }
  private boolean calling() {
    return getSystemService(AudioManager.class).getMode() != AudioManager.MODE_NORMAL;
  }
  private InputMethod.AccessibilityInputConnection connection() {
    InputMethod im = getInputMethod();
    EditorInfo e = im == null ? null : im.getCurrentInputEditorInfo();
    return editor && eligible(e) && pkg.equals(e.packageName) && unlocked() && !calling()
        ? im.getCurrentInputConnection()
        : null;
  }
  private final Runnable unloadIdle = () -> {
    if (!isBusy()) Engine.get(this).releaseIfIdle();
  };

  @Override
  public void onTrimMemory(int level) {
    super.onTrimMemory(level);
    if (!isBusy() && level >= TRIM_MEMORY_BACKGROUND) Engine.get(this).releaseIfIdle();
  }

  public boolean isBusy() {
    return take != null;
  }
  public String sessionId() {
    return take == null ? "" : take.id;
  }
  public String targetId() {
    return editor && unlocked() ? target : "";
  }
  public void positionStrip() {
    if (strip != null && strip.isAttachedToWindow()) {
      placement.layout(strip, params);
      getSystemService(WindowManager.class).updateViewLayout(strip, params);
    }
  }
  @Override
  public void onConfigurationChanged(Configuration c) {
    super.onConfigurationChanged(c);
    positionStrip();
  }
  private static final int FAST_IME_TICKS = 15;
  private int fastImeTicks;
  private int lastImeBottom = -1;
  private final Runnable imeWatch = new Runnable() {
    @Override
    public void run() {
      if (strip == null) {
        lastImeBottom = -1;
        return;
      }
      int ime = placement.currentImeBottom(HexBubbleService.this, strip);
      if (fastImeTicks > 0) fastImeTicks--;
      if (ime != lastImeBottom) {
        fastImeTicks = FAST_IME_TICKS;
        lastImeBottom = ime;
        positionStrip();
      }
      main.postDelayed(this, fastImeTicks > 0 ? 100 : 500);
    }
  };
  private void renderAnywhereHandle(WindowManager wm) {
    boolean show = connected && unlocked() && !calling()
        && labs.isEnabled(LabId.TYPE_ANYWHERE) && !TypeAnywhereActivity.visible;
    if (!show) {
      if (anywhereHandle != null) {
        try { wm.removeView(anywhereHandle); } catch (RuntimeException ignored) {}
        anywhereHandle = null;
      }
      return;
    }
    if (anywhereHandle != null) {
      if (anywhereHandle.getVisibility() != View.VISIBLE) anywhereHandle.setVisibility(View.VISIBLE);
      return;
    }
    var bounds = wm.getCurrentWindowMetrics().getBounds();
    int pad = HexTheme.dp(this, 6);
    int size = HexTheme.dp(this, 28);
    SharedPreferences prefs = getSharedPreferences("hex_settings", MODE_PRIVATE);
    boolean left = prefs.getBoolean("anywhere_edge_left", false);
    float yRatio = prefs.getFloat("anywhere_y_ratio", 0.42f);
    anywhereParams = new WindowManager.LayoutParams(size, size,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT);
    anywhereParams.gravity = Gravity.TOP | Gravity.START;
    anywhereParams.x = left ? pad : Math.max(pad, bounds.width() - size - pad);
    anywhereParams.y = Math.max(HexTheme.dp(this, 60),
        Math.min(bounds.height() - size - HexTheme.dp(this, 80), Math.round(bounds.height() * yRatio)));
    anywhereParams.setTitle("Hex Type anywhere");
    anywhereHandle = new AnywhereHandleView(this, new AnywhereHandleView.Listener() {
      @Override public void onTap() {
        if (anywhereHandle != null) anywhereHandle.setVisibility(View.INVISIBLE);
        main.postDelayed(HexBubbleService.this::launchTypeAnywhere, 60);
      }
      @Override public void onDrag(float rawX, float rawY, boolean done) {
        if (anywhereHandle == null || anywhereParams == null) return;
        var b = wm.getCurrentWindowMetrics().getBounds();
        int maxY = Math.max(HexTheme.dp(HexBubbleService.this, 60), b.height() - size - HexTheme.dp(HexBubbleService.this, 80));
        int ny = Math.max(HexTheme.dp(HexBubbleService.this, 60), Math.min(maxY, Math.round(rawY - size * 0.5f)));
        int nx = done
            ? (rawX < b.width() * 0.5f ? pad : Math.max(pad, b.width() - size - pad))
            : Math.max(pad, Math.min(b.width() - size - pad, Math.round(rawX - size * 0.5f)));
        anywhereParams.x = nx;
        anywhereParams.y = ny;
        try { wm.updateViewLayout(anywhereHandle, anywhereParams); } catch (RuntimeException ignored) {}
        if (done) {
          prefs.edit()
              .putBoolean("anywhere_edge_left", rawX < b.width() * 0.5f)
              .putFloat("anywhere_y_ratio", Math.max(0.1f, Math.min(0.85f, ny / (float) Math.max(1, b.height()))))
              .apply();
        }
      }
    });
    try { wm.addView(anywhereHandle, anywhereParams); } catch (RuntimeException ignored) { anywhereHandle = null; }
  }
  private void render() {
    if (notification != null) {
      if (state.equals("idle")) { notification.dismiss(); notification = null; }
      else if (state.equals("listening") || state.equals("transcribing")) notification.show(state.equals("listening"));
    }
    WindowManager wm = getSystemService(WindowManager.class);
    renderAnywhereHandle(wm);
    if (!connected || !editor || !unlocked() || calling() || KeyboardPresence.isImeVisible()
        || KeyboardPresence.isHexKeyboardDefault(this)) {
      main.removeCallbacks(imeWatch);
      lastImeBottom = -1;
      if (strip != null) {
        placement.end(false);
        wm.removeView(strip);
        strip = null;
      }
      return;
    }
    boolean fresh = strip == null;
    if (fresh) {
      strip = new MicStrip(this);
      params = new WindowManager.LayoutParams(-2, -2,
          WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
          WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
              | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
          PixelFormat.TRANSLUCENT);
      params.setTitle("Hex dictation");
      params.setFitInsetsTypes(WindowInsets.Type.systemBars()
          | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
    }
    strip.update(state, words, !passageId().isEmpty());
    if (fresh) {
      placement.layout(strip, params);
      wm.addView(strip, params);
      lastImeBottom = placement.currentImeBottom(this, strip);
      main.removeCallbacks(imeWatch);
      fastImeTicks = FAST_IME_TICKS;
      main.postDelayed(imeWatch, 100);
    } else
      positionStrip();
  }
  private void vibrate() {
    if (strip != null && settings.hapticsEnabled())
      strip.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
  }

  public String start() {
    return start(() -> new Microphone(this, settings.preferBuiltInMic()));
  }
  /** In-process fixture source; never selectable through the exported control receiver. */
  public String start(SpeechSession.Source source) {
    if (take != null || review != null)
      return "busy";
    if (calling())
      return "call_active";
    InputMethod.AccessibilityInputConnection ic = connection();
    if (ic == null)
      return "no_editor";
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
      return "microphone_permission_required";
    if (ModelStore.inspect(this).state == ModelStore.State.MISSING)
      return "model_missing";
    SurroundingText ctx = ic.getSurroundingText(64, 1, 0);
    if (ctx == null)
      return "unsupported_editor";
    Take t = new Take();
    t.a = absolute(ctx, true);
    t.b = absolute(ctx, false);
    if (t.a < 0 || t.b < 0)
      return "unsupported_editor";
    String before = ctx.getText()
                        .subSequence(0, Math.min(ctx.getSelectionStart(), ctx.getSelectionEnd()))
                        .toString();
    CustomVocabulary vocabulary = new CustomVocabulary(this);
    java.util.List<String> vocab = vocabulary.isEnabled() ? vocabulary.terms() : java.util.List.of();
    java.util.Map<String,String> fixes = fixStore.isEnabled() ? fixStore.activeFixes() : java.util.Map.of();
    String locale = SpeechLanguage.resolveLocale(new SpeechLanguage(this).selectedId(),
        java.util.Locale.getDefault()).toLanguageTag();
    boolean spoken = labs.isEnabled(LabId.SPOKEN_CLEANUP);
    invalidateCleanup();
    if (labs.isEnabled(LabId.RECOVERY_DRAFTS)) {
      drafts = new Drafts(this);
      try {
        t.draft = drafts.createAudioDraft("unconfirmed_dictation");
      } catch (RuntimeException e) {
        return "recovery_storage_unavailable";
      }
    }
    Drafts store = drafts;
    t.session = new SpeechSession(this, Engine.get(this), source,
        raw
        -> TranscriptFormatter.applyPipeline(raw, t.style, vocab, spoken, before, fixes, locale),
        new SpeechSession.Sink() {
          public void onMeter(int[] levels) {
            main.post(() -> {
              if (take == t && strip != null && state.equals("listening"))
                strip.meter(levels);
            });
          }
          public void onPhraseReady(String raw, String processed, long ms) {
            main.post(() -> {
              if (take != t)
                return;
              if (pending.size() >= 32) {
                cancel("editor_backlog");
                return;
              }
              pending.add(() -> insert(t, raw, processed, ms));
              pump(t);
            });
          }
          public void onStateChanged(String s) {
            main.post(() -> {
              if (take != t)
                return;
              if (s.equals("finished")) {
                t.finished = true;
                pump(t);
              } else if (s.equals("error") || s.equals("cancelled")) {
                cancel(s);
              } else {
                state = s;
                render();
              }
            });
          }
          public void onError(String code, String detail) {
            main.post(() -> {
              if (take == t) {
                cancel(code);
                Toast.makeText(HexBubbleService.this, code.replace('_', ' '), Toast.LENGTH_SHORT)
                    .show();
              }
            });
          }
          public void onCheckpoint(byte[] pcm) {
            synchronized (t) {
              if (t.draft != null && labs.isEnabled(LabId.RECOVERY_DRAFTS))
                store.appendChunk(t.draft, pcm);
            }
          }
        });
    clearUndo();
    notification = new DictationNotification(this, 7101, this::stop, () -> cancel("cancelled"));
    words = 0;
    state = "starting";
    outcome = "none";
    main.removeCallbacks(unloadIdle);
    take = t;
    last = t.session;
    vibrate();
    render();
    t.session.start();
    return "ok";
  }
  private int absolute(SurroundingText s, boolean first) {
    return s.getOffset() >= 0
        ? s.getOffset() + (first ? s.getSelectionStart() : s.getSelectionEnd())
        : first ? selectionA
                : selectionB;
  }
  public String stop() {
    if (take == null || !state.equals("listening"))
      return "not_listening";
    state = "transcribing";
    take.session.finish();
    vibrate();
    render();
    return "ok";
  }
  public void cancel(String why) {
    Take t = take;
    if (t == null)
      return;
    take = null;
    main.postDelayed(unloadIdle, 120_000);
    pending.clear();
    confirming = false;
    t.session.cancel();
    if ("cancelled".equals(why))
      deleteDraft(t);
    outcome = why;
    state = "idle";
    render();
  }
  private void deleteDraft(Take t) {
    synchronized (t) {
      if (t.draft != null && drafts != null && labs.isEnabled(LabId.RECOVERY_DRAFTS))
        drafts.removeConfirmed(t.draft);
      t.draft = null;
    }
  }
  private void pump(Take t) {
    if (take != t || confirming)
      return;
    if (!pending.isEmpty()) {
      confirming = true;
      pending.remove().run();
    } else if (t.finished) {
      deleteDraft(t);
      take = null;
      main.postDelayed(unloadIdle, 120_000);
      state = "idle";
      outcome = words > 0 ? "inserted" : "no_speech";
      render();
    }
  }
  private void insert(Take t, String raw, String processed, long ms) {
    try {
      InputMethod.AccessibilityInputConnection ic = connection();
      SurroundingText s = ic == null ? null : ic.getSurroundingText(64, 1, 0);
      if (s == null || !target.equals(t.owner) || absolute(s, true) != t.a
          || absolute(s, false) != t.b) {
        cancel("editor_changed");
        return;
      }
      String text = processed.trim();
      if (text.isEmpty()) {
        confirming = false;
        pump(t);
        return;
      }
      int left = Math.min(s.getSelectionStart(), s.getSelectionEnd()),
          right = Math.max(s.getSelectionStart(), s.getSelectionEnd());
      CharSequence around = s.getText();
      boolean space = left > 0 && !Character.isWhitespace(around.charAt(left - 1))
          && Character.isLetterOrDigit(text.codePointAt(0));
      String sent = (space ? " " : "") + text
          + (right < around.length() && Character.isLetterOrDigit(around.charAt(right)) ? " " : "");
      int start = Math.min(t.a, t.b);
      t.a = t.b = start + sent.length();
      ic.commitText(sent, 1, null);
      confirm(t, raw, sent, start, ms, 0);
    } catch (RuntimeException e) {
      cancel("check_insertion");
    }
  }
  private boolean matches(String sent, int start) {
    InputMethod.AccessibilityInputConnection ic = connection();
    SurroundingText s = ic == null ? null : ic.getSurroundingText(sent.length(), 0, 0);
    return s != null && absolute(s, true) == start + sent.length()
        && absolute(s, false) == absolute(s, true) && s.getSelectionStart() >= sent.length()
        && s.getText()
               .subSequence(s.getSelectionStart() - sent.length(), s.getSelectionStart())
               .toString()
               .equals(sent);
  }
  private void confirm(Take t, String raw, String sent, int start, long ms, int attempt) {
    main.postDelayed(() -> {
      if (take != t)
        return;
      if (!matches(sent, start)) {
        if (attempt < 2)
          confirm(t, raw, sent, start, ms, attempt + 1);
        else
          cancel("check_insertion");
        return;
      }
      words += sent.trim().split("\\s+").length;
      String inserted = undoTarget != null && undoEnd == start ? undoText + sent : sent;
      int first = undoTarget != null && undoEnd == start ? undoStart : start;
      clearUndo();
      if (inserted.length() <= DictationEditTracker.LIMIT) {
        undoInvalid = false; undoTarget = target; undoStart = first; undoEnd = t.a; undoText = inserted;
        if (tracker != null && fixStore.isEnabled()) {
          InputMethod.AccessibilityInputConnection current = connection();
          SurroundingText s = current == null ? null : current.getSurroundingText(inserted.length()+32, 33, 0);
          if (s != null && s.getOffset() >= 0 && absolute(s,true) == undoEnd
              && s.getText().length()-s.getSelectionEnd() < 33 && first-s.getOffset() >= Math.min(32, first)) {
            int a = first-s.getOffset();
            editPrefix = s.getText().subSequence(a-Math.min(32,first),a).toString();
            editSuffix = s.getText().subSequence(s.getSelectionEnd(),s.getText().length()).toString();
            tracker.arm(target, undoStart, undoEnd, inserted);
            main.postDelayed(expireEdits, 60_000);
          }
        }
      }
      history.record(raw, sent.trim(), t.style, ms, settings.historyRetention());
      if (labs.isEnabled(LabId.GEMMA_CLEANUP)) {
        if (cleanup == null)
          cleanup = new Cleanup(this);
        cleanup.recordConfirmed(sent, pkg, start, t.a);
      }
      confirming = false;
      render();
      pump(t);
    }, attempt == 0 ? 80 : 150);
  }
  public static void refreshLabs() {
    HexBubbleService h = active;
    if (h != null) {
      if (!h.labs.isEnabled(LabId.GEMMA_CLEANUP))
        h.invalidateCleanup();
      if (!h.labs.isEnabled(LabId.RECOVERY_DRAFTS) && h.take != null) {
        synchronized (h.take) {
          h.take.draft = null;
        }
      }
      h.render();
    }
  }
  private boolean applying;
  private String proposed;
  private List<String> hints = Collections.emptyList();
  private int generation;
  private void invalidateCleanup() {
    closeReview();
    if (cleanup != null)
      cleanup.invalidate();
  }
  private void closeReview() {
    generation++;
    applying = false;
    proposed = null;
    hints = Collections.emptyList();
    if (review != null) {
      review.dismiss();
      review = null;
    }
  }
  public String passageId() {
    if (cleanup == null || !labs.isEnabled(LabId.GEMMA_CLEANUP))
      return "";
    Cleanup.Passage p = cleanup.current();
    return p == null ? "" : p.id;
  }
  public String closeCleanup(String id) {
    if (id == null || id.isEmpty() || !id.equals(passageId()))
      return "stale_passage";
    closeReview();
    return "ok";
  }
  public String reviewCleanup(String id) {
    if (isBusy() || applying)
      return "busy";
    if (id == null || id.isEmpty() || !id.equals(passageId()) || connection() == null)
      return "stale_passage";
    Cleanup.Passage p = cleanup.current();
    if (!matches(p.original, p.start))
      return "stale_passage";
    closeReview();
    showReview(p);
    return "ok";
  }
  private void showReview(Cleanup.Passage p) {
    if (!p.id.equals(passageId()) || connection() == null)
      return;
    if (review != null)
      review.dismiss();
    int epoch = generation;
    AlertDialog.Builder dialog = new AlertDialog.Builder(this)
                                     .setTitle("Review cleanup")
                                     .setMessage(proposed != null ? proposed : p.original)
                                     .setNegativeButton("Close", (d, n) -> closeReview());
    Runnable clean = () -> {
      if (proposed != null) {
        applyProposal(p);
        return;
      }
      cleanup.requestPolish(p,
          labs.isEnabled(LabId.WORDS_ON_SCREEN) ? hints : Collections.emptyList(),
          result
          -> main.post(() -> {
            if (epoch == generation && p.id.equals(passageId())) {
              proposed = result;
              showReview(p);
            }
          }),
          err -> main.post(() -> {
            if (epoch == generation) {
              Toast.makeText(this, "Cleanup unavailable", Toast.LENGTH_SHORT).show();
              closeReview();
            }
          }));
    };
    dialog.setPositiveButton(proposed == null ? "Clean up" : "Apply", null);
    if (proposed == null && labs.isEnabled(LabId.WORDS_ON_SCREEN)
        && HexHintWindowService.isConnected())
      dialog.setNeutralButton("Read this window", null);
    review = dialog.create();
    review.getWindow().setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);
    review.getWindow().addFlags(
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_SECURE);
    review.setOnCancelListener(d -> closeReview());
    review.show();
    review.getButton(-1).setOnClickListener(v -> {
      if (!p.id.equals(passageId()) || connection() == null) {
        closeReview();
        return;
      }
      v.setEnabled(false);
      clean.run();
    });
    Button read = review.getButton(-3);
    if (read != null)
      read.setOnClickListener(v -> {
        if (!labs.isEnabled(LabId.WORDS_ON_SCREEN) || connection() == null)
          return;
        v.setEnabled(false);
        HexHintWindowService.captureHints(pkg,
            list
            -> main.post(() -> {
              if (epoch == generation && labs.isEnabled(LabId.WORDS_ON_SCREEN))
                hints = list;
            }),
            err -> main.post(() -> {
              if (epoch == generation)
                Toast.makeText(this, "Window hints unavailable", Toast.LENGTH_SHORT).show();
            }));
      });
  }
  private void applyProposal(Cleanup.Passage p) {
    String replacement = proposed;
    if (replacement == null || replacement.trim().isEmpty() || !matches(p.original, p.start)) {
      invalidateCleanup();
      return;
    }
    InputMethod.AccessibilityInputConnection ic = connection();
    if (ic == null)
      return;
    clearUndo();
    applying = true;
    int epoch = generation;
    ic.setSelection(p.start, p.end);
    main.postDelayed(() -> {
      if (generation != epoch)
        return;
      InputMethod.AccessibilityInputConnection current = connection();
      SurroundingText s = current == null ? null : current.getSurroundingText(1, 1, 0);
      if (s != null && p.id.equals(passageId()) && pkg.equals(p.packageName)
          && absolute(s, true) == p.start && absolute(s, false) == p.end
          && s.getText()
              .subSequence(s.getSelectionStart(), s.getSelectionEnd())
              .toString()
              .equals(p.original))
        current.commitText(replacement, 1, null);
      invalidateCleanup();
      render();
    }, 120);
  }
  public JSONObject status() throws JSONException {
    JSONObject o = new JSONObject()
                       .put("state", state)
                       .put("outcome", outcome)
                       .put("target", targetId())
                       .put("session", sessionId())
                       .put("confirmedWords", words)
                       .put("position", placement.info())
                       .put("passage", passageId());
    if (last != null)
      o.put("progress", last.progressJson());
    if (cleanup != null && labs.isEnabled(LabId.GEMMA_CLEANUP))
      o.put("cleanup", cleanup.statusJson(true, labs.isEnabled(LabId.WORDS_ON_SCREEN)));
    return o;
  }
  @Override
  public void onDestroy() {
    KeyboardPresence.setOnVisibilityChanged(null);
    if (connected) {
      getAccessibilityButtonController().unregisterAccessibilityButtonCallback(anywhereButton);
      getContentResolver().unregisterContentObserver(defaultImeObserver);
      unregisterReceiver(screen);
      getSystemService(AudioManager.class).removeOnModeChangedListener(mode);
    }
    active = null;
    connected = false;
    loseEditor();
    main.removeCallbacksAndMessages(null);
    super.onDestroy();
  }
}
