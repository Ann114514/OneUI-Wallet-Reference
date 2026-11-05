package dev.oneui.toolbox.samsung.wallet;

import android.app.Activity;
import android.content.ContentProvider;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;
import java.util.concurrent.atomic.AtomicBoolean;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Adds preparation metadata to an already permission-checked native Wallet response. */
final class PassPreparationState {
    private static final String PASS = "com.samsung.android.samsungpass";
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static boolean guideRoute;
    private static final AtomicBoolean SYNC_COMPLETED = new AtomicBoolean();
    private static final AtomicBoolean RESTORE_REQUEST_RUNNING = new AtomicBoolean();

    static void install(Context context) throws Throwable {
        if (!PASS.equals(context.getPackageName()) || Process.myUid() / 100000 != 0
                || !"SM-S9180".equals(Build.MODEL)
                || context.getPackageManager().getPackageInfo(PASS, 0).getLongVersionCode() != 521010900L) return;
        XposedBridge.hookMethod(ContentProvider.class.getDeclaredMethod("attachInfo", Context.class, ProviderInfo.class),
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam call) throws Throwable {
                        if (call.hasThrowable() || !(PASS + ".walletsettings.WalletSettingsProvider")
                                .equals(call.thisObject.getClass().getName())
                                || !INSTALLED.compareAndSet(false, true)) return;
                        ClassLoader passLoader = call.thisObject.getClass().getClassLoader();
                        Class<?> syncManager = XposedHelpers.findClass(PASS + ".common.scloud.SCloudSyncManager", passLoader);
                        XposedBridge.hookMethod(syncManager.getDeclaredMethod("setLastSyncTime", String.class, long.class),
                                new XC_MethodHook() {
                                    @Override protected void afterHookedMethod(MethodHookParam sync) {
                                        if (!sync.hasThrowable() && "scloud_last_sync_time".equals(sync.args[0])) {
                                            SYNC_COMPLETED.set(true);
                                            // No records/identifiers in this notification. Wallet
                                            // revalidates readiness through this native provider.
                                            context.sendBroadcast(new Intent(WalletPassConnectFlow.RESTORE_READY)
                                                    .setPackage("com.samsung.android.spay"));
                                        }
                                    }
                                });
                        XposedBridge.hookMethod(syncManager.getDeclaredMethod("resetSyncTime"), new XC_MethodHook() {
                            @Override protected void afterHookedMethod(MethodHookParam reset) { SYNC_COMPLETED.set(false); }
                        });
                        try {
                            Class<?> entry = XposedHelpers.findClass(PASS + ".launcher.WalletDeeplinkActivity",
                                    call.thisObject.getClass().getClassLoader());
                            XposedBridge.hookMethod(entry.getDeclaredMethod("handleDeeplink", Uri.class, Bundle.class),
                                    new XC_MethodHook() {
                                        @Override protected void beforeHookedMethod(MethodHookParam launch) {
                                            Activity activity = (Activity) launch.thisObject;
                                            Uri uri = (Uri) launch.args[0];
                                            if (!activity.getIntent().getBooleanExtra("toolbox_prepare_only", false)
                                                    || uri == null || !"launch".equals(uri.getHost())
                                                    || !"passSignUp".equals(uri.getQueryParameter("action"))) return;
                                            launch.setResult(null);
                                            try {
                                                Object model = XposedHelpers.callMethod(activity, "getViewModel");
                                                XposedHelpers.callMethod(model, "startPassSignUpActivity", activity,
                                                        false, (Bundle) launch.args[1]);
                                            } catch (Throwable error) {
                                                activity.setResult(Activity.RESULT_CANCELED);
                                                activity.finish();
                                                Log.i("OneUIToolboxPass", "native Pass guide launch unavailable: "
                                                        + error.getClass().getSimpleName());
                                            }
                                        }
                                    });
                            guideRoute = true;
                        } catch (Throwable error) {
                            Log.i("OneUIToolboxPass", "Pass preparation guide unavailable: "
                                    + error.getClass().getSimpleName());
                        }
                        XposedBridge.hookMethod(call.thisObject.getClass().getDeclaredMethod("call", String.class,
                                String.class, Bundle.class), new XC_MethodHook() {
                            @Override protected void afterHookedMethod(MethodHookParam query) {
                                if (query.hasThrowable() || !"getSignUpState".equals(query.args[0])
                                        || !(query.getResult() instanceof Bundle)) return;
                                Bundle original = (Bundle) query.getResult();
                                if (!original.containsKey("isSignedUp")) return;
                                ContentProvider provider = (ContentProvider) query.thisObject;
                                try {
                                    if (!"com.samsung.android.spay".equals(provider.getCallingPackage())) return;
                                    Bundle state = new Bundle(original);
                                    boolean signedUp = original.getBoolean("isSignedUp");
                                    boolean busy = false;
                                    long syncTime = 0;
                                    if (signedUp) {
                                        ClassLoader loader = provider.getClass().getClassLoader();
                                        Class<?> pref = XposedHelpers.findClass(PASS + ".common.framework.SPassPref", loader);
                                        syncTime = (Long) XposedHelpers.callStaticMethod(pref, "toLong", "scloud_last_sync_time", 0L);
                                        Class<?> sync = XposedHelpers.findClass(PASS + ".common.scloud.SCloudSyncManager", loader);
                                        Object request = XposedHelpers.getStaticObjectField(sync, "mRequestOnGoingSCloud");
                                        busy = ((AtomicBoolean) request).get();
                                        Bundle options = (Bundle) query.args[2];
                                        if (options != null && options.getBoolean("toolbox_request_restore")
                                                && syncTime <= 0 && !SYNC_COMPLETED.get() && !busy
                                                && RESTORE_REQUEST_RUNNING.compareAndSet(false, true)) {
                                            // Same native manual-sync routine used by Pass. It checks
                                            // cloud preconditions and keeps the existing sync listener
                                            // and all account/network/sync preferences intact.
                                            new Thread(() -> {
                                                try {
                                                    XposedHelpers.callStaticMethod(sync, "initSamsungCloudRPCProfile");
                                                    Log.i("OneUIToolboxPass", "native Pass cloud restore request dispatched");
                                                } catch (Throwable error) {
                                                    Log.i("OneUIToolboxPass", "native Pass cloud restore request unavailable: "
                                                            + error.getClass().getSimpleName());
                                                } finally {
                                                    RESTORE_REQUEST_RUNNING.set(false);
                                                }
                                            }, "pass-native-cloud-restore").start();
                                        }
                                    } else {
                                        SYNC_COMPLETED.set(false);
                                    }
                                    state.putBoolean("toolbox_prepare_known", true);
                                    state.putBoolean("toolbox_guide_route", guideRoute);
                                    state.putBoolean("toolbox_sync_complete", signedUp && (syncTime > 0 || SYNC_COMPLETED.get()));
                                    state.putBoolean("toolbox_sync_busy", busy);
                                    Class<?> walletUtil = XposedHelpers.findClass(PASS + ".base.common.utils.WalletUtil",
                                            provider.getClass().getClassLoader());
                                    state.putBoolean("toolbox_wallet_linked",
                                            (Boolean) XposedHelpers.callStaticMethod(walletUtil, "getWalletMigrated"));
                                    query.setResult(state);
                                } catch (Throwable error) {
                                    // Keep native response untouched if readiness cannot be established.
                                    Log.i("OneUIToolboxPass", "Pass preparation state unavailable: "
                                            + error.getClass().getSimpleName());
                                }
                            }
                        });
                        Log.i("OneUIToolboxPass", "Pass native preparation state bridge ready");
                    }
                });
    }
}
