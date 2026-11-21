package anomaly.hex.android.keyboard;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import java.util.Locale;
import static anomaly.hex.android.keyboard.KeyboardLayout.*;
import static anomaly.hex.android.keyboard.KeyboardPalette.*;

/** A transparent first row lets balloons float over the suggestion strip. Geometry is in dp. */
public final class KeyPlaneView extends View {
    interface Input {
        void onCharacter(String text);
        void onDeleteChar();
        void onDeleteWord();
        void onSpace();
        void onReturn();
        void onCursorMove(int delta);
        void onSwitchKeyboard();
    }
    private final Input host;
    private final Runnable openEmoji;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ShiftStateMachine shift = new ShiftStateMachine();
    private Layer layer = Layer.LETTERS;
    private FieldKind field = FieldKind.TEXT;
    private KeyboardLanguage language = KeyboardLanguage.EN;
    private String action = "";
    private KeyboardLayout layout;
    private Key down;
    private long started;
    private int pointer = -1, heldShiftPointer = -1, selection;
    private float origin, cursor, pickerLeft, cell;
    private String[] variants;
    private boolean dragging, popups = true, haptics = true, emoji;

    KeyPlaneView(Context c, Input host, Runnable openEmoji) {
        super(c); this.host = host; this.openEmoji = openEmoji;
        paint.setTextAlign(Paint.Align.CENTER);
    }
    boolean landscape() { return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE; }
    private float density() { return getResources().getDisplayMetrics().density; }
    private void rebuild(int width) {
        layout = new KeyboardLayout(width/density(), landscape(), layer, field, true, emoji, language);
        invalidate();
    }
    void settings(KeyboardLanguage lang, boolean popup, boolean haptic, boolean emojis) {
        language = lang; popups = popup; haptics = haptic; emoji = emojis; reset(); rebuild(getWidth()); requestLayout();
    }
    void field(FieldKind kind, String label) {
        field = kind; action = label == null || label.equalsIgnoreCase("return") ? "" : label;
        reset(); rebuild(getWidth());
    }
    void letters() { layer = Layer.LETTERS; reset(); rebuild(getWidth()); }
    void autoCap(boolean cap) { shift.applyAutoCap(cap); invalidate(); }
    @Override protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w); rebuild(width);
        setMeasuredDimension(width, resolveSize(dp(getContext(),layout.totalHeight()),h));
    }
    private String casing(String s) { return shift.isUppercase() ? s.toUpperCase(Locale.ROOT) : s; }
    private String face(Key k) {
        return switch (k.kind()) {
            case CHAR -> casing(k.label());
            case SHIFT -> shift.state()==ShiftStateMachine.State.CAPS_LOCK ? "⇪" : "⇧";
            case DELETE -> "⌫"; case GLOBE -> "🌐"; case EMOJI -> "☺";
            case RETURN -> action.isBlank() ? "↵" : action;
            default -> k.label();
        };
    }
    private void box(Canvas c,float l,float t,float r,float b,int fill) {
        paint.setColor(fill); c.drawRoundRect(l,t,r,b,8.5f,8.5f,paint);
    }
    private void text(Canvas c,String s,float x,float y,float size,float maxWidth) {
        paint.setColor(TEXT); paint.setTextSize(size*getResources().getDisplayMetrics().scaledDensity/density());
        float measured = paint.measureText(s);
        if (measured > maxWidth && maxWidth > 0) paint.setTextSize(paint.getTextSize()*maxWidth/measured);
        c.drawText(s,x,y-(paint.ascent()+paint.descent())/2,paint);
    }
    @Override protected void onDraw(Canvas c) {
        if (layout == null) return;
        c.save(); c.scale(density(),density());
        for (Key k : layout.keys()) {
            boolean primary = k.kind()==KeyKind.RETURN && !action.isBlank();
            boolean active = k==down || (k.kind()==KeyKind.SHIFT && shift.isUppercase());
            box(c,k.left(),k.top(),k.right(),k.bottom(),primary ? (active?ACTION_PRESSED:ACTION_FILL) : (active?KEY_PRESSED:KEY_FILL));
            String s = face(k);
            float size = k.kind()!=KeyKind.CHAR ? 18 : Character.isLowerCase(s.codePointAt(0)) ? 22.5f : 21;
            text(c,s,(k.left()+k.right())/2,(k.top()+k.bottom())/2,size,k.right()-k.left()-3);
        }
        if (down!=null && down.kind()==KeyKind.CHAR && (variants!=null || (popups && !landscape()))) {
            float width = getWidth()/density();
            float w = variants==null ? Math.min(width,down.right()-down.left()+20) : variants.length*cell;
            float l = variants==null ? Math.max(0,Math.min(width-w,(down.left()+down.right()-w)/2)) : pickerLeft;
            float t = Math.max(0,down.top()-49);
            box(c,l,t,l+w,t+46,POPUP_FILL); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1);
            box(c,l+.5f,t+.5f,l+w-.5f,t+45.5f,POPUP_BORDER); paint.setStyle(Paint.Style.FILL);
            if (variants==null) text(c,face(down),l+w/2,t+23,28,w);
            else for(int i=0;i<variants.length;i++) {
                float x=l+i*cell;
                if (i==selection) box(c,x+1,t+2,x+cell-1,t+44,ACTION_FILL);
                text(c,casing(variants[i]),x+cell/2,t+23,23,cell-2);
            }
        }
        c.restore();
    }
    private final Runnable accent = () -> {
        if (down==null) return;
        String[] options = AccentTable.accentsFor(down.label(),language);
        if (options.length==0) return;
        variants=options; float width=getWidth()/density();
        cell=Math.min(37,width/options.length);
        pickerLeft=Math.max(0,Math.min(width-cell*options.length,origin-cell/2));
        select(origin); invalidate();
    };
    private void select(float x) { selection=Math.max(0,Math.min(variants.length-1,(int)((x-pickerLeft)/cell))); }
    private final Runnable repeat = new Runnable() {
        public void run() {
            if(down==null || down.kind()!=KeyKind.DELETE) return;
            long elapsed=SystemClock.uptimeMillis()-started;
            if(elapsed>=1400) host.onDeleteWord();
            else { host.onDeleteChar(); if(elapsed>=850) host.onDeleteChar(); }
            postDelayed(this,75);
        }
    };
    private void reset() {
        removeCallbacks(repeat); removeCallbacks(accent);
        if(heldShiftPointer!=-1 || (down!=null && down.kind()==KeyKind.SHIFT)) shift.onShiftUp();
        heldShiftPointer=-1;
        down=null; pointer=-1; variants=null; dragging=false; invalidate();
    }
    @Override protected void onDetachedFromWindow() { reset(); super.onDetachedFromWindow(); }
    @Override public boolean onTouchEvent(MotionEvent e) {
        int a=e.getActionMasked();
        if(a==MotionEvent.ACTION_POINTER_UP && e.getPointerId(e.getActionIndex())==heldShiftPointer) {
            shift.onShiftUp(); heldShiftPointer=-1; invalidate(); return true;
        }
        if(a==MotionEvent.ACTION_DOWN || a==MotionEvent.ACTION_POINTER_DOWN) {
            int i=e.getActionIndex();
            if(a==MotionEvent.ACTION_DOWN) reset();
            else {
                if(down!=null && down.kind()==KeyKind.SHIFT) heldShiftPointer=pointer;
                else if(down!=null && down.kind()==KeyKind.CHAR) commit();
                removeCallbacks(repeat); removeCallbacks(accent); variants=null; dragging=false;
            }
            origin=cursor=e.getX(i)/density(); down=layout.hitTest(origin,e.getY(i)/density());
            if(down==null) return a!=MotionEvent.ACTION_DOWN;
            pointer=e.getPointerId(i); started=e.getEventTime();
            if(haptics) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            switch(down.kind()) {
                case DELETE -> { host.onDeleteChar(); postDelayed(repeat,360); }
                case SHIFT -> shift.onShiftDown(started);
                case CHAR -> postDelayed(accent,400);
                default -> {}
            }
            invalidate(); return true;
        }
        int index=e.findPointerIndex(pointer);
        if(a==MotionEvent.ACTION_CANCEL) { reset(); return true; }
        if(index<0 || down==null) {
            if(a==MotionEvent.ACTION_UP) reset();
            return true;
        }
        float x=e.getX(index)/density(), y=e.getY(index)/density();
        if(variants!=null) select(x);
        else if(down.kind()==KeyKind.SPACE && (dragging || Math.abs(x-origin)>=10)) {
            dragging=true; int delta=(int)((x-cursor)/7.5f);
            if(delta!=0) { cursor+=delta*7.5f; host.onCursorMove(delta); }
        } else if(a==MotionEvent.ACTION_MOVE && !down.hitTest(x,y)) {
            removeCallbacks(accent); removeCallbacks(repeat);
        }
        if(a==MotionEvent.ACTION_UP || (a==MotionEvent.ACTION_POINTER_UP && e.getActionIndex()==index)) {
            if(variants!=null || down.hitTest(x,y)) commit();
            int stillHeld=heldShiftPointer; heldShiftPointer=-1;
            reset(); heldShiftPointer=stillHeld;
        }
        invalidate(); return true;
    }
    private void commit() {
        switch(down.kind()) {
            case CHAR -> { String s=casing(variants==null?down.label():variants[selection]); shift.onCharacterInserted(); host.onCharacter(s); }
            case SPACE -> { if(!dragging) host.onSpace(); }
            case LAYER -> { layer=switch(down.label()) { case "ABC" -> Layer.LETTERS; case "#+=" -> Layer.SYMBOLS; default -> Layer.NUMBERS; }; rebuild(getWidth()); }
            case RETURN -> host.onReturn(); case EMOJI -> openEmoji.run(); case GLOBE -> host.onSwitchKeyboard();
            default -> {} // Shift and delete act on down.
        }
    }
}
