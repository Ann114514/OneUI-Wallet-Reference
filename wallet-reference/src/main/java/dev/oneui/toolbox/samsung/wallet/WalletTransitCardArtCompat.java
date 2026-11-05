package dev.oneui.toolbox.samsung.wallet;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.view.ViewOutlineProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Transit detail entry and native settings clipping; editing has its own Activity. */
final class WalletTransitCardArtCompat {
    private static final String PKG = "com.samsung.android.spay";
    private static final String TRANSIT = PKG + ".vas.transportcard.";
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();

    static void install(Context context, ClassLoader loader) throws Exception {
        if (!INSTALLED.compareAndSet(false, true)) return;
        try { WalletTransitDesignModule.install(context, loader); new State(context, loader).install(); }
        catch (Exception error) { INSTALLED.set(false); throw error; }
    }

    private static final class State {
        final Context context;
        final ClassLoader loader;
        final Map<Object, Session> sessions = new WeakHashMap<>();
        final List<XC_MethodHook.Unhook> hooks = new ArrayList<>();

        State(Context context, ClassLoader loader) { this.context = context; this.loader = loader; }

        void install() throws Exception {
            try {
                Class<?> detail = Class.forName(TRANSIT + "ui.TransitDetailActivity", false, loader);
                hooks.add(XposedBridge.hookMethod(detail.getDeclaredMethod("onActivityResult", int.class,
                        int.class, Intent.class), new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam call) {
                        if (Integer.valueOf(WalletTransitDesignModule.EDIT_REQUEST).equals(call.args[0]))
                            call.setResult(null);
                    }
                }));
                Class<?> fragment = Class.forName(TRANSIT + "ui.TransitDetailBaseFragment", false, loader);
                hooks.add(XposedBridge.hookMethod(fragment.getDeclaredMethod("onCreateView",
                        LayoutInflater.class, ViewGroup.class, Bundle.class), new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam call) {
                        if (call.hasThrowable()) return;
                        try { attach(call.thisObject); }
                        catch (Throwable error) { report("entry unavailable " + error.getClass().getSimpleName()); }
                    }
                }));
                Class<?> base = Class.forName(PKG + ".common.ui.detail.CommonCardDetailBaseFragment", false, loader);
                hooks.add(XposedBridge.hookMethod(base.getDeclaredMethod("onDestroyView"), new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam call) {
                        Session old = sessions.remove(call.thisObject);
                        if (old != null && old.panel.group.getParent() instanceof ViewGroup)
                            ((ViewGroup) old.panel.group.getParent()).removeView(old.panel.group);
                    }
                }));
            } catch (Exception error) {
                for (XC_MethodHook.Unhook hook : hooks) hook.unhook();
                throw error;
            }
            WalletTransitCardArtBindings.install(loader);
            IO.execute(() -> {
                WalletTransitCardLogo.initialize(context);
                MAIN.post(WalletCardArtCompat::refreshTransit);
            });
            report("independent transit design route ready");
        }

        void attach(Object fragment) throws Exception {
            Activity activity = (Activity) XposedHelpers.getObjectField(fragment, "U");
            View root = (View) XposedHelpers.getObjectField(fragment, "V");
            if (root != null) {
                root.post(() -> clipNativeSettings(root));
                root.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                    if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop)
                        clipNativeSettings(root);
                });
            }
            if (activity == null || root == null || sessions.containsKey(fragment)) return;
            Object card = XposedHelpers.getObjectField(fragment, "C0");
            if (card == null) card = XposedHelpers.getObjectField(activity, "a");
            String key = WalletTransitCardArtBindings.key(card);
            if (key == null) return;
            String template = (String) XposedHelpers.getObjectField(card, "mCardTemplateId");
            View anchor = root.findViewById(activity.getResources().getIdentifier("switch_default_card_area", "id", PKG));
            if (anchor == null || !(anchor.getParent() instanceof ViewGroup)) return;
            ViewGroup nativeGroup = (ViewGroup) anchor.getParent();
            if (!(nativeGroup.getParent() instanceof ViewGroup)) return;
            WalletTransitCardArtPanel panel = WalletTransitCardArtPanel.create(activity, nativeGroup);
            Session s = new Session(key, template, panel);
            panel.showEntryOnly();
            panel.designRow.setOnClickListener(view -> {
                // An independent transit Activity; native proxy is replaced only for this marker.
                Intent edit = new Intent().setClassName(PKG,
                        WalletTransitDesignModule.PROXY)
                        .putExtra(WalletTransitDesignModule.MARKER, true)
                        .putExtra(WalletTransitDesignModule.KEY, s.key)
                        .putExtra(WalletTransitDesignModule.TEMPLATE, s.template);
                int cardImage = activity.getResources().getIdentifier("detail_card_image_view", "id", PKG);
                View image = root.findViewById(cardImage);
                if (image instanceof ImageView) WalletTransitDesignModule.rememberOriginal(s.key,
                        WalletCardArtCompat.nativeTransitImage((ImageView) image));
                WalletTransitDesignModule.open(activity, edit);
            });
            ViewGroup parent = (ViewGroup) nativeGroup.getParent();
            parent.addView(panel.group, parent.indexOfChild(nativeGroup) + 1);
            sessions.put(fragment, s);
            report("transit change-design entry attached");
        }

    }

    /** Native group backgrounds supply the exact outline; only clip the row feedback. */
    private static void clipNativeSettings(View view) {
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        Drawable background = group.getBackground();
        if (background != null && group.getWidth() > 0 && group.getHeight() > 0) {
            Outline outline = new Outline();
            background.getOutline(outline);
            if (!outline.isEmpty() && outline.getRadius() > 0) {
                group.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
                group.setClipToOutline(true);
            }
        }
        for (int i = 0; i < group.getChildCount(); i++) clipNativeSettings(group.getChildAt(i));
    }

    private static final class Session {
        final String key, template;
        final WalletTransitCardArtPanel panel;
        Session(String key, String template, WalletTransitCardArtPanel panel) {
            this.key = key; this.template = template; this.panel = panel;
        }
    }

    private static void report(String text) { Log.i("OneUIToolboxTransitArt", text); }
}
