package anomaly.hex.android.keyboard;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;

/** Icons use a centered 20-unit design grid. All keyboard views draw on the UI thread. */
final class KeyIcons {
    private KeyIcons() {}
    private static final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path path=new Path();
    private static void begin(Canvas c,float x,float y,float size,int color) {
        c.save(); c.translate(x,y); c.scale(size/20,size/20);
        p.setColor(color); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.6f);
        p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
    }
    private static void line(Canvas c,float... points) {
        path.rewind(); path.moveTo(points[0],points[1]);
        for(int i=2;i<points.length;i+=2) path.lineTo(points[i],points[i+1]);
        c.drawPath(path,p);
    }
    static void drawGlobe(Canvas c,float x,float y,float s,int color) {
        begin(c,x,y,s,color); c.drawCircle(0,0,8.5f,p); c.drawOval(-4,-8.5f,4,8.5f,p); c.drawLine(-8,0,8,0,p); c.restore();
    }
    static void drawSmiley(Canvas c,float x,float y,float s,int color) {
        begin(c,x,y,s,color); c.drawCircle(0,0,8.5f,p); c.drawArc(-5,-4,5,5,25,130,false,p);
        p.setStyle(Paint.Style.FILL); c.drawCircle(-3,-2,1,p); c.drawCircle(3,-2,1,p); c.restore();
    }
    static void drawShift(Canvas c,float x,float y,float s,ShiftStateMachine.State state,int color) {
        begin(c,x,y,s,color); boolean lock=state==ShiftStateMachine.State.CAPS_LOCK;
        if(state!=ShiftStateMachine.State.OFF) p.setStyle(Paint.Style.FILL);
        line(c,0,-8,9,1,4,1,4,lock?5:8,-4,lock?5:8,-4,1,-9,1,0,-8);
        if(lock) c.drawRoundRect(-4,7,4,9,0.6f,0.6f,p); c.restore();
    }
    static void drawDelete(Canvas c,float x,float y,float s,int color) {
        begin(c,x,y,s,color); line(c,-9,0,-3,-6,9,-6,9,6,-3,6,-9,0); line(c,0,-2.5f,5,2.5f); line(c,5,-2.5f,0,2.5f); c.restore();
    }
    static void drawReturn(Canvas c,float x,float y,float s,int color) {
        begin(c,x,y,s,color); line(c,9,-6,9,2,-9,2,-4,-3); line(c,-9,2,-4,7); c.restore();
    }
    static void drawCheckmark(Canvas c,float x,float y,float s,int color) {
        begin(c,x,y,s,color); p.setStrokeWidth(2); line(c,-7,0,-2,5,7,-5); c.restore();
    }
    static void drawCancel(Canvas c,float x,float y,float s,int color) {
        begin(c,x,y,s,color); p.setStrokeWidth(2); line(c,-5,-5,5,5); line(c,5,-5,-5,5); c.restore();
    }
    static void drawSearch(Canvas c,float x,float y,float s,int color) {
        begin(c,x,y,s,color); c.drawCircle(-2,-2,5.8f,p); line(c,2,2,8,8); c.restore();
    }
    static void drawEmojiCategory(Canvas c,int index,float x,float y,float s,int color) {
        if(index==1) { drawSmiley(c,x,y,s,color); return; }
        begin(c,x,y,s,color); path.rewind();
        switch(index) {
            case 0 -> { c.drawCircle(0,0,8,p); line(c,0,-5,0,0,4,2); }
            case 2 -> { path.moveTo(-6,6); path.quadTo(-11,-5,7,-8); path.quadTo(9,9,-6,6); c.drawPath(path,p); line(c,-8,8,3,-3); }
            case 3 -> { c.drawRoundRect(-8,-5,4,6,2,2,p); c.drawArc(1,-4,9,3,-90,180,false,p); line(c,-8,9,6,9); }
            case 4 -> { c.drawCircle(0,0,8,p); c.drawArc(-13,-8,-2,8,-65,130,false,p); c.drawArc(2,-8,13,8,115,130,false,p); line(c,-7,0,7,0); }
            case 5 -> { path.moveTo(0,9); path.cubicTo(-20,-13,20,-13,0,9); c.drawPath(path,p); c.drawCircle(0,-2,2,p); }
            case 6 -> { c.drawArc(-6,-9,6,3,40,280,false,p); line(c,-4,1,-3,5,3,5,4,1); line(c,-2,8,2,8); }
            case 7 -> { path.moveTo(0,8); path.cubicTo(-17,-3,-6,-13,0,-5); path.cubicTo(6,-13,17,-3,0,8); c.drawPath(path,p); }
            case 8 -> { line(c,-6,8,-6,-8); path.moveTo(-6,-7); path.cubicTo(0,-11,1,-4,7,-7); path.lineTo(7,1); path.cubicTo(1,5,0,-2,-6,2); c.drawPath(path,p); }
        }
        c.restore();
    }
}
