package dev.oneui.toolbox.samsung.wallet;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import dalvik.system.InMemoryDexClassLoader;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Independent transit editor. It never instantiates a door-key Activity or Fragment. */
final class WalletTransitDesignModule {
    // Registered opaque wallet component with the exact same manifest theme as
    // the native access-design Activity. Its implementation is never run here.
    static final String PROXY = "com.samsung.android.spay.common.util.QuickAccessCardDragDragValueActivity";
    static final String MARKER = "oneui.toolbox.transitArt.independentDesign";
    static final String KEY = MARKER + ".key", TEMPLATE = MARKER + ".template";
    private static final String PKG = "com.samsung.android.spay";
    private static final String ENTRY = "dev.oneui.toolbox.samsung.wallet.transit.TransitDesignActivity";
    private static final String SESSION = MARKER + ".session", SELECTED = MARKER + ".selected";
    private static final int PICK = 0x6e49;
    static final int EDIT_REQUEST = 0x6e4a;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final ThreadLocal<Boolean> APPLYING = new ThreadLocal<>();
    private static final Map<String, Drawable.ConstantState> ORIGINALS = new java.util.LinkedHashMap<String, Drawable.ConstantState>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Drawable.ConstantState> entry) { return size() > 8; }
    };

    static void rememberOriginal(String key, Drawable drawable) {
        if (key != null && drawable != null && drawable.getConstantState() != null)
            ORIGINALS.put(key, drawable.getConstantState());
    }

    static void install(Context context, ClassLoader loader) throws Exception { new State(context, loader).install(); }

    static void open(Activity activity, Intent intent) {
        // Match the native access-design entry: ordinary Activity-for-result launch
        // with no custom transition options, followed by native finish on return.
        activity.startActivityForResult(intent, EDIT_REQUEST);
    }

    private static final class State {
        final Context context;
        final ClassLoader loader;
        final Map<Activity, Editor> editors = new WeakHashMap<>();
        final Map<ImageView, Editor> images = new WeakHashMap<>();
        final Map<String, Draft> drafts = new HashMap<>();
        Class<?> activityClass;

        State(Context context, ClassLoader loader) { this.context = context; this.loader = loader; }
        Class<?> type(String name) throws ClassNotFoundException { return Class.forName(name, false, loader); }

        void install() throws Exception {
            XposedBridge.hookMethod(Instrumentation.class.getDeclaredMethod("newActivity", ClassLoader.class,
                    String.class, Intent.class), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam call) throws Throwable {
                    Intent intent = (Intent) call.args[2];
                    if (!PROXY.equals(call.args[1]) || intent == null || !intent.getBooleanExtra(MARKER, false)) return;
                    if (activityClass == null) {
                        Context module = context.createPackageContext("dev.oneui.toolbox", 0);
                        byte[] dex;
                        try (InputStream in = module.getAssets().open("transit_design_activity.dex");
                             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                            byte[] buffer = new byte[8192]; int count;
                            while ((count = in.read(buffer)) >= 0) out.write(buffer, 0, count);
                            dex = out.toByteArray();
                        }
                        activityClass = new InMemoryDexClassLoader(ByteBuffer.wrap(dex), loader).loadClass(ENTRY);
                    }
                    Activity activity = (Activity) activityClass.getDeclaredConstructor().newInstance();
                    InvocationHandler callbacks = (owner, method, args) -> {
                        Activity host = (Activity) owner;
                        try { return event(host, args); }
                        catch (Throwable error) {
                            Log.i("OneUIToolboxTransitDesign", "editor unavailable stage=" + args[0]
                                    + " type=" + error.getClass().getSimpleName());
                            if (!host.isFinishing()) host.finish();
                            return null;
                        }
                    };
                    activityClass.getMethod("setController", InvocationHandler.class).invoke(activity, callbacks);
                    call.setResult(activity);
                    Log.i("OneUIToolboxTransitDesign", "independent transit Activity created");
                }
            });
            // Only this independent editor's preview is tracked. Access-card bindings
            // and editor state never participate in these native-image callbacks.
            for (String method : new String[]{"setImageDrawable", "setImageBitmap", "setImageResource"}) {
                Class<?> arg = method.equals("setImageDrawable") ? Drawable.class
                        : method.equals("setImageBitmap") ? Bitmap.class : int.class;
                XposedBridge.hookMethod(ImageView.class.getDeclaredMethod(method, arg), new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam call) {
                        if (Boolean.TRUE.equals(APPLYING.get())) return;
                        Editor editor = images.get((ImageView) call.thisObject);
                        if (editor == null || editor.ended) return;
                        if (!(editor.image.getDrawable() instanceof Art)) editor.original = editor.image.getDrawable();
                        if (editor.bitmap != null) render(editor, editor.bitmap);
                    }
                });
            }
            Log.i("OneUIToolboxTransitDesign", "independent transit design hooks ready");
        }

        Object event(Activity activity, Object[] args) throws Exception {
            String name = (String) args[0];
            Editor editor = editors.get(activity);
            if ("create".equals(name)) attach(activity, (Bundle) args[1]);
            else if ("result".equals(name) && editor != null && ((Integer) args[1]) == PICK) {
                editor.draft.picking = false;
                Intent data = (Intent) args[3];
                Uri uri = data == null ? null : data.getData();
                if (uri == null && data != null && data.getClipData() != null && data.getClipData().getItemCount() > 0)
                    uri = data.getClipData().getItemAt(0).getUri();
                if (((Integer) args[2]) == Activity.RESULT_OK && uri != null) decode(editor.draft, uri);
            } else if ("saveState".equals(name) && editor != null) {
                Bundle saved = (Bundle) args[1];
                saved.putString(SESSION, editor.draft.id); saved.putString(SELECTED, editor.draft.selected);
                saved.putBoolean(SESSION + ".picking", editor.draft.picking);
                saved.putBoolean(SESSION + ".reset", editor.draft.reset);
                saved.putBoolean(SESSION + ".logo", editor.draft.logo);
                saved.putBoolean(SESSION + ".selectionChanged", editor.draft.selectionChanged);
            } else if ("back".equals(name)) return editor != null && editor.draft.busy;
            else if ("destroy".equals(name) && editor != null) {
                editor.ended = true; editors.remove(activity); images.remove(editor.image);
                boolean keep = activity.isChangingConfigurations(); editor.keep = keep;
                if (editor.crop != null) editor.crop.dismiss();
                if (!keep) { editor.draft.active = false; drafts.remove(editor.draft.id); release(editor.draft); }
            }
            return null;
        }

        void attach(Activity activity, Bundle saved) throws Exception {
            String key = activity.getIntent().getStringExtra(KEY);
            String template = activity.getIntent().getStringExtra(TEMPLATE);
            Object card = XposedHelpers.callStaticMethod(type(PKG + ".vas.transportcard.controller.TransitManagerHelper"), "m", template);
            if (key == null || !key.equals(WalletTransitCardArtBindings.key(card))) { activity.finish(); return; }
            Draft draft = saved == null ? null : drafts.get(saved.getString(SESSION));
            if (draft == null || !draft.active || !draft.key.equals(key)) {
                draft = new Draft(key);
                draft.reset = !WalletCardArtStore.exists(context, key);
                draft.logo = WalletTransitCardLogo.enabled(context, key);
                if (saved != null) {
                    draft.selected = saved.getString(SELECTED);
                    draft.picking = saved.getBoolean(SESSION + ".picking");
                    draft.reset = saved.getBoolean(SESSION + ".reset", draft.reset);
                    draft.logo = saved.getBoolean(SESSION + ".logo", draft.logo);
                    draft.selectionChanged = saved.getBoolean(SESSION + ".selectionChanged");
                }
                drafts.put(draft.id, draft);
            }
            View root = activity.getLayoutInflater().inflate(
                    activity.getResources().getIdentifier("fragment_design_your_key", "layout", PKG), null, false);
            activity.setContentView(root);
            // Use the same native floating title, insets, scrolling and button layout
            // as the door-card editor, without moving widgets into a new wrapper.
            XposedHelpers.callStaticMethod(type(PKG + ".common.extensions.FloatingExtKt"), "applyFloatingFeatureExt",
                    activity, "更改设计", view(root, "coordinator_layout"), view(root, "nested_scroll_view"),
                    view(root, "floating_bottom_container"));
            activity.setTitle("更改设计");
            Editor s = new Editor(activity, draft, (ImageView) view(root, "ig_big_image"),
                    view(root, "primary_button"), view(root, "secondary_button"));
            Drawable.ConstantState original = ORIGINALS.get(key);
            if (original != null) s.original = original.newDrawable(activity.getResources()).mutate();
            else {
                int fallback = activity.getResources().getIdentifier("transit_wlt_default_card", "drawable", PKG);
                s.original = fallback == 0 ? null : activity.getDrawable(fallback);
            }
            set(s.image, s.original);
            editors.put(activity, s); images.put(s.image, s); draft.owner = new WeakReference<>(s);
            view(root, "rg_container").setVisibility(View.GONE);
            View nameSection = view(root, "rl_name_container");
            ((ViewGroup) nameSection.getParent()).removeView(nameSection);
            view(root, "cb_add_to_pay_tab").setVisibility(View.GONE);
            View presets = view(root, "rv_card"); presets.setVisibility(View.GONE);
            ViewGroup parent = (ViewGroup) presets.getParent();
            s.library = new WalletCardArtLibraryView(activity, new WalletCardArtLibraryView.Listener() {
                @Override public void onAdd() { if (available(s)) pick(s); }
                @Override public void onSelect(String id) {
                    if (!available(s)) return;
                    s.draft.selected = id; s.draft.reset = false;
                    s.draft.selectionChanged = true;
                    s.library.setSelected(id); syncOptions(s); preview(s);
                }
                @Override public void onDelete(String id) { if (available(s)) delete(s.draft, id); }
            });
            parent.addView(s.library, parent.indexOfChild(presets) + 1, new LinearLayout.LayoutParams(-1, -2));
            s.options = WalletTransitCardArtPanel.createSettings(activity);
            parent.addView(s.options.group, parent.indexOfChild(s.library) + 1,
                    new LinearLayout.LayoutParams(-1, -2));
            s.options.resetRow.setOnClickListener(v -> {
                if (!available(s)) return;
                s.draft.reset = true; s.draft.selected = null;
                s.draft.selectionChanged = true;
                s.library.setSelected(null); syncOptions(s); preview(s);
            });
            s.options.logoSwitch.setOnCheckedChangeListener((button, checked) -> {
                if (s.updatingOptions || !available(s) || !custom(s.draft)) return;
                s.draft.logo = checked;
                if (s.bitmap != null) render(s, s.bitmap);
            });
            ((TextView) s.save).setText("保存"); ((TextView) s.cancel).setText("取消");
            s.save.setOnClickListener(v -> { if (available(s)) commit(s.draft); });
            s.cancel.setOnClickListener(v -> { if (!s.draft.busy) activity.finish(); });
            String url = (String) XposedHelpers.callStaticMethod(type(PKG + ".vas.transportcard.utils.TransitUtils"), "k", card, false);
            s.image.post(() -> {
                // A successfully displayed native detail image is already the right
                // original, including issuer-specific artwork choices. Keep its copy.
                if (original != null || !live(s) || url == null || url.isEmpty()) return;
                try {
                    Object callback = XposedHelpers.newInstance(type("ys1"), s.image);
                    XposedHelpers.callStaticMethod(type(PKG + ".vas.transportcard.utils.UiUtil"), "g", url,
                            s.image.getWidth(), s.image.getHeight(), callback, callback);
                } catch (Throwable error) {
                    Log.i("OneUIToolboxTransitDesign", "native preview load unavailable " + error.getClass().getSimpleName());
                }
            });
            load(s);
            if (draft.source != null) crop(draft);
            Log.i("OneUIToolboxTransitDesign", "independent transit editor attached; native preview without name or tabs");
        }

        boolean live(Editor s) { return s != null && !s.ended && !s.activity.isDestroyed() && !s.activity.isFinishing(); }
        Editor current(Draft d) { Editor s = d.owner.get(); return live(s) ? s : null; }
        boolean available(Editor s) { return live(s) && !s.loading && !s.draft.busy && !s.draft.picking; }
        boolean custom(Draft d) {
            return !d.reset && (d.selected != null || WalletCardArtStore.exists(context, d.key));
        }
        void syncOptions(Editor s) {
            if (s.options == null) return;
            boolean custom = custom(s.draft);
            s.updatingOptions = true;
            try {
                s.options.resetRow.setEnabled(available(s));
                // A default card disables the toggle, retaining its checked preference.
                s.options.logoSwitch.setChecked(s.draft.logo);
                s.options.logoSwitch.setEnabled(custom && available(s));
                s.options.logoRow.setAlpha(custom ? 1f : 0.45f);
            } finally { s.updatingOptions = false; }
        }
        void busy(Draft d, boolean value) {
            d.busy = value; Editor s = current(d);
            if (s != null) { s.save.setEnabled(!value && !s.loading); s.cancel.setEnabled(!value); syncOptions(s); }
        }
        void load(Editor s) {
            s.loading = true; s.save.setEnabled(false); syncOptions(s);
            IO.execute(() -> {
                List<String> ids = WalletCardArtStore.library(context);
                String selected = s.draft.selected;
                if (!s.draft.reset && !ids.contains(selected))
                    selected = context.getSharedPreferences("oneui.toolbox.transitArt", 0).getString(s.draft.key, null);
                String chosen = !s.draft.reset && ids.contains(selected) ? selected : null;
                MAIN.post(() -> {
                    if (!live(s)) return;
                    s.loading = false; s.draft.selected = chosen; s.library.setItems(ids, chosen);
                    busy(s.draft, s.draft.busy); preview(s);
                });
                for (String id : ids) {
                    Bitmap bitmap = WalletCardArtStore.loadLibrary(context, id); if (bitmap == null) continue;
                    Bitmap thumb = Bitmap.createScaledBitmap(bitmap, 240, 150, true);
                    MAIN.post(() -> { if (live(s)) s.library.setBitmap(id, thumb); });
                }
            });
        }
        void preview(Editor s) {
            String selected = s.draft.selected;
            boolean reset = s.draft.reset;
            boolean changed = s.draft.selectionChanged;
            IO.execute(() -> {
                Bitmap bitmap = reset ? null : changed && selected != null
                        ? WalletCardArtStore.loadLibrary(context, selected) : WalletCardArtStore.load(context, s.draft.key);
                if (bitmap == null && !reset && selected != null) bitmap = WalletCardArtStore.loadLibrary(context, selected);
                Bitmap loaded = bitmap;
                MAIN.post(() -> {
                    if (!live(s) || reset != s.draft.reset || changed != s.draft.selectionChanged
                            || !java.util.Objects.equals(selected, s.draft.selected)) return;
                    s.bitmap = loaded;
                    if (loaded != null) render(s, loaded);
                    else set(s.image, s.original);
                });
            });
        }
        void render(Editor s, Bitmap bitmap) { set(s.image, new Art(s.image, bitmap,
                s.draft.logo)); }
        void pick(Editor s) {
            s.draft.picking = true;
            Intent gallery = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("image/*").setPackage("com.sec.android.gallery3d").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try { s.activity.startActivityForResult(gallery, PICK); }
            catch (ActivityNotFoundException absent) {
                try { s.activity.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE).setType("image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), PICK); }
                catch (RuntimeException error) { s.draft.picking = false; toast(s.draft, "无法打开图片选择器"); }
            }
        }
        void decode(Draft d, Uri uri) {
            busy(d, true);
            IO.execute(() -> {
                Bitmap source = null;
                try { source = WalletCardArtStore.decodeSource(context, uri); } catch (Exception ignored) { }
                Bitmap decoded = source;
                MAIN.post(() -> {
                    busy(d, false);
                    if (!d.active) { if (decoded != null) decoded.recycle(); return; }
                    if (decoded == null) { toast(d, "无法读取这张图片"); return; }
                    release(d); d.source = decoded; d.position.clear(); crop(d);
                });
            });
        }
        void crop(Draft d) {
            Editor s = current(d); if (s == null || s.crop != null || d.source == null) return;
            AlertDialog.Builder builder = new AlertDialog.Builder(s.activity).setTitle("调整卡面")
                    .setMessage("拖动调整位置，双指缩放");
            WalletCardArtCropView crop = new WalletCardArtCropView(builder.getContext(), d.source);
            crop.restoreState(d.position, MARKER);
            AlertDialog dialog = builder.setView(crop).setNegativeButton("取消", null).setPositiveButton("添加", null).create();
            s.crop = dialog; s.loading = true; s.save.setEnabled(false);
            dialog.setOnDismissListener(v -> {
                crop.saveState(d.position, MARKER); s.crop = null; s.loading = false;
                if (!s.keep) release(d);
                if (live(s)) busy(d, d.busy);
            });
            dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
                if (d.busy) return;
                Bitmap output;
                try { output = crop.render(); } catch (Throwable error) { toast(d, "图片尚未加载"); return; }
                busy(d, true); dialog.setCancelable(false);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
                IO.execute(() -> {
                    String added = null;
                    try { added = WalletCardArtStore.addLibrary(context, output); } catch (Exception ignored) { }
                    finally { output.recycle(); }
                    String id = added;
                    MAIN.post(() -> {
                        busy(d, false); Editor target = current(d);
                        if (id == null) {
                            if (target != null && target.crop != null) {
                                target.crop.setCancelable(true);
                                target.crop.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                                target.crop.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
                            }
                            toast(d, "添加失败，请重试"); return;
                        }
                        d.selected = id; d.reset = false; d.selectionChanged = true;
                        if (target != null && target.crop != null) target.crop.dismiss(); else release(d);
                        if (target != null) load(target);
                    });
                });
            }));
            dialog.show();
        }
        void delete(Draft d, String id) {
            busy(d, true);
            IO.execute(() -> {
                boolean deleted;
                try { WalletCardArtStore.deleteLibrary(context, id); deleted = true; } catch (Exception error) { deleted = false; }
                boolean success = deleted;
                MAIN.post(() -> {
                    busy(d, false); Editor target = current(d);
                    if (success && target != null) load(target); else if (!success) toast(d, "删除失败，请重试");
                });
            });
        }
        void commit(Draft d) {
            if (!d.reset && d.selected == null && !WalletCardArtStore.exists(context, d.key)) { toast(d, "请先选择一张图片"); return; }
            busy(d, true); String selected = d.selected;
            boolean reset = d.reset, logo = d.logo;
            IO.execute(() -> {
                boolean success;
                try {
                    if (reset) WalletCardArtStore.remove(context, d.key);
                    else if (d.selectionChanged && selected != null) {
                        WalletCardArtStore.applyLibrary(context, selected, d.key);
                    }
                    android.content.SharedPreferences.Editor preferences = context
                            .getSharedPreferences("oneui.toolbox.transitArt", 0).edit().putBoolean("logo." + d.key, logo);
                    if (reset) preferences.remove(d.key);
                    else if (d.selectionChanged && selected != null) preferences.putString(d.key, selected);
                    preferences.apply();
                    success = true;
                } catch (Exception error) { success = false; }
                boolean saved = success;
                MAIN.post(() -> {
                    busy(d, false); Editor target = current(d);
                    if (saved) { WalletCardArtCompat.refreshTransit(); if (target != null) target.activity.finish(); }
                    else toast(d, "保存失败，请重试");
                });
            });
        }
        void toast(Draft d, String text) { Editor s = current(d); if (s != null) Toast.makeText(s.activity, text, Toast.LENGTH_SHORT).show(); }
        void release(Draft d) { if (d.source != null && !d.source.isRecycled()) d.source.recycle(); d.source = null; }
    }

    private static View view(View root, String name) {
        View result = root.findViewById(root.getResources().getIdentifier(name, "id", PKG));
        if (result == null) throw new IllegalStateException("Native transit design view missing: " + name);
        return result;
    }
    private static void set(ImageView image, Drawable drawable) {
        APPLYING.set(true);
        try { image.setImageDrawable(drawable); } finally { APPLYING.remove(); }
    }
    private static final class Editor {
        final Activity activity; final Draft draft; final ImageView image; final View save, cancel;
        WalletCardArtLibraryView library; Drawable original; Bitmap bitmap;
        WalletTransitCardArtPanel options;
        AlertDialog crop; boolean loading, ended, keep, updatingOptions;
        Editor(Activity activity, Draft draft, ImageView image, View save, View cancel) {
            this.activity = activity; this.draft = draft; this.image = image; this.save = save; this.cancel = cancel;
            original = image.getDrawable();
        }
    }
    private static final class Draft {
        final String key, id = UUID.randomUUID().toString(); final Bundle position = new Bundle();
        WeakReference<Editor> owner = new WeakReference<>(null);
        String selected; Bitmap source; boolean active = true, busy, picking, reset, logo, selectionChanged;
        Draft(String key) { this.key = key; }
    }
    private static final class Art extends BitmapDrawable {
        final Path clip = new Path(); final boolean logo;
        Art(ImageView view, Bitmap bitmap, boolean logo) { super(view.getResources(), bitmap); this.logo = logo; setFilterBitmap(true); }
        @Override protected void onBoundsChange(Rect bounds) {
            super.onBoundsChange(bounds); clip.reset(); float radius = Math.min(bounds.width(), bounds.height()) * 0.04f;
            clip.addRoundRect(new RectF(bounds), radius, radius, Path.Direction.CW);
        }
        @Override public void draw(Canvas canvas) {
            int save = canvas.save(); canvas.clipPath(clip); super.draw(canvas);
            if (logo) WalletTransitCardLogo.draw(canvas, getBounds()); canvas.restoreToCount(save);
        }
    }
}
