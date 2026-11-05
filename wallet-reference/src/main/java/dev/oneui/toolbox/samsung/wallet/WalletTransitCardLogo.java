package dev.oneui.toolbox.samsung.wallet;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.Log;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/** Optional presentation overlay; source card images and issuer data stay unchanged. */
final class WalletTransitCardLogo {
    private static final String PACKAGE = "dev.oneui.toolbox";
    private static final String ASSET = "transit_union_logo.png";
    private static final String PREFERENCES = "oneui.toolbox.transitArt";
    private static final AtomicBoolean ATTEMPTED = new AtomicBoolean();
    private static final Paint PAINT = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static volatile Bitmap logo;

    private WalletTransitCardLogo() { }

    /** The caller runs this once on its image worker, never during a drawable's draw. */
    static void initialize(Context wallet) {
        if (wallet == null || !ATTEMPTED.compareAndSet(false, true)) return;
        try {
            Context module = wallet.createPackageContext(PACKAGE, 0);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream stream = module.getAssets().open(ASSET)) {
                BitmapFactory.decodeStream(stream, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0
                    || bounds.outWidth > 32768 || bounds.outHeight > 32768)
                throw new IllegalStateException("Unsupported logo dimensions");
            int sample = 1;
            while ((long) Math.max(bounds.outWidth, bounds.outHeight) > 512L * sample) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inScaled = false;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap decoded;
            try (InputStream stream = module.getAssets().open(ASSET)) {
                decoded = BitmapFactory.decodeStream(stream, null, options);
            }
            if (decoded == null) throw new IllegalStateException("Logo decoding failed");
            logo = decoded;
        } catch (Throwable error) {
            // A missing optional asset must never interrupt card rendering.
            Log.i("OneUIToolboxTransitArt", "logo unavailable " + error.getClass().getSimpleName());
        }
    }

    static boolean enabled(Context context, String cardKey) {
        if (context == null || !valid(cardKey)) return false;
        try {
            return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                    .getBoolean("logo." + cardKey, true);
        } catch (RuntimeException ignored) {
            return true;
        }
    }

    static boolean ready() {
        Bitmap bitmap = logo;
        return bitmap != null && !bitmap.isRecycled();
    }

    /** Call only for a custom-art drawable, after its card-shaped clipping is applied. */
    static void draw(Canvas canvas, Rect bounds) {
        Bitmap bitmap = logo;
        if (canvas == null || bounds == null || bounds.isEmpty()
                || bitmap == null || bitmap.isRecycled()) return;
        float ratio = Math.min(bounds.width() * 0.22f / bitmap.getWidth(),
                bounds.height() * 0.24f / bitmap.getHeight());
        float width = bitmap.getWidth() * ratio;
        float height = bitmap.getHeight() * ratio;
        float inset = bounds.height() * 0.04f;
        float right = bounds.right - inset;
        float bottom = bounds.bottom - inset;
        canvas.drawBitmap(bitmap, null, new RectF(right - width, bottom - height, right, bottom), PAINT);
    }

    private static boolean valid(String key) { return key != null && key.matches("[a-f0-9]{64}"); }
}
