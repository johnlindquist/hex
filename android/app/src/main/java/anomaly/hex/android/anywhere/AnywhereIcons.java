package anomaly.hex.android.anywhere;

import static anomaly.hex.android.ui.HexTheme.*;

import android.content.Context;
import android.graphics.*;
import android.view.*;

/** Minimal 20-unit vector stroke icons matching KeyIcons, with native long-press tooltips. */
public final class AnywhereIcons {
  private AnywhereIcons() {}

  public enum Glyph {
    SUMMARIZE,
    EXPLAIN,
    KEY_POINTS,
    REPLY,
    COPY,
    CHECK,
    SEND,
    CLOSE
  }

  public static IconButton button(
      Context c, Glyph glyph, String tooltip, int sizeDp, int iconDp, int bgColor, int fgColor,
      int borderColor, Runnable action) {
    IconButton b = new IconButton(c, glyph, iconDp, fgColor);
    b.setContentDescription(tooltip);
    b.setTooltipText(tooltip);
    b.setBackground(shape(c, bgColor, 999, borderColor));
    b.setClickable(true);
    b.setFocusable(true);
    b.setOnClickListener(v -> {
      v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
      action.run();
    });
    b.setLayoutParams(new ViewGroup.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
    return b;
  }

  public static final class IconButton extends View {
    private Glyph glyph;
    private int iconColor;
    private final int iconPx;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    IconButton(Context c, Glyph glyph, int iconDp, int color) {
      super(c);
      this.glyph = glyph;
      this.iconColor = color;
      this.iconPx = dp(c, iconDp);
    }

    public void setGlyph(Glyph glyph, int color, String tooltip) {
      this.glyph = glyph;
      this.iconColor = color;
      setContentDescription(tooltip);
      setTooltipText(tooltip);
      invalidate();
    }

    @Override
    public void setPressed(boolean pressed) {
      super.setPressed(pressed);
      setAlpha(pressed ? 0.65f : 1f);
      float s = pressed ? 0.94f : 1f;
      setScaleX(s);
      setScaleY(s);
    }

    @Override
    protected void onDraw(Canvas c) {
      drawGlyph(c, glyph, getWidth() * 0.5f, getHeight() * 0.5f, iconPx, iconColor, paint, path);
    }
  }

  private static void drawGlyph(
      Canvas c, Glyph glyph, float cx, float cy, float size, int color, Paint p, Path path) {
    c.save();
    c.translate(cx, cy);
    c.scale(size / 20f, size / 20f);
    p.reset();
    p.setAntiAlias(true);
    p.setColor(color);
    p.setStyle(Paint.Style.STROKE);
    p.setStrokeWidth(1.7f);
    p.setStrokeCap(Paint.Cap.ROUND);
    p.setStrokeJoin(Paint.Join.ROUND);

    switch (glyph) {
      case SUMMARIZE -> {
        c.drawLine(-6.5f, -4.5f, 6.5f, -4.5f, p);
        c.drawLine(-6.5f, 0f, 6.5f, 0f, p);
        c.drawLine(-6.5f, 4.5f, 1.5f, 4.5f, p);
      }
      case EXPLAIN -> {
        // Minimal lightbulb matching Hex's rounded stroke style
        c.drawArc(-5.5f, -8f, 5.5f, 3f, 135f, 270f, false, p);
        c.drawLine(-3.2f, 1.5f, -2.5f, 4.8f, p);
        c.drawLine(3.2f, 1.5f, 2.5f, 4.8f, p);
        c.drawLine(-2.5f, 4.8f, 2.5f, 4.8f, p);
        c.drawLine(-2f, 7.6f, 2f, 7.6f, p);
      }
      case KEY_POINTS -> {
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(-6f, -5f, 1.3f, p);
        c.drawCircle(-6f, 0f, 1.3f, p);
        c.drawCircle(-6f, 5f, 1.3f, p);
        p.setStyle(Paint.Style.STROKE);
        c.drawLine(-2f, -5f, 6.5f, -5f, p);
        c.drawLine(-2f, 0f, 6.5f, 0f, p);
        c.drawLine(-2f, 5f, 6.5f, 5f, p);
      }
      case REPLY -> {
        line(c, p, path, -2.5f, -5.5f, -7f, -1f, -2.5f, 3.5f);
        path.rewind();
        path.moveTo(-6.5f, -1f);
        path.lineTo(1.5f, -1f);
        path.cubicTo(7.5f, -1f, 7.5f, 5.5f, 3.5f, 6.5f);
        c.drawPath(path, p);
      }
      case COPY -> {
        c.drawRoundRect(-2.5f, -2.5f, 6.5f, 6.5f, 1.8f, 1.8f, p);
        path.rewind();
        path.moveTo(-2.5f, 2.5f);
        path.lineTo(-4.8f, 2.5f);
        path.quadTo(-6.5f, 2.5f, -6.5f, 0.8f);
        path.lineTo(-6.5f, -4.8f);
        path.quadTo(-6.5f, -6.5f, -4.8f, -6.5f);
        path.lineTo(0.8f, -6.5f);
        path.quadTo(2.5f, -6.5f, 2.5f, -4.8f);
        path.lineTo(2.5f, -2.5f);
        c.drawPath(path, p);
      }
      case CHECK -> {
        p.setStrokeWidth(2.1f);
        line(c, p, path, -6f, 0.5f, -1.5f, 5f, 6.5f, -4.5f);
      }
      case SEND -> {
        p.setStrokeWidth(2.1f);
        c.drawLine(0f, 5.5f, 0f, -5.5f, p);
        line(c, p, path, -4.8f, -0.8f, 0f, -5.5f, 4.8f, -0.8f);
      }
      case CLOSE -> {
        p.setStrokeWidth(1.9f);
        c.drawLine(-4.5f, -4.5f, 4.5f, 4.5f, p);
        c.drawLine(4.5f, -4.5f, -4.5f, 4.5f, p);
      }
    }
    c.restore();
  }

  private static void line(Canvas c, Paint p, Path path, float... pts) {
    path.rewind();
    path.moveTo(pts[0], pts[1]);
    for (int i = 2; i < pts.length; i += 2) path.lineTo(pts[i], pts[i + 1]);
    c.drawPath(path, p);
  }
}
