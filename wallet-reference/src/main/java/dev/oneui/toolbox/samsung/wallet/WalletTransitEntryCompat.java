package dev.oneui.toolbox.samsung.wallet;

import android.content.Context;
import android.database.Cursor;
import android.os.Build;
import android.os.Process;
import android.util.Log;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Uses native transit policies with a local carrier alias in entry and catalog UI only. */
public final class WalletTransitEntryCompat {
    private static final String PACKAGE = "com.samsung.android.spay";
    private static final String TRANSIT = PACKAGE + ".vas.transportcard.";
    private static final String TAG = "OneUIToolboxTransit";
    private static final String UI_KEY = "toolbox.transitEntry.ui";
    private static final String PROPERTY_KEY = "toolbox.transitEntry.property";
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final ThreadLocal<Boolean> UI = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> PROPERTY = new ThreadLocal<>();
    private static final Set<String> LOGGED = new HashSet<>();

    private WalletTransitEntryCompat() { }

    public static void install(Context context, ClassLoader loader) {
        try {
            if (context == null || loader == null || !PACKAGE.equals(context.getPackageName())
                    || Process.myUid() / 100000 != 0 || !"SM-S9180".equals(Build.MODEL)
                    || !"samsung".equalsIgnoreCase(Build.MANUFACTURER)
                    || context.getPackageManager().getPackageInfo(PACKAGE, 0).getLongVersionCode()
                    != 559700323L || !INSTALLED.compareAndSet(false, true)) return;
            try {
                new State(loader).install();
                report("transit entry UI hooks ready; native policy retained");
            } catch (Throwable error) {
                INSTALLED.set(false);
                report("transit entry hooks unavailable; native retained type="
                        + error.getClass().getSimpleName());
            }
        } catch (Throwable error) {
            report("transit entry target unavailable; native retained");
        }
    }

    private static final class State {
        final Constructor<?> menuConstructor;
        final Method menuRefresh, menuSupport, nativeSupport, nativeCitySupport, factory;
        final Class<?> decoderClass, preferenceClass;
        final ClassLoader loader;
        volatile boolean active;
        boolean getterReady;
        Boolean blockedTgy;

        State(ClassLoader loader) throws Exception {
            this.loader = loader;
            Class<?> menu = Class.forName(TRANSIT + "ui.module.TransitCardMenuFrame", false, loader);
            menuConstructor = menu.getDeclaredConstructor(String.class);
            menuRefresh = menu.getDeclaredMethod("requestToUpdateMenuFrameViewWithCheck");
            menuSupport = Class.forName(TRANSIT + "controller.TransitCardInterfaceImpl", false, loader)
                    .getDeclaredMethod("W");
            nativeSupport = Class.forName(TRANSIT + "utils.TransitUtils", false, loader)
                    .getDeclaredMethod("L");
            nativeCitySupport = nativeSupport.getDeclaringClass().getDeclaredMethod("I", String.class);
            factory = Class.forName(PACKAGE + ".common.frameworkInterface.APIFactory", false, loader)
                    .getDeclaredMethod("a");
            decoderClass = Class.forName("com.xshield.dc", false, loader);
            preferenceClass = Class.forName(PACKAGE + ".common.util.pref.CNTransitPref", false, loader);
            if (menuSupport.getReturnType() != boolean.class
                    || nativeSupport.getReturnType() != boolean.class
                    || nativeCitySupport.getReturnType() != boolean.class
                    || !Modifier.isStatic(nativeCitySupport.getModifiers())
                    || !Modifier.isStatic(nativeSupport.getModifiers())) throw new NoSuchMethodException();
        }

