package dev.oneui.toolbox.samsung.wallet;

import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Binder;
import android.os.Build;
import android.os.Process;
import android.util.Log;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Only a supported, natively authorized campus/transit callback sees a local carrier alias. */
public final class WalletCampusCardCompat {
    private static final String PACKAGE = "com.samsung.android.spay";
    private static final String TRANSIT = PACKAGE + ".vas.transportcard.";
    private static final String CONTROLLER = TRANSIT + "sdk.controller.";
    private static final String MARK = "OneUIToolboxCampusScope";
    private static final String TAG = "OneUIToolboxCampus";
    private static final AtomicBoolean WATCHING = new AtomicBoolean();
    private static final ThreadLocal<Scope> REQUEST = new ThreadLocal<>();
    private static final ThreadLocal<Scope> CALLBACK = new ThreadLocal<>();
    private static final ThreadLocal<Scope> PROPERTY = new ThreadLocal<>();
    private static final Set<String> LOGGED = new HashSet<>();
    private static final XC_MethodHook.Unhook[] WATCHERS = new XC_MethodHook.Unhook[2];
    private static int attempts;
    private static boolean installed;
    private WalletCampusCardCompat() { }

    public static void install(XC_LoadPackage.LoadPackageParam target) {
        if (!PACKAGE.equals(target.packageName) || Process.myUid() / 100000 != 0
                || !"SM-S9180".equals(Build.MODEL)
                || !"samsung".equalsIgnoreCase(Build.MANUFACTURER)
                || !WATCHING.compareAndSet(false, true)) return;
        try {
            WATCHERS[0] = XposedHelpers.findAndHookMethod(Application.class, "attach",
                    Context.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam call) {
                    if (!call.hasThrowable()) attempt((Context) call.args[0], target.classLoader);
                }
            });
            WATCHERS[1] = XposedHelpers.findAndHookMethod(Instrumentation.class,
                    "callApplicationOnCreate", Application.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam call) {
                    attempt((Application) call.args[0], target.classLoader);
                }
                @Override protected void afterHookedMethod(MethodHookParam call) {
                    Context context = (Application) call.args[0];
                    attempt(context, target.classLoader);
                    if (isTarget(context)) closeWatchers();
                }
            });
        } catch (Throwable error) { report("bootstrap unavailable"); closeWatchers(); }
    }

    private static synchronized void attempt(Context context, ClassLoader fallback) {
        if (installed || attempts >= 6 || !isTarget(context)) return;
        attempts++;
        try {
            if (context.getPackageManager().getPackageInfo(PACKAGE, 0).getLongVersionCode()
                    != 559700323L) { report("wallet version differs; native retained"); closeWatchers(); return; }
            Context app = context.getApplicationContext();
            Context owner = app == null ? context : app;
            ClassLoader real = owner.getClassLoader();
            State state;
            try { state = new State(owner, real); }
            catch (Throwable unavailable) {
                if (fallback == null || fallback == real) throw unavailable;
                state = new State(owner, fallback);
            }
            state.install();
            installed = true;
            report("campus eligibility hooks ready; native checks retained");
            closeWatchers();
        } catch (Throwable error) { report("target hooks unavailable; native retained"); }
        if (attempts >= 6) closeWatchers();
    }

    private static final class State {
        final Context context;
        final ClassLoader loader;
        final Class<?> callbackClass, issueCallbackClass, preIssueCallbackClass, enumClass;
        final Method preCheck, onSuccess, issueOnSuccess, preIssueOnSuccess,
                cityCheck, cityPref, cardName, factory;
        final Class<?> decoderClass;
        volatile boolean active, getterReady;
        Boolean blockedTgy;

        State(Context owner, ClassLoader loader) throws Exception {
            context = owner;
            this.loader = loader;
            callbackClass = Class.forName(CONTROLLER + "TransitAidlBaseController$9", false, loader);
            issueCallbackClass = Class.forName(CONTROLLER + "TransitAidlBaseController$4", false, loader);
            preIssueCallbackClass = Class.forName(CONTROLLER + "TransitAidlBaseController$3", false, loader);
            enumClass = Class.forName(TRANSIT + "appInterface.model.ETransitCardType", false, loader);
            Class<?> result = Class.forName(TRANSIT + "sdk.interfaces.ResultCallback", false, loader);
            preCheck = Class.forName(CONTROLLER + "TransitAidlBaseController", false, loader)
                    .getDeclaredMethod("preCheckEligibility", result, enumClass, String.class, Object[].class);
            onSuccess = callbackClass.getDeclaredMethod("onSuccess", Object.class);
            issueOnSuccess = issueCallbackClass.getDeclaredMethod("onSuccess", Object.class);
            preIssueOnSuccess = preIssueCallbackClass.getDeclaredMethod("onSuccess", Object.class);
            cityCheck = Class.forName(TRANSIT + "utils.TransitUtils", false, loader)
                    .getDeclaredMethod("I", String.class);
            cityPref = Class.forName(PACKAGE + ".common.util.pref.CNTransitPref", false, loader)
                    .getDeclaredMethod("c", String.class);
            cardName = enumClass.getDeclaredMethod("getName");
            factory = Class.forName(PACKAGE + ".common.frameworkInterface.APIFactory", false, loader)
                    .getDeclaredMethod("a");
            decoderClass = Class.forName("com.xshield.dc", false, loader);
            if (cityCheck.getReturnType() != boolean.class || cardName.getReturnType() != String.class
                    || cityPref.getReturnType() != String.class) throw new NoSuchMethodException();
        }

        void install() throws Throwable {
            List<XC_MethodHook.Unhook> hooks = new ArrayList<>();
            try {
                hooks.add(XposedBridge.hookMethod(preCheck, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam call) {
                        try {
                            if (!active || call.args[0] == null
                                    || (call.args[0].getClass() != callbackClass
                                    && call.args[0].getClass() != issueCallbackClass
                                    && call.args[0].getClass() != preIssueCallbackClass)) return;
                            XposedHelpers.removeAdditionalInstanceField(call.args[0], MARK);
                            Scope request = REQUEST.get();
                            if (request == null || request.callbackType != call.args[0].getClass()
                                    || call.args[1] != request.cardType) return;
                            Object city = cardName.invoke(request.cardType);
                            if (!(city instanceof String) || ((String) city).isEmpty()) return;
                            XposedHelpers.setAdditionalInstanceField(call.args[0], MARK,
                                    new Scope((String) city, request.cardType, request.callbackType));
                            report("campus callback marked=" + request.callbackType.getSimpleName());
                        } catch (Throwable ignored) { }
                    }
                }));
                for (Method success : new Method[]{onSuccess, issueOnSuccess, preIssueOnSuccess}) {
                hooks.add(XposedBridge.hookMethod(success, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam call) {
                        if (!active) return;
                        enter(call, "callback", CALLBACK, null);
                        try {
                            Object mark = XposedHelpers.getAdditionalInstanceField(call.thisObject, MARK);
                            if (mark instanceof Scope && ((Scope) mark).callbackType == call.thisObject.getClass())
                                CALLBACK.set((Scope) mark);
                        } catch (Throwable ignored) { }
                    }
                    @Override protected void afterHookedMethod(MethodHookParam call) {
                        restore(call, "callback", CALLBACK);
                    }
                }));
                }
                hooks.add(XposedBridge.hookMethod(cityCheck, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam call) {
                        if (!active) return;
                        enter(call, "property", PROPERTY, null);
                        try {
                            Scope scope = CALLBACK.get();
                            if (scope == null || scope.city == null || !scope.city.equals(call.args[0])) return;
                            call.setObjectExtra("campusCityCheck", Boolean.TRUE);
                            if (prepareGetter()) PROPERTY.set(scope);
                        } catch (Throwable ignored) { report("carrier alias unavailable; native retained"); }
                    }
                    @Override protected void afterHookedMethod(MethodHookParam call) {
                        restore(call, "property", PROPERTY);
                        if (Boolean.TRUE.equals(call.getObjectExtra("campusCityCheck")))
                            report("native campus city eligibility result=" + Boolean.TRUE.equals(call.getResult())
                                    + " threw=" + call.hasThrowable());
                    }
                }));
                hooks.add(XposedBridge.hookMethod(cityPref, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam call) {
                        try {
                            Scope scope = PROPERTY.get();
                            if (scope == null || !scope.city.equals(call.args[0]) || call.hasThrowable()) return;
                            Object result = call.getResult();
                            boolean present = result instanceof String && !((String) result).isEmpty();
                            report("native campus city config present=" + present + " startsY="
                                    + (present && ((String) result).startsWith("Y")));
                        } catch (Throwable ignored) { }
                    }
                }));
                boolean cmn = installEntries(false,
                        "cmn.TransitAidlCmnController$TransitCmnStub", Map.class,
                        new String[]{"checkIssueConditions", "issueCard", "preIssueCard"},
                        new String[]{"TRANSIT_CARD_TYPE_BJMOT_V2", "TRANSIT_CARD_TYPE_BJMOT_XJT"}, hooks);
                boolean sptc = installEntries(true,
                        "sptc.TransitAidlSptcController$TransitSptcStub", HashMap.class,
                        new String[]{"f", "c", "n4"}, new String[]{"TRANSIT_CARD_TYPE_SHMOT"}, hooks);
                if (!cmn && !sptc) throw new NoSuchMethodException("No supported campus route");
                active = true;
            } catch (Throwable error) {
                for (XC_MethodHook.Unhook hook : hooks) try { hook.unhook(); } catch (Throwable ignored) { }
                throw error;
            }
        }

        boolean installEntries(boolean sptc, String stubName, Class<?> mapType,
                               String[] methodNames, String[] cardFields,
                               List<XC_MethodHook.Unhook> installedHooks) {
            String route = sptc ? "SPTC" : "CMN";
            List<XC_MethodHook.Unhook> hooks = new ArrayList<>();
            try {
                final Map<String, Object> cards = new HashMap<>();
                for (String field : cardFields) {
                    try {
                        Object card = XposedHelpers.getStaticObjectField(enumClass, field);
                        if (card != null && enumClass.isInstance(card)) cards.put(field, card);
                    } catch (Throwable ignored) { }
                }
                if (cards.isEmpty()) throw new NoSuchFieldException();
                Class<?> stub = Class.forName(CONTROLLER + stubName, false, loader);
                Method[] entries = new Method[methodNames.length];
                for (int index = 0; index < methodNames.length; index++) {
                    entries[index] = stub.getDeclaredMethod(methodNames[index], mapType);
                    if (entries[index].getReturnType() != String.class
                            || Modifier.isStatic(entries[index].getModifiers())) throw new NoSuchMethodException();
                }
                Class<?>[] callbacks = {callbackClass, issueCallbackClass, preIssueCallbackClass};
                for (int index = 0; index < entries.length; index++) {
                    final Method entry = entries[index];
                    final Class<?> expectedCallback = callbacks[index];
                    hooks.add(XposedBridge.hookMethod(entry, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam call) {
                            if (!active) return;
                            enter(call, "request", REQUEST, null);
                            try {
                                String caller = externalCaller();
                                Object issuer = call.args[0] instanceof Map
                                        ? ((Map<?, ?>) call.args[0]).get("issuerID") : null;
                                String field = cardFieldFor(sptc, caller,
                                        issuer instanceof String ? (String) issuer : null);
                                Object card = field == null ? null : cards.get(field);
                                if (card == null) return;
                                REQUEST.set(new Scope(null, card, expectedCallback));
                                report("campus " + route + " " + entry.getName() + " request matched");
                            } catch (Throwable ignored) { }
                        }
                        @Override protected void afterHookedMethod(MethodHookParam call) {
                            restore(call, "request", REQUEST);
                        }
                    }));
                }
                installedHooks.addAll(hooks);
                report("campus " + route + " request hooks ready");
                return true;
            } catch (Throwable error) {
                for (XC_MethodHook.Unhook hook : hooks) try { hook.unhook(); } catch (Throwable ignored) { }
                report("campus " + route + " route unavailable; native retained");
                return false;
            }
        }

        String externalCaller() {
            int uid = Binder.getCallingUid();
            if (uid / 100000 != 0 || uid == Process.myUid()) return null;
            String[] packages = context.getPackageManager().getPackagesForUid(uid);
            if (packages == null || packages.length != 1 || packages[0] == null
                    || packages[0].isEmpty() || PACKAGE.equals(packages[0])) return null;
            return packages[0];
        }

        synchronized boolean prepareGetter() throws Exception {
            if (blockedTgy == null) {
                Method decoder = null;
                for (Method method : decoderClass.getDeclaredMethods()) {
                    if (!"\u0254\u018d\u0212\u034f".equals(method.getName())
                            || method.getReturnType() != String.class || !Modifier.isStatic(method.getModifiers())
                            || method.getParameterTypes().length != 1 || method.getParameterTypes()[0] != int.class) continue;
                    if (decoder != null) throw new NoSuchMethodException();
                    decoder = method;
                }
                if (decoder == null) throw new NoSuchMethodException();
                decoder.setAccessible(true);
                blockedTgy = "TGY".equals(decoder.invoke(null, 727876909));
                report("blocked carrier confirmed TGY=" + blockedTgy);
            }
            if (!blockedTgy) return false;
            if (!getterReady) {
                Object api = factory.invoke(null);
                if (api == null) return false;
                Class<?> apiClass = api.getClass();
                Method getter = apiClass.getMethod("V", String.class);
                if (getter.getReturnType() != String.class || Modifier.isStatic(getter.getModifiers())) return false;
                XposedBridge.hookMethod(getter, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam call) {
                        try {
                            if (!active || PROPERTY.get() == null || call.hasThrowable()
                                    || call.thisObject == null || call.thisObject.getClass() != apiClass
                                    || !"ro.boot.carrierid".equals(call.args[0]) || !"TGY".equals(call.getResult())) return;
                            call.setResult("CHC");
                            report("campus carrier lookup used local CHC alias");
                        } catch (Throwable ignored) { }
                    }
                });
                getterReady = true;
            }
            return true;
        }
    }

    private static final class Scope {
        final String city;
        final Object cardType;
        final Class<?> callbackType;
        Scope(String value, Object card, Class<?> handler) {
            city = value; cardType = card; callbackType = handler;
        }
    }

    // Route selection only. UID isolation and native partner authorization are separate checks.
    static String cardFieldFor(boolean sptc, String caller, String issuer) {
        if (caller == null || caller.isEmpty() || PACKAGE.equals(caller)) return null;
        if (sptc) return "SHT_2IN1".equals(issuer) ? "TRANSIT_CARD_TYPE_SHMOT" : null;
        if ("cn.com.bmac.nfc".equals(caller) && "BEIJING_MOT_V2_CAMPUS".equals(issuer))
            return "TRANSIT_CARD_TYPE_BJMOT_V2";
        if ("cn.com.bmac.xjt".equals(caller) && "BEIJING_MOT_V2_XJT".equals(issuer))
            return "TRANSIT_CARD_TYPE_BJMOT_XJT";
        return null;
    }
    private static void enter(XC_MethodHook.MethodHookParam call, String key, ThreadLocal<Scope> local, Scope next) {
        call.setObjectExtra(key, local.get());
        call.setObjectExtra(key + ".saved", Boolean.TRUE);
        if (next == null) local.remove(); else local.set(next);
    }
    private static void restore(XC_MethodHook.MethodHookParam call, String key, ThreadLocal<Scope> local) {
        try {
            if (!Boolean.TRUE.equals(call.getObjectExtra(key + ".saved"))) return;
            Object prior = call.getObjectExtra(key);
            if (prior instanceof Scope) local.set((Scope) prior); else local.remove();
        } catch (Throwable ignored) { local.remove(); }
    }
    private static boolean isTarget(Context context) {
        try { return context != null && PACKAGE.equals(context.getPackageName()); }
        catch (Throwable ignored) { return false; }
    }
    private static synchronized void closeWatchers() {
        for (XC_MethodHook.Unhook hook : WATCHERS) if (hook != null)
            try { hook.unhook(); } catch (Throwable ignored) { }
    }
    private static synchronized void report(String message) {
        if (LOGGED.size() >= 24 || !LOGGED.add(message)) return;
        try { Log.i(TAG, message); } catch (Throwable ignored) { }
        try { XposedBridge.log(TAG + ": " + message); } catch (Throwable ignored) { }
    }
}
