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
        void onSuggestionPick(String word);
        void onEmojiPick(String emoji);
        void onUndoDictation();
        void onStartDictation();
        void onStopDictation();
        void onCancelDictation();
        void onSwitchKeyboard();
    }
    private final Host host;
    private final SuggestionStripView strip;
    private final KeyPlaneView plane;
    private EmojiPanelView panel;
    private boolean emojiEnabled, emojiOpen;

    public FullKeyboardView(Context c, Host host) {
        super(c); this.host=host; setBackgroundColor(BACKDROP); setClipChildren(false); setClipToPadding(false);
        strip=new SuggestionStripView(c,host); plane=new KeyPlaneView(c,new PlaneHost(),this::toggleEmoji);
        addView(strip,new LayoutParams(-1,dp(c,plane.landscape()?39:44))); addView(plane,new LayoutParams(-1,-2));
    }
    private void toggleEmoji() {
        if(!emojiEnabled || emojiOpen) { closeEmoji(); return; }
        if(panel==null) {
            panel=new EmojiPanelView(getContext(),host,this::closeEmoji,this::toggleSearch);
            addView(panel,indexOfChild(plane),new LayoutParams(-1,-1));
        }
        emojiOpen=true; panel.setVisibility(VISIBLE); panel.open(); panel.contextEmoji(emojiContext);
        strip.setVisibility(INVISIBLE); plane.setVisibility(INVISIBLE);
    }
    private void toggleSearch() {
        boolean search=!panel.searching(); panel.search(search); plane.letters(); plane.setVisibility(search?VISIBLE:INVISIBLE); requestLayout();
    }
    private void closeEmoji() {
        if(!emojiOpen) return;
        requestLayout();
        emojiOpen=false; panel.setVisibility(GONE); plane.letters(); plane.setVisibility(VISIBLE); strip.setVisibility(VISIBLE);
    }
    private String emojiContext;
    public void setEmojiContext(String context) {
        emojiContext=emojiEnabled ? EmojiCatalog.suggestForContext(context) : null;
        strip.contextEmoji(emojiContext);
        if(emojiOpen) panel.contextEmoji(emojiContext);
    }
    public void setDictationState(String state,int[] meterLevels) {
        if("listening".equalsIgnoreCase(state)||"recording".equalsIgnoreCase(state)||"transcribing".equalsIgnoreCase(state)) closeEmoji();
        strip.state(state,meterLevels);
    }
    public void setSuggestions(List<String> items) { strip.suggestions(items==null?List.of():items); }
    public void setUndoVisible(boolean visible) { strip.setUndo(visible); }
    public void setFieldKind(KeyboardLayout.FieldKind kind,String returnLabel) { closeEmoji(); plane.field(kind,returnLabel); }
    public void setAutoCap(boolean value) { plane.autoCap(value); }
    public void setSettings(KeyboardLanguage language,boolean keyPopups,boolean haptics,boolean emojiEnabled) {
        this.emojiEnabled=emojiEnabled;
        if(!emojiEnabled) { closeEmoji(); emojiContext=null; strip.contextEmoji(null); }
        plane.settings(language,keyPopups,haptics,emojiEnabled);
    }
    private final class PlaneHost implements KeyPlaneView.Input {
        public void onCharacter(String s) { if(emojiOpen) panel.append(s); else host.onCharacter(s); }
        public void onDeleteChar() { if(emojiOpen) panel.erase(); else host.onDeleteChar(); }
        public void onDeleteWord() { if(emojiOpen) panel.search(true); else host.onDeleteWord(); }
        public void onSpace() { if(emojiOpen) panel.append(" "); else host.onSpace(); }
        public void onReturn() { if(emojiOpen) toggleSearch(); else host.onReturn(); }
        public void onCursorMove(int d) { if(!emojiOpen) host.onCursorMove(d); }
        public void onSwitchKeyboard() { host.onSwitchKeyboard(); }
    }
    @Override protected void onMeasure(int w,int h) {
        plane.measure(w,MeasureSpec.makeMeasureSpec(0,MeasureSpec.UNSPECIFIED));
        strip.getLayoutParams().height=dp(getContext(),plane.landscape()?39:44);
        int lift=emojiOpen && panel.searching()?dp(getContext(),88)-strip.getLayoutParams().height:0;
        ((LayoutParams)plane.getLayoutParams()).topMargin=lift;
        super.onMeasure(w,MeasureSpec.makeMeasureSpec(resolveSize(plane.getMeasuredHeight()+lift,h),MeasureSpec.EXACTLY));
    }
}