        void install() throws Throwable {
            List<XC_MethodHook.Unhook> hooks = new ArrayList<>();
            try {
                hooks.add(XposedBridge.hookMethod(menuConstructor, uiScope()));
                hooks.add(XposedBridge.hookMethod(menuRefresh, uiScope()));
                hooks.add(XposedBridge.hookMethod(menuSupport, uiScope()));
                optionalUi(hooks, "ui.TransitSupportCardsActivity", "onLoadFinished",
                        "androidx.loader.content.Loader", "android.database.Cursor");
                optionalUi(hooks, "ui.search.TransitSearchViewModel", "loadSupportCardsFromDB");
                optionalUi(hooks, "ui.TransitRegistrationActivity", "G");
                optionalUi(hooks, "ui.TransitCnMainActivity", "G",
                        TRANSIT + "appInterface.model.ETransitCardType", "java.util.List");
                optionalUi(hooks, "ui.TransitCnMainActivity", "onCreate", "android.os.Bundle");
                for (Method policy : new Method[]{nativeSupport, nativeCitySupport}) {
                hooks.add(XposedBridge.hookMethod(policy, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam call) {
                        try {
                            if (!active || !Boolean.TRUE.equals(UI.get())) return;
                            enter(call, PROPERTY_KEY, PROPERTY, null);
                            if (prepareGetter()) PROPERTY.set(Boolean.TRUE);
                        } catch (Throwable error) {
                            report("entry carrier lookup unavailable; native retained type="
                                    + error.getClass().getSimpleName());
                        }
                    }
                    @Override protected void afterHookedMethod(MethodHookParam call) {
                        boolean scoped = Boolean.TRUE.equals(call.getObjectExtra(PROPERTY_KEY + ".saved"));
                        restore(call, PROPERTY_KEY, PROPERTY);
                        if (scoped) report("native transit " + (policy == nativeSupport ? "entry" : "city")
                                + " result=" + Boolean.TRUE.equals(call.getResult())
                                + " threw=" + call.hasThrowable());
                    }
                }));
                }
                // Evidence only; missing this optional preference getter never changes policy.
                try {
                    Method pref = preferenceClass.getDeclaredMethod("d");
                    if (pref.getReturnType() == boolean.class && Modifier.isStatic(pref.getModifiers())) {
                        hooks.add(XposedBridge.hookMethod(pref, new XC_MethodHook() {
                            @Override protected void afterHookedMethod(MethodHookParam call) {
                                try {
                                    if (active && Boolean.TRUE.equals(PROPERTY.get()) && !call.hasThrowable())
                                        report("native transit global configuration="
                                                + Boolean.TRUE.equals(call.getResult()));
                                } catch (Throwable ignored) { }
                            }
                        }));
                    }
                } catch (Throwable ignored) { }
                active = true;
            } catch (Throwable error) {
                active = false;
                for (XC_MethodHook.Unhook hook : hooks)
                    try { hook.unhook(); } catch (Throwable ignored) { }
                throw error;
            }
        }

        void optionalUi(List<XC_MethodHook.Unhook> hooks, String owner, String name, String... parameters) {
            try {
                Class<?>[] signature = new Class<?>[parameters.length];
                for (int i = 0; i < parameters.length; i++)
                    signature[i] = Class.forName(parameters[i], false, loader);
                Method method = Class.forName(TRANSIT + owner, false, loader).getDeclaredMethod(name, signature);
                hooks.add(XposedBridge.hookMethod(method,
                        uiScope("ui.TransitSupportCardsActivity".equals(owner) && "onLoadFinished".equals(name))));
                report("transit catalog UI hook ready=" + owner + "." + name);
            } catch (Throwable error) {
                report("transit catalog UI hook unavailable=" + owner + "." + name);
            }
        }

        XC_MethodHook uiScope() { return uiScope(false); }

        XC_MethodHook uiScope(boolean countCatalog) {
            return new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam call) {
                    try { if (active) enter(call, UI_KEY, UI, Boolean.TRUE); }
                    catch (Throwable ignored) { }
                }
                @Override protected void afterHookedMethod(MethodHookParam call) {
                    try {
                        if (countCatalog && Boolean.TRUE.equals(call.getObjectExtra(UI_KEY + ".saved"))
                                && !call.hasThrowable()) {
                            Object cards = XposedHelpers.getObjectField(call.thisObject, "a");
                            int templates = call.args[1] instanceof Cursor ? ((Cursor) call.args[1]).getCount() : -1;
                            int visible = cards instanceof List ? ((List<?>) cards).size() : -1;
                            report("native transit catalog templates=" + templates + " visible=" + visible);
                        }
                    } catch (Throwable ignored) { }
                    finally { restore(call, UI_KEY, UI); }
                }
            };
        }

        synchronized boolean prepareGetter() throws Exception {
            if (blockedTgy == null) {
                Method decoder = decoderClass.getDeclaredMethod("\u0254\u018d\u0212\u034f", int.class);
                if (decoder.getReturnType() != String.class || !Modifier.isStatic(decoder.getModifiers()))
                    throw new NoSuchMethodException();
                decoder.setAccessible(true);
                blockedTgy = "TGY".equals(decoder.invoke(null, 727876909));
                report("entry blocked carrier confirmed TGY=" + blockedTgy);
            }
            if (!blockedTgy) return false;
            if (!getterReady) {
                Object api = factory.invoke(null);
                if (api == null) return false;
                Class<?> apiClass = api.getClass();
                Method getter = apiClass.getMethod("V", String.class);
                if (getter.getReturnType() != String.class || Modifier.isStatic(getter.getModifiers()))
                    return false;
                XposedBridge.hookMethod(getter, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam call) {
                        try {
                            if (!active || !Boolean.TRUE.equals(PROPERTY.get())
                                    || !Boolean.TRUE.equals(UI.get()) || call.hasThrowable()
                                    || call.thisObject == null || call.thisObject.getClass() != apiClass
                                    || !"ro.boot.carrierid".equals(call.args[0])
                                    || !"TGY".equals(call.getResult())) return;
                            call.setResult("CHC");
                            report("transit entry carrier lookup used local CHC alias");
                        } catch (Throwable ignored) { }
                    }
                });
                getterReady = true;
            }
            return true;
        }
    }

    private static void enter(XC_MethodHook.MethodHookParam call, String key,
                              ThreadLocal<Boolean> local, Boolean next) {
        call.setObjectExtra(key, local.get());
        call.setObjectExtra(key + ".saved", Boolean.TRUE);
        if (next == null) local.remove(); else local.set(next);
    }

    private static void restore(XC_MethodHook.MethodHookParam call, String key, ThreadLocal<Boolean> local) {
        try {
            if (!Boolean.TRUE.equals(call.getObjectExtra(key + ".saved"))) return;
            Object prior = call.getObjectExtra(key);
            if (prior instanceof Boolean) local.set((Boolean) prior); else local.remove();
        } catch (Throwable ignored) { local.remove(); }
    }

    private static synchronized void report(String message) {
        if (LOGGED.size() >= 20 || !LOGGED.add(message)) return;
        try { Log.i(TAG, message); } catch (Throwable ignored) { }
        try { XposedBridge.log(TAG + ": " + message); } catch (Throwable ignored) { }
    }
}
