package anomaly.hex.android.ui;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** Navigation marks on a common 24-unit grid. */
final class TabIcon extends View {
  private final int tab;
  private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
  TabIcon(Context context, int tab) {
    super(context);
    this.tab = tab;
  }
  @Override
  protected void onDraw(Canvas c) {
    float size = HexTheme.dp(getContext(), 20);
    c.save();
    c.translate((getWidth() - size) / 2, (getHeight() - size) / 2);
    c.scale(size / 24, size / 24);
    ink.setColor(isSelected() ? HexTheme.TEXT : HexTheme.FAINT);
    ink.setStyle(Paint.Style.STROKE);
    ink.setStrokeWidth(1.65f);
    ink.setStrokeCap(Paint.Cap.ROUND);
    switch (tab) {
      case 0 -> {
        for (int j = 0; j < 3; j++) {
          int y = 5 + 7 * j, x = j == 1 ? 15 : 9;
          c.drawLine(3, y, x - 3, y, ink);
          c.drawCircle(x, y, 2.5f, ink);
          c.drawLine(x + 3, y, 21, y, ink);
        }
      }
      case 1 -> {
        for (int j = 0; j < 4; j++)
          c.drawLine(4 + j * 3, 7 - j % 3 * 2, 4 + j * 3, 9 + j % 3 * 2, ink);
        c.drawLine(4, 17, 20, 17, ink);
        c.drawLine(4, 21, 15, 21, ink);
      }
      case 2 -> {
        c.drawArc(3, 3, 21, 21, 212, 315, false, ink);
        c.drawLine(3, 4, 3, 9, ink);
        c.drawLine(3, 9, 8, 9, ink);
        c.drawLine(12, 6, 12, 12, ink);
        c.drawLine(12, 12, 16, 15, ink);
      }
      case 3 -> {
        Path p = new Path();
        p.moveTo(9, 3);
        p.lineTo(9, 9);
        p.lineTo(3, 20);
        p.lineTo(21, 20);
        p.lineTo(15, 9);
        p.lineTo(15, 3);
        p.close();
        c.drawPath(p, ink);
        c.drawLine(7, 15, 17, 15, ink);
      }
    }
    c.restore();
  }
}
