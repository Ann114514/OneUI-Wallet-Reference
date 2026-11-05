package dev.oneui.toolbox.samsung.wallet;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** Restores the existing Samsung Pass dashboard item in the CN wallet. */
final class WalletPassEntryCompat {
    private static final String WALLET = "com.samsung.android.spay";
    private static final String PASS = "com.samsung.android.samsungpass";
    private static final String TAG = "OneUIToolboxPass";
    private static final String PREVIOUS = TAG + ".previous";

    private WalletPassEntryCompat() { }

    static void install(Context context, ClassLoader loader) throws Throwable {
        if (!WALLET.equals(context.getPackageName()) || !"SM-S9180".equals(Build.MODEL)
                || context.getPackageManager().getPackageInfo(WALLET, 0).getLongVersionCode() != 559700323L) return;
        Class<?> menu = Class.forName(WALLET + ".pass.dashboard.PassDashboardMenu", false, loader);
        Class<?> util = Class.forName(WALLET + ".common.walletapps.util.WalletAppsUtil", false, loader);
        Method supported = util.getDeclaredMethod("o", Context.class);
        Method check = menu.getDeclaredMethod("onCheckToAddRemovedDashboardItem");
        Method update = menu.getDeclaredMethod("onUpdateDashboardItemView");
        Class<?> repository = Class.forName(WALLET + ".pass.repository.PassDataRepositoryImpl", false, loader);
        Method regionSelection = repository.getDeclaredMethod("whereCheckChina", boolean.class);
        WalletPassRegistrationCompat.install(context, loader);
        ThreadLocal<Boolean> menuScope = new ThreadLocal<>();
        AtomicBoolean reported = new AtomicBoolean();
        AtomicBoolean dataReported = new AtomicBoolean();
        XC_MethodHook scope = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam call) {
                call.setObjectExtra(PREVIOUS, menuScope.get());
                menuScope.set(Boolean.TRUE);
            }

            @Override protected void afterHookedMethod(MethodHookParam call) {
                if (Boolean.TRUE.equals(call.getObjectExtra(PREVIOUS))) menuScope.set(Boolean.TRUE);
                else menuScope.remove();
            }
        };

        List<XC_MethodHook.Unhook> hooks = new ArrayList<>();
        try {
            hooks.add(XposedBridge.hookMethod(supported, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam call) {
                    if (call.hasThrowable() || !Boolean.FALSE.equals(call.getResult())
                            || !(call.args[0] instanceof Context)) return;
                    Context wallet = (Context) call.args[0];
                    if (!Boolean.TRUE.equals(menuScope.get())
                            && !WalletPassRegistrationCompat.hasRegistration(wallet, loader)) return;
                    Intent migration = new Intent(Intent.ACTION_VIEW, Uri.parse("walletpass://migration"))
                            .setPackage(PASS);
                    if (wallet.getPackageManager().resolveActivity(migration, PackageManager.MATCH_DEFAULT_ONLY) == null) {
                        return;
                    }
                    call.setResult(true);
                    if (reported.compareAndSet(false, true)) Log.i(TAG, "native Pass dashboard gate adjusted");
                }
            }));
            hooks.add(XposedBridge.hookMethod(check, scope));
            hooks.add(XposedBridge.hookMethod(update, scope));
            hooks.add(XposedBridge.hookMethod(regionSelection, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam call) throws Throwable {
                    if (call.hasThrowable() || !(call.getResult() instanceof String)
                            || !WalletPassRegistrationCompat.hasRegistration(context, loader)) return;
                    // Native repository authentication already gates each data query. Do not
                    // choose another data partition when its separate migration cache is stale.
                    String selection = (String) call.getResult();
                    if (!selection.contains("reserved_2 = '1'")) return;
                    call.setResult(selection.replace("reserved_2 = '1'", "reserved_2 != '1'"));
                    if (dataReported.compareAndSet(false, true)) Log.i(TAG, "Pass data region selection CN -> HK");
                }
            }));
        } catch (Throwable error) {
            for (XC_MethodHook.Unhook hook : hooks) hook.unhook();
            throw error;
        }
        Log.i(TAG, "Pass dashboard entry hook ready");
        try {
            WalletPassDataReloadCompat.install(context, loader, repository);
        } catch (Throwable error) {
            Log.i(TAG, "Pass data reload unavailable: " + error.getClass().getSimpleName());
        }
    }
}
