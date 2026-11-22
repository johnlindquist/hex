package anomaly.hex.android.keyboard;

import android.content.Context;
import android.view.*;
import android.widget.*;
import java.util.List;
import anomaly.hex.android.ui.DotScopeView;
import static anomaly.hex.android.keyboard.KeyboardPalette.*;

public final class SuggestionStripView extends FrameLayout {
    private final FullKeyboardView.Host host;
    private final LinearLayout typing, recording, slots, toolbar;
    private final View emojiTool, promptRewriteBtn, rewriteBtn;
    private final FrameLayout center;
    private final LinearLayout undoBar, undo, rewriteOptionsPill, noticeBar;
    private final TextView clock, noticeText, noticeAction, undoLabel;
    private final Runnable clearNotice = () -> showNotice(null, false);
    private List<String> items=List.of();
    private boolean autoReplace;
    private String emojiContext;
    private final View finish;
    private final android.os.Handler timer=new android.os.Handler(android.os.Looper.getMainLooper());
    private long started=-1;
    private final Runnable tick=this::tick;
    private void tick() {
        if(started<0) return;
        long ms=android.os.SystemClock.elapsedRealtime()-started, sec=ms/1000;
        clock.setText(String.format(java.util.Locale.ROOT,"%d:%02d",sec/60,sec%60));
        if(isAttachedToWindow()) timer.postDelayed(tick,1000-ms%1000);
    }
    private final HexMarkView mark;
    private final DotScopeView scope;
    private boolean tracking, held;
    private final Runnable hold = this::holdElapsed;

