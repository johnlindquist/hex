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
    private final TextView undo, clock;
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
        LinearLayout well=row(c); well.setBackground(pill(WELL_FILL)); well.setClipToOutline(true);
        recording.addView(well,new LinearLayout.LayoutParams(0,dp(c,32),1));
        clock=label(c,"0:00",12); clock.setTypeface(android.graphics.Typeface.MONOSPACE); clock.setTextColor(TEXT_MUTED);
        clock.setPadding(dp(c,9),0,dp(c,4),0); well.addView(clock,-2,-1); clock.setVisibility(GONE);
        scope=new DotScopeView(c); well.addView(scope,weight());
        View cancel=round(false,"Cancel dictation",FUNCTION_FILL); cancel.setOnClickListener(v->host.onCancelDictation());
        finish=round(true,"Finish dictation",ACTION_FILL); finish.setOnClickListener(v->host.onStopDictation());
        recording.setVisibility(GONE);
    }
    private LinearLayout row(Context c) { LinearLayout r=new LinearLayout(c); r.setGravity(Gravity.CENTER_VERTICAL); return r; }
    private static LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0,-1,1); }
    private View round(boolean check,String desc,int color) {
        Context c=getContext(); View b=new View(c) {
            private final android.graphics.Paint paint=new android.graphics.Paint(1);
            protected void onDraw(android.graphics.Canvas canvas) {
                float x=getWidth()/2f,y=getHeight()/2f,s=dp(c,20);
                if(!isEnabled()) {paint.setColor(TEXT_MUTED); for(int i=-1;i<=1;i++) canvas.drawCircle(x+i*s/4,y,s/14,paint);}
                else if(check) KeyIcons.drawCheckmark(canvas,x,y,s,TEXT);
                else KeyIcons.drawCancel(canvas,x,y,s,TEXT);
            }
        }; b.setContentDescription(desc); b.setBackground(pill(color));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(c,34),dp(c,34)); p.leftMargin=dp(c,6);
        recording.addView(b,p); return b;
    }
    void setUndo(boolean visible) { undo.setVisibility(visible?VISIBLE:GONE); slots.setVisibility(visible?GONE:VISIBLE); }
    void suggestions(List<String> items) {
        slots.removeAllViews(); String first=items.isEmpty()?"":items.get(0);
        boolean literal=isQuoted(first);
        slots.setPadding(items.isEmpty()?0:mark.getLayoutParams().width,0,0,0);
        for(int i=0;i<3;i++) {
            String value=i<items.size()?items.get(i):"";
            if(i>0 && !items.isEmpty()) {
                View line=new View(getContext()); line.setBackgroundColor(SEPARATOR);
                slots.addView(line,new LinearLayout.LayoutParams(1,dp(getContext(),18)));
            }
            boolean quoted=isQuoted(value);
            String word=quoted?value.substring(1,value.length()-1):value;
            TextView slot=label(getContext(),i==0 && !word.isEmpty()?"“"+word+"”":value,16);
            slot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayout cell=new FrameLayout(getContext()); slots.addView(cell,weight());
            if(i==1 && !word.isEmpty() && literal && !word.equals(first.substring(1,first.length()-1))) {
                var bg=pill(CHIP_FILL); bg.setCornerRadius(dp(getContext(),8.5f)); slot.setBackground(bg);
                slot.setPadding(dp(getContext(),8),0,dp(getContext(),8),0);
            }
            cell.addView(slot,new LayoutParams(-2,dp(getContext(),32),Gravity.CENTER));
            if(!word.isEmpty()) { cell.setContentDescription(slot.getText()); cell.setOnClickListener(v->host.onSuggestionPick(word)); }
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
