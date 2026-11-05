package dev.oneui.toolbox.samsung.wallet;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.LinearLayout;
import android.widget.Toast;
import android.widget.TextView;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Adds private image artwork to the existing access-card Home/Work design editor. */
public final class WalletCardArtCompat {
    private static final String PKG = "com.samsung.android.spay", ACCESS = PKG + ".vas.dummykey.";
    private static final String STATE = "oneui.toolbox.accessArt.";
    private static final int CUSTOM = 0x6f747842, PICK = 0x6e42;
    private static final AtomicBoolean WATCHING = new AtomicBoolean(), READY = new AtomicBoolean();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final ThreadLocal<Boolean> APPLYING = new ThreadLocal<>();
    private static final Map<ImageView, Binding> VIEWS = new WeakHashMap<>();
    private static State displayState;
    private WalletCardArtCompat() { }

    public static void install(Context context, ClassLoader ignored) {
        try {
            if (context == null || Process.myUid() / 100000 != 0 || !PKG.equals(context.getPackageName())
                    || !"SM-S9180".equals(Build.MODEL)
                    || context.getPackageManager().getPackageInfo(PKG, 0).getLongVersionCode() != 559700323L
                    || !WATCHING.compareAndSet(false, true)) return;
            // Wait for the wallet's real Activity loader. Never enumerate unrelated methods:
            // their unused signatures can reference optional classes absent on this device.
            final XC_MethodHook.Unhook[] watch = new XC_MethodHook.Unhook[1];
            watch[0] = XposedHelpers.findAndHookMethod(Instrumentation.class, "callActivityOnCreate",
                    Activity.class, Bundle.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam call) {
                    Activity activity = (Activity) call.args[0];
                    if (!PKG.equals(activity.getPackageName()) || !READY.compareAndSet(false, true)) return;
                    try {
                        State state = new State(activity.getApplicationContext(), activity.getClassLoader());
                        state.install();
                        displayState = state;
                        try { WalletTransitCardArtCompat.install(activity.getApplicationContext(), activity.getClassLoader()); }
                        catch (Throwable error) { failure("transit install", error); }
                        try { WalletTapArtCompat.install(activity.getApplicationContext(), activity.getClassLoader()); }
                        catch (Throwable error) { failure("tap artwork install", error); }
                        if (watch[0] != null) watch[0].unhook();
                    } catch (Throwable error) { READY.set(false); failure("install", error); }
                }
            });
        } catch (Throwable error) { failure("watch", error); }
    }
    // Transit uses the same bounded artwork renderer and native-image restoration.
    static void bindTransit(ImageView view, String key) {
        State state = displayState; if (state != null) state.bind(view, key, null, true);
    }
    static void clearTransit(ImageView view) {
        State state = displayState; if (state != null) state.clear(view);
    }
    static void refreshTransit() {
        State state = displayState; if (state != null) state.refresh();
    }

    static void clearTapResult(ImageView view) {
        State state = displayState; if (state != null) state.clear(view);
    }
    static void bindTapResult(ImageView view, String key, boolean transit) {
        State state = displayState; if (state != null) state.bind(view, key, null, transit);
    }
    static Drawable nativeTransitImage(ImageView view) {
        Binding binding = VIEWS.get(view);
        return binding != null && binding.transit ? binding.original : view.getDrawable();
    }

    private static final class State {
        final Context context;
        final ClassLoader loader;
        final Class<?> edit, registration;
        final Map<Activity, Editor> editors = new WeakHashMap<>();
        final Map<String, Editor> pendingCommits = new java.util.HashMap<>();
        final Map<String, Crop> crops = new java.util.HashMap<>();
        final ThreadLocal<Editor> committing = new ThreadLocal<>();
        final List<XC_MethodHook.Unhook> hooks = new ArrayList<>();
        boolean active;
        State(Context context, ClassLoader loader) throws Exception {
            this.context = context; this.loader = loader;
            edit = type(ACCESS + "ui.cardreg.EditFragment");
            registration = type(ACCESS + "ui.cardreg.DummyKeyRegistrationActivity");
        }
        Class<?> type(String name) throws Exception { return Class.forName(name, false, loader); }
        void hook(Class<?> owner, String method, Class<?>[] args, SafeHook callback) throws Exception {
            hooks.add(XposedBridge.hookMethod(owner.getDeclaredMethod(method, args), callback));
        }
        abstract class SafeHook extends XC_MethodHook {
            void before(MethodHookParam call) throws Exception { }
            void after(MethodHookParam call) throws Exception { }
            @Override protected final void beforeHookedMethod(MethodHookParam call) {
                if (active) try { before(call); } catch (Throwable error) { failure("callback", error); }
            }
            @Override protected final void afterHookedMethod(MethodHookParam call) {
                if (active) try {
                    if (!call.hasThrowable() || call.getObjectExtra(STATE + "commit") != null) after(call);
                } catch (Throwable error) { failure("callback", error); }
            }
        }
        void install() throws Throwable {
            try {
                hook(edit, "onCreateView", new Class<?>[]{LayoutInflater.class, ViewGroup.class, Bundle.class}, new SafeHook() {
                    @Override void after(MethodHookParam call) throws Exception { attach(call.thisObject, (Bundle) call.args[2]); }
                });
                hook(edit, "onCheckedChanged", new Class<?>[]{RadioGroup.class, int.class}, new SafeHook() {
                    @Override void before(MethodHookParam call) {
                        Editor s = session(call.thisObject); if (s == null) return;
                        if (s.saving) { call.setResult(null); return; }
                        int id = (Integer) call.args[1];
                        if (id == CUSTOM) { s.custom = true; preview(s); call.setResult(null); }
                        else if (id == s.home.getId() || id == s.work.getId()) {
                            s.custom = false; s.nativeId = id;
                            clear(s.image); s.presets.setVisibility(s.presetsVisibility); s.library.setVisibility(View.GONE);
                        }
                    }
                    @Override void after(MethodHookParam call) {
                        Editor s = session(call.thisObject);
                        if (s != null && !s.custom && !s.saving && !(s.image.getDrawable() instanceof ArtDrawable))
                            s.nativeArt = s.image.getDrawable();
                    }
                });
                hook(edit, "onClick", new Class<?>[]{View.class}, new SafeHook() {
                    @Override void before(MethodHookParam call) {
                        Editor s = session(call.thisObject);
                        if (s == null || call.args[0] != s.save) return;
                        if (s.loading || s.saving) { toast(s, "图片正在处理，请稍候"); call.setResult(null); return; }
                        if (s.custom && s.selectedId == null && !WalletCardArtStore.exists(context, s.key)) {
                            toast(s, "请先选择一张图片"); call.setResult(null); return;
                        }
                        if (!s.custom && !WalletCardArtStore.exists(context, s.key)) return;
                        call.setObjectExtra(STATE + "commit", s);
                        call.setObjectExtra(STATE + "prior", committing.get());
                        s.saving = true; s.closeDeferred = false; s.save.setEnabled(false); committing.set(s);
                        // Native save only understands Home/Work. Retain a valid native base.
                        if (s.custom) s.group.check(s.nativeId);
                    }
                    @Override void after(MethodHookParam call) {
                        Editor s = (Editor) call.getObjectExtra(STATE + "commit"); if (s == null) return;
                        Editor prior = (Editor) call.getObjectExtra(STATE + "prior");
                        if (prior == null) committing.remove(); else committing.set(prior);
                        if (s.custom) s.group.check(CUSTOM);
                        if (!s.closeDeferred) { s.saving = false; s.save.setEnabled(true); }
                    }
                });
                hook(Activity.class, "finish", new Class<?>[0], new SafeHook() {
                    @Override void before(MethodHookParam call) {
                        Editor s = committing.get();
                        if (s == null || s.activity.get() != call.thisObject) return;
                        call.setResult(null);
                        if (!s.closeDeferred) { s.closeDeferred = true; commit(s); }
                    }
                });
                hook(Activity.class, "setResult", new Class<?>[]{int.class, Intent.class}, new SafeHook() {
                    @Override void before(MethodHookParam call) {
                        Editor s = committing.get();
                        if (s != null && s.activity.get() == call.thisObject) {
                            s.resultCode = (Integer) call.args[0]; s.resultData = (Intent) call.args[1];
                        }
                    }
                });
                hook(edit, "onSaveInstanceState", new Class<?>[]{Bundle.class}, new SafeHook() {
                    @Override void before(MethodHookParam call) {
                        Editor s = session(call.thisObject); if (s == null) return;
                        Bundle state = (Bundle) call.args[0];
                        state.putBoolean(STATE + "custom", s.custom); state.putString(STATE + "selected", s.selectedId);
                        state.putString(STATE + "session", s.sessionId);
                        state.putBoolean(STATE + "picking", s.picking); state.putInt(STATE + "native", s.nativeId);
                        state.putBoolean(STATE + "committing", s.saving && s.closeDeferred);
                    }
                });
                hook(edit, "onDestroyView", new Class<?>[0], new SafeHook() {
                    @Override void before(MethodHookParam call) {
                        Editor s = session(call.thisObject); if (s == null) return;
                        Activity activity = s.activity.get();
                        s.destroyed = true; editors.remove(activity); clear(s.image);
                        if (s.cropView != null && s.crop != null) s.cropView.saveState(s.crop.position, STATE);
                        s.keepCrop = activity != null && activity.isChangingConfigurations();
                        if (s.cropDialog != null) s.cropDialog.dismiss();
                    }
                });
                hook(registration, "onActivityResult", new Class<?>[]{int.class, int.class, Intent.class}, new SafeHook() {
                    @Override void before(MethodHookParam call) {
                        Editor s = editors.get((Activity) call.thisObject);
                        if (s == null || !s.picking || (Integer) call.args[0] != PICK) return;
                        s.picking = false; call.setResult(null);
                        Intent data = (Intent) call.args[2]; Uri uri = data == null ? null : data.getData();
                        if (uri == null && data != null && data.getClipData() != null && data.getClipData().getItemCount() > 0)
                            uri = data.getClipData().getItemAt(0).getUri();
                        if ((Integer) call.args[1] == Activity.RESULT_OK && uri != null) importDraft(s, uri);
                    }
                });
                for (String name : new String[]{"setImageDrawable", "setImageBitmap", "setImageResource"}) {
                    Class<?> arg = name.equals("setImageDrawable") ? Drawable.class : name.equals("setImageBitmap") ? Bitmap.class : int.class;
                    hook(ImageView.class, name, new Class<?>[]{arg}, new SafeHook() {
                        @Override void after(MethodHookParam call) {
                            if (Boolean.TRUE.equals(APPLYING.get())) return;
                            ImageView view = (ImageView) call.thisObject; Binding binding = VIEWS.get(view);
                            if (binding == null) return;
                            if (!(view.getDrawable() instanceof ArtDrawable)) binding.original = view.getDrawable();
                            apply(view, binding);
                        }
                    });
                }
                active = true; report("access design custom-image option ready");
            } catch (Throwable error) {
                active = false;
                for (XC_MethodHook.Unhook hook : hooks) try { hook.unhook(); } catch (Throwable ignored) { }
                throw error;
            }
            displayBindings();
        }
        Editor session(Object fragment) {
            Object activity = field(fragment, "a");
            Editor s = activity instanceof Activity ? editors.get((Activity) activity) : null;
            return s != null && s.fragment.get() == fragment ? s : null;
        }
        void attach(Object fragment, Bundle state) throws Exception {
            if (!"DESIGN_YOUR_KEY_PARAMS_FROM_CARD_DETAIL".equals(field(fragment, "r"))) return;
            Activity activity = (Activity) field(fragment, "a"); String key = accessKey(field(fragment, "l"));
            if (activity == null || key == null || editors.containsKey(activity)) return;
            Editor s = new Editor(fragment, activity, key); editors.put(activity, s);
            if (state != null) s.sessionId = state.getString(STATE + "session", s.sessionId);
            try {
                // Inflate the same native XML so buttonDrawable=@null, the segment
                // background, typography and all dimensions exactly match Home/Work.
                View template = activity.getLayoutInflater().inflate(
                        activity.getResources().getIdentifier("fragment_design_your_key", "layout", PKG), null, false);
                RadioButton custom = (RadioButton) template.findViewById(s.home.getId());
                ((ViewGroup) custom.getParent()).removeView(custom);
                custom.setChecked(false); custom.setId(CUSTOM); custom.setText("自定义");
                s.group.addView(custom, new RadioGroup.LayoutParams(s.home.getLayoutParams()));
                if (s.group.getOrientation() == RadioGroup.HORIZONTAL) {
                    s.group.setWeightSum(0f);
                    for (RadioButton button : new RadioButton[]{s.home, s.work, custom}) {
                        RadioGroup.LayoutParams params = (RadioGroup.LayoutParams) button.getLayoutParams();
                        params.width = 0; params.weight = 1f; button.setLayoutParams(params);
                    }
                }
                s.library = new WalletCardArtLibraryView(activity, new WalletCardArtLibraryView.Listener() {
                    @Override public void onAdd() { pick(s); }
                    @Override public void onSelect(String id) {
                        if (s.loading || s.saving) return;
                        s.selectedId = id; s.library.setSelected(id); preview(s);
                    }
                    @Override public void onDelete(String id) { deleteImage(s, id); }
                });
                ViewGroup parent = (ViewGroup) s.presets.getParent();
                parent.addView(s.library, parent.indexOfChild(s.presets) + 1,
                        new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                s.library.setVisibility(View.GONE);
                custom.setOnClickListener(view -> { s.group.check(CUSTOM); preview(s); });
                s.custom = state == null ? WalletCardArtStore.exists(context, key) : state.getBoolean(STATE + "custom", WalletCardArtStore.exists(context, key));
                s.selectedId = state == null ? null : state.getString(STATE + "selected");
                s.picking = state != null && state.getBoolean(STATE + "picking");
                int nativeId = state == null ? s.nativeId : state.getInt(STATE + "native", s.nativeId);
                if (nativeId == s.home.getId() || nativeId == s.work.getId()) s.nativeId = nativeId;
                if (s.custom) { s.group.check(CUSTOM); preview(s); }
                if (state != null && state.getBoolean(STATE + "committing")) {
                    s.commitOrigin = pendingCommits.get(key);
                    if (s.commitOrigin != null) { s.saving = true; s.closeDeferred = true; s.save.setEnabled(false); }
                }
                loadLibrary(s, true);
                report("access design custom-image option attached");
            } catch (Throwable error) { editors.remove(activity); throw error; }
        }
        void pick(Editor s) {
            Activity activity = s.activity.get(); if (activity == null || s.picking || s.loading || s.saving) return;
            try {
                s.group.check(CUSTOM); s.picking = true;
                Intent gallery = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("image/*").setPackage("com.sec.android.gallery3d")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try { activity.startActivityForResult(gallery, PICK); }
                catch (ActivityNotFoundException unavailable) {
                    activity.startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), PICK);
                }
            } catch (Throwable error) { s.picking = false; toast(s, "无法打开图片选择器"); }
        }
        void importDraft(Editor s, Uri uri) {
            s.loading = true; s.save.setEnabled(false);
            IO.execute(() -> {
                Bitmap source;
                try { source = WalletCardArtStore.decodeSource(context, uri); }
                catch (Throwable error) { source = null; failure("image import", error); }
                final Bitmap decoded = source;
                MAIN.post(() -> {
                    Editor current = current(s);
                    if (current.destroyed) { if (decoded != null) decoded.recycle(); return; }
                    current.loading = false; current.save.setEnabled(!current.saving);
                    if (decoded == null) { toast(current, "无法读取这张图片，请重新选择"); return; }
                    Crop crop = new Crop(decoded); crops.put(current.sessionId, crop); showCrop(current, crop);
                });
            });
        }
        SharedPreferences preferences() { return context.getSharedPreferences(STATE + "selection", Context.MODE_PRIVATE); }
        Editor current(Editor old) {
            if (!old.destroyed) return old;
            for (Editor candidate : new ArrayList<>(editors.values()))
                if (!candidate.destroyed && candidate.sessionId.equals(old.sessionId)) return candidate;
            return old;
        }
        void loadLibrary(Editor s, boolean migrate) {
            s.loading = true; s.save.setEnabled(false);
            IO.execute(() -> {
                List<String> ids = WalletCardArtStore.library(context);
                String preferred = preferences().getString(s.key, null);
                try {
                    if (migrate && !preferences().getBoolean("migrated." + s.key, false)) {
                        Bitmap applied = WalletCardArtStore.load(context, s.key);
                        if (applied != null && preferred == null) {
                            preferred = WalletCardArtStore.addLibrary(context, applied);
                            preferences().edit().putString(s.key, preferred).apply();
                            ids = WalletCardArtStore.library(context);
                        }
                        preferences().edit().putBoolean("migrated." + s.key, true).apply();
                    }
                } catch (Throwable error) { failure("library migration", error); }
                final List<String> items = ids; final String savedSelection = preferred;
                MAIN.post(() -> {
                    if (s.destroyed) return;
                    s.loading = false; s.save.setEnabled(!s.saving);
                    if (!items.contains(s.selectedId)) s.selectedId = items.contains(savedSelection) ? savedSelection : null;
                    s.library.setItems(items, s.selectedId);
                    if (s.custom) preview(s);
                    Crop crop = crops.get(s.sessionId);
                    if (crop != null && s.cropDialog == null) showCrop(s, crop);
                });
                for (String id : items) {
                    Bitmap bitmap = WalletCardArtStore.loadLibrary(context, id);
                    if (bitmap == null) continue;
                    Bitmap thumbnail = Bitmap.createScaledBitmap(bitmap, 240, 150, true);
                    MAIN.post(() -> { if (!s.destroyed) s.library.setBitmap(id, thumbnail); });
                }
            });
        }
        void preview(Editor s) {
            s.presets.setVisibility(View.GONE); s.library.setVisibility(View.VISIBLE);
            s.library.setSelected(s.selectedId);
            if (s.selectedId == null) {
                clear(s.image); set(s.image, s.nativeArt); bind(s.image, s.key); return;
            }
            clear(s.image); final String id = s.selectedId;
            IO.execute(() -> {
                Bitmap bitmap = WalletCardArtStore.loadLibrary(context, id);
                MAIN.post(() -> {
                    if (!s.destroyed && s.custom && id.equals(s.selectedId) && bitmap != null)
                        set(s.image, new ArtDrawable(s.image, bitmap));
                });
            });
        }
        void deleteImage(Editor s, String id) {
            if (s.loading || s.saving) return;
            s.loading = true; s.save.setEnabled(false);
            IO.execute(() -> {
                boolean deleted;
                try { WalletCardArtStore.deleteLibrary(context, id); deleted = true; }
                catch (Throwable error) { deleted = false; failure("library delete", error); }
                final boolean success = deleted;
                MAIN.post(() -> {
                    if (s.destroyed) return;
                    s.loading = false; s.save.setEnabled(!s.saving);
                    if (!success) { toast(s, "删除失败，请重试"); return; }
                    if (id.equals(s.selectedId)) s.selectedId = null;
                    loadLibrary(s, false);
                });
            });
        }
        void showCrop(Editor s, Crop crop) {
            Activity activity = s.activity.get();
            if (activity == null || s.destroyed || activity.isFinishing() || activity.isDestroyed() || crop.source.isRecycled()) {
                if (crops.get(s.sessionId) == crop) crops.remove(s.sessionId);
                if (!crop.source.isRecycled()) crop.source.recycle();
                return;
            }
            s.crop = crop; s.keepCrop = false; s.loading = true; s.save.setEnabled(false);
            AlertDialog.Builder builder = new AlertDialog.Builder(activity).setTitle("调整卡面")
                    .setMessage("拖动调整位置，双指缩放");
            WalletCardArtCropView view = new WalletCardArtCropView(builder.getContext(), crop.source); s.cropView = view;
            view.restoreState(crop.position, STATE);
            AlertDialog dialog = builder.setView(view).setNegativeButton("取消", null).setPositiveButton("添加", null).create();
            s.cropDialog = dialog;
            dialog.setOnDismissListener(ignored -> {
                s.cropDialog = null; s.cropView = null; s.crop = null;
                if (!s.keepCrop && crops.get(s.sessionId) == crop) {
                    crops.remove(s.sessionId); if (!crop.source.isRecycled()) crop.source.recycle();
                }
                s.loading = false; if (!s.destroyed) s.save.setEnabled(!s.saving);
            });
            dialog.setOnShowListener(ignored -> {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(!crop.adding);
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(!crop.adding); dialog.setCancelable(!crop.adding);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
                if (crop.adding) return;
                final Bitmap output;
                try { output = view.render(); }
                catch (Throwable error) { toast(s, "图片尚未加载，请稍后再试"); return; }
                crop.adding = true;
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false); dialog.setCancelable(false);
                IO.execute(() -> {
                    String added;
                    try { added = WalletCardArtStore.addLibrary(context, output); }
                    catch (Throwable error) { added = null; failure("library add", error); }
                    finally { output.recycle(); }
                    final String id = added;
                    MAIN.post(() -> {
                        crop.adding = false;
                        Editor current = current(s);
                        if (id == null) {
                            AlertDialog activeDialog = current.cropDialog;
                            if (!current.destroyed && activeDialog != null) {
                                activeDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                                activeDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true); activeDialog.setCancelable(true);
                                toast(current, "添加失败，请重试");
                            }
                            return;
                        }
                        // Dismiss whichever crop window owns this image after a rotation.
                        if (current.cropDialog != null) { current.keepCrop = false; current.cropDialog.dismiss(); }
                        else if (crops.remove(s.sessionId) == crop && !crop.source.isRecycled()) crop.source.recycle();
                        if (!current.destroyed) { current.selectedId = id; loadLibrary(current, false); }
                    });
                });
                });
            });
            try { dialog.show(); }
            catch (RuntimeException error) {
                s.cropDialog = null; s.cropView = null; s.crop = null; s.loading = false; s.save.setEnabled(!s.saving);
                if (crops.get(s.sessionId) == crop) crops.remove(s.sessionId);
                if (!crop.source.isRecycled()) crop.source.recycle();
                failure("crop window", error);
            }
        }
        void commit(Editor s) {
            final boolean custom = s.custom; final String selected = s.selectedId;
            pendingCommits.put(s.key, s);
            IO.execute(() -> {
                boolean success;
                try {
                    if (custom && selected != null) WalletCardArtStore.applyLibrary(context, selected, s.key);
                    else if (!custom) WalletCardArtStore.remove(context, s.key);
                    SharedPreferences.Editor pref = preferences().edit().putBoolean("migrated." + s.key, true);
                    if (custom && selected != null) pref.putString(s.key, selected); else pref.remove(s.key);
                    pref.apply();
                    success = true;
                } catch (Throwable error) { success = false; failure("image save", error); }
                final boolean saved = success;
                MAIN.post(() -> {
                    pendingCommits.remove(s.key);
                    Editor current = s;
                    if (s.destroyed) {
                        for (Editor candidate : new ArrayList<>(editors.values()))
                            if (!candidate.destroyed && candidate.commitOrigin == s) { current = candidate; break; }
                    }
                    if (saved && custom && !current.destroyed) bind(current.image, current.key);
                    refresh(); Activity activity = current.activity.get();
                    if (activity == null || activity.isDestroyed()) return;
                    if (saved) {
                        if (!activity.isFinishing()) { activity.setResult(s.resultCode, s.resultData); activity.finish(); }
                    } else {
                        current.saving = false; current.closeDeferred = false; current.save.setEnabled(true);
                        toast(current, "图片保存失败，请重试");
                    }
                });
            });
        }
        interface Setup { void run() throws Exception; }
        void optional(String label, Setup setup) { try { setup.run(); } catch (Throwable error) { failure(label, error); } }
        void displayBindings() {
            // A missing legacy display class must not disable the design editor.
            optional("detail", () -> hook(type(ACCESS + "ui.cardlist.DummyKeyDetailBaseActivity"), "setHeaderView", new Class<?>[0], new SafeHook() {
                ImageView target(Object owner) { return image((View) field(owner, "mCardView"), "cardview_image"); }
                @Override void before(MethodHookParam call) { clear(target(call.thisObject)); }
                @Override void after(MethodHookParam call) throws Exception { bind(target(call.thisObject), accessKey(field(call.thisObject, "mDummyKeyVO"))); }
            }));
            optional("use card", () -> hook(type(ACCESS + "ui.view.SimpleCardInterface"), "a", new Class<?>[]{Context.class, type(ACCESS + "model.DummyKeyVO")}, new SafeHook() {
                @Override void after(MethodHookParam call) throws Exception {
                    View root = (View) call.getResult();
                    bind(image(root, "card_image"), accessKey(call.args[1]), name(root, "card_holder_name"));
                }
            }));
            optional("pay card", () -> hook(type(ACCESS + "controller.DummyKeyInterfaceImpl"), "getSimpleCardFrontView", new Class<?>[]{Context.class, type(PKG + ".pay.WfCardModel")}, new SafeHook() {
                @Override void after(MethodHookParam call) throws Exception {
                    Bundle data = (Bundle) XposedHelpers.callMethod(call.args[1], "getData"); String aid = data == null ? null : data.getString("AID");
                    Object card = aid == null ? null : XposedHelpers.callStaticMethod(type(ACCESS + "controller.DummykeyDataManager"), "k", aid);
                    View root = (View) call.getResult();
                    bind(image(root, "key_clone_pay_tab_big_iv"), accessKey(card), name(root, "dummy_key_nick_name"));
                }
            }));
            optional("card list", () -> hook(type(ACCESS + "ui.homeframe.DoorKeyWalletListAdapter"), "onBindViewHolder", new Class<?>[]{type("androidx.recyclerview.widget.RecyclerView$ViewHolder"), int.class, List.class}, new SafeHook() {
                View root(MethodHookParam call) { return (View) field(call.args[0], "itemView"); }
                @Override void before(MethodHookParam call) { clear(image(root(call), "door_key_image")); }
                @Override void after(MethodHookParam call) throws Exception { View root = root(call); bind(image(root, "door_key_image"), accessKey(root.getTag())); }
            }));
        }
        void bind(ImageView view, String key) {
            bind(view, key, null);
        }
        void bind(ImageView view, String key, TextView name) {
            bind(view, key, name, false);
        }
        void bind(ImageView view, String key, TextView name, boolean transit) {
            if (view == null) return; clear(view); if (key == null) return;
            Binding binding = new Binding(key, view.getDrawable(), name, transit); VIEWS.put(view, binding);
            if (name != null) {
                WeakReference<ImageView> target = new WeakReference<>(view);
                binding.layout = (changed, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    ImageView current = target.get();
                    if (current != null && VIEWS.get(current) == binding) updateName(current, binding);
                };
                view.addOnLayoutChangeListener(binding.layout); name.addOnLayoutChangeListener(binding.layout);
                view.post(() -> {
                    ImageView current = target.get();
                    if (current != null && VIEWS.get(current) == binding) updateName(current, binding);
                });
            }
            apply(view, binding);
        }
        void clear(ImageView view) {
            if (view == null) return; Binding old = VIEWS.remove(view);
            if (old == null) return;
            if (view.getDrawable() instanceof ArtDrawable) set(view, old.original);
            if (old.layout != null) view.removeOnLayoutChangeListener(old.layout);
            TextView name = old.name.get();
            if (name != null) {
                if (old.layout != null) name.removeOnLayoutChangeListener(old.layout);
                if (old.nameColors != null) name.setTextColor(old.nameColors);
            }
        }
        void refresh() {
            for (Map.Entry<ImageView, Binding> entry : new ArrayList<>(VIEWS.entrySet()))
                if (entry.getKey() != null) apply(entry.getKey(), entry.getValue());
        }
        void apply(ImageView view, Binding binding) {
            if (!WalletCardArtStore.exists(context, binding.key)) {
                if (view.getDrawable() instanceof ArtDrawable) set(view, binding.original);
                updateName(view, binding); return;
            }
            Bitmap bitmap = WalletCardArtStore.cached(binding.key);
            if (bitmap != null) {
                boolean logo = binding.transit && WalletTransitCardLogo.ready()
                        && WalletTransitCardLogo.enabled(context, binding.key);
                if (!(view.getDrawable() instanceof ArtDrawable)
                        || ((ArtDrawable) view.getDrawable()).getBitmap() != bitmap
                        || ((ArtDrawable) view.getDrawable()).logo != logo)
                    set(view, new ArtDrawable(view, bitmap, logo));
                updateName(view, binding);
                return;
            }
            if (binding.loading) return; binding.loading = true; WeakReference<ImageView> target = new WeakReference<>(view);
            IO.execute(() -> {
                Bitmap loaded = WalletCardArtStore.load(context, binding.key);
                MAIN.post(() -> {
                    binding.loading = false; ImageView current = target.get();
                    // Another card controller may have applied/reset this key after the
                    // disk read. Re-read current cache/state rather than replaying old art.
                    if (current != null && VIEWS.get(current) == binding
                            && (loaded != null || WalletCardArtStore.cached(binding.key) != null)) apply(current, binding);
                });
            });
        }
        void updateName(ImageView view, Binding binding) {
            TextView name = binding.name.get(); if (name == null) return;
            if (view.getDrawable() instanceof ArtDrawable)
                WalletCardArtNameContrast.apply(view, name, ((ArtDrawable) view.getDrawable()).getBitmap());
            else if (binding.nameColors != null) name.setTextColor(binding.nameColors);
        }
    }
    private static final class Editor {
        final WeakReference<Object> fragment;
        final WeakReference<Activity> activity;
        final String key;
        final RadioGroup group;
        final RadioButton home, work;
        final ImageView image;
        final View presets, save;
        final int presetsVisibility;
        WalletCardArtLibraryView library;
        String selectedId;
        String sessionId = java.util.UUID.randomUUID().toString();
        Drawable nativeArt;
        AlertDialog cropDialog;
        WalletCardArtCropView cropView;
        Crop crop;
        int nativeId;
        int resultCode = 4098;
        Intent resultData;
        Editor commitOrigin;
        boolean custom, picking, loading, saving, closeDeferred, destroyed, keepCrop;
        Editor(Object owner, Activity activity, String key) throws Exception {
            this.fragment = new WeakReference<>(owner); this.activity = new WeakReference<>(activity);
            this.key = key;
            group = (RadioGroup) field(owner, "w"); home = (RadioButton) field(owner, "f"); work = (RadioButton) field(owner, "g");
            image = (ImageView) field(owner, "k"); presets = (View) field(owner, "m"); save = (View) field(owner, "h");
            if (group == null || home == null || work == null || image == null || presets == null || save == null) throw new IllegalStateException("Native design views unavailable");
            presetsVisibility = presets.getVisibility(); nativeId = work.isChecked() ? work.getId() : home.getId();
            nativeArt = image.getDrawable();
        }
    }
    private static final class Crop {
        final Bitmap source; final Bundle position = new Bundle();
        boolean adding;
        Crop(Bitmap source) { this.source = source; }
    }
    private static final class Binding {
        final String key; Drawable original; boolean loading;
        final boolean transit;
        final WeakReference<TextView> name;
        final ColorStateList nameColors;
        View.OnLayoutChangeListener layout;
        Binding(String key, Drawable original, TextView name, boolean transit) {
            this.key = key; this.original = original; this.name = new WeakReference<>(name);
            this.transit = transit;
            nameColors = name == null ? null : name.getTextColors();
        }
    }
    private static final class ArtDrawable extends BitmapDrawable {
        final Path clip = new Path();
        final boolean logo;
        ArtDrawable(ImageView view, Bitmap bitmap) { this(view, bitmap, false); }
        ArtDrawable(ImageView view, Bitmap bitmap, boolean logo) {
            super(view.getResources(), bitmap); setFilterBitmap(true); this.logo = logo;
        }
        @Override protected void onBoundsChange(Rect bounds) {
            super.onBoundsChange(bounds); clip.reset(); float r = Math.min(bounds.width(), bounds.height()) * 0.04f;
            clip.addRoundRect(new RectF(bounds), r, r, Path.Direction.CW);
        }
        @Override public void draw(Canvas canvas) {
            int save = canvas.save(); canvas.clipPath(clip); super.draw(canvas);
            if (logo) WalletTransitCardLogo.draw(canvas, getBounds());
            canvas.restoreToCount(save);
        }
    }
    private static void set(ImageView view, Drawable drawable) {
        Boolean prior = APPLYING.get(); APPLYING.set(Boolean.TRUE);
        try { view.setImageDrawable(drawable); } finally { if (prior == null) APPLYING.remove(); else APPLYING.set(prior); }
    }
    private static ImageView image(View root, String name) {
        if (root == null) return null; int id = root.getResources().getIdentifier(name, "id", PKG);
        View view = id == 0 ? null : root.findViewById(id); return view instanceof ImageView ? (ImageView) view : null;
    }
    private static TextView name(View root, String name) {
        if (root == null) return null; int id = root.getResources().getIdentifier(name, "id", PKG);
        View view = id == 0 ? null : root.findViewById(id); return view instanceof TextView ? (TextView) view : null;
    }
    private static Object field(Object owner, String name) {
        if (owner == null) return null; try { return XposedHelpers.getObjectField(owner, name); } catch (Throwable ignored) { return null; }
    }
    static String accessKey(Object card) throws Exception {
        Object uid = field(card, "c"); return uid instanceof String && !((String) uid).isEmpty() ? digest("access\0" + uid) : null;
    }
    private static String digest(String value) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); StringBuilder hex = new StringBuilder(64);
        for (byte b : bytes) hex.append(Character.forDigit((b >>> 4) & 15, 16)).append(Character.forDigit(b & 15, 16)); return hex.toString();
    }
    private static void toast(Editor s, String message) { Activity activity = s.activity.get(); if (activity != null) Toast.makeText(activity, message, Toast.LENGTH_SHORT).show(); }
    private static final Set<String> REPORTED = new HashSet<>();
    private static void failure(String step, Throwable error) {
        Throwable cause = error instanceof java.lang.reflect.InvocationTargetException && error.getCause() != null ? error.getCause() : error;
        String detail = cause instanceof LinkageError || cause instanceof ReflectiveOperationException ? String.valueOf(cause.getMessage()) : "";
        if (detail.length() > 240) detail = detail.substring(0, 240);
        report("access " + step + " unavailable " + cause.getClass().getSimpleName() + " " + detail);
    }
    private static synchronized void report(String message) {
        if (REPORTED.size() >= 16 || !REPORTED.add(message)) return;
        Log.i("OneUIToolboxArt", message); XposedBridge.log("OneUIToolboxArt: " + message);
    }
}
