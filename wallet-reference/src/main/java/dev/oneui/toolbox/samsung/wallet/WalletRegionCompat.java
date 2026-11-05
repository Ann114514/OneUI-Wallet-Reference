package dev.oneui.toolbox.samsung.wallet;

import android.os.Process;
import android.util.Log;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Wallet-only region compatibility derived from XiYu 3.0 kg0/fg0/l9/db.
 * Source project: https://github.com/qiaoing888/XiYu
 * Upstream package/version: com.keyscafe.unlock, 3.0/code8.
 * Attribution scope: CN region getter mapping for Wallet, payment framework and UnionPay only.
 * Toolbox adaptation: legacy Xposed API 82 bootstrap, signatures and failure isolation.
 * Full upstream license scope is not independently verified; see NOTICE.md.
 * Changes getter results in the target process; never writes system properties.
 */
public final class WalletRegionCompat {
    private static final String TAG = "OneUIToolboxWallet";
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final Set<Method> HOOKED = new HashSet<>();
    private static final Set<String> REPORTED = new HashSet<>();

    private WalletRegionCompat() { }

    public static void install(ClassLoader loader, String packageName) {
        if (!isTarget(packageName) || Process.myUid() / 100000 != 0
                || !INSTALLED.compareAndSet(false, true)) return;
        int count = installClass(loader, "android.os.SystemProperties", false);
        // The optional Samsung helper must not prevent standard getter hooks.
        count += installClass(loader, "android.os.SemSystemProperties", true);
        report("region getter hooks=" + count + " package=" + packageName);
    }

    private static boolean isTarget(String name) {
        return "com.samsung.android.spay".equals(name)
                || "com.samsung.android.spayfw".equals(name)
                || "com.unionpay.tsmservice".equals(name);
    }

    private static int installClass(ClassLoader loader, String className, boolean samsung) {
        int count = 0;
        try {
            Class<?> owner = Class.forName(className, false, loader);
            for (Method method : owner.getDeclaredMethods()) {
                if (method.getReturnType() != String.class
                        || Modifier.isAbstract(method.getModifiers())) continue;
                String name = method.getName();
                Class<?>[] parameters = method.getParameterTypes();
                boolean keyed = ("get".equals(name) || (!samsung && "native_get".equals(name)))
                        && parameters.length > 0 && parameters[0] == String.class;
                String fixed = samsung && parameters.length == 0 ? helperValue(name) : null;
                if (!keyed && fixed == null) continue;
                if (hook(method, keyed, fixed)) count++;
            }
        } catch (Throwable error) {
            report(className + " unavailable type=" + error.getClass().getSimpleName());
        }
        return count;
    }

    private static synchronized boolean hook(Method method, boolean keyed, String fixed) {
        if (HOOKED.contains(method)) return false;
        try {
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam call) {
                    try {
                        String key = keyed && call.args[0] instanceof String
                                ? (String) call.args[0] : null;
                        String value = keyed ? propertyValue(key) : fixed;
                        if (value == null) return;
                        call.setResult(value);
                        reportMapping(keyed ? key : method.getName());
                    } catch (Throwable ignored) { }
                }
            });
            HOOKED.add(method);
            return true;
        } catch (Throwable error) {
            report(method.getDeclaringClass().getSimpleName() + "." + method.getName()
                    + " hook unavailable type=" + error.getClass().getSimpleName());
            return false;
        }
    }

    private static String propertyValue(String key) {
        if (key == null) return null;
        switch (key) {
            case "ril.sales_code":
            case "persist.omc.sales_code":
            case "ro.csc.sales_code": return "CHC";
            case "ro.csc.countryiso_code": return "CN";
            case "ro.csc.country_code": return "China";
            default: return null;
        }
    }

    private static String helperValue(String name) {
        switch (name) {
            case "getSalesCode": return "CHC";
            case "getCountryIso": return "CN";
            case "getCountryCode": return "China";
            default: return null;
        }
    }

    private static void reportMapping(String key) {
        synchronized (REPORTED) {
            if (!REPORTED.add(key)) return;
        }
        report("local CN mapping used for " + key);
    }

    private static void report(String message) {
        try { Log.i(TAG, message); } catch (Throwable ignored) { }
        try { XposedBridge.log(TAG + ": " + message); } catch (Throwable ignored) { }
    }
}
