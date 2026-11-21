package anomaly.hex.android.keyboard;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import static anomaly.hex.android.keyboard.KeyboardPalette.*;

public final class HexMarkView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path outer = polygon(32,4, 57,18.5f, 57,45.5f, 32,60, 7,45.5f, 7,18.5f);
    private final Path middle = polygon(32,15, 48,24.25f, 48,39.75f, 32,49, 16,39.75f, 16,24.25f);
    private final Path core = polygon(32,24, 39.5f,28.25f, 39.5f,36.75f, 32,41, 24.5f,36.75f, 24.5f,28.25f);
    private boolean warm;
    public HexMarkView(Context c) { super(c); setContentDescription("Dictate with Hex"); }
    public void setWarm(boolean value) { warm = value; invalidate(); }
    private static Path polygon(float... p) {
        Path path = new Path(); path.moveTo(p[0], p[1]);
        for (int i=2; i<p.length; i+=2) path.lineTo(p[i], p[i+1]);
        path.close(); return path;
    }
    @Override protected void drawableStateChanged() { super.drawableStateChanged(); invalidate(); }
    @Override protected void onDraw(Canvas c) {
        if (isPressed()) {
            paint.setStyle(Paint.Style.FILL); paint.setColor(KEY_PRESSED);
            c.drawRoundRect(0,0,getWidth(),getHeight(),getHeight()/2f,getHeight()/2f,paint);
        }
        float side = Math.min(dp(getContext(),19), getHeight()*.56f), scale = side/64;
        if (scale <= 0) return;
        c.save(); c.translate((getWidth()-side)/2,(getHeight()-side)/2); c.scale(scale,scale);
        paint.setColor(TEXT); paint.setStyle(Paint.Style.STROKE); paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeWidth(Math.max(4.5f,1.15f*getResources().getDisplayMetrics().density/scale));
        c.drawPath(outer,paint); c.drawPath(middle,paint);
        paint.setStyle(Paint.Style.FILL); paint.setColor(warm ? WARM_CORE : TEXT); c.drawPath(core,paint); c.restore();
    }
}
