package dev.oneui.toolbox.samsung.wallet;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.widget.ImageView;
import android.widget.TextView;
import java.util.Arrays;

/** Selects a readable name color from the artwork directly underneath its native text. */
final class WalletCardArtNameContrast {
    private static final int LIGHT = 0xffffffff;
    private static final int DARK = 0xff151515;
    private static final int COLUMNS = 16, ROWS = 8;
    private static final double DARK_LUMINANCE = linear(21d / 255d);

    private WalletCardArtNameContrast() { }

    /** The caller supplies only native name views that overlay this card image. */
    static void apply(ImageView art, TextView name, Bitmap bitmap) {
        if (art == null || name == null || bitmap == null || bitmap.isRecycled()
                || name.getVisibility() != android.view.View.VISIBLE
                || name.getText() == null || name.getText().length() == 0) return;
        try {
            RectF region = region(art, name, bitmap);
            if (region == null || region.isEmpty()) return;
            double[] light = new double[COLUMNS * ROWS];
            double[] dark = new double[COLUMNS * ROWS];
            int count = 0;
            for (int row = 0; row < ROWS; row++) {
                int y = Math.min(bitmap.getHeight() - 1,
                        Math.max(0, (int) (region.top + (row + .5f) * region.height() / ROWS)));
                for (int column = 0; column < COLUMNS; column++) {
                    int x = Math.min(bitmap.getWidth() - 1,
                            Math.max(0, (int) (region.left + (column + .5f) * region.width() / COLUMNS)));
                    double background = luminance(bitmap.getPixel(x, y));
                    light[count] = 1.05d / (background + .05d);
                    dark[count] = (Math.max(background, DARK_LUMINANCE) + .05d)
                            / (Math.min(background, DARK_LUMINANCE) + .05d);
                    count++;
                }
            }
            int color = score(light, count) >= score(dark, count) ? LIGHT : DARK;
            if (name.getCurrentTextColor() != color) name.setTextColor(color);
        } catch (RuntimeException ignored) {
            // A detached view or a bitmap replaced during binding must not interrupt the wallet.
        }
    }

    private static RectF region(ImageView art, TextView name, Bitmap bitmap) {
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        if (width <= 0 || height <= 0) return null;
        if (art.getWidth() <= 0 || art.getHeight() <= 0
                || name.getWidth() <= 0 || name.getHeight() <= 0 || name.getLayout() == null)
            return fallback(width, height);

        RectF text = textBounds(name);
        if (text == null) return null;
        Matrix nameToGlobal = new Matrix();
        Matrix artToGlobal = new Matrix();
        name.transformMatrixToGlobal(nameToGlobal);
        art.transformMatrixToGlobal(artToGlobal);
        Matrix globalToArt = new Matrix();
        if (!artToGlobal.invert(globalToArt)) return fallback(width, height);
        // Shared global coordinates account for screen position and scaled parent CardViews.
        Matrix nameToArt = new Matrix();
        nameToArt.setConcat(globalToArt, nameToGlobal);
        nameToArt.mapRect(text);
        if (!finite(text)) return fallback(width, height);
        if (!text.intersect(art.getPaddingLeft(), art.getPaddingTop(),
                art.getWidth() - art.getPaddingRight(), art.getHeight() - art.getPaddingBottom()))
            return null; // A title outside the card must retain its native theme color.

        Drawable drawable = art.getDrawable();
        if (drawable == null) return fallback(width, height);
        Rect bounds = drawable.getBounds();
        if (bounds.width() <= 0 || bounds.height() <= 0) return fallback(width, height);
        text.offset(-art.getPaddingLeft(), -art.getPaddingTop());
        Matrix inverse = new Matrix();
        if (!art.getImageMatrix().invert(inverse)) return fallback(width, height);
        inverse.mapRect(text);
        if (!finite(text)) return fallback(width, height);
        // BitmapDrawable's resource density can change its intrinsic size. Its actual
        // drawable bounds, rather than bitmap pixels or view size, define this mapping.
        text.set((text.left - bounds.left) * width / bounds.width(),
                (text.top - bounds.top) * height / bounds.height(),
                (text.right - bounds.left) * width / bounds.width(),
                (text.bottom - bounds.top) * height / bounds.height());
        return finite(text) && text.intersect(0, 0, width, height) ? text : null;
    }

    private static RectF textBounds(TextView name) {
        Layout layout = name.getLayout();
        if (layout == null || layout.getLineCount() == 0) return null;
        Paint.FontMetrics metrics = name.getPaint().getFontMetrics();
        RectF bounds = null;
        int lines = Math.min(2, layout.getLineCount());
        for (int line = 0; line < lines; line++) {
            if (layout.getLineStart(line) >= layout.getLineEnd(line)) continue;
            float left = Math.min(layout.getLineLeft(line), layout.getLineRight(line));
            float right = Math.max(layout.getLineLeft(line), layout.getLineRight(line));
            float top = layout.getLineBaseline(line) + metrics.ascent;
            float bottom = layout.getLineBaseline(line) + metrics.descent;
            if (right <= left || bottom <= top) continue;
            if (bounds == null) bounds = new RectF(left, top, right, bottom);
            else bounds.union(left, top, right, bottom);
        }
        if (bounds == null) return null;
        bounds.offset(name.getTotalPaddingLeft() - name.getScrollX(),
                name.getTotalPaddingTop() - name.getScrollY());
        return bounds.intersect(0, 0, name.getWidth(), name.getHeight()) ? bounds : null;
    }

    private static RectF fallback(int width, int height) {
        return new RectF(width * .04f, height * .04f, width * .80f, height * .20f);
    }

    private static boolean finite(RectF bounds) {
        return Float.isFinite(bounds.left) && Float.isFinite(bounds.top)
                && Float.isFinite(bounds.right) && Float.isFinite(bounds.bottom);
    }

    private static double luminance(int color) {
        double alpha = ((color >>> 24) & 255) / 255d;
        // Native card surfaces are light; transparent artwork is composited over white.
        double red = ((color >>> 16) & 255) / 255d * alpha + 1d - alpha;
        double green = ((color >>> 8) & 255) / 255d * alpha + 1d - alpha;
        double blue = (color & 255) / 255d * alpha + 1d - alpha;
        return .2126d * linear(red) + .7152d * linear(green) + .0722d * linear(blue);
    }

    private static double linear(double value) {
        return value <= .04045d ? value / 12.92d : Math.pow((value + .055d) / 1.055d, 2.4d);
    }

    private static double score(double[] contrasts, int count) {
        if (count == 0) return 0;
        Arrays.sort(contrasts, 0, count);
        int quarter = Math.max(1, count / 4);
        double total = 0, worst = 0;
        for (int i = 0; i < count; i++) {
            total += contrasts[i];
            if (i < quarter) worst += contrasts[i];
        }
        return .7d * worst / quarter + .3d * total / count;
    }
}
