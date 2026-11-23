package anomaly.hex.android.keyboard;

import android.content.Context;
import android.widget.FrameLayout;
import java.util.List;
import static anomaly.hex.android.keyboard.KeyboardPalette.*;

/** Keyboard surface only. The IME host owns editor mutations (including double-space period). */
public final class FullKeyboardView extends FrameLayout {
    public interface Host {
        void onCharacter(String text);
        void onDeleteChar();
        void onDeleteWord();
        void onSpace();
        void onTwoThumbSpace();
        void onReturn();
        void onCursorMove(int delta);
        void onSelectionStart();
        void onSelectionExtend(int delta);
        void onSelectionEnd();
        void onSuggestionPick(String word);
        void onEmojiPick(String emoji);
        void onKeyTapTelemetry(String keyLabel, String kind, float tapXdp, float tapYdp,
                               float keyCenterXdp, float keyCenterYdp, String layer, float kbWidthDp);
        void onSpacebarTailTrigger(KeyboardSettings.SpacebarFixMode mode);
        void onTailFixApply(int replaceLength, String replacement, String modeId, String originalTail);
        void onUndoDictation();
        void onQuickFix();
        void onRewriteMessage();
        void onRewriteWithPrompt();
        void onRewriteSubmitPrompt(String instruction);
        void onRewritePick(String replacement);
        void onRewriteReopen();
        void onRewriteRetry();
        void onRewriteClose();
        void onStartDictation();
        void onLivePunctuation(String mark);
        void onStopDictation();
        void onCancelDictation();
        void onSwitchKeyboard();
        void onOpenApp();
    }
    private final Host host;
    private final SuggestionStripView strip;
    private final KeyPlaneView plane;
    private EmojiPanelView panel;
    private RewritePanelView rewritePanel;
    private TailFixPanelView tailFixPanel;
    private SpacebarSlideOverlayView slideOverlay;
    private PunctuationDrumsOverlay drums;
    private boolean emojiEnabled, emojiOpen, rewriteOpen, tailPanelOpen;
    private TailCorrectionEngine.TailAnalysis activeTailAnalysis;

