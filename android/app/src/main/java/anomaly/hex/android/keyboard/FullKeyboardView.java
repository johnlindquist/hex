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
        void onReturn();
        void onCursorMove(int delta);
        void onSelectionStart();
        void onSelectionExtend(int delta);
        void onSelectionEnd();
        void onSuggestionPick(String word);
        void onEmojiPick(String emoji);
        void onUndoDictation();
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
    private PunctuationDrumsOverlay drums;
    private boolean emojiEnabled, emojiOpen, rewriteOpen;

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
    public void showNotice(String message, boolean openApp) { closeEmoji(); closeRewrite(); strip.showNotice(message, openApp); }
    public void setFieldKind(KeyboardLayout.FieldKind kind,String returnLabel) {
        setFieldKind(kind, returnLabel, KeyboardLayout.Layer.LETTERS, true);
    }
    public void setFieldKind(KeyboardLayout.FieldKind kind,String returnLabel,KeyboardLayout.Layer initialLayer,boolean resetLayer) {
        if(resetLayer) { closeEmoji(); closeRewrite(); }
        plane.field(kind,returnLabel,initialLayer,resetLayer);
    }
    public void resetSession() {
        closeEmoji(); closeRewrite(); plane.resetSession();
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
    private final class PlaneHost implements KeyPlaneView.Input {
        private boolean inRewritePrompt() { return rewriteOpen && rewritePanel!=null && rewritePanel.prompting(); }
        public void onCharacter(String s) { if(emojiOpen) panel.append(s); else if(inRewritePrompt()) rewritePanel.append(s); else host.onCharacter(s); }
        public void onDeleteChar() { if(emojiOpen) panel.erase(); else if(inRewritePrompt()) rewritePanel.erase(); else host.onDeleteChar(); }
        public void onDeleteWord() { if(emojiOpen) panel.eraseWord(); else if(inRewritePrompt()) rewritePanel.eraseWord(); else host.onDeleteWord(); }
        public void onSpace() { if(emojiOpen) panel.append(" "); else if(inRewritePrompt()) rewritePanel.append(" "); else host.onSpace(); }
        public void onReturn() { if(emojiOpen) toggleSearch(); else if(inRewritePrompt()) rewritePanel.submitPrompt(); else host.onReturn(); }
        public void onSelectionStart() { if(!emojiOpen && !inRewritePrompt()) host.onSelectionStart(); }
        public void onSelectionExtend(int d) { if(!emojiOpen && !inRewritePrompt()) host.onSelectionExtend(d); }
        public void onSelectionEnd() { host.onSelectionEnd(); }
        public void onCursorMove(int d) { if(!emojiOpen && !inRewritePrompt()) host.onCursorMove(d); }
        public void onSwitchKeyboard() { host.onSwitchKeyboard(); }
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
