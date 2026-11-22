package anomaly.hex.android.keyboard;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.inputmethodservice.InputMethodService;
import android.os.*;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.inputmethod.*;
import android.widget.*;
import anomaly.hex.android.core.*;
import anomaly.hex.android.speech.*;
import anomaly.hex.android.ui.HexHomeActivity;
import java.util.*;
import java.util.regex.*;

/** Owns the active editor. Worker callbacks never carry text over to another input session. */
public final class HexInputMethodService extends InputMethodService implements FullKeyboardView.Host, DictationBarView.Host {
  private FrameLayout container;
  private FullKeyboardView fullView;
  private DictationBarView barView;
  private KeyboardSettings kbSettings;
  private HexSettings hexSettings;
  private HexLabs labs;
  private HexHistory history;
  private AutocorrectEngine autocorrect;
  private PersonalAutocorrectStore personalStore;
  private SystemSpellChecker spellChecker;
  private final LocalSpatialReranker spatialReranker = new LocalSpatialReranker();
  private volatile WordDictionary englishDictionary;
  private volatile SystemUserDictionary.Snapshot userDictionary = SystemUserDictionary.Snapshot.EMPTY;
  private SpeechLanguage speechLang;
  private List<String> vocabulary = List.of();
  private EditorInfo editor;
  private boolean privateField, words, emojiEnabled, spatialAutocorrect;
  private int action;
  private long lastSpace, generation;
  private String state = "idle";
  private DictationUndo undo;
  private String rewriteUndoOriginal, rewriteUndoApplied;
  private List<TypoFixExtractor.FixPair> pendingQuickFixPairs;
  private long rewriteGen;
  private String lastRewriteDraft, lastRewriteInstruction;
  private KeyboardRewriteClient.Session rewriteSession;
  private final SelectionTrackpad trackpad = new SelectionTrackpad();
  private int trackpadOffset;
  private SpeechSession session;
  private InputConnection speechTarget;
  private LivePunctuationSession live;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Runnable expireUndo = () -> setUndo(null);
  private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{M}\\p{N}_'’]+\\z");

