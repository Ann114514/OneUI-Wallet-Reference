package dev.oneui.toolbox.samsung.wallet;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small gallery using the wallet's own category and card-item layouts. No file access. */
final class WalletCardArtLibraryView extends LinearLayout {
    interface Listener {
        void onAdd();
        void onSelect(String id);
        void onDelete(String id);
    }

    private static final String PKG = "com.samsung.android.spay";
    private final Listener listener;
    private final LinearLayout cards;
    private final Map<String, Tile> tiles = new LinkedHashMap<>();
    private final int spacing;

    WalletCardArtLibraryView(Context context, Listener listener) {
        this(context, listener, true);
    }

    WalletCardArtLibraryView(Context context, Listener listener, boolean showHeading) {
        super(context);
        this.listener = listener;
        setOrientation(VERTICAL);
        spacing = dimension("qa_tab_8_dp", 8);
        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setFillViewport(false);
        cards = new LinearLayout(context);
        cards.setOrientation(HORIZONTAL);
        cards.setGravity(Gravity.CENTER_VERTICAL);
        scroll.addView(cards, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View category = inflate("design_your_key_art_item", this);
        TextView title = category == null ? null : find(category, "dummykey_category_title", TextView.class);
        View originalList = category == null ? null : find(category, "dummykey_category_item_recyclerview", View.class);
        if (title != null && originalList != null && originalList.getParent() instanceof ViewGroup) {
            title.setText("自定义");
            title.setVisibility(showHeading ? VISIBLE : GONE);
            ViewGroup parent = (ViewGroup) originalList.getParent();
            int index = parent.indexOfChild(originalList);
            ViewGroup.LayoutParams params = originalList.getLayoutParams();
            scroll.setPadding(originalList.getPaddingLeft(), originalList.getPaddingTop(),
                    originalList.getPaddingRight(), originalList.getPaddingBottom());
            if (originalList instanceof ViewGroup)
                scroll.setClipToPadding(((ViewGroup) originalList).getClipToPadding());
            parent.removeViewAt(index);
            parent.addView(scroll, index, params);
            addView(category);
        } else {
            TextView fallbackTitle = new TextView(context);
            fallbackTitle.setText("自定义");
            fallbackTitle.setVisibility(showHeading ? VISIBLE : GONE);
            fallbackTitle.setTextAppearance(android.R.style.TextAppearance_Material_Body1);
            fallbackTitle.setPadding(0, spacing, 0, spacing);
            addView(fallbackTitle, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            addView(scroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        setItems(null, null);
    }

    void setItems(List<String> ids, String selectedId) {
        Map<String, Bitmap> existing = new LinkedHashMap<>();
        for (Map.Entry<String, Tile> entry : tiles.entrySet())
            if (entry.getValue().bitmap != null) existing.put(entry.getKey(), entry.getValue().bitmap);
        cards.removeAllViews();
        tiles.clear();

        Tile add = newTile();
        add.mask.setVisibility(GONE);
        add.image.setImageDrawable(new AddDrawable(
                themeColor(android.R.attr.textColorPrimary, 0xff333333),
                getResources().getDisplayMetrics().density));
        add.root.setContentDescription("添加图片");
        GradientDrawable addBackground = new GradientDrawable();
        addBackground.setColor(themeColor(android.R.attr.colorControlHighlight, 0x14777777));
        addBackground.setCornerRadius(add.radius);
        add.image.setBackground(addBackground);
        View.OnClickListener addClick = view -> { if (listener != null) listener.onAdd(); };
        add.root.setEnabled(true);
        add.image.setEnabled(true);
        add.root.setOnClickListener(addClick);
        add.image.setOnClickListener(addClick);
        append(add.root);

        if (ids != null) for (String id : ids) {
            if (id == null || id.isEmpty() || tiles.containsKey(id)) continue;
            Tile tile = newTile();
            tile.root.setContentDescription("自定义卡面");
            View.OnClickListener select = view -> { if (listener != null) listener.onSelect(id); };
            View.OnLongClickListener remove = view -> {
                new AlertDialog.Builder(getContext()).setItems(new String[]{"删除卡面"},
                        (dialog, which) -> { if (listener != null) listener.onDelete(id); })
                        .setNegativeButton("取消", null).show();
                return true;
            };
            for (View target : new View[]{tile.root, tile.image, tile.mask}) {
                target.setEnabled(true);
                target.setOnClickListener(select);
                target.setOnLongClickListener(remove);
            }
            tiles.put(id, tile);
            append(tile.root);
            Bitmap bitmap = existing.get(id);
            if (bitmap != null) setBitmap(id, bitmap);
        }
        setSelected(selectedId);
    }

    void setBitmap(String id, Bitmap bitmap) {
        Tile tile = tiles.get(id);
        if (tile == null) return;
        tile.bitmap = bitmap;
        tile.image.setImageBitmap(bitmap);
    }

    void setSelected(String id) {
        for (Map.Entry<String, Tile> entry : tiles.entrySet()) {
            boolean selected = entry.getKey().equals(id);
            Tile tile = entry.getValue();
            tile.mask.setVisibility(selected ? VISIBLE : GONE);
            tile.root.setSelected(selected);
            tile.image.setSelected(selected);
            // Native presets disable the selected card. Gallery cards retain long-press deletion.
            tile.image.setEnabled(true);
            tile.mask.setEnabled(true);
        }
    }

    private void append(View child) {
        ViewGroup.LayoutParams original = child.getLayoutParams();
        LinearLayout.LayoutParams params = original == null
                ? new LinearLayout.LayoutParams(dp(160), dp(100))
                : new LinearLayout.LayoutParams(original);
        if (original instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) original;
            params.topMargin = margins.topMargin;
            params.bottomMargin = margins.bottomMargin;
        }
        params.setMarginStart(cards.getChildCount() == 0 ? 0 : spacing);
        params.setMarginEnd(0);
        cards.addView(child, params);
    }

    private Tile newTile() {
        View root = inflate("design_your_key_rectangle_card_item", cards);
        ImageView image = root == null ? null : find(root, "iv_card", ImageView.class);
        View mask = root == null ? null : find(root, "iv_mask", View.class);
        if (root != null && image != null && mask != null) return configureTile(root, image, mask);

        FrameLayout fallback = new FrameLayout(getContext());
        fallback.setLayoutParams(new LinearLayout.LayoutParams(dp(160), dp(100)));
        ImageView fallbackImage = new ImageView(getContext());
        fallbackImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
        fallback.addView(fallbackImage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        View fallbackMask = new View(getContext());
        GradientDrawable outline = new GradientDrawable();
        outline.setColor(0x183388ff);
        outline.setCornerRadius(dimension("qa_tab_2_dp", 2));
        outline.setStroke(dp(2), themeColor(android.R.attr.colorAccent, 0xff3388ff));
        fallbackMask.setBackground(outline);
        fallback.addView(fallbackMask, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return configureTile(fallback, fallbackImage, fallbackMask);
    }

    private Tile configureTile(View root, ImageView image, View mask) {
        float radius = dimension("qa_tab_2_dp", 2);
        if (image.getParent() instanceof View) {
            View card = (View) image.getParent();
            try {
                // Native design_your_key_rectangle_card_item uses CardView's
                // qa_tab_2_dp, which changes with Samsung's size qualifiers.
                radius = ((Number) card.getClass().getMethod("getRadius").invoke(card)).floatValue();
            } catch (ReflectiveOperationException | ClassCastException ignored) { }
            if (card != root && card.getParent() == root && mask.getParent() == root) {
                // The native selection frame is 77x52 while its card is 67x42.
                // A custom image/add tile must select that same visible card area.
                ViewGroup.LayoutParams cardParams = card.getLayoutParams();
                ViewGroup.LayoutParams maskParams = mask.getLayoutParams();
                if (cardParams != null && maskParams != null) {
                    maskParams.width = cardParams.width;
                    maskParams.height = cardParams.height;
                    mask.setLayoutParams(maskParams);
                }
            }
        }
        final float cardRadius = Math.max(0f, radius);
        ViewOutlineProvider contour = new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), cardRadius);
            }
        };
        image.setOutlineProvider(contour);
        image.setClipToOutline(true);
        mask.setOutlineProvider(contour);
        mask.setClipToOutline(true);
        if (mask instanceof ViewGroup) {
            ViewGroup layers = (ViewGroup) mask;
            if (layers.getChildCount() > 0 && layers.getChildAt(0) instanceof ImageView) {
                ImageView border = (ImageView) layers.getChildAt(0);
                Drawable nativeBorder = border.getDrawable();
                if (nativeBorder instanceof GradientDrawable) {
                    Drawable.ConstantState state = nativeBorder.getConstantState();
                    GradientDrawable fitted = (GradientDrawable) (state == null ? nativeBorder
                            : state.newDrawable(getResources(), getContext().getTheme())).mutate();
                    // Keep the native stroke/color and center check icon; replace
                    // only the outer-frame radius with the actual image radius.
                    fitted.setCornerRadius(cardRadius);
                    border.setImageDrawable(fitted);
                }
            }
        }
        return new Tile(root, image, mask, cardRadius);
    }

    private View inflate(String layout, ViewGroup parent) {
        int id = getResources().getIdentifier(layout, "layout", PKG);
        if (id == 0) return null;
        try { return LayoutInflater.from(getContext()).inflate(id, parent, false); }
        catch (RuntimeException | LinkageError ignored) { return null; }
    }

    private <T extends View> T find(View root, String name, Class<T> type) {
        int id = getResources().getIdentifier(name, "id", PKG);
        View found = id == 0 ? null : root.findViewById(id);
        return type.isInstance(found) ? type.cast(found) : null;
    }

    private int dimension(String name, int fallbackDp) {
        int id = getResources().getIdentifier(name, "dimen", PKG);
        return id == 0 ? dp(fallbackDp) : getResources().getDimensionPixelSize(id);
    }

    private int themeColor(int attribute, int fallback) {
        TypedValue value = new TypedValue();
        if (!getContext().getTheme().resolveAttribute(attribute, value, true)) return fallback;
        if (value.resourceId != 0) {
            try { return getResources().getColorStateList(value.resourceId, getContext().getTheme()).getDefaultColor(); }
            catch (RuntimeException ignored) { return fallback; }
        }
        return value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT
                ? value.data : fallback;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    /** No intrinsic size: ImageView gives this drawable its exact padded card-image bounds. */
    private static final class AddDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float arm;

        AddDrawable(int color, float density) {
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(2f * density);
            arm = 14f * density;
        }

        @Override public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            float centerX = bounds.exactCenterX();
            float centerY = bounds.exactCenterY();
            float half = Math.min(arm, Math.min(bounds.width(), bounds.height()) * 0.2f);
            canvas.drawLine(centerX - half, centerY, centerX + half, centerY, paint);
            canvas.drawLine(centerX, centerY - half, centerX, centerY + half, paint);
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private static final class Tile {
        final View root;
        final ImageView image;
        final View mask;
        final float radius;
        Bitmap bitmap;
        Tile(View root, ImageView image, View mask, float radius) {
            this.root = root; this.image = image; this.mask = mask; this.radius = radius;
        }
    }
}
