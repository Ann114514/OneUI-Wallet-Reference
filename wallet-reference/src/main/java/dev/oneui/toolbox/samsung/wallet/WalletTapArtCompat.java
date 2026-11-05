package dev.oneui.toolbox.samsung.wallet;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.widget.ImageView;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Binds the native swipe popup's actual WfCardModel to that card's private artwork. */
final class WalletTapArtCompat {
    private static final String WALLET = "com.samsung.android.spay";
    private static final String TAG = "OneUIToolboxTapArt";
    private static final String KEY = TAG + ".key";
    private static final String TRANSIT = TAG + ".transit";
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean REPORTED = new AtomicBoolean();
    private static final AtomicBoolean TRANSIT_REPORTED = new AtomicBoolean();
    private static final AtomicBoolean ACCESS_REPORTED = new AtomicBoolean();

    private WalletTapArtCompat() { }

    static void install(Context context, ClassLoader loader) throws Exception {
        if (INSTALLED.get()) return;
        Class<?> popup = Class.forName(WALLET + ".autoswitch.miniwindow.CardSwipingMiniWindowActivity", false, loader);
        Class<?> model = Class.forName(WALLET + ".pay.WfCardModel", false, loader);
        Class<?> transitManager = Class.forName(WALLET + ".vas.transportcard.controller.TransitManagerHelper", false, loader);
        Class<?> accessManager = Class.forName(WALLET + ".vas.dummykey.controller.DummykeyDataManager", false, loader);
        Method getTransit = transitManager.getDeclaredMethod("m", String.class);
        Method getAccess = accessManager.getDeclaredMethod("k", String.class);
        getTransit.setAccessible(true);
        getAccess.setAccessible(true);
        Method image = popup.getDeclaredMethod("inflateCardImage", ImageView.class, model);
        image.setAccessible(true);
        if (!INSTALLED.compareAndSet(false, true)) return;
        try {
            XposedBridge.hookMethod(image, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam call) {
                    ImageView target = (ImageView) call.args[0];
                    // Clear the previous card before native setters or an asynchronous
                    // request run. A reused view must never retain another card's binding.
                    WalletCardArtCompat.clearTapResult(target);
                    Object card = call.args[1];
                    if (target == null || card == null) return;
                    try {
                        int type = XposedHelpers.getIntField(card, "cardType");
                        String key;
                        if (type == 106) {
                            // Native TransitCardInterfaceImpl.b constructs model.id from
                            // mCardTemplateId. Resolve that exact card, never its image URL.
                            String id = (String) XposedHelpers.getObjectField(card, "id");
                            if (id == null || id.isEmpty()) return;
                            Object record = getTransit.invoke(null, id);
                            if (record == null || !id.equals(XposedHelpers.getObjectField(record, "mCardTemplateId"))) return;
                            key = WalletTransitCardArtBindings.key(record);
                        } else if (type == 104) {
                            // The native card model carries the actual door-key AID.
                            Bundle data = (Bundle) XposedHelpers.callMethod(card, "getData");
                            String aid = data == null ? null : data.getString("AID");
                            if (aid == null || aid.isEmpty()) return;
                            Object record = getAccess.invoke(null, aid);
                            if (record == null || !aid.equals(XposedHelpers.getObjectField(record, "d"))) return;
                            key = WalletCardArtCompat.accessKey(record);
                        } else return;
                        boolean custom = key != null && WalletCardArtStore.exists(context, key);
                        AtomicBoolean reported = type == 106 ? TRANSIT_REPORTED : ACCESS_REPORTED;
                        if (reported.compareAndSet(false, true)) Log.i(TAG,
                                (type == 106 ? "transit" : "access") + " popup card identity resolved; custom=" + custom);
                        if (!custom) return;
                        call.setObjectExtra(KEY, key);
                        call.setObjectExtra(TRANSIT, type == 106);
                    } catch (Throwable error) {
                        reportUnavailable(error);
                    }
                }

                @Override protected void afterHookedMethod(MethodHookParam call) {
                    if (call.hasThrowable()) return;
                    String key = (String) call.getObjectExtra(KEY);
                    if (key == null) return;
                    try {
                        // Keep native layout/animation and the common async renderer.
                        // Native image callbacks cannot overwrite this card's binding.
                        WalletCardArtCompat.bindTapResult((ImageView) call.args[0], key,
                                Boolean.TRUE.equals(call.getObjectExtra(TRANSIT)));
                    } catch (Throwable error) {
                        reportUnavailable(error);
                    }
                }
            });
            Log.i(TAG, "swipe popup card-model artwork hook ready");
        } catch (Throwable error) {
            INSTALLED.set(false);
            throw error;
        }
    }

    private static void reportUnavailable(Throwable error) {
        if (REPORTED.compareAndSet(false, true)) Log.i(TAG,
                "popup artwork unavailable " + error.getClass().getSimpleName());
    }
}