    public FullKeyboardView(Context c, Host host) {
        super(c); this.host=host; setBackgroundColor(BACKDROP); setClipChildren(false); setClipToPadding(false);
        strip=new SuggestionStripView(c,host,this::toggleEmoji); plane=new KeyPlaneView(c,new PlaneHost(),this::toggleEmoji);
        addView(strip,new LayoutParams(-1,dp(c,plane.landscape()?42:50))); addView(plane,new LayoutParams(-1,-2));
    }
    void enableDrums(boolean enabled) {
        if(enabled && drums==null) {
            drums=new PunctuationDrumsOverlay(getContext(),host);
            addView(drums,new LayoutParams(-1,-1));
        } else if(!enabled && drums!=null) { removeView(drums); drums=null; }
    }
    private void toggleEmoji() {
        if(!emojiEnabled) { closeEmoji(); return; }
        if(emojiOpen) {
            if(panel.searching()) toggleSearch();
            else closeEmoji();
            return;
        }
        if(panel==null) {
            panel=new EmojiPanelView(getContext(),host,this::closeEmoji,this::toggleSearch);
            addView(panel,indexOfChild(plane),new LayoutParams(-1,-1));
        }
        closeRewrite();
        emojiOpen=true; panel.setVisibility(VISIBLE); panel.open(); panel.contextEmoji(emojiContext);
        plane.setSearching(false); strip.setVisibility(INVISIBLE); plane.setVisibility(INVISIBLE);
    }
    private void ensureRewritePanel() {
        if(rewritePanel==null) {
            rewritePanel=new RewritePanelView(
                getContext(),
                opt -> host.onRewritePick(opt.text),
                instruction -> {
                    plane.setSearching(false);
                    plane.setVisibility(INVISIBLE);
                    requestLayout();
                    host.onRewriteSubmitPrompt(instruction);
                },
                host::onRewriteWithPrompt,
                host::onRewriteRetry,
                () -> { closeRewrite(); host.onRewriteClose(); });
            addView(rewritePanel,indexOfChild(plane),new LayoutParams(-1,-1));
        }
    }
    public void showRewritePrompt(String initialPrompt) {
        closeEmoji();
        ensureRewritePanel();
        rewriteOpen=true;
        rewritePanel.openPrompt(initialPrompt);
        rewritePanel.setVisibility(VISIBLE);
        strip.setVisibility(INVISIBLE);
        plane.letters();
        plane.setSearching(true);
        plane.autoCap(false);
        plane.setVisibility(VISIBLE);
        requestLayout();
    }
    public void showRewriteLoading(String draft, String instruction, boolean readingScreen) {
        closeEmoji();
        ensureRewritePanel();
        rewriteOpen=true;
        rewritePanel.showLoading(draft, instruction, readingScreen);
        rewritePanel.setVisibility(VISIBLE);
        strip.setVisibility(INVISIBLE);
        plane.setSearching(false);
        plane.setVisibility(INVISIBLE);
        requestLayout();
    }
    public void showRewriteOptions(List<KeyboardRewriteClient.Option> options, boolean usedScreen, boolean usedWeb) {
        showRewriteOptions(options, usedScreen, usedWeb, null, null);
    }
    public void showRewriteOptions(List<KeyboardRewriteClient.Option> options, boolean usedScreen, boolean usedWeb,
                                   String originalDraft, String appliedText) {
        closeEmoji();
        ensureRewritePanel();
        rewriteOpen=true;
        rewritePanel.showOptions(options, usedScreen, usedWeb, originalDraft, appliedText);
        rewritePanel.setVisibility(VISIBLE);
        strip.setVisibility(INVISIBLE);
        plane.setSearching(false);
        plane.setVisibility(INVISIBLE);
        requestLayout();
    }
    public void showRewriteError(String error) {
        if(!rewriteOpen || rewritePanel==null) return;
        rewritePanel.showError(error);
    }
    public void closeRewrite() {
        if(!rewriteOpen) return;
        rewriteOpen=false;
        if(rewritePanel!=null) rewritePanel.setVisibility(GONE);
        plane.setSearching(false);
        if(!emojiOpen) {
            plane.letters();
            plane.setVisibility(VISIBLE);
            strip.setVisibility(VISIBLE);
        }
        requestLayout();
    }
    private void toggleSearch() {
        boolean search=!panel.searching(); panel.search(search); plane.letters(); plane.setSearching(search); if(search) plane.autoCap(false); plane.setVisibility(search?VISIBLE:INVISIBLE); requestLayout();
    }
    private void closeEmoji() {
        if(!emojiOpen) return;
        requestLayout();
        emojiOpen=false; panel.setVisibility(GONE); plane.setSearching(false); plane.letters(); plane.setVisibility(VISIBLE); strip.setVisibility(VISIBLE);
    }
    private String emojiContext;
    public void setEmojiContext(String context) {
        emojiContext=emojiEnabled ? EmojiCatalog.suggestForContext(context) : null;
        strip.contextEmoji(emojiContext);
        if(emojiOpen) panel.contextEmoji(emojiContext);
    }
    public void setDictationState(String state,int[] meterLevels) {
        if("listening".equalsIgnoreCase(state)||"recording".equalsIgnoreCase(state)||"transcribing".equalsIgnoreCase(state)) { closeEmoji(); closeRewrite(); }
        if(drums!=null) {
            boolean live="listening".equals(state)||"recording".equals(state);
            drums.update(live,meterLevels);
            plane.setImportantForAccessibility(live ? IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS : IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        }
        strip.state(state,meterLevels);
    }
    public void flashDrum(String label) { if(drums!=null) drums.flashDrum(label); }
    public void setSuggestions(List<String> items) { setSuggestions(items, false); }
    public void setSuggestions(List<String> items, boolean autoReplace) { strip.suggestions(items==null?List.of():items, autoReplace); }
    public void setUndoVisible(boolean visible) { strip.setUndo(visible); }
    public void setUndoVisible(boolean visible, String label) { strip.setUndo(visible, label, false); }
    public void setRewriteUndoVisible(boolean visible) { strip.setUndo(visible, "Original", visible); }
    public void setQuickFixBusy(boolean busy) { strip.setQuickFixBusy(busy); }
    public void showNotice(String message, boolean openApp) { closeEmoji(); closeRewrite(); strip.showNotice(message, openApp); }
    public void setFieldKind(KeyboardLayout.FieldKind kind,String returnLabel) {
        setFieldKind(kind, returnLabel, KeyboardLayout.Layer.LETTERS, true);
    }
    public void setFieldKind(KeyboardLayout.FieldKind kind,String returnLabel,KeyboardLayout.Layer initialLayer,boolean resetLayer) {
        if(resetLayer) { closeEmoji(); closeRewrite(); }
        plane.field(kind,returnLabel,initialLayer,resetLayer);
    }
    public void resetSession() {
        closeEmoji(); closeRewrite(); closeTailFix(); plane.resetSession();
    }
    public void setPrivateField(boolean privateField) {
        strip.setPrivateField(privateField);
    }
    public void setAutoCap(boolean value) {
        boolean typingInPanel = (emojiOpen && panel!=null && panel.searching()) || (rewriteOpen && rewritePanel!=null && rewritePanel.prompting());
        if(!typingInPanel) plane.autoCap(value);
    }
    public void setSettings(KeyboardLanguage language,boolean keyPopups,boolean haptics,boolean emojiEnabled) {
        this.emojiEnabled=emojiEnabled; strip.setEmojiEnabled(emojiEnabled);
        if(!emojiEnabled) { closeEmoji(); emojiContext=null; strip.contextEmoji(null); }
        plane.settings(language,keyPopups,haptics,emojiEnabled);
    }
    public void setTwoThumbSpace(boolean enabled) {
        plane.setTwoThumbSpace(enabled);
    }
    public void setSpacebarFix(boolean enabled, KeyboardSettings.SpacebarFixMode mode) {
        plane.setSpacebarFix(enabled, mode);
    }
    public void setQuickAccents(boolean enabled) {
        plane.setQuickAccents(enabled);
    }
    public void showTailScrubber(TailCorrectionEngine.TailAnalysis analysis) {
        closeTailPanel();
        hideTailSlide();
        activeTailAnalysis = analysis;
        if (analysis == null || analysis.isEmpty()) {
            strip.showNotice("Type a few words first", false);
            return;
        }
        renderTailScrubber(analysis.defaultFocusIndex());
    }
    private void renderTailScrubber(int focusIdx) {
        if (activeTailAnalysis == null) return;
        strip.showScrubber(activeTailAnalysis, focusIdx,
                this::renderTailScrubber,
                (wordIdx, cand) -> {
                    TailCorrectionEngine.TailAnalysis a = activeTailAnalysis;
                    String replaced = TailCorrectionEngine.replaceWord(a, wordIdx, cand);
                    String fromWord = a.words().get(wordIdx).original();
                    closeTailFix();
                    host.onTailFixApply(a.replaceLength(), replaced,
                            KeyboardSettings.SpacebarFixMode.FLICK_SCRUBBER.id, fromWord + " -> " + cand);
                },
                this::closeTailFix);
    }
    public void showTailPanel(TailCorrectionEngine.TailAnalysis analysis) {
        closeEmoji();
        closeRewrite();
        strip.hideScrubber();
        hideTailSlide();
        activeTailAnalysis = analysis;
        if (tailFixPanel == null) {
            tailFixPanel = new TailFixPanelView(getContext(), this::closeTailFix);
            addView(tailFixPanel, indexOfChild(plane), new LayoutParams(-1, -1));
        }
        tailPanelOpen = true;
        tailFixPanel.show(analysis,
                phrase -> {
                    TailCorrectionEngine.TailAnalysis a = activeTailAnalysis;
                    closeTailFix();
                    if (a != null) {
                        host.onTailFixApply(a.replaceLength(), phrase.replacement(),
                                KeyboardSettings.SpacebarFixMode.LONG_PRESS_PANEL.id, a.originalTail().trim());
                    }
                },
                (wordIdx, cand) -> {
                    TailCorrectionEngine.TailAnalysis a = activeTailAnalysis;
                    if (a == null) return;
                    String replaced = TailCorrectionEngine.replaceWord(a, wordIdx, cand);
                    closeTailFix();
                    host.onTailFixApply(a.replaceLength(), replaced,
                            KeyboardSettings.SpacebarFixMode.LONG_PRESS_PANEL.id, a.words().get(wordIdx).original());
                });
        tailFixPanel.setVisibility(VISIBLE);
        strip.setVisibility(INVISIBLE);
        plane.setVisibility(INVISIBLE);
        requestLayout();
    }
    public void showTailSlide(TailCorrectionEngine.TailAnalysis analysis) {
        strip.hideScrubber();
        activeTailAnalysis = analysis;
        if (slideOverlay == null) {
            slideOverlay = new SpacebarSlideOverlayView(getContext());
            addView(slideOverlay, new LayoutParams(-1, -1));
        }
        slideOverlay.show(analysis == null ? List.of() : analysis.phraseFixes());
        slideOverlay.setVisibility(VISIBLE);
        slideOverlay.bringToFront();
    }
    public void closeTailFix() {
        strip.hideScrubber();
        closeTailPanel();
        hideTailSlide();
        activeTailAnalysis = null;
    }
    private void closeTailPanel() {
        if (!tailPanelOpen) return;
        tailPanelOpen = false;
        if (tailFixPanel != null) tailFixPanel.setVisibility(GONE);
        if (!emojiOpen && !rewriteOpen) {
            plane.setVisibility(VISIBLE);
            strip.setVisibility(VISIBLE);
        }
        requestLayout();
    }
    private void hideTailSlide() {
        if (slideOverlay != null) slideOverlay.setVisibility(GONE);
    }
    private final class PlaneHost implements KeyPlaneView.Input {
        private boolean inRewritePrompt() { return rewriteOpen && rewritePanel!=null && rewritePanel.prompting(); }
        public void onCharacter(String s) { if(emojiOpen) panel.append(s); else if(inRewritePrompt()) rewritePanel.append(s); else host.onCharacter(s); }
        public void onDeleteChar() { if(emojiOpen) panel.erase(); else if(inRewritePrompt()) rewritePanel.erase(); else host.onDeleteChar(); }
        public void onDeleteWord() { if(emojiOpen) panel.eraseWord(); else if(inRewritePrompt()) rewritePanel.eraseWord(); else host.onDeleteWord(); }
        public void onSpace() { if(emojiOpen) panel.append(" "); else if(inRewritePrompt()) rewritePanel.append(" "); else host.onSpace(); }
        public void onTwoThumbSpace() { if(emojiOpen) panel.append(" "); else if(inRewritePrompt()) rewritePanel.append(" "); else host.onTwoThumbSpace(); }
        public void onReturn() { if(emojiOpen) toggleSearch(); else if(inRewritePrompt()) rewritePanel.submitPrompt(); else host.onReturn(); }
        public void onSelectionStart() { if(!emojiOpen && !inRewritePrompt()) host.onSelectionStart(); }
        public void onSelectionExtend(int d) { if(!emojiOpen && !inRewritePrompt()) host.onSelectionExtend(d); }
        public void onSelectionEnd() { host.onSelectionEnd(); }
        public void onCursorMove(int d) { if(!emojiOpen && !inRewritePrompt()) host.onCursorMove(d); }
        public void onSwitchKeyboard() { host.onSwitchKeyboard(); }
        public void onKeyTapTelemetry(String keyLabel, String kind, float tapXdp, float tapYdp,
                                      float keyCenterXdp, float keyCenterYdp, String layer, float kbWidthDp) {
            if (!emojiOpen && !inRewritePrompt()) {
                host.onKeyTapTelemetry(keyLabel, kind, tapXdp, tapYdp, keyCenterXdp, keyCenterYdp, layer, kbWidthDp);
            }
        }
        public void onSpacebarFlickUp() {
            if (!emojiOpen && !inRewritePrompt()) host.onSpacebarTailTrigger(KeyboardSettings.SpacebarFixMode.FLICK_SCRUBBER);
        }
        public void onSpacebarLongPress() {
            if (!emojiOpen && !inRewritePrompt()) host.onSpacebarTailTrigger(KeyboardSettings.SpacebarFixMode.LONG_PRESS_PANEL);
        }
        public void onSpacebarSlideStart() {
            if (!emojiOpen && !inRewritePrompt()) host.onSpacebarTailTrigger(KeyboardSettings.SpacebarFixMode.HOLD_SLIDE_PICKER);
        }
        public void onSpacebarSlideMove(float dyUpDp) {
            if (slideOverlay != null && slideOverlay.getVisibility() == VISIBLE) {
                if (slideOverlay.updateSlide(dyUpDp)) {
                    performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
                }
            }
        }
        public void onSpacebarSlideEnd() {
            if (slideOverlay != null && slideOverlay.getVisibility() == VISIBLE) {
                TailCorrectionEngine.PhraseFix pick = slideOverlay.currentPick();
                TailCorrectionEngine.TailAnalysis a = activeTailAnalysis;
                hideTailSlide();
                activeTailAnalysis = null;
                if (pick != null && a != null) {
                    host.onTailFixApply(a.replaceLength(), pick.replacement(),
                            KeyboardSettings.SpacebarFixMode.HOLD_SLIDE_PICKER.id, a.originalTail().trim());
                }
            }
        }
    }
    @Override protected void onMeasure(int w,int h) {
        plane.measure(w,MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));
        strip.getLayoutParams().height=dp(getContext(),plane.landscape()?42:50);
        if(drums!=null) ((LayoutParams)drums.getLayoutParams()).topMargin=strip.getLayoutParams().height;
        boolean lifted = (emojiOpen && panel!=null && panel.searching()) || (rewriteOpen && rewritePanel!=null && rewritePanel.prompting());
        int lift=lifted ? dp(getContext(),96)-strip.getLayoutParams().height : 0;
        ((LayoutParams)plane.getLayoutParams()).topMargin=lift;
        super.onMeasure(w,MeasureSpec.makeMeasureSpec(resolveSize(plane.getMeasuredHeight()+lift,h),MeasureSpec.EXACTLY));
    }
}
