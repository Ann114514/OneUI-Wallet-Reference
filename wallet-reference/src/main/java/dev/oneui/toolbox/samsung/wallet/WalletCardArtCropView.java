package dev.oneui.toolbox.samsung.wallet;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityNodeInfo;

/** A fixed card frame over a movable image. The caller owns the source bitmap. */
final class WalletCardArtCropView extends View {
    private static final int OUTPUT_WIDTH = 960;
    private static final int OUTPUT_HEIGHT = 600;
    private static final float ASPECT = (float) OUTPUT_WIDTH / OUTPUT_HEIGHT;
    private static final float MAX_ZOOM = 6f;

    private final Bitmap source;
    private final Paint imagePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guidePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF frame = new RectF();
    private final Path frameClip = new Path();
    private final ScaleGestureDetector scaling;
    private final float inset;
    private final float verticalInset;
    private final float touchSlop;

    private float minimumScale;
    private float imageScale;
    private float imageX;
    private float imageY;
    private float centerX = 0.5f;
    private float centerY = 0.5f;
    private float zoom = 1f;
    private float lastFocusX;
    private float lastFocusY;
    private float lastX;
    private float lastY;
    private float downX;
    private float downY;
    private int activePointer = MotionEvent.INVALID_POINTER_ID;
    private boolean touching;
    private boolean moved;