  @Override public void onCreate() {
    super.onCreate();
    kbSettings = new KeyboardSettings(this); hexSettings = new HexSettings(this);
    labs = new HexLabs(this); history = new HexHistory(this); speechLang = new SpeechLanguage(this);
    personalStore = new PersonalAutocorrectStore(this);
    spellChecker = new SystemSpellChecker(this::refresh);
    new Thread(() -> {
      englishDictionary = WordDictionary.loadEnglish(this);
      userDictionary = SystemUserDictionary.load(this);
      EmojiCatalog.load(this);
      main.post(() -> {
        if (editor != null && autocorrect != null) {
          KeyboardLanguage lang = KeyboardLanguage.resolve(kbSettings.layoutLanguage(), Locale.getDefault());
          boolean allowLearn = !privateField && (editor.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) == 0;
          autocorrect = new AutocorrectEngine(lang, englishDictionary, personalStore, allowLearn, userDictionary, spatialAutocorrect);
          refresh();
        }
      });
    }, "hex-dict-load").start();
  }
  @Override public View onCreateInputView() {
    container = new FrameLayout(this);
    container.setBackgroundColor(KeyboardPalette.BACKDROP);
    container.setOnApplyWindowInsetsListener((v, insets) -> {
      int bottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
      int safeBottom = bottom > 0 ? bottom : KeyboardPalette.dp(this, 8);
      if (v.getPaddingBottom() != safeBottom) v.setPadding(0, 0, 0, safeBottom);
      return insets;
    });
    Window w = getWindow() != null ? getWindow().getWindow() : null;
    if (w != null) w.setNavigationBarColor(KeyboardPalette.BACKDROP);
    fullView = null; barView = null; attachMode();
    container.requestApplyInsets();
    return container;
  }
  private void attachMode() {
    if (kbSettings.isFullKeyboard() ? fullView != null : barView != null) return;
    container.removeAllViews(); fullView = null; barView = null;
    View v = kbSettings.isFullKeyboard() ? (fullView = new FullKeyboardView(this, this)) : (barView = new DictationBarView(this, this));
    container.addView(v, new FrameLayout.LayoutParams(-1, -2));
  }
  @Override public void onStartInputView(EditorInfo info, boolean restarting) {
    super.onStartInputView(info, restarting); resetEditor(); editor = info; attachMode();
    int type = info.inputType, cls = type & InputType.TYPE_MASK_CLASS, v = type & InputType.TYPE_MASK_VARIATION;
    boolean text = cls == InputType.TYPE_CLASS_TEXT;
    privateField = text && (v == InputType.TYPE_TEXT_VARIATION_PASSWORD || v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
        || cls == InputType.TYPE_CLASS_NUMBER && v == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
    KeyboardLayout.FieldKind kind = !text ? KeyboardLayout.FieldKind.TEXT : switch (v) {
      case InputType.TYPE_TEXT_VARIATION_URI -> KeyboardLayout.FieldKind.URL;
      case InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> KeyboardLayout.FieldKind.EMAIL;
      default -> KeyboardLayout.FieldKind.TEXT;
    };
    words = text && !privateField && kind == KeyboardLayout.FieldKind.TEXT && (type & InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) == 0;
    emojiEnabled = labs.isEnabled(LabId.EMOJI_KEYBOARD);
    spatialAutocorrect = labs.isEnabled(LabId.SPATIAL_AUTOCORRECT);
    KeyboardLanguage language = KeyboardLanguage.resolve(kbSettings.layoutLanguage(), Locale.getDefault());
    boolean allowLearn = !privateField && (info.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) == 0;
    autocorrect = new AutocorrectEngine(language, englishDictionary, personalStore, allowLearn, userDictionary, spatialAutocorrect);
    if (words && kbSettings.autocorrect() && !spatialAutocorrect) spellChecker.open(this, Locale.forLanguageTag(language.code()));
    else spellChecker.close();
    CustomVocabulary vocab = new CustomVocabulary(this); vocabulary = vocab.isEnabled() ? vocab.terms() : List.of();
    action = (text && (type & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0 || (info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0)
        ? EditorInfo.IME_ACTION_NONE : info.imeOptions & EditorInfo.IME_MASK_ACTION;
    if (fullView != null) {
      fullView.enableDrums(labs.isEnabled(LabId.PUNCTUATION_DRUMS));
      fullView.setSettings(language, kbSettings.keyPopups(), kbSettings.haptics(), emojiEnabled && !privateField && !EditorDeletePolicy.raw(type));
      fullView.setPrivateField(privateField);
      fullView.setFieldKind(kind, actionLabel(), KeyboardLayout.initialLayerForInputClass(cls), !restarting);
    }
    meta(); drawState(idleState(), null); refresh(); Engine.get(this).prewarmAsync();
  }
  private String idleState() {
    return !privateField
        && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        && Engine.get(this).isAvailable() ? "ready" : "idle";
  }
  private String actionLabel() {
    return switch (action) {
      case EditorInfo.IME_ACTION_DONE -> "Done"; case EditorInfo.IME_ACTION_SEARCH -> "Search";
      case EditorInfo.IME_ACTION_SEND -> "Send"; case EditorInfo.IME_ACTION_GO -> "Go";
      case EditorInfo.IME_ACTION_NEXT -> "Next"; default -> "";
    };
  }
  private void meta() {
    if (barView == null) return;
    String id = speechLang.selectedId();
    barView.setMeta(hexSettings.style(), id, SpeechLanguage.AUTO.equals(id) ? "Auto" : languageLabel(id));
    barView.setReturnAction(!actionLabel().isEmpty());
  }
  private void resetEditor() { commitPendingQuickFixLearning(); rewriteGen++; rewriteSession = null; if (fullView != null) { fullView.setQuickFixBusy(false); fullView.closeRewrite(); } trackpad.end(); if (barView != null) barView.resetGesture(); interruptDictation("editor_closed"); setUndo(null); editor = null; lastSpace = 0; }
  @Override public void onFinishInputView(boolean finishing) { resetEditor(); if (fullView != null) fullView.resetSession(); super.onFinishInputView(finishing); }
  @Override public void onWindowShown() { super.onWindowShown(); KeyboardPresence.setImeVisible(true); }
  @Override public void onWindowHidden() { KeyboardPresence.setImeVisible(false); resetEditor(); if (fullView != null) fullView.resetSession(); super.onWindowHidden(); }
  @Override public void onDestroy() { KeyboardPresence.setImeVisible(false); if (spellChecker != null) spellChecker.close(); spatialReranker.release(); resetEditor(); main.removeCallbacksAndMessages(null); super.onDestroy(); }
  @Override public void onTrimMemory(int level) { super.onTrimMemory(level); if (level >= TRIM_MEMORY_BACKGROUND) { spatialReranker.release(); if (session == null) Engine.get(this).releaseIfIdle(); } }
  private InputConnection ic() { return editor == null ? null : getCurrentInputConnection(); }
  private String before(int n) {
    InputConnection c = ic(); CharSequence s = c == null ? null : c.getTextBeforeCursor(n, 0); return s == null ? "" : s.toString();
  }
  private static String word(String context) { return AutocorrectEngine.currentToken(context); }
  private void refresh() {
    InputConnection c = ic(); if (fullView == null || c == null) return;
    int explicitCap = editor.inputType & (InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_CAP_WORDS | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    int capFlags = explicitCap != 0 ? editor.inputType : (words ? (editor.inputType | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) : editor.inputType);
    fullView.setAutoCap(!privateField && c.getCursorCapsMode(capFlags) != 0);
    String context = words ? before(256) : "";
    String current = word(context);
    List<String> extraHints = lookupHints(context, current);
    List<String> suggestions = new ArrayList<>(autocorrect.suggestions(current, vocabulary, extraHints));
    boolean autoReplace = words && kbSettings.autocorrect() && autocorrect.willAutoReplace(current, extraHints);
    String emoji = emojiEnabled ? EmojiCatalog.suggestForContext(context) : null;
    if (emoji != null) { if (suggestions.size() == 3) suggestions.remove(2); suggestions.add(emoji); }
    fullView.setSuggestions(suggestions, autoReplace); fullView.setEmojiContext(context);
  }
  @Override public void onUpdateSelection(int a,int b,int x,int y,int cs,int ce) {
    super.onUpdateSelection(a,b,x,y,cs,ce); if (x != a || y != b) refresh();
  }
  private void commitPendingQuickFixLearning() {
    List<TypoFixExtractor.FixPair> pairs = pendingQuickFixPairs;
    pendingQuickFixPairs = null;
    if (pairs == null || pairs.isEmpty() || personalStore == null) return;
    for (TypoFixExtractor.FixPair pair : pairs) {
      personalStore.recordAccepted(pair.from(), pair.to());
    }
  }
  private void setUndo(DictationUndo value) {
    commitPendingQuickFixLearning();
    main.removeCallbacks(expireUndo); undo = value; rewriteUndoOriginal = null; rewriteUndoApplied = null;
    if (fullView != null) fullView.setUndoVisible(value != null);
    if (barView != null) barView.setUndoVisible(value != null);
    if (value != null) main.postDelayed(expireUndo, 12000);
  }
  private void setRewriteUndo(String original, String applied) {
    commitPendingQuickFixLearning();
    main.removeCallbacks(expireUndo); undo = null; rewriteUndoOriginal = original; rewriteUndoApplied = applied;
    if (fullView != null) fullView.setRewriteUndoVisible(true);
    if (barView != null) barView.setUndoVisible(true);
    main.postDelayed(expireUndo, 25000);
  }
  private void setQuickFixUndo(String original, String applied, List<TypoFixExtractor.FixPair> learnedPairs) {
    commitPendingQuickFixLearning();
    main.removeCallbacks(expireUndo); undo = null; rewriteUndoOriginal = original; rewriteUndoApplied = applied;
    pendingQuickFixPairs = learnedPairs;
    if (fullView != null) fullView.setUndoVisible(true, "Undo fix");
    if (barView != null) barView.setUndoVisible(true);
    main.postDelayed(expireUndo, 20000);
  }
  private void editing() { commitPendingQuickFixLearning(); lastSpace = 0; rewriteSession = null; autocorrect.clearUndo(); setUndo(null); }
  private void replace(int n, String s) {
    InputConnection c = ic(); if (c == null) return;
    c.beginBatchEdit(); try { if (n == 0 || c.deleteSurroundingText(n, 0)) c.commitText(s, 1); }
    finally { c.endBatchEdit(); } refresh();
  }
  public void onCharacter(String s) { if (ic() != null) { editing(); replace(0, s); } }
  public void onEmojiPick(String s) { onCharacter(s); }
  public void onSuggestionPick(String s) {
    if (ic() != null && words) {
      String typed = word(before(256));
      autocorrect.onSuggestionPicked(typed, s);
      editing();
      replace(typed.length(), s + " ");
    }
  }
  private List<String> lookupHints(String context, String current) {
    if (!words || !kbSettings.autocorrect() || current == null || current.isEmpty()) return List.of();
    if (spatialAutocorrect) {
      List<String> cands = autocorrect.spatialCandidates(current);
      if (cands.size() < 2) return List.of();
      String beforeWord = context.length() > current.length()
          ? context.substring(0, context.length() - current.length()) : "";
      String cached = spatialReranker.lookupCached(beforeWord, current, cands);
      if (cached != null) return List.of(cached);
      spatialReranker.requestRerank(this, beforeWord, current, cands, this::refresh);
      return List.of();
    }
    return spellChecker != null ? spellChecker.lookupOrQuery(current) : List.of();
  }
  public void onSpace() {
    if (ic() == null) return; setUndo(null);
    long now = SystemClock.uptimeMillis(); String s = words ? before(256) : "";
    if (lastSpace != 0 && now - lastSpace < 350 && s.endsWith(" ")) { editing(); replace(1, ". "); return; }
    lastSpace = now;
    List<String> extraHints = lookupHints(s, word(s));
    var fix = words && kbSettings.autocorrect() ? autocorrect.correctOnSpace(s, extraHints) : null;
    replace(fix == null ? 0 : fix.original().length(), fix == null ? " " : fix.caseMatched() + " ");
  }
  public void onDelete() { onDeleteChar(); }
  public void onDeleteChar() {
    InputConnection c = ic(); if (c == null) return;
    CharSequence selected = c.getSelectedText(0);
    String context = EditorDeletePolicy.raw(editor.inputType) ? "" : before(words ? 256 : 1);
    var fix = words ? autocorrect.checkBackspaceUndo(context) : null;
    editing();
    switch (EditorDeletePolicy.deletion(editor.inputType, selected, context)) {
      case SELECTION -> c.commitText("", 1);
      case KEY_EVENT -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
      case SURROUNDING_TEXT -> {
        if (fix != null) {
          CharSequence after = c.getTextAfterCursor(1, 0);
          if (after != null && after.length() > 0 && !Character.isWhitespace(after.charAt(0))) {
            c.beginBatchEdit();
            try {
              if (c.deleteSurroundingText(fix.caseMatched().length() + 1, 0)) {
                c.commitText(fix.original(), 1);
                c.commitText(" ", 0);
              }
            } finally { c.endBatchEdit(); }
            refresh();
          } else {
            replace(fix.caseMatched().length()+1, fix.original());
          }
          return;
        }
        if (!c.deleteSurroundingTextInCodePoints(1, 0)) c.deleteSurroundingText(1, 0);
      }
    }
    refresh();
  }
  public void onDeleteWord() {
    if (ic() == null) return; if (privateField) { onDeleteChar(); return; }
    editing(); String s = EditorDeletePolicy.raw(editor.inputType) ? "" : before(2048);
    if (EditorDeletePolicy.deletion(editor.inputType, null, s) == EditorDeletePolicy.Delete.KEY_EVENT) {
      sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL); refresh(); return;
    }
    int i = s.length();
    while (i>0 && Character.isWhitespace(s.charAt(i-1))) i--;
    while (i>0 && !Character.isWhitespace(s.charAt(i-1))) i--;
    ic().deleteSurroundingText(s.length()-i,0); refresh();
  }
  public void onCursorMove(int delta) {
    InputConnection c = ic(); if (c == null) return; editing();
    ExtractedText t = c.getExtractedText(new ExtractedTextRequest(), 0); if (t == null || t.text == null) return;
    int p = t.startOffset + Math.max(0, Math.min(t.text.length(), t.selectionEnd + delta)); c.setSelection(p,p); refresh();
  }
  public void onSelectionStart() {
    trackpad.end(); InputConnection c = ic(); if (c == null) return; editing();
    ExtractedText t = c.getExtractedText(new ExtractedTextRequest(), 0);
    if (t == null || t.text == null || t.startOffset < 0 || t.selectionStart < 0 || t.selectionEnd < 0) return;
    trackpadOffset = t.startOffset;
    trackpad.begin(t.selectionStart, t.selectionEnd, t.text.length());
  }
  public void onSelectionExtend(int delta) {
    var range = trackpad.move(delta); InputConnection c = ic();
    if (range != null && (c == null || !c.setSelection(trackpadOffset + range.anchor(), trackpadOffset + range.active()))) trackpad.end();
  }
  public void onSelectionEnd() { trackpad.end(); }
  public void onReturn() {
    if (ic() == null) return;
    editing(); String label = actionLabel();
    if (EditorDeletePolicy.sendEnter(editor.inputType, label)) sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER);
    else if (label.isEmpty()) replace(0,"\n");
    else ic().performEditorAction(action);
  }
  public void onSwitchKeyboard() { if (!switchToNextInputMethod(false)) getSystemService(InputMethodManager.class).showInputMethodPicker(); }
  public void onOpenApp() { startActivity(new Intent(this, HexHomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
  static String languageLabel(String id) {
    Locale locale = Locale.forLanguageTag(id);
    String name = locale.getDisplayLanguage(locale);
    if (name == null || name.isEmpty()) return id;
    int firstEnd = Character.charCount(name.codePointAt(0));
    return name.substring(0, firstEnd).toUpperCase(locale) + name.substring(firstEnd);
  }
  public void onStyleSelected(TranscriptStyle style) { hexSettings.setStyle(style); meta(); }
  public void onLanguageSelected(String id) { speechLang.select(id); meta(); }
  public void onKeyFeedback() {
    if (container != null && kbSettings.haptics()) container.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
  }
  public void onUndo() { onUndoDictation(); }
  private boolean at(InputConnection c, DictationUndo value) {
    SurroundingText s = c.getSurroundingText(value.inserted().length(),0,0);
    return s != null && s.getOffset() >= 0 && s.getSelectionStart()==s.getSelectionEnd()
        && value.matches(s.getOffset()+s.getSelectionStart(), s.getText().subSequence(0,s.getSelectionStart()));
  }
  public void onUndoDictation() {
    InputConnection c = ic();
    if (rewriteUndoOriginal != null && c != null) {
      // User undid a quick fix or rewrite: discard pending learning so rejected fixes are never learned.
      pendingQuickFixPairs = null;
      String orig = rewriteUndoOriginal;
      String applied = rewriteUndoApplied;
      setUndo(null);
      String current = readFullMessage(c);
      if (applied == null || current.equals(applied)) {
        replaceEntireMessage(c, orig);
        if (rewriteSession != null) rewriteSession = rewriteSession.withApplied(null);
      }
      refresh();
      return;
    }
    DictationUndo value = undo;
    if (value != null && c != null && at(c,value)) value.restore(c);
    setUndo(null); refresh();
  }
  private String readFullMessage(InputConnection c) {
    if (c == null) return "";
    ExtractedText et = c.getExtractedText(new ExtractedTextRequest(), 0);
    if (et != null && et.text != null) return et.text.toString();
    CharSequence before = c.getTextBeforeCursor(4000, 0);
    CharSequence after = c.getTextAfterCursor(4000, 0);
    return (before == null ? "" : before.toString()) + (after == null ? "" : after.toString());
  }
  private void replaceEntireMessage(InputConnection c, String replacement) {
    if (c == null) return;
    c.beginBatchEdit();
    try {
      c.finishComposingText();
      ExtractedText et = c.getExtractedText(new ExtractedTextRequest(), 0);
      if (et != null && et.text != null) {
        c.setSelection(0, et.text.length());
        c.commitText(replacement, 1);
      } else {
        CharSequence before = c.getTextBeforeCursor(4000, 0);
        CharSequence after = c.getTextAfterCursor(4000, 0);
        int bLen = before == null ? 0 : before.length();
        int aLen = after == null ? 0 : after.length();
        if (bLen > 0 || aLen > 0) c.deleteSurroundingText(bLen, aLen);
        c.commitText(replacement, 1);
      }
    } finally {
      c.endBatchEdit();
    }
  }
  @Override public void onQuickFix() {
    InputConnection c = ic();
    if (c == null || fullView == null) return;
    if (privateField) { notice("Quick fix unavailable in passwords"); return; }
    String draft = readFullMessage(c).trim();
    if (draft.isEmpty()) { notice("Type or dictate a message first"); return; }
    boolean allowLearn = !privateField && editor != null
        && (editor.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) == 0;
    long ticket = ++rewriteGen;
    fullView.setQuickFixBusy(true);
    notice("Fixing with screen…");
    anomaly.hex.android.service.HexBubbleService.captureScreenForKeyboard(screen -> {
      if (ticket != rewriteGen || fullView == null) {
        if (screen != null && !screen.isRecycled()) screen.recycle();
        return;
      }
      KeyboardRewriteClient.quickFix(this, draft, screen, new KeyboardRewriteClient.QuickFixCallback() {
        @Override public void onFixed(String fixedText, List<TypoFixExtractor.FixPair> learnedPairs, boolean usedScreen) {
          if (fullView != null) fullView.setQuickFixBusy(false);
          if (ticket != rewriteGen || fullView == null) return;
          InputConnection liveIc = ic();
          if (liveIc == null) return;
          String current = readFullMessage(liveIc).trim();
          if (!current.equals(draft)) return; // User edited while waiting; don't overwrite.
          if (fixedText == null || fixedText.isBlank() || fixedText.trim().equals(draft)) {
            notice("Looks good — no fixes needed");
            return;
          }
          String cleaned = fixedText.trim();
          replaceEntireMessage(liveIc, cleaned);
          setQuickFixUndo(draft, cleaned, allowLearn ? learnedPairs : List.of());
          refresh();
        }
        @Override public void onError(String message) {
          if (fullView != null) fullView.setQuickFixBusy(false);
          if (ticket != rewriteGen || fullView == null) return;
          notice(message);
        }
      });
    });
  }
  @Override public void onRewriteMessage() {
    InputConnection c = ic();
    if (c == null || fullView == null) return;
    if (privateField) { notice("Rewrite unavailable in passwords"); return; }
    String draft = readFullMessage(c).trim();
    if (draft.isEmpty()) { notice("Type or dictate a message first"); return; }
    // If the message still matches our cached rewrite batch, reopen instantly (0ms, $0) instead of re-querying.
    if (rewriteSession != null && rewriteSession.matchesEditorText(draft)) {
      reopenRewriteSession();
      return;
    }
    lastRewriteDraft = draft;
    lastRewriteInstruction = null;
    startRewrite(draft, null);
  }
  @Override public void onRewriteReopen() {
    InputConnection c = ic();
    if (c == null || fullView == null) return;
    if (rewriteSession != null && !rewriteSession.options.isEmpty()) {
      reopenRewriteSession();
      return;
    }
    onRewriteMessage();
  }
  private void reopenRewriteSession() {
    if (fullView == null || rewriteSession == null) return;
    fullView.showRewriteOptions(
        rewriteSession.options,
        rewriteSession.usedScreen,
        rewriteSession.usedWeb,
        rewriteSession.originalDraft,
        rewriteSession.appliedText);
  }
  @Override public void onRewriteWithPrompt() {
    InputConnection c = ic();
    if (c == null || fullView == null) return;
    if (privateField) { notice("Rewrite unavailable in passwords"); return; }
    lastRewriteDraft = (rewriteSession != null && !rewriteSession.originalDraft.isEmpty())
        ? rewriteSession.originalDraft : readFullMessage(c).trim();
    fullView.showRewritePrompt(lastRewriteInstruction);
  }
  @Override public void onRewriteSubmitPrompt(String instruction) {
    InputConnection c = ic();
    if (c == null || fullView == null) return;
    String inst = instruction == null ? "" : instruction.trim();
    if (inst.isEmpty()) return;
    String current = readFullMessage(c).trim();
    String draft = (rewriteSession != null && rewriteSession.matchesEditorText(current) && !rewriteSession.originalDraft.isEmpty())
        ? rewriteSession.originalDraft : current;
    lastRewriteDraft = draft;
    lastRewriteInstruction = inst;
    startRewrite(draft, inst);
  }
  @Override public void onRewriteRetry() {
    InputConnection c = ic();
    String current = c != null ? readFullMessage(c).trim() : "";
    String draft = (rewriteSession != null && rewriteSession.matchesEditorText(current) && !rewriteSession.originalDraft.isEmpty())
        ? rewriteSession.originalDraft : current;
    if (draft.isEmpty() && lastRewriteDraft != null) draft = lastRewriteDraft;
    if (draft.isEmpty() && (lastRewriteInstruction == null || lastRewriteInstruction.isBlank())) return;
    startRewrite(draft, lastRewriteInstruction);
  }
  @Override public void onRewriteClose() {
    rewriteGen++;
  }
  @Override public void onRewritePick(String replacement) {
    rewriteGen++;
    if (fullView != null) fullView.closeRewrite();
    InputConnection c = ic();
    if (c == null || replacement == null || replacement.isBlank()) return;
    String current = readFullMessage(c);
    String trueOriginal = (rewriteSession != null && !rewriteSession.originalDraft.isEmpty())
        ? rewriteSession.originalDraft : current;
    replaceEntireMessage(c, replacement);
    boolean restoredOriginal = replacement.equals(trueOriginal);
    if (rewriteSession != null) {
      rewriteSession = rewriteSession.withApplied(restoredOriginal ? null : replacement);
    }
    if (restoredOriginal) {
      setUndo(null);
    } else {
      setRewriteUndo(trueOriginal, replacement);
    }
    refresh();
  }
  private void startRewrite(String draft, String instruction) {
    if (fullView == null) return;
    long ticket = ++rewriteGen;
    rewriteSession = null;
    fullView.showRewriteLoading(draft, instruction, true);
    anomaly.hex.android.service.HexBubbleService.captureScreenForKeyboard(screen -> {
      if (ticket != rewriteGen || fullView == null) {
        if (screen != null && !screen.isRecycled()) screen.recycle();
        return;
      }
      fullView.showRewriteLoading(draft, instruction, screen != null);
      KeyboardRewriteClient.rewrite(this, draft, instruction, screen, new KeyboardRewriteClient.Callback() {
        @Override public void onOptions(List<KeyboardRewriteClient.Option> options, boolean usedScreen, boolean usedWeb) {
          if (ticket != rewriteGen || fullView == null) return;
          rewriteSession = new KeyboardRewriteClient.Session(draft, instruction, options, usedScreen, usedWeb, null);
          fullView.showRewriteOptions(options, usedScreen, usedWeb, draft, null);
        }
        @Override public void onError(String message) {
          if (ticket != rewriteGen || fullView == null) return;
          fullView.showRewriteError(message);
        }
      });
    });
  }
  private void drawState(String value,int[] levels) {
    state = value; if (fullView != null) fullView.setDictationState(value,levels); if (barView != null) barView.setDictationState(value,levels);
  }
  public void onDictateToggle() { if (session == null) onStartDictation(); else onStopDictation(); }
  public void onCancel() { onCancelDictation(); }
  private boolean verifiedCursor(InputConnection target) {
    SurroundingText s = target.getSurroundingText(0,0,0);
    if (s != null && s.getOffset()>=0 && live.matches(s.getOffset()+s.getSelectionStart(),s.getOffset()+s.getSelectionEnd())) return true;
    interruptDictation("cursor_moved"); notice("Dictation interrupted — audio saved to History"); return false;
  }
  private static String drumName(String mark) {
    return switch (mark) {
      case "," -> "Comma";
      case "." -> "Period";
      case "?" -> "Question";
      case "!" -> "Exclamation";
      case "\n" -> "New line";
      case "\n\n" -> "Paragraph";
      default -> "Punctuation";
    };
  }
  private void applyPunctuation(InputConnection c, String mark) {
    if (live == null || c == null || c != speechTarget || !verifiedCursor(c)) return;
    var edit = live.tap(mark, before(256));
    c.beginBatchEdit();
    try {
      if ((edit.trim() > 0 && !c.deleteSurroundingText(edit.trim(), 0)) || !c.commitText(edit.text(), 1)) {
        interruptDictation("editor_failed"); notice("Editor closed — audio saved to History"); return;
      }
      live.committed(edit, true); setUndo(null);
    } finally { c.endBatchEdit(); }
    refresh();
  }
  public void onLivePunctuation(String mark) {
    InputConnection c = ic();
    if (session==null || c==null || c!=speechTarget || !(state.equals("listening") || state.equals("recording"))
        || !labs.isEnabled(LabId.PUNCTUATION_DRUMS) || !verifiedCursor(c)) return;
    session.insertPunctuation(mark);
    onKeyFeedback();
    if (fullView != null) fullView.flashDrum(drumName(mark));
  }
  private android.os.PowerManager.WakeLock wakeLock;
  private void acquireWakeLock() {
    releaseWakeLock();
    try {
      var pm = getSystemService(android.os.PowerManager.class);
      if (pm != null) {
        wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Hex:Dictation");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(30L * 60L * 1000L);
      }
    } catch (RuntimeException ignored) {}
  }
  private void releaseWakeLock() {
    if (wakeLock != null) {
      try { if (wakeLock.isHeld()) wakeLock.release(); } catch (RuntimeException ignored) {}
      wakeLock = null;
    }
  }
  private volatile boolean explicitCancel;
  private void interruptDictation(String reason) {
    if (session == null) return;
    explicitCancel = false;
    generation++; live=null; speechTarget=null; releaseWakeLock();
    session.cancel(); session = null; drawState(idleState(),null);
  }
  public void onCancelDictation() {
    explicitCancel = true;
    generation++; live=null; speechTarget=null; releaseWakeLock();
    if (session != null) session.cancel();
    session = null; drawState(idleState(),null);
  }
  public void onStopDictation() { if (session != null) { session.finish(); drawState("transcribing",null); } }
  private void notice(String message) { notice(message, false); }
  private void notice(String message, boolean openApp) {
    if (fullView != null) fullView.showNotice(message, openApp);
    else Toast.makeText(this,message,Toast.LENGTH_SHORT).show();
  }
  public void onStartDictation() {
    InputConnection target = ic(); if (session != null || target == null) return;
    if (privateField) { notice("Dictation unavailable in passwords"); return; }
    if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { notice("Allow mic in Hex", true); return; }
    if (!Engine.get(this).isAvailable()) { notice("Install model in Hex", true); return; }
    SurroundingText anchor = target.getSurroundingText(0,0,0);
    if (anchor == null || anchor.getOffset() < 0) { notice("This editor cannot confirm the dictation target"); return; }
    CharSequence selected = target.getSelectedText(0);
    String replacedSelection = selected == null || selected.length() == 0 ? null : selected.toString();
    if (Math.abs(anchor.getSelectionEnd() - anchor.getSelectionStart()) != (replacedSelection == null ? 0 : replacedSelection.length())) {
      notice("This editor cannot confirm the dictation target"); return;
    }
    editing(); long id = ++generation; explicitCancel = false;
    TranscriptStyle style = hexSettings.style(); String lang = SpeechLanguage.resolveLocale(speechLang.selectedId(),Locale.getDefault()).toLanguageTag();
    var fixStore = new DictationFixStore(this); Map<String,String> fixes = fixStore.isEnabled() ? fixStore.activeFixes() : Map.of();
    boolean spoken = labs.isEnabled(LabId.SPOKEN_CLEANUP); List<String> vocab = vocabulary;
    boolean allowHistory = (editor.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) == 0;
    anomaly.hex.android.speech.RecordingStore.Writer recWriter = null;
    if (allowHistory && hexSettings.historyRetention() != HistoryRetention.OFF) {
      try { recWriter = new anomaly.hex.android.speech.RecordingStore.Writer(this); } catch (java.io.IOException ignored) {}
    }
    final anomaly.hex.android.speech.RecordingStore.Writer recorder = recWriter;
    acquireWakeLock();
    // Format each delivered phrase against its verified live cursor, not the cursor at mic start.
    speechTarget = target; live = new LivePunctuationSession(anchor.getOffset()+anchor.getSelectionStart(),anchor.getOffset()+anchor.getSelectionEnd());
    session = new SpeechSession(this, Engine.get(this), () -> new Microphone(this,hexSettings.preferBuiltInMic()), raw -> raw, new SpeechSession.Sink() {
      DictationUndo replacement;
      final StringBuilder allRaw = new StringBuilder();
      final StringBuilder allFinal = new StringBuilder();
      long totalMs;
      void post(Runnable r) { main.post(() -> { if (generation == id && ic() == target) r.run(); }); }
      public void onMeter(int[] levels) { post(() -> { if (state.equals("listening") || state.equals("recording")) drawState(state,levels); }); }
      public void onCheckpoint(byte[] pcm) { if (recorder != null) recorder.appendPcm(pcm); }
      public void onError(String code,String detail) { post(() -> notice("Dictation: " + detail)); }
      public void onPunctuationReady(String mark) { post(() -> {
        applyPunctuation(target, mark);
        if (allFinal.length() > 0) allFinal.append(mark.equals("\n") || mark.equals("\n\n") ? mark : mark + " ");
      }); }
      public void onStateChanged(String s) {
        if (s.equals("finished") || s.equals("cancelled") || s.equals("error")) {
          boolean interrupted = (s.equals("cancelled") && !explicitCancel) || s.equals("error");
          boolean keep = (!s.equals("cancelled") || interrupted)
              && allowHistory && hexSettings.historyRetention() != HistoryRetention.OFF;
          String wavPath = recorder != null ? recorder.finish(HexInputMethodService.this, keep) : null;
          long recMs = recorder != null ? Math.max(totalMs, recorder.durationMs()) : totalMs;
          main.post(() -> {
            if (keep && (allFinal.length() > 0 || (wavPath != null && recMs >= 1000))) {
              String rawStr = allRaw.toString().trim();
              String finalStr;
              if (allFinal.length() > 0) {
                finalStr = interrupted
                    ? allFinal.toString().trim() + " [interrupted]"
                    : allFinal.toString().trim();
              } else {
                finalStr = "(Interrupted recording — tap Re-transcribe)";
              }
              history.record(rawStr, finalStr, style, recMs, hexSettings.historyRetention(), wavPath);
            } else if (wavPath != null) {
              anomaly.hex.android.speech.RecordingStore.deleteFile(wavPath);
            }
            if (generation == id) {
              releaseWakeLock(); session = null; speechTarget = null; live = null; drawState(idleState(),null);
            }
          });
        } else {
          post(() -> { if (!state.equals("transcribing") || !s.equals("listening")) drawState(s,null); });
        }
      }
      public void onPhraseReady(String raw,String ignored,long ms) { post(() -> {
        if (!verifiedCursor(target)) return;
        if (replacedSelection != null && (replacement == null
            ? (live.start() != live.end() && !replacedSelection.equals(java.util.Objects.toString(target.getSelectedText(0), null)))
            : !at(target, replacement))) {
          interruptDictation("text_changed"); notice("Dictation interrupted — audio saved to History"); return;
        }
        var edit = live.phrase(TranscriptFormatter.applyPipeline(raw,style,vocab,spoken,before(128),fixes,lang));
        String text = edit.text();
        if (text.isBlank() || !target.commitText(text,1)) return;
        live.committed(edit,false); int end=live.end();
        DictationUndo value = replacement == null ? new DictationUndo(text,end,replacedSelection) : replacement.append(text);
        if (replacedSelection != null) replacement = value;
        setUndo(at(target,value) ? value : null);
        if (allRaw.length() > 0) allRaw.append(' ');
        allRaw.append(raw.trim());
        allFinal.append(text);
        totalMs += ms;
        refresh();
      }); }
    });
    drawState("listening",null); session.start();
  }
}
