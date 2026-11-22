package anomaly.hex.android.anywhere;

import static anomaly.hex.android.keyboard.KeyboardPalette.*;

import android.app.Dialog;
import android.content.Context;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import anomaly.hex.android.anywhere.AnywhereIcons.Glyph;
import anomaly.hex.android.ui.HexTheme;
import java.util.*;

/** Full-screen translucent drawing overlay to circle or highlight part of the screenshot. */
final class AnnotateImageDialog {
  interface Callback {
    void onAnnotated(Bitmap annotated);
  }

  private AnnotateImageDialog() {}

  static void show(Context c, Bitmap source, Callback cb) {
    if (source == null) return;
    Dialog d = new Dialog(c, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
    FrameLayout root = new FrameLayout(c);
    root.setBackgroundColor(0xF00A0B0D);

    DrawCanvas canvasView = new DrawCanvas(c, source);
    root.addView(canvasView, new FrameLayout.LayoutParams(-1, -1));

    LinearLayout bar = new LinearLayout(c);
    bar.setGravity(Gravity.CENTER);
    int pad = HexTheme.dp(c, 10);
    bar.setPadding(pad, pad, pad, pad);
    bar.setBackground(HexTheme.shape(c, 0xE61A1B20, 999, 0x26FFFFFF));

    addBarIcon(c, bar, Glyph.CLOSE, "Cancel", TEXT_MUTED, d::dismiss);
    addBarIcon(c, bar, Glyph.UNDO, "Undo stroke", TEXT, canvasView::undo);
    addBarIcon(c, bar, Glyph.TRASH, "Clear ink", TEXT_MUTED, canvasView::clear);
    addBarIcon(c, bar, Glyph.CHECK, "Use annotated screenshot", HexTheme.MIC, () -> {
      Bitmap out = canvasView.renderAnnotated();
      d.dismiss();
      if (out != null) cb.onAnnotated(out);
    });

    FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
    blp.bottomMargin = HexTheme.dp(c, 28);
    root.addView(bar, blp);

    TextView hint = new TextView(c);
    hint.setText("Circle or highlight what to ask about");
    hint.setTextSize(13);
    hint.setTextColor(TEXT_MUTED);
    hint.setBackground(HexTheme.shape(c, 0xCC1A1B20, 999, 0x22FFFFFF));
    hint.setPadding(HexTheme.dp(c, 14), HexTheme.dp(c, 6), HexTheme.dp(c, 14), HexTheme.dp(c, 6));
    FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
    hlp.topMargin = HexTheme.dp(c, 42);
    root.addView(hint, hlp);

    d.setContentView(root);
    d.show();
  }

  private static void addBarIcon(Context c, LinearLayout bar, Glyph g, String tip, int color, Runnable r) {
    var b = AnywhereIcons.button(c, g, tip, 42, 18, KEY_FILL, color, 0x1FFFFFFF, r);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(HexTheme.dp(c, 42), HexTheme.dp(c, 42));
    lp.leftMargin = HexTheme.dp(c, 6);
    lp.rightMargin = HexTheme.dp(c, 6);
    bar.addView(b, lp);
  }

  private static final class DrawCanvas extends View {
    private final Bitmap source;
    private final List<Path> strokes = new ArrayList<>();
    private final RectF dst = new RectF();
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Path current;

    DrawCanvas(Context c, Bitmap source) {
      super(c);
      this.source = source;
      ink.setColor(HexTheme.MIC);
      ink.setStyle(Paint.Style.STROKE);
      ink.setStrokeWidth(HexTheme.dp(c, 4.5f));
      ink.setStrokeCap(Paint.Cap.ROUND);
      ink.setStrokeJoin(Paint.Join.ROUND);
    }

    void undo() {
      if (!strokes.isEmpty()) {
        strokes.remove(strokes.size() - 1);
        invalidate();
      }
    }

    void clear() {
      strokes.clear();
      invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
      super.onSizeChanged(w, h, ow, oh);
      float pad = HexTheme.dp(getContext(), 16);
      float availW = Math.max(1, w - pad * 2);
      float availH = Math.max(1, h - HexTheme.dp(getContext(), 150));
      float scale = Math.min(availW / source.getWidth(), availH / source.getHeight());
      float bw = source.getWidth() * scale;
      float bh = source.getHeight() * scale;
      float left = (w - bw) * 0.5f;
      float top = (h - bh) * 0.5f;
      dst.set(left, top, left + bw, top + bh);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
      float x = e.getX(), y = e.getY();
      switch (e.getActionMasked()) {
        case MotionEvent.ACTION_DOWN -> {
          current = new Path();
          current.moveTo(x, y);
          strokes.add(current);
          invalidate();
          return true;
        }
        case MotionEvent.ACTION_MOVE -> {
          if (current != null) {
            current.lineTo(x, y);
            invalidate();
          }
          return true;
        }
        case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
          current = null;
          return true;
        }
        default -> { return false; }
      }
    }

    @Override
    protected void onDraw(Canvas c) {
      c.drawBitmap(source, null, dst, null);
      for (Path p : strokes) c.drawPath(p, ink);
    }

    Bitmap renderAnnotated() {
      if (strokes.isEmpty() || dst.width() <= 0 || dst.height() <= 0) return source;
      Bitmap copy = source.copy(Bitmap.Config.ARGB_8888, true);
      Canvas canvas = new Canvas(copy);
      float sx = copy.getWidth() / dst.width();
      float sy = copy.getHeight() / dst.height();
      Matrix m = new Matrix();
      m.postTranslate(-dst.left, -dst.top);
      m.postScale(sx, sy);
      Paint scaledInk = new Paint(ink);
      scaledInk.setStrokeWidth(ink.getStrokeWidth() * Math.max(sx, sy));
      for (Path p : strokes) {
        Path mapped = new Path(p);
        mapped.transform(m);
        canvas.drawPath(mapped, scaledInk);
      }
      return copy;
    }
  }
}