    SuggestionStripView(Context c, FullKeyboardView.Host host, Runnable toggleEmoji) {
        super(c); this.host=host;
        typing=row(c); typing.setPadding(dp(c,4.5f),0,dp(c,6f),0); addView(typing); center=new FrameLayout(c); typing.addView(center, weight());
        toolbar=row(c); center.addView(toolbar);
        emojiTool=addTool(true,"Emoji picker",toggleEmoji); emojiTool.setVisibility(GONE);
        addTool(false,"Open Hex",host::onOpenApp);
        slots=row(c); center.addView(slots);
        undoBar=row(c);
        undo=row(c); undo.setBackground(pressable(CHIP_FILL)); undo.setPadding(dp(c,10),0,dp(c,10),0);
        View arrow=new View(c){protected void onDraw(android.graphics.Canvas canvas){KeyIcons.drawUndoArrow(canvas,getWidth()/2f,getHeight()/2f,dp(c,16),TEXT_MUTED);}};
        undo.addView(arrow,dp(c,20),-1); undoLabel=label(c,"Undo dictation",14); undoLabel.setTextSize(13.5f); undoLabel.setTextColor(TEXT_MUTED);
        undo.addView(undoLabel); undo.setContentDescription("Undo dictation"); undo.setOnClickListener(v->host.onUndoDictation());
        undoBar.addView(undo,new LinearLayout.LayoutParams(-2,dp(c,30)));
        rewriteOptionsPill=row(c); rewriteOptionsPill.setBackground(pressable(CHIP_FILL)); rewriteOptionsPill.setPadding(dp(c,10),0,dp(c,10),0);
        View spIcon=new View(c){protected void onDraw(android.graphics.Canvas canvas){KeyIcons.drawSparkle(canvas,getWidth()/2f,getHeight()/2f,dp(c,15),anomaly.hex.android.ui.HexTheme.MIC);}};
        rewriteOptionsPill.addView(spIcon,dp(c,18),-1);
        TextView optsLbl=label(c,"Options",14); optsLbl.setTextSize(13.5f); optsLbl.setTextColor(TEXT);
        LinearLayout.LayoutParams ollp=new LinearLayout.LayoutParams(-2,-2); ollp.leftMargin=dp(c,4);
        rewriteOptionsPill.addView(optsLbl,ollp);
        rewriteOptionsPill.setContentDescription("Show rewrite options");
        rewriteOptionsPill.setOnClickListener(v->host.onRewriteReopen());
        LinearLayout.LayoutParams rop=new LinearLayout.LayoutParams(-2,dp(c,30)); rop.leftMargin=dp(c,6);
        undoBar.addView(rewriteOptionsPill,rop);
        rewriteOptionsPill.setVisibility(GONE);
        center.addView(undoBar,new LayoutParams(-2,dp(c,30),Gravity.CENTER));
        noticeBar=row(c); noticeBar.setBackground(pill(CHIP_FILL)); noticeBar.setPadding(dp(c,10),0,dp(c,6),0);
        noticeText=label(c,"",13); noticeText.setTextColor(TEXT); noticeBar.addView(noticeText);
        noticeAction=label(c,"Open",13); noticeAction.setTextColor(ON_PILL_TEXT);
        noticeAction.setBackground(pill(RETURN_PILL_FILL)); noticeAction.setPadding(dp(c,9),0,dp(c,9),0);
        LinearLayout.LayoutParams nap=new LinearLayout.LayoutParams(-2,dp(c,22)); nap.leftMargin=dp(c,8);
        noticeAction.setOnClickListener(v->{ showNotice(null,false); host.onOpenApp(); });
        noticeBar.addView(noticeAction,nap); noticeBar.setVisibility(GONE);
        center.addView(noticeBar,new LayoutParams(-2,dp(c,30),Gravity.CENTER)); setUndo(false);
        promptRewriteBtn=new View(c) {
            protected void onDraw(android.graphics.Canvas canvas) {
                KeyIcons.drawPromptRewrite(canvas,getWidth()/2f,getHeight()/2f,dp(c,18),TEXT_MUTED);
            }
        };
        promptRewriteBtn.setContentDescription("Rewrite with prompt");
        promptRewriteBtn.setBackground(pressable(CHIP_FILL));
        promptRewriteBtn.setOnClickListener(v->host.onRewriteWithPrompt());
        LinearLayout.LayoutParams prp=new LinearLayout.LayoutParams(dp(c,34),dp(c,34)); prp.leftMargin=dp(c,4);
        typing.addView(promptRewriteBtn,prp);
        rewriteBtn=new View(c) {
            protected void onDraw(android.graphics.Canvas canvas) {
                KeyIcons.drawSparkle(canvas,getWidth()/2f,getHeight()/2f,dp(c,18),TEXT_MUTED);
            }
        };
        rewriteBtn.setContentDescription("Rewrite message");
        rewriteBtn.setBackground(pressable(CHIP_FILL));
        rewriteBtn.setOnClickListener(v->host.onRewriteMessage());
        LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(dp(c,34),dp(c,34)); rp.leftMargin=dp(c,4);
        typing.addView(rewriteBtn,rp);
        mark=new HexMarkView(c); mark.setBackground(pill(CHIP_FILL));
        LinearLayout.LayoutParams mp=new LinearLayout.LayoutParams(dp(c,34),dp(c,34)); mp.leftMargin=dp(c,4);
        typing.addView(mark,mp);
        mark.setOnClickListener(v->host.onStartDictation());
        recording=row(c); recording.setPadding(dp(c,6.5f),0,dp(c,6.5f),0); addView(recording);
        LinearLayout well=row(c); well.setBackground(pill(WELL_FILL)); well.setClipToOutline(true);
        recording.addView(well,new LinearLayout.LayoutParams(0,dp(c,32),1));
        clock=label(c,"0:00",12); clock.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0)); clock.setFontFeatureSettings("tnum"); clock.setTextColor(TEXT_MUTED);
        clock.setPadding(dp(c,10),0,dp(c,4),0); well.addView(clock,-2,-1); clock.setVisibility(GONE);
        scope=new DotScopeView(c); scope.setDrawPlate(false); well.addView(scope,weight());
        View cancel=round(false,"Cancel dictation",FUNCTION_FILL); cancel.setOnClickListener(v->host.onCancelDictation());
        finish=round(true,"Finish dictation",ACTION_FILL); finish.setOnClickListener(v->host.onStopDictation());
        recording.setVisibility(GONE); renderSlots();
    }
    private View addTool(boolean emoji,String description,Runnable action) {
        Context c=getContext();
        View button=new View(c) {
            protected void onDraw(android.graphics.Canvas canvas) {
                float x=getWidth()/2f,y=getHeight()/2f,s=dp(c,19);
                if(emoji) KeyIcons.drawSmiley(canvas,x,y,s,TEXT_MUTED);
                else KeyIcons.drawOpenApp(canvas,x,y,s,TEXT_MUTED);
            }
        };
        button.setContentDescription(description); button.setOnClickListener(v->action.run());
        button.setBackground(pressable(0));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(c,34),dp(c,34)); p.leftMargin=dp(c,6);
        toolbar.addView(button,p); return button;
    }
    private static android.graphics.drawable.StateListDrawable pressable(int fill) {
        var states=new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed},pill(KEY_PRESSED));
        states.addState(new int[0],pill(fill)); return states;
    }
    void setEmojiEnabled(boolean enabled) { emojiTool.setVisibility(enabled?VISIBLE:GONE); }
    void setPrivateField(boolean priv) {
        int vis = priv ? GONE : VISIBLE;
        promptRewriteBtn.setVisibility(vis);
        rewriteBtn.setVisibility(vis);
    }
    private void updateContent() {
        boolean available=undoBar.getVisibility()!=VISIBLE && noticeBar.getVisibility()!=VISIBLE;
        boolean idle=items.isEmpty() && emojiContext==null;
        showContent(toolbar,available && idle); showContent(slots,available && !idle);
    }
    private void showContent(View view,boolean show) {
        if((view.getVisibility()==VISIBLE)==show) return;
        view.animate().cancel(); view.setVisibility(show?VISIBLE:GONE);
        if(show && isShown()) { view.setAlpha(0f); view.animate().alpha(1f).setDuration(100).start(); }
        else view.setAlpha(1f);
    }
    private LinearLayout row(Context c) { LinearLayout r=new LinearLayout(c); r.setGravity(Gravity.CENTER_VERTICAL); return r; }
    private static LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0,-1,1); }
    private View round(boolean check,String desc,int color) {
        Context c=getContext(); View b=new View(c) {
            private final android.graphics.Paint paint=new android.graphics.Paint(1);
            protected void onDraw(android.graphics.Canvas canvas) {
                float x=getWidth()/2f,y=getHeight()/2f,s=dp(c,20);
                if(!isEnabled()) {paint.setColor(TEXT_MUTED); for(int i=-1;i<=1;i++) canvas.drawCircle(x+i*s/4,y,s/14,paint);}
                else if(check) KeyIcons.drawCheckmark(canvas,x,y,s,ON_PILL_TEXT);
                else KeyIcons.drawCancel(canvas,x,y,s,TEXT);
            }
        }; b.setContentDescription(desc); b.setBackground(pill(color));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(c,32),dp(c,32)); p.leftMargin=dp(c,6);
        recording.addView(b,p); return b;
    }
    void setUndo(boolean visible) { setUndo(visible, "Undo dictation", false); }
    void setUndo(boolean visible, String labelText) { setUndo(visible, labelText, false); }
    void setUndo(boolean visible, String labelText, boolean showOptions) {
        if(visible) showNotice(null,false);
        undoLabel.setText(labelText);
        undo.setContentDescription(labelText);
        rewriteOptionsPill.setVisibility(visible && showOptions ? VISIBLE : GONE);
        undoBar.setVisibility(visible?VISIBLE:GONE);
        updateContent();
    }
    void showNotice(String message, boolean openApp) {
        timer.removeCallbacks(clearNotice);
        if(message==null || message.isBlank()) {
            noticeBar.setVisibility(GONE);
            updateContent();
            return;
        }
        undoBar.setVisibility(GONE);
        noticeText.setText(message); noticeAction.setVisibility(openApp?VISIBLE:GONE);
        noticeBar.setVisibility(VISIBLE); updateContent(); timer.postDelayed(clearNotice,4000);
    }
    void suggestions(List<String> values, boolean willAutoReplace) {
        if(!items.equals(values) || autoReplace!=willAutoReplace) { items=List.copyOf(values); autoReplace=willAutoReplace; renderSlots(); }
    }
    void contextEmoji(String value) { if(!java.util.Objects.equals(value,emojiContext)){emojiContext=value; renderSlots();} }
    private void renderSlots() {
        Context c=getContext(); slots.removeAllViews();
        String first=items.isEmpty()?"":items.get(0);
        boolean lone=emojiContext!=null && (items.isEmpty() || items.size()==1 && first.equals(emojiContext));
        boolean correction=autoReplace && isQuoted(first) && items.size()>1 && !items.get(1).isBlank() && !items.get(1).equals(emojiContext);
        String[] values=new String[3]; String[] words=new String[3];
        for(int i=0;i<3;i++) {
            values[i]=lone?(i==1?emojiContext:""):i<items.size()?items.get(i):"";
            words[i]=isQuoted(values[i])?values[i].substring(1,values[i].length()-1):values[i];
        }
        if(!lone && emojiContext!=null && emojiContext.equals(values[1])) {
            words[1]=values[1]=""; words[2]=values[2]=emojiContext;
        }
        updateContent();
        for(int i=0;i<3;i++) {
            if(i>0) {
                View line=new View(c);
                if(!correction && !words[i-1].isEmpty() && !words[i].isEmpty()) line.setBackgroundColor(SEPARATOR);
                slots.addView(line,dp(c,1),dp(c,24));
            }
            String value=values[i], word=words[i];
            boolean emoji=word.equals(emojiContext);
            FrameLayout cell=new FrameLayout(c); slots.addView(cell,weight());
            TextView slot=label(c,i==0 && !word.isEmpty() && !emoji?"“"+word+"”":value,emoji?23:16);
            slot.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            var p=new LayoutParams(-1,dp(c,34),Gravity.CENTER); p.leftMargin=p.rightMargin=dp(c,4);
            if(!word.isEmpty()) { slot.setDuplicateParentStateEnabled(true); slot.setBackground(pressable(i==1 && correction?SELECT_FILL:0)); }
            if(i==1 && correction) {
                slot.setTextSize(16.5f); slot.setTypeface(android.graphics.Typeface.create("sans-serif-medium",0)); }
            cell.addView(slot,p);
            if(!word.isEmpty()) { cell.setContentDescription(slot.getText()); cell.setOnClickListener(v->{if(emoji) host.onEmojiPick(word); else host.onSuggestionPick(word);}); }
        }
    }
    private static boolean isQuoted(String s) {
        return s.length()>=2 && (s.startsWith("“") && s.endsWith("”") || s.startsWith("\"") && s.endsWith("\""));
    }
    void state(String state,int[] meter) {
        boolean listen="listening".equalsIgnoreCase(state) || "recording".equalsIgnoreCase(state);
        boolean busy="transcribing".equalsIgnoreCase(state); boolean active=listen || busy;
        typing.setVisibility(active?INVISIBLE:VISIBLE); recording.setVisibility(active?VISIBLE:GONE);
        if(listen!=(started>=0)) {
            timer.removeCallbacks(tick); started=listen?android.os.SystemClock.elapsedRealtime():-1;
            clock.setVisibility(listen?VISIBLE:GONE); if(listen) tick(); else clock.setText("0:00");
        }
        if(finish.isEnabled()==busy) {
            finish.setEnabled(!busy); finish.invalidate();
            finish.setContentDescription(busy?"Transcribing":"Finish dictation"); finish.setBackground(pill(busy?FUNCTION_FILL:ACTION_FILL));
        }
        mark.setWarm("ready".equalsIgnoreCase(state)||"warm".equalsIgnoreCase(state)); scope.update(listen,meter);
    }
    // The strip owns this pointer even when starting a hold hides the mark.
    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        return e.getActionMasked()==MotionEvent.ACTION_DOWN && typing.getVisibility()==VISIBLE && e.getX()>=mark.getLeft();
    }
    private void holdElapsed() { if(tracking) { held=true; host.onStartDictation(); } }
    @Override public boolean onTouchEvent(MotionEvent e) {
        switch(e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                if(typing.getVisibility()!=VISIBLE || e.getX()<mark.getLeft()) return false;
                tracking=true; held=false; mark.setPressed(true); postDelayed(hold,350); return true; }
            case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if(!tracking) return false;
                removeCallbacks(hold); tracking=false; mark.setPressed(false);
                if(held) { if(e.getActionMasked()==MotionEvent.ACTION_CANCEL) host.onCancelDictation(); else host.onStopDictation(); }
                else if(e.getActionMasked()==MotionEvent.ACTION_UP && typing.getVisibility()==VISIBLE && e.getX()>=mark.getLeft() && e.getY()>=0 && e.getY()<getHeight()) mark.performClick();
                held=false; return true;
            }
            default -> { return tracking; }
        }
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); timer.removeCallbacks(tick); tick(); }
    @Override protected void onDetachedFromWindow() {
        timer.removeCallbacks(tick);
        removeCallbacks(hold); boolean cancel=tracking && held; tracking=held=false; mark.setPressed(false);
        super.onDetachedFromWindow(); if(cancel) host.onCancelDictation();
    }
}