    WalletCardArtCropView(Context context, Bitmap bitmap) {
        super(context);
        if (bitmap == null || bitmap.isRecycled() || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0
                || bitmap.getConfig() == Bitmap.Config.HARDWARE) {
            throw new IllegalArgumentException("A valid software image is required");
        }
        source = bitmap;
        inset = dp(24);
        verticalInset = dp(8);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        int foreground = Color.DKGRAY;
        TypedArray colors = context.obtainStyledAttributes(new int[]{android.R.attr.textColorPrimary});
        try {
            ColorStateList text = colors.getColorStateList(0);
            if (text != null) foreground = text.getDefaultColor();
        } finally {
            colors.recycle();
        }
        // Let the dialog's actual surface continue behind the crop area.
        setBackgroundColor(Color.TRANSPARENT);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(1.5f));
        borderPaint.setColor(foreground);
        guidePaint.setStyle(Paint.Style.STROKE);
        guidePaint.setStrokeWidth(dp(0.75f));
        guidePaint.setColor(foreground);
        guidePaint.setAlpha(Math.round(Color.alpha(foreground) * 0.55f));
        setFocusable(true);
        setClickable(true);
        setContentDescription("卡面裁剪预览。拖动图片调整位置，双指缩放。无障碍向前或向后滚动可缩放图片。");
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        scaling = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
                lastFocusX = detector.getFocusX();
                lastFocusY = detector.getFocusY();
                moved = true;
                return usable();
            }

            @Override public boolean onScale(ScaleGestureDetector detector) {
                float factor = detector.getScaleFactor();
                if (!usable() || !finite(factor) || factor <= 0f) return false;
                float next = clamp(imageScale * factor, minimumScale, minimumScale * MAX_ZOOM);
                float ratio = next / imageScale;
                // Follow the moving pinch focus while keeping its source point stable.
                imageX = detector.getFocusX() - (lastFocusX - imageX) * ratio;
                imageY = detector.getFocusY() - (lastFocusY - imageY) * ratio;
                imageScale = next;
                lastFocusX = detector.getFocusX();
                lastFocusY = detector.getFocusY();
                constrain();
                invalidate();
                return true;
            }
        });
        // Two-finger gestures are explicit; a double tap should not unexpectedly zoom.
        scaling.setQuickScaleEnabled(false);
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = Math.max(getSuggestedMinimumWidth(), Math.round(dp(360)));
        int measuredWidth = resolveSizeAndState(width, widthMeasureSpec, 0);
        float cardWidth = Math.max(0, (measuredWidth & MEASURED_SIZE_MASK)
                - getPaddingLeft() - getPaddingRight() - inset * 2f);
        int height = Math.max(getSuggestedMinimumHeight(), Math.round(cardWidth / ASPECT
                + verticalInset * 2f) + getPaddingTop() + getPaddingBottom());
        setMeasuredDimension(measuredWidth, resolveSizeAndState(height, heightMeasureSpec, 0));
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        rememberPosition();
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        float left = getPaddingLeft() + inset;
        float top = getPaddingTop() + verticalInset;
        float right = width - getPaddingRight() - inset;
        float bottom = height - getPaddingBottom() - verticalInset;
        if (right <= left || bottom <= top) {
            frame.setEmpty();
            frameClip.reset();
            minimumScale = imageScale = 0f;
            return;
        }
        float cardWidth = Math.min(right - left, (bottom - top) * ASPECT);
        float cardHeight = cardWidth / ASPECT;
        float middleX = (left + right) / 2f;
        float middleY = (top + bottom) / 2f;
        frame.set(middleX - cardWidth / 2f, middleY - cardHeight / 2f,
                middleX + cardWidth / 2f, middleY + cardHeight / 2f);
        frameClip.reset();
        float radius = cardHeight * 0.04f;
        frameClip.addRoundRect(frame, radius, radius, Path.Direction.CW);
        minimumScale = Math.max(cardWidth / source.getWidth(), cardHeight / source.getHeight());
        applyPosition();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!usable()) return;
        int save = canvas.save();
        canvas.clipPath(frameClip);
        drawImage(canvas);
        if (touching) {
            for (int i = 1; i < 3; i++) {
                float x = frame.left + frame.width() * i / 3f;
                float y = frame.top + frame.height() * i / 3f;
                canvas.drawLine(x, frame.top, x, frame.bottom, guidePaint);
                canvas.drawLine(frame.left, y, frame.right, y, guidePaint);
            }
        }
        canvas.restoreToCount(save);
        float radius = frame.height() * 0.04f;
        canvas.drawRoundRect(frame, radius, radius, borderPaint);
    }

    private void drawImage(Canvas canvas) {
        int save = canvas.save();
        canvas.translate(imageX, imageY);
        canvas.scale(imageScale, imageScale);
        canvas.drawBitmap(source, 0f, 0f, imagePaint);
        canvas.restoreToCount(save);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled() || !usable()) return false;
        scaling.onTouchEvent(event);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            activePointer = event.getPointerId(0);
            lastX = downX = event.getX();
            lastY = downY = event.getY();
            moved = false;
            touching = true;
            ViewParent parent = getParent();
            if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
        } else if (action == MotionEvent.ACTION_MOVE) {
            int index = event.findPointerIndex(activePointer);
            if (index >= 0) {
                float x = event.getX(index);
                float y = event.getY(index);
                if (!scaling.isInProgress() && event.getPointerCount() == 1) {
                    imageX += x - lastX;
                    imageY += y - lastY;
                    constrain();
                }
                if (Math.abs(x - downX) > touchSlop || Math.abs(y - downY) > touchSlop) moved = true;
                lastX = x;
                lastY = y;
            }
        } else if (action == MotionEvent.ACTION_POINTER_UP) {
            int index = event.getActionIndex();
            if (event.getPointerId(index) == activePointer && event.getPointerCount() > 1) {
                int next = index == 0 ? 1 : 0;
                activePointer = event.getPointerId(next);
                lastX = event.getX(next);
                lastY = event.getY(next);
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            if (action == MotionEvent.ACTION_UP && !moved) performClick();
            touching = false;
            activePointer = MotionEvent.INVALID_POINTER_ID;
            rememberPosition();
            ViewParent parent = getParent();
            if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
        }
        invalidate();
        return true;
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    /** Returns the selected rectangular card art; the display supplies rounded corners. */
    Bitmap render() {
        if (!usable() || getWidth() <= 0 || getHeight() <= 0)
            throw new IllegalStateException("The crop preview is not laid out");
        Bitmap output = Bitmap.createBitmap(OUTPUT_WIDTH, OUTPUT_HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        canvas.scale(OUTPUT_WIDTH / frame.width(), OUTPUT_HEIGHT / frame.height());
        canvas.translate(-frame.left, -frame.top);
        drawImage(canvas);
        return output;
    }

    void saveState(Bundle state, String prefix) {
        if (state == null || prefix == null) return;
        rememberPosition();
        state.putFloat(prefix + "centerX", centerX);
        state.putFloat(prefix + "centerY", centerY);
        state.putFloat(prefix + "zoom", zoom);
    }

    void restoreState(Bundle state, String prefix) {
        if (state == null || prefix == null) return;
        centerX = safe(state.getFloat(prefix + "centerX", 0.5f), 0.5f, 0f, 1f);
        centerY = safe(state.getFloat(prefix + "centerY", 0.5f), 0.5f, 0f, 1f);
        zoom = safe(state.getFloat(prefix + "zoom", 1f), 1f, 1f, MAX_ZOOM);
        applyPosition();
        invalidate();
    }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.ImageView");
        info.setScrollable(true);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        float current = minimumScale > 0f ? imageScale / minimumScale : zoom;
        info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(
                AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, 1f, MAX_ZOOM, clamp(current, 1f, MAX_ZOOM)));
    }

    @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (usable() && (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
            rememberPosition();
            zoom = clamp(zoom * (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? 1.25f : 0.8f), 1f, MAX_ZOOM);
            applyPosition();
            invalidate();
            return true;
        }
        return super.performAccessibilityAction(action, arguments);
    }

    private void rememberPosition() {
        if (!usable()) return;
        centerX = clamp((frame.centerX() - imageX) / imageScale / source.getWidth(), 0f, 1f);
        centerY = clamp((frame.centerY() - imageY) / imageScale / source.getHeight(), 0f, 1f);
        zoom = clamp(imageScale / minimumScale, 1f, MAX_ZOOM);
    }

    private void applyPosition() {
        if (frame.isEmpty() || minimumScale <= 0f) return;
        imageScale = minimumScale * zoom;
        imageX = frame.centerX() - centerX * source.getWidth() * imageScale;
        imageY = frame.centerY() - centerY * source.getHeight() * imageScale;
        constrain();
    }

    private void constrain() {
        imageX = clamp(imageX, frame.right - source.getWidth() * imageScale, frame.left);
        imageY = clamp(imageY, frame.bottom - source.getHeight() * imageScale, frame.top);
    }

    private boolean usable() {
        return !source.isRecycled() && !frame.isEmpty() && minimumScale > 0f && imageScale > 0f;
    }

    private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
    private static boolean finite(float value) { return !Float.isNaN(value) && !Float.isInfinite(value); }
    private static float clamp(float value, float low, float high) {
        return Math.max(low, Math.min(high, value));
    }
    private static float safe(float value, float fallback, float low, float high) {
        return finite(value) ? clamp(value, low, high) : fallback;
    }
}
