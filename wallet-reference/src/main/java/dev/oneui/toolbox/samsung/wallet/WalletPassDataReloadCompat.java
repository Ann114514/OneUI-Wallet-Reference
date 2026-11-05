package dev.oneui.toolbox.samsung.wallet;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Reloads through the native authenticated repository after lifecycle/data transitions. */
final class WalletPassDataReloadCompat {
    private static final String TAG = "OneUIToolboxPass";
    private static final String WALLET = "com.samsung.android.spay";
    private final Context context;
    private final ClassLoader loader;
    private final Class<?> repository;
    private final Method update, migrated;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WeakReference<Activity> activity = new WeakReference<>(null);
    private final Runnable first = () -> reload();
    private final Runnable settled = () -> reload();

    private WalletPassDataReloadCompat(Context context, ClassLoader loader, Class<?> repository)
            throws Exception {
        this.context = context;
        this.loader = loader;
        this.repository = repository;
        update = repository.getDeclaredMethod("updateCount", int.class);
        migrated = repository.getDeclaredMethod("isPassMigrated");
    }

    static void install(Context context, ClassLoader loader, Class<?> repository) throws Throwable {
        WalletPassDataReloadCompat state = new WalletPassDataReloadCompat(context, loader, repository);
        XposedBridge.hookMethod(Instrumentation.class.getDeclaredMethod("callActivityOnResume", Activity.class),
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam call) {
                        Activity host = (Activity) call.args[0];
                        if (!WALLET.equals(host.getPackageName())) return;
                        state.activity = new WeakReference<>(host);
                        state.schedule();
                    }
                });
        XposedBridge.hookMethod(Instrumentation.class.getDeclaredMethod("callActivityOnPause", Activity.class),
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam call) {
                        if (state.activity.get() != call.args[0]) return;
                        state.activity.clear();
                        state.main.removeCallbacks(state.first);
                        state.main.removeCallbacks(state.settled);
                    }
                });
        Class<?> fragment = Class.forName(WALLET + ".pass.dashboard.list.PassWalletListFragment", false, loader);
        XposedBridge.hookMethod(fragment.getDeclaredMethod("onResume"), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam call) { state.schedule(); }
        });
        // The native observer may drop an update while a previous async query is in flight.
        // Follow the notification with bounded, coalesced native reloads after it settles.
        for (String kind : new String[]{"signInInfo", "privateInfo"}) {
            Class<?> observer = Class.forName(WALLET + ".pass.repository.PassDataRepositoryImpl$"
                    + kind + "Observer$2$1", false, loader);
            XposedBridge.hookMethod(observer.getDeclaredMethod("onChange", boolean.class), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam call) { state.schedule(); }
            });
        }
        Log.i(TAG, "Pass native data reload hooks ready");
    }

    private void schedule() {
        main.post(() -> {
            main.removeCallbacks(first);
            main.removeCallbacks(settled);
            main.postDelayed(first, 300);
            main.postDelayed(settled, 1800);
        });
    }

    private void reload() {
        Activity host = activity.get();
        if (host == null || host.isFinishing() || host.isDestroyed()) return;
        try {
            if (!WalletPassRegistrationCompat.hasRegistration(context, loader)) return;
            Object companion = XposedHelpers.getStaticObjectField(repository, "n");
            Object data = XposedHelpers.callMethod(companion, "getInstance");
            // Keep native sign-in and migration checks. No credential values are inspected,
            // copied, fabricated or rewritten; the provider remains the data source.
            if (!Boolean.TRUE.equals(migrated.invoke(data))) return;
            update.invoke(data, 1);
            update.invoke(data, 2);
        } catch (Throwable error) {
            Log.i(TAG, "Pass native reload unavailable: " + error.getClass().getSimpleName());
        }
    }
}
