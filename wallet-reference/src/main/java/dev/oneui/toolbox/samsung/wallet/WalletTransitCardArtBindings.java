package dev.oneui.toolbox.samsung.wallet;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Registers native transit artwork views without changing card records or issuer images. */
final class WalletTransitCardArtBindings {
    private static final String PKG = "com.samsung.android.spay";
    private static final String TRANSIT = PKG + ".vas.transportcard.";
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final Set<String> REPORTED = new HashSet<>();

    private WalletTransitCardArtBindings() { }

    static void install(ClassLoader loader) {
        if (loader == null || !INSTALLED.compareAndSet(false, true)) return;
        new State(loader).install();
    }

    static String key(Object card) throws Exception {
        Object templateValue = field(card, "mCardTemplateId");
        Object numberValue = field(card, "mCardNum");
        if (!(templateValue instanceof String) || !(numberValue instanceof String)) return null;
        String template = (String) templateValue;
        String number = (String) numberValue;
        if (template.trim().isEmpty() || number.trim().isEmpty()
                || "null".equalsIgnoreCase(number.trim()) || number.indexOf('*') >= 0
                || number.indexOf('\u2022') >= 0 || number.indexOf('\u25cf') >= 0
                || number.indexOf('\u2026') >= 0) return null;
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(
                ("transit\0" + template + "\0" + number).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(64);
        for (byte value : bytes) hex.append(Character.forDigit((value >>> 4) & 15, 16))
                .append(Character.forDigit(value & 15, 16));
        return hex.toString();
    }

    private static final class State {
        final ClassLoader loader;
        State(ClassLoader loader) { this.loader = loader; }
        Class<?> type(String name) throws ClassNotFoundException {
            return Class.forName(name, false, loader);
        }
        Method method(Class<?> owner, String name, Class<?>... args) throws Exception {
            Method method = owner.getDeclaredMethod(name, args);
            method.setAccessible(true);
            return method;
        }
        void hook(Class<?> owner, String name, Class<?>[] args, SafeHook callback) throws Exception {
            XposedBridge.hookMethod(method(owner, name, args), callback);
        }
        interface Setup { void run() throws Exception; }
        void optional(String label, Setup setup) {
            try { setup.run(); report(label + " ready"); }
            catch (Throwable error) { report(label + " unavailable " + error.getClass().getSimpleName()); }
        }
        void install() {
            optional("detail", () -> hook(type(TRANSIT + "ui.TransitDetailBaseFragment"), "inflateCardView",
                    new Class<?>[]{type("androidx.cardview.widget.CardView")}, new SafeHook() {
                @Override void before(MethodHookParam call) {
                    WalletCardArtCompat.clearTransit(image((View) call.args[0], "detail_card_image_view"));
                }
                @Override void after(MethodHookParam call) throws Exception {
                    Object activity = field(call.thisObject, "U");
                    WalletCardArtCompat.bindTransit(image((View) call.args[0], "detail_card_image_view"),
                            key(field(activity, "a")));
                }
            }));
            optional("pay front", () -> {
                final Method getCard = method(type(TRANSIT + "controller.TransitManagerHelper"), "m", String.class);
                hook(type(TRANSIT + "controller.TransitCardInterfaceImpl"), "getSimpleCardFrontView",
                        new Class<?>[]{Context.class, type(PKG + ".pay.WfCardModel")}, new SafeHook() {
                    @Override void after(MethodHookParam call) throws Exception {
                        Object template = field(call.args[1], "arg3");
                        Object card = template instanceof String ? getCard.invoke(null, template) : null;
                        WalletCardArtCompat.bindTransit(image((View) call.getResult(), "card_image"), key(card));
                    }
                });
            });
            optional("wallet list", () -> hook(type(TRANSIT + "ui.adapter.TransitWalletListAdapter"), "onBindViewHolder",
                    new Class<?>[]{type("androidx.recyclerview.widget.RecyclerView$ViewHolder"), int.class, List.class}, new SafeHook() {
                ImageView target(MethodHookParam call) { return image((View) field(call.args[0], "itemView"), "transit_card_image"); }
                @Override void before(MethodHookParam call) { WalletCardArtCompat.clearTransit(target(call)); }
                @Override void after(MethodHookParam call) throws Exception {
                    Object values = field(call.thisObject, "g");
                    int position = (Integer) call.args[1];
                    List<?> cards = values instanceof List ? (List<?>) values : null;
                    Object card = cards != null && position >= 0 && position < cards.size() ? cards.get(position) : null;
                    WalletCardArtCompat.bindTransit(target(call), key(card));
                }
            }));
            optional("added list", () -> listAdapter(TRANSIT + "ui.TransitAddedCardsActivity$TransitCardsAdapter"));
            optional("default selection", () -> listAdapter(TRANSIT + "ui.TransitSelectDefaultAdapter"));
            optional("use mode", () -> hook(type(TRANSIT + "ui.carduse.TransitCardUseModeFragment"), "onCreateView",
                    new Class<?>[]{LayoutInflater.class, ViewGroup.class, Bundle.class}, new SafeHook() {
                @Override void before(MethodHookParam call) {
                    WalletCardArtCompat.clearTransit(image((View) field(call.thisObject, "y"), "card_image"));
                }
                @Override void after(MethodHookParam call) throws Exception {
                    WalletCardArtCompat.bindTransit(image((View) field(call.thisObject, "y"), "card_image"),
                            key(field(call.thisObject, "p")));
                }
            }));
            optional("cover use mode", () -> {
                final Method getImage = method(type(TRANSIT + "ui.carduse.TransitCardB5CoverPaymentModeView"), "getCardImageView");
                hook(type(TRANSIT + "ui.carduse.TransitCardCoverUseModeFragment"), "onCreateView",
                        new Class<?>[]{LayoutInflater.class, ViewGroup.class, Bundle.class}, new SafeHook() {
                    ImageView target(Object owner) throws Exception {
                        Object view = field(owner, "j");
                        return view == null ? null : (ImageView) getImage.invoke(view);
                    }
                    @Override void before(MethodHookParam call) throws Exception { WalletCardArtCompat.clearTransit(target(call.thisObject)); }
                    @Override void after(MethodHookParam call) throws Exception {
                        WalletCardArtCompat.bindTransit(target(call.thisObject), key(field(call.thisObject, "i")));
                    }
                });
            });
        }
        void listAdapter(String name) throws Exception {
            Class<?> adapter = type(name);
            final Method getItem = method(adapter, "getItem", int.class);
            hook(adapter, "getView", new Class<?>[]{int.class, View.class, ViewGroup.class}, new SafeHook() {
                @Override void before(MethodHookParam call) {
                    WalletCardArtCompat.clearTransit(image((View) call.args[1], "transit_card_image"));
                }
                @Override void after(MethodHookParam call) throws Exception {
                    Object card = getItem.invoke(call.thisObject, call.args[0]);
                    WalletCardArtCompat.bindTransit(image((View) call.getResult(), "transit_card_image"), key(card));
                }
            });
        }
    }

    private abstract static class SafeHook extends XC_MethodHook {
        void before(MethodHookParam call) throws Exception { }
        void after(MethodHookParam call) throws Exception { }
        @Override protected final void beforeHookedMethod(MethodHookParam call) {
            try { before(call); } catch (Throwable error) { report("bind unavailable " + error.getClass().getSimpleName()); }
        }
        @Override protected final void afterHookedMethod(MethodHookParam call) {
            if (call.hasThrowable()) return;
            try { after(call); } catch (Throwable error) { report("bind unavailable " + error.getClass().getSimpleName()); }
        }
    }

    private static Object field(Object owner, String name) {
        if (owner == null) return null;
        try { return XposedHelpers.getObjectField(owner, name); } catch (Throwable ignored) { return null; }
    }
    private static ImageView image(View root, String name) {
        if (root == null) return null;
        int id = root.getResources().getIdentifier(name, "id", PKG);
        View view = id == 0 ? null : root.findViewById(id);
        return view instanceof ImageView ? (ImageView) view : null;
    }
    private static synchronized void report(String message) {
        if (REPORTED.size() >= 16 || !REPORTED.add(message)) return;
        Log.i("OneUIToolboxTransitArt", message);
        XposedBridge.log("OneUIToolboxTransitArt: " + message);
    }
}
