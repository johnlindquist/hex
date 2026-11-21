package anomaly.hex.android.keyboard;

import android.content.Context;
import android.view.*;
import android.widget.*;
import java.util.List;
import anomaly.hex.android.ui.DotScopeView;
import static anomaly.hex.android.keyboard.KeyboardPalette.*;

public final class SuggestionStripView extends FrameLayout {
    private final FullKeyboardView.Host host;
    private final LinearLayout typing, recording, slots;
    private final FrameLayout center;
    private final TextView undo, finish;
    private final HexMarkView mark;
    private final DotScopeView scope;
    private boolean tracking, held;
    private final Runnable hold = this::holdElapsed;

    SuggestionStripView(Context c, FullKeyboardView.Host host) {
        super(c); this.host=host;
        typing=row(c); addView(typing); center=new FrameLayout(c); typing.addView(center, weight());
        slots=row(c); center.addView(slots);
        undo=label(c,"Undo dictation",15); undo.setBackground(pill(CHIP_FILL));
        undo.setPadding(dp(c,16),0,dp(c,16),0); undo.setOnClickListener(v->host.onUndoDictation());
        center.addView(undo,new LayoutParams(-2,dp(c,32),Gravity.CENTER)); setUndo(false);
        mark=new HexMarkView(c); typing.addView(mark,new LinearLayout.LayoutParams(dp(c,44),-1));
        mark.setOnClickListener(v->host.onStartDictation());
        recording=row(c); recording.setPadding(dp(c,6),0,dp(c,6),0); addView(recording);
        scope=new DotScopeView(c); scope.setBackground(pill(WELL_FILL)); scope.setClipToOutline(true);
        recording.addView(scope,new LinearLayout.LayoutParams(0,dp(c,32),1));
        TextView cancel=round("×","Cancel dictation",FUNCTION_FILL); cancel.setOnClickListener(v->host.onCancelDictation());
        finish=round("✓","Finish dictation",ACTION_FILL); finish.setOnClickListener(v->host.onStopDictation());
        recording.setVisibility(GONE);
    }
    private LinearLayout row(Context c) { LinearLayout r=new LinearLayout(c); r.setGravity(Gravity.CENTER_VERTICAL); return r; }
    private static LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0,-1,1); }
    private TextView round(String text,String desc,int color) {
        Context c=getContext(); TextView b=label(c,text,21); b.setContentDescription(desc); b.setBackground(pill(color));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(c,34),dp(c,34)); p.leftMargin=dp(c,6);
        recording.addView(b,p); return b;
    }
    void setUndo(boolean visible) { undo.setVisibility(visible?VISIBLE:GONE); slots.setVisibility(visible?GONE:VISIBLE); }
    void suggestions(List<String> items) {
        slots.removeAllViews(); String previous="";
        for(int i=0;i<3;i++) {
            String value=i<items.size()?items.get(i):"";
            if(!previous.isEmpty() && !value.isEmpty()) {
                View line=new View(getContext()); line.setBackgroundColor(SEPARATOR);
                slots.addView(line,new LinearLayout.LayoutParams(1,dp(getContext(),18)));
            }
            boolean quoted=value.startsWith("“") && value.endsWith("”") && value.length()>=2;
            String word=quoted?value.substring(1,value.length()-1):value;
            TextView slot=label(getContext(),i==0 && !word.isEmpty()?"“"+word+"”":value,16);
            if(!word.isEmpty()) slot.setOnClickListener(v->host.onSuggestionPick(word));
            slots.addView(slot,weight()); previous=value;
        }
    }
    void state(String state,int[] meter) {
        boolean listen="listening".equalsIgnoreCase(state) || "recording".equalsIgnoreCase(state);
        boolean busy="transcribing".equalsIgnoreCase(state); boolean active=listen || busy;
        typing.setVisibility(active?INVISIBLE:VISIBLE); recording.setVisibility(active?VISIBLE:GONE);
        if(finish.isEnabled()==busy) {
            finish.setText(busy?"⋯":"✓"); finish.setEnabled(!busy); finish.setTextColor(busy?TEXT_MUTED:TEXT);
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
    @Override protected void onDetachedFromWindow() {
        removeCallbacks(hold); boolean cancel=tracking && held; tracking=held=false; mark.setPressed(false);
        super.onDetachedFromWindow(); if(cancel) host.onCancelDictation();
    }
}
