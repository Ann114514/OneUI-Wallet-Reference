package dev.oneui.toolbox.samsung.wallet;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Process;
import android.util.Log;
import java.util.concurrent.atomic.AtomicBoolean;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/** Installs wallet compatibility before Application construction caches region values. */
public final class WalletModule {
    private static final String TAG = "OneUIToolboxWallet";
    private static final AtomicBoolean WATCHING = new AtomicBoolean();
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean();

    private WalletModule() { }

    public static boolean isTarget(String name) {
        return "com.samsung.android.spay".equals(name)
                || "com.samsung.android.samsungpass".equals(name)
                || "com.samsung.android.spayfw".equals(name)
                || "com.unionpay.tsmservice".equals(name);
    }

    public static void install(XC_LoadPackage.LoadPackageParam target) {
        if (!isTarget(target.packageName) || Process.myUid() / 100000 != 0
                || !WATCHING.compareAndSet(false, true)) return;
        final XC_MethodHook.Unhook[] watcher = new XC_MethodHook.Unhook[1];
        try {
            watcher[0] = XposedHelpers.findAndHookMethod(Instrumentation.class,
                    "newApplication", ClassLoader.class, String.class, Context.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam call) {
                            try {
                                if (call.args[0] != target.classLoader
                                        || !(call.args[2] instanceof Context)) return;
                                Context context = (Context) call.args[2];
                                if (!target.packageName.equals(context.getPackageName())
                                        || !INITIALIZED.compareAndSet(false, true)) return;
                                if (watcher[0] != null) watcher[0].unhook();
                                if ("com.samsung.android.samsungpass".equals(target.packageName)) {
                                    PassPreparationState.install(context);
                                    return;
                                }
                                try {
                                    WalletRegionCompat.install(target.classLoader, target.packageName);
                                } catch (Throwable error) {
                                    report("wallet region compatibility unavailable type="
                                            + error.getClass().getSimpleName());
                                }
                                if ("com.samsung.android.spay".equals(target.packageName)) {
                                    try {
                                        WalletPassEntryCompat.install(context, target.classLoader);
                                    } catch (Throwable error) {
                                        report("wallet Pass entry compatibility unavailable type="
                                                + error.getClass().getSimpleName());
                                    }
                                    try {
                                        WalletTransitEntryCompat.install(context, target.classLoader);
                                    } catch (Throwable error) {
                                        report("wallet transit compatibility unavailable type="
                                                + error.getClass().getSimpleName());
                                    }
                                    try {
                                        WalletAccessNfcCompat.install(context, target.classLoader);
                                    } catch (Throwable error) {
                                        report("wallet access NFC compatibility unavailable type="
                                                + error.getClass().getSimpleName());
                                    }
                                    try {
                                        WalletCardArtCompat.install(context, target.classLoader);
                                    } catch (Throwable error) {
                                        report("wallet card artwork unavailable type="
                                                + error.getClass().getSimpleName());
                                    }
                                }
                                report("wallet compatibility applied in " + target.processName);
                            } catch (Throwable error) {
                                report("wallet initialization unavailable type="
                                        + error.getClass().getSimpleName());
                            }
                        }
                    });
        } catch (Throwable error) {
            report("wallet bootstrap unavailable type=" + error.getClass().getSimpleName());
        }
    }

    private static void report(String message) {
        try { Log.i(TAG, message); } catch (Throwable ignored) { }
        try { XposedBridge.log(TAG + ": " + message); } catch (Throwable ignored) { }
    }
}
