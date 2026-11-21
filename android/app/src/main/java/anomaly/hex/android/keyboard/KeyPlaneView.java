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
    private int layoutWidth;
    private boolean layoutWide;
    private Key down;
    private long started;
    private int pointer = -1, heldShiftPointer = -1, selection;
    private float origin, cursor, lastX, pickerLeft, cell;
    private String[] variants;
    private boolean dragging, searching, popups = true, haptics = true, emoji;

    KeyPlaneView(Context c, Input host, Runnable openEmoji) {
        super(c); this.host = host; this.openEmoji = openEmoji;
        paint.setTextAlign(Paint.Align.CENTER);
    }
    boolean landscape() { return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE; }
    private float density() { return getResources().getDisplayMetrics().density; }
    private void rebuild(int width) {
        layoutWidth=width; layoutWide=landscape();
        layout = new KeyboardLayout(width/density(), layoutWide, layer, field, false, emoji, language==null?KeyboardLanguage.EN:language);
        invalidate();
    }
    void settings(KeyboardLanguage lang, boolean popup, boolean haptic, boolean emojis) {
        language = lang; popups = popup; haptics = haptic; emoji = emojis; reset(); rebuild(getWidth()); requestLayout();
    }
    void field(FieldKind kind, String label) {
        field = kind;
        action = (label == null || label.isBlank() || label.equals("↵") || label.equalsIgnoreCase("return")) ? "" : label;
        reset(); rebuild(getWidth());
    }
    void setSearching(boolean value) { if (searching != value) { searching = value; invalidate(); } }
    void letters() { layer = Layer.LETTERS; reset(); rebuild(getWidth()); }
    void autoCap(boolean cap) { shift.applyAutoCap(cap); invalidate(); }
    @Override protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w); if(layout==null || width!=layoutWidth || landscape()!=layoutWide) rebuild(width);
        setMeasuredDimension(width, resolveSize(dp(getContext(),layout.totalHeight()),h));
    }
    private String casing(String s) { return shift.isUppercase() ? s.toUpperCase(Locale.ROOT) : s; }
    private String face(Key k) {
        return switch (k.kind()) {
            case CHAR -> casing(k.label());
            case RETURN -> action.isBlank() ? "↵" : action;
            default -> k.label();
        };
    }
    private final android.graphics.Path balloon=new android.graphics.Path();
    private static final android.graphics.Typeface LIGHT=android.graphics.Typeface.create("sans-serif-light",0);
    private void callout(Canvas c) {
        float l=down.left(),r=down.right(),b=down.bottom(),w=Math.min(getWidth()/density()-4,r-l+20);
        float x=Math.max(2,Math.min(getWidth()/density()-2-w,(l+r-w)/2)), right=x+w;
        float y=Math.max(0,down.top()-52), bottom=y+46, neck=6, stem=Math.max(down.top()+neck,bottom+neck);
        float br=11,kr=8.5f, shoulder=Math.min(br,Math.max(0,right-r)),leftShoulder=Math.min(br,Math.max(0,l-x));
        var p=balloon; p.rewind(); p.moveTo(x,y+br); p.quadTo(x,y,x+br,y); p.lineTo(right-br,y);
        p.quadTo(right,y,right,y+br); p.lineTo(right,bottom-shoulder); p.quadTo(right,bottom,right-shoulder,bottom);
        p.cubicTo(r,bottom,r,stem-neck/2,r,stem); p.lineTo(r,b-kr); p.quadTo(r,b,r-kr,b);
        p.lineTo(l+kr,b); p.quadTo(l,b,l,b-kr); p.lineTo(l,stem); p.cubicTo(l,stem-neck/2,l,bottom,x+leftShoulder,bottom);
        p.quadTo(x,bottom,x,bottom-leftShoulder); p.close();
        paint.setColor(POPUP_FILL); paint.setShadowLayer(6,0,2,POPUP_SHADOW); c.drawPath(p,paint); paint.clearShadowLayer();
        paint.setStyle(Paint.Style.STROKE); paint.setColor(POPUP_BORDER); paint.setStrokeWidth(1f/density()); c.drawPath(p,paint);
        paint.setStyle(Paint.Style.FILL); paint.setTypeface(LIGHT); text(c,face(down),x+w/2,y+23,32,w); paint.setTypeface(null);
    }
    private static final android.graphics.Typeface MEDIUM=android.graphics.Typeface.create("sans-serif-medium",0);
    private void box(Canvas c,float l,float t,float r,float b,float radius,int fill) {
        paint.setColor(fill); c.drawRoundRect(l,t,r,b,radius,radius,paint);
    }
    private void text(Canvas c,String s,float x,float y,float size,float maxWidth) {
        text(c,s,x,y,size,maxWidth,TEXT);
    }
    private void text(Canvas c,String s,float x,float y,float size,float maxWidth,int color) {
        paint.setColor(color); paint.setTextSize(size);
        float measured = paint.measureText(s);
        if (measured > maxWidth && maxWidth > 0) paint.setTextSize(paint.getTextSize()*maxWidth/measured);
        c.drawText(s,x,y-(paint.ascent()+paint.descent())/2,paint);
    }
    @Override protected void onDraw(Canvas c) {
        if (layout == null) return;
        c.save(); c.scale(density(),density());
        boolean wide=landscape(), show=popups && !wide && variants==null && down!=null && down.kind()==KeyKind.CHAR;
        int row0Index = 0;
        for (Key k : layout.keys()) {
            int myRow0 = (layer == Layer.LETTERS && k.row() == 0 && k.kind() == KeyKind.CHAR) ? row0Index++ : -1;
            if(show && k==down) continue;
            boolean active = k==down;
            boolean layerPill = k.kind()==KeyKind.LAYER && k.row()==3;
            boolean returnPill = k.kind()==KeyKind.RETURN;
            boolean modKey = k.kind()==KeyKind.SHIFT || k.kind()==KeyKind.DELETE
                    || (k.kind()==KeyKind.LAYER && k.row()==2)
                    || (k.kind()==KeyKind.CHAR && k.row()==3);
            float radius = (layerPill || returnPill) ? (k.bottom() - k.top()) / 2f : layout.cornerRadius();
            int fill;
            if (returnPill) {
                fill = (dragging || searching) ? KEY_FILL : (active ? RETURN_PILL_PRESSED : RETURN_PILL_FILL);
            } else if (layerPill) {
                fill = dragging ? KEY_FILL : (active ? LAYER_PILL_PRESSED : LAYER_PILL_FILL);
            } else if (modKey) {
                fill = dragging ? KEY_FILL : (active ? MOD_PRESSED : MOD_FILL);
            } else {
                fill = active ? KEY_PRESSED : KEY_FILL;
            }
            box(c,k.left(),k.top(),k.right(),k.bottom(),radius,fill);
            if(dragging) continue;
            String s = face(k);
            boolean lower=k.kind()==KeyKind.CHAR && Character.isLowerCase(s.codePointAt(0));
            float size=k.kind()!=KeyKind.CHAR?(wide?13.5f:14.5f):lower?(wide?19:22):(wide?18:20.5f);
            float x=(k.left()+k.right())/2, y=(k.top()+k.bottom())/2;
            switch(k.kind()) {
                case GLOBE -> KeyIcons.drawGlobe(c,x,y,20,TEXT);
                case EMOJI -> KeyIcons.drawSmiley(c,x,y,21,TEXT);
                case SHIFT -> KeyIcons.drawShift(c,x,y,21,shift.state(),TEXT);
                case DELETE -> KeyIcons.drawDelete(c,x,y,21,TEXT);
                case RETURN -> {
                    int fg = searching ? TEXT : ON_PILL_TEXT;
                    if(s.equals("↵")) KeyIcons.drawReturn(c,x,y,20,fg);
                    else { paint.setTypeface(MEDIUM); text(c,s,x,y,size,k.right()-k.left()-8,fg); paint.setTypeface(null); }
                }
                case LAYER -> {
                    int fg = layerPill ? ON_PILL_TEXT : TEXT;
                    paint.setTypeface(MEDIUM); text(c,s,x,y,size,k.right()-k.left()-6,fg); paint.setTypeface(null);
                }
                case SPACE -> {}
                default -> {
                    if(!(k==down && k.kind()==KeyKind.CHAR && (variants!=null || popups && !landscape()))) {
                        text(c,s,x,y+(lower?(wide?-.5f:-.5f):0),size,k.right()-k.left()-3,TEXT);
                        if (myRow0 >= 0 && myRow0 < 10 && !wide) {
                            paint.setTextAlign(Paint.Align.RIGHT);
                            paint.setColor(HINT_TEXT);
                            paint.setTextSize(10f);
                            c.drawText(String.valueOf((myRow0 + 1) % 10), k.right() - 4f, k.top() + 11.5f, paint);
                            paint.setTextAlign(Paint.Align.CENTER);
                        }
                    }
                }
            }
        }
        if(show) callout(c);
        if(variants!=null) {
            float l=pickerLeft,t=Math.max(1,down.top()-58),w=variants.length*cell+8;
            paint.setColor(POPUP_FILL); c.drawRoundRect(l,t,l+w,t+52,12,12,paint);
            for(int i=0;i<variants.length;i++) {
                float x=l+4+i*cell;
                if(i==selection) {paint.setColor(RETURN_PILL_FILL); c.drawRoundRect(x,t+4,x+cell,t+48,8,8,paint);}
                text(c,casing(variants[i]),x+cell/2,t+26,23,cell-2,i==selection?ON_PILL_TEXT:TEXT);
            }
        }
        c.restore();
    }
    private String topRowDigit(Key k) {
        if (layer != Layer.LETTERS || k.row() != 0 || k.kind() != KeyKind.CHAR) return null;
        int idx = 0;
        for (Key key : layout.keys()) {
            if (key.row() == 0 && key.kind() == KeyKind.CHAR) {
                if (key == k) return idx < 10 ? String.valueOf((idx + 1) % 10) : null;
                idx++;
            }
        }
        return null;
    }
    private final Runnable accent = () -> {
        if (down==null) return;
        String[] base = AccentTable.accentsFor(down.label(),language==null?KeyboardLanguage.EN:language);
        String digit = topRowDigit(down);
        String[] options;
        if (digit != null) {
            options = new String[base.length + 1];
            options[0] = digit;
            System.arraycopy(base, 0, options, 1, base.length);
        } else {
            options = base;
        }
        if (options.length==0) return;
        variants=options; float width=getWidth()/density();
        cell=Math.min(Math.min(38,Math.max(28,down.right()-down.left())),(width-12)/options.length);
        boolean left=(down.left()+down.right())/2>width*.52f;
        if(left) java.util.Collections.reverse(java.util.Arrays.asList(options));
        selection=left?options.length-1:0;
        pickerLeft=Math.max(2,Math.min(width-cell*options.length-10,(down.left()+down.right())/2-4-(selection+.5f)*cell));
        if (Math.abs(lastX - origin) > cell * 0.5f) select(lastX);
        invalidate();
    };
    private void select(float x) { selection=Math.max(0,Math.min(variants.length-1,(int)((x-pickerLeft-4)/cell))); }
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
            origin=cursor=lastX=e.getX(i)/density(); down=layout.hitTest(origin,e.getY(i)/density());
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
        lastX=x;
        if(variants!=null) {
            if(a==MotionEvent.ACTION_MOVE && Math.abs(x-origin)>1) {
                int prev=selection; select(x); if(haptics && prev!=selection) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            }
        }
        else if(down.kind()==KeyKind.SPACE && (dragging || Math.abs(x-origin)>=10)) {
            dragging=true; int delta=(int)((x-cursor)/7.5f);
            if(delta!=0) { cursor+=delta*7.5f; host.onCursorMove(delta); }
        } else if(a==MotionEvent.ACTION_MOVE && !down.hitTest(x,y)) {
            if(down.kind()==KeyKind.CHAR) {
                Key under=layout.hitTest(x,y);
                if(under!=null && under.kind()==KeyKind.CHAR) {
                    down=under; origin=x; removeCallbacks(accent); postDelayed(accent,400);
                } else {
                    removeCallbacks(accent); removeCallbacks(repeat);
                }
            } else {
                removeCallbacks(accent); removeCallbacks(repeat);
            }
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
            case LAYER -> { layer=switch(down.label()) { case "ABC" -> Layer.LETTERS; case "=\\<", "#+=" -> Layer.SYMBOLS; default -> Layer.NUMBERS; }; rebuild(getWidth()); }
            case RETURN -> host.onReturn(); case EMOJI -> openEmoji.run(); case GLOBE -> host.onSwitchKeyboard();
            default -> {} // Shift and delete act on down.
        }
    }
}
