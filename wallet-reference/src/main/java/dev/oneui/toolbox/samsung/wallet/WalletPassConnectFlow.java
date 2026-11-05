package dev.oneui.toolbox.samsung.wallet;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.app.Dialog;
import android.content.Intent;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.Toast;
import android.util.Log;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XC_MethodHook;

/** Prepares native Pass and waits for its real restore before adding it to Wallet. */
final class WalletPassConnectFlow {
    private static final String PASS = "com.samsung.android.samsungpass";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(job -> {
        Thread thread = new Thread(job, "wallet-pass-prepare");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicBoolean CALLBACKS = new AtomicBoolean();
    private static final AtomicBoolean RESTORING = new AtomicBoolean();
    static final String RESTORE_READY = "dev.oneui.toolbox.action.PASS_RESTORE_READY";
    private static volatile Pending current;

    static void start(Activity host, Intent request) {
        MAIN.post(() -> {
            if (host.isFinishing() || host.isDestroyed()) return;
            if (current != null) { onWalletResumed(host); return; }
            installCallbacks(host.getApplication());
            Pending flow = new Pending(host, request);
            current = flow;
            flow.showLoading();
            IO.execute(() -> {
                String failure = null;
                try { WalletPassRegistrationCompat.ensureRegistration(host); }
                catch (Exception error) {
                    failure = error.getMessage() != null ? error.getMessage() : "三星 Pass 准备未完成，请重试。";
                }
                String result = failure;
                MAIN.post(() -> {
                    if (current != flow) return;
                    flow.registered = true;
                    if (result != null) flow.fail(result);
                    else {
                        flow.persist();
                        if (flow.visible) flow.probe();
                    }
                });
            });
        });
    }

    static void onWalletResumed(Activity activity) {
        if (!"com.samsung.android.spay".equals(activity.getPackageName())) return;
        installCallbacks(activity.getApplication());
        Pending flow = current;
        if (flow != null && activity.getClass().getName()
                .equals("com.samsung.android.spay.pass.quickaccess.AddPassCardActivity")) {
            // The native confirmation page is the hand-off boundary. Cancel/Add belongs
            // to that page, so never reopen it after the user leaves it.
            flow.cancel();
            return;
        }
        if (flow != null) {
            if (flow.host != activity && !activity.getClass().getName()
                    .equals("com.samsung.android.spay.ui.HomeContainerActivity")) return;
            if (flow.host != activity) flow.detach();
            flow.host = activity;
            flow.visible = true;
            flow.waitSince = 0;
            if (flow.guideDeparted) flow.guideOpen = false;
            if (flow.registered && !flow.guideOpen) flow.probe();
            return;
        }
        if (!activity.getClass().getName().equals("com.samsung.android.spay.ui.HomeContainerActivity")
                || !RESTORING.compareAndSet(false, true)) return;
        Context context = activity.getApplicationContext();
        IO.execute(() -> {
            WalletPassPendingStore.Entry stored = null;
            try { stored = WalletPassPendingStore.load(context); }
            catch (Exception error) { WalletPassPendingStore.clear(context); }
            WalletPassPendingStore.Entry entry = stored;
            MAIN.post(() -> {
                RESTORING.set(false);
                if (entry == null || current != null || activity.isDestroyed() || activity.isFinishing()) return;
                Pending restored = new Pending(activity, entry.request);
                restored.until = entry.until;
                restored.binding = entry.binding;
                restored.registered = true;
                current = restored;
                Log.i("OneUIToolboxPass", "pending Pass connection resumed");
                restored.probe();
            });
        });
    }

    private static void installCallbacks(Application application) {
        if (!CALLBACKS.compareAndSet(false, true)) return;
        // A hint only: readiness and account identity are rechecked through the native
        // signature-protected provider before any continuation is allowed.
        application.registerReceiver(new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                Pending flow = current;
                if (RESTORE_READY.equals(intent.getAction()) && flow != null && flow.visible
                        && flow.registered && !flow.guideOpen) flow.probe();
            }
        }, new IntentFilter(RESTORE_READY), Context.RECEIVER_EXPORTED);
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity activity) {
                onWalletResumed(activity);
            }
            @Override public void onActivityPaused(Activity activity) {
                Pending flow = current;
                if (flow == null || flow.host != activity) return;
                flow.visible = false;
                if (flow.guideOpen) flow.guideDeparted = true;
                MAIN.removeCallbacks(flow.poll);
                flow.hideLoading();
            }
            @Override public void onActivityDestroyed(Activity activity) {
                if (current != null && current.host == activity) current.detach();
            }
            @Override public void onActivityCreated(Activity a, Bundle b) { }
            @Override public void onActivityStarted(Activity a) { }
            @Override public void onActivityStopped(Activity a) { }
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
        });
    }

    private static final class Pending {
        Activity host;
        final Context context;
        final Intent request;
        long until = System.currentTimeMillis() + WalletPassPendingStore.LIFETIME;
        final Runnable poll = () -> probe();
        boolean visible = true, registered, guideOpen, guideDeparted, guideAttempted, probing;
        long waitSince, guideReturnedAt, bindingReturnedAt;
        boolean guideSucceeded, binding;
        boolean restoreRequested, requestRestoreNext;
        boolean probeAgain;
        XC_MethodHook.Unhook resultHook;
        Dialog loading;
        int lastState = -1;

        Pending(Activity host, Intent request) {
            this.host = host;
            this.context = host.getApplicationContext();
            this.request = new Intent(request);
        }

        boolean alive() { return current == this && host != null && !host.isFinishing() && !host.isDestroyed(); }

        void persist() {
            if (!registered) return;
            Intent saved = new Intent(request);
            boolean linking = binding;
            long expires = until;
            IO.execute(() -> {
                if (current != this) return;
                try { WalletPassPendingStore.save(context, saved, expires, linking); }
                catch (Exception error) {
                    Log.i("OneUIToolboxPass", "pending connection save unavailable: " + error.getClass().getSimpleName());
                }
            });
        }

        void detach() {
            MAIN.removeCallbacks(poll);
            hideLoading();
            if (resultHook != null) { resultHook.unhook(); resultHook = null; }
            if (guideOpen) guideDeparted = true;
            visible = false;
            host = null;
            Log.i("OneUIToolboxPass", "pending Pass connection retained across page change");
        }

        void probe() {
            if (!alive() || !visible || guideOpen) return;
            if (System.currentTimeMillis() >= until) { fail("连接等待已结束，请稍后重试。"); return; }
            if (probing) { probeAgain = true; return; }
            probing = true;
            showLoading();
            Bundle options = new Bundle();
            options.putBoolean("toolbox_request_restore", requestRestoreNext);
            requestRestoreNext = false;
            IO.execute(() -> {
                Bundle state = null;
                try {
                    state = context.getContentResolver().call(
                            Uri.parse("content://com.samsung.android.samsungpass.walletsettings"),
                            "getSignUpState", null, options);
                } catch (Exception ignored) { }
                Bundle reply = state;
                MAIN.post(() -> {
                    probing = false;
                    if (!alive() || !visible) return;
                    accept(reply);
                    if (probeAgain && alive()) { probeAgain = false; MAIN.post(poll); }
                });
            });
        }

        void accept(Bundle state) {
            if (state == null || !state.getBoolean("toolbox_prepare_known")) {
                fail("暂时无法读取三星 Pass 的准备状态，请确认工具箱作用域已勾选三星 Pass 后重试。");
                return;
            }
            if (binding) {
                if (state.getBoolean("toolbox_wallet_linked")) {
                    Log.i("OneUIToolboxPass", "native Pass wallet link confirmed");
                    finishAtQuickAccessConfirmation();
                } else {
                    if (bindingReturnedAt == 0) bindingReturnedAt = SystemClock.elapsedRealtime();
                    if (SystemClock.elapsedRealtime() - bindingReturnedAt >= 5000) {
                        fail("三星 Pass 尚未完成连接，请重试。");
                    } else MAIN.postDelayed(poll, 500);
                }
                return;
            }
            boolean signedUp = state.getBoolean("isSignedUp");
            boolean restored = state.getBoolean("toolbox_sync_complete");
            boolean syncing = state.getBoolean("toolbox_sync_busy");
            int flags = (signedUp ? 1 : 0) | (restored ? 2 : 0) | (syncing ? 4 : 0);
            if (flags != lastState) {
                lastState = flags;
                Log.i("OneUIToolboxPass", "Pass preparation signedUp=" + signedUp
                        + " restored=" + restored + " syncing=" + syncing);
            }
            if (!signedUp) {
                if (guideAttempted) {
                    if (guideSucceeded && SystemClock.elapsedRealtime() - guideReturnedAt < 5000) {
                        MAIN.postDelayed(poll, 500);
                    } else cancel();
                    return;
                }
                guideAttempted = true;
                guideOpen = true;
                hideLoading();
                if (!state.getBoolean("toolbox_guide_route")) {
                    fail("暂时无法打开三星 Pass 引导，请稍后重试。");
                    return;
                }
                Intent guide = new Intent(Intent.ACTION_VIEW,
                        Uri.parse("walletpass://launch?action=passSignUp")).setPackage(PASS)
                        .putExtra("toolbox_prepare_only", true);
                try {
                    resultHook = XposedBridge.hookMethod(XposedHelpers.findMethodBestMatch(host.getClass(),
                            "onActivityResult", int.class, int.class, Intent.class), new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam result) {
                            if (current != Pending.this || result.thisObject != host
                                    || !Integer.valueOf(0x6d51).equals(result.args[0])) return;
                            guideSucceeded = Integer.valueOf(Activity.RESULT_OK).equals(result.args[1]);
                            guideReturnedAt = SystemClock.elapsedRealtime();
                            // Some native hand-off screens return RESULT_CANCELED even
                            // after signup completed. On resume, the real signup state decides:
                            // incomplete/canceled signup stops; completed signup continues.
                            Log.i("OneUIToolboxPass", "native Pass guide returned; rechecking signup");
                        }
                    });
                    host.startActivityForResult(guide, 0x6d51);
                }
                catch (Exception error) { fail("无法打开三星 Pass 引导，请稍后重试。"); }
                return;
            }
            // A completed restore remains usable while a later/periodic sync is queued.
            if (restored) {
                boolean linked = state.getBoolean("toolbox_wallet_linked");
                if (linked) { finishAtQuickAccessConfirmation(); return; }
                Activity target = host;
                Intent connect = new Intent(request);
                connect.putExtra("toolbox_prepared_connect", true);
                // Native background-onboarding route performs walletSignIn/requestLink
                // after all preparation is complete, without the unsupported CN redirect.
                Uri data = connect.getData();
                Uri.Builder uri = data.buildUpon().clearQuery();
                for (String name : data.getQueryParameterNames()) {
                    if ("migration_caller".equals(name)) continue;
                    for (String value : data.getQueryParameters(name)) uri.appendQueryParameter(name, value);
                }
                connect.setData(uri.appendQueryParameter("migration_caller", "16").build());
                connect.putExtra("migration_caller", "16");
                binding = true;
                persist();
                hideLoading();
                Log.i("OneUIToolboxPass", "prepared Pass native link launched");
                try { target.startActivity(connect); }
                catch (Throwable error) {
                    cancel();
                    new AlertDialog.Builder(target).setMessage("无法继续连接三星 Pass，请重试。")
                            .setPositiveButton(android.R.string.ok, null).show();
                }
                return;
            }
            if (!syncing && !restoreRequested) {
                restoreRequested = true;
                requestRestoreNext = true;
                Log.i("OneUIToolboxPass", "requesting native Pass cloud restore once");
                MAIN.post(poll);
                return;
            }
            if (waitSince == 0) waitSince = SystemClock.elapsedRealtime();
            if (SystemClock.elapsedRealtime() - waitSince >= 120_000) {
                // Stop foreground polling, retain the requested connection. Native completion
                // notification or the next Wallet resume will continue it without another tap.
                hideLoading();
                Toast.makeText(host, "仍在同步，完成后将自动继续连接", Toast.LENGTH_LONG).show();
                return;
            }
            MAIN.removeCallbacks(poll);
            MAIN.postDelayed(poll, 1500);
        }

        void showLoading() {
            if (!alive() || !visible || loading != null) return;
            // Samsung's themed framework widget supplies the device's native spinner.
            Dialog dialog = new Dialog(host);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            FrameLayout content = new FrameLayout(host);
            int size = Math.round(56 * host.getResources().getDisplayMetrics().density);
            ProgressBar spinner = new ProgressBar(host, null, android.R.attr.progressBarStyleLarge);
            spinner.setIndeterminate(true);
            spinner.setContentDescription("正在加载");
            content.addView(spinner, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
            dialog.setContentView(content);
            dialog.setCanceledOnTouchOutside(false);
            dialog.setOnCancelListener(ignored -> cancel());
            loading = dialog;
            dialog.show();
            Window window = dialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                window.setStatusBarColor(Color.TRANSPARENT);
                window.setStatusBarContrastEnforced(false);
                // The full-display dialog owns the status bar while waiting. Keep it
                // transparent and retain Wallet's current light/dark icon appearance.
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    WindowInsetsController source = host.getWindow().getInsetsController();
                    WindowInsetsController target = window.getInsetsController();
                    if (source != null && target != null) target.setSystemBarsAppearance(
                            source.getSystemBarsAppearance() & WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
                } else {
                    int light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                    window.getDecorView().setSystemUiVisibility(
                            (window.getDecorView().getSystemUiVisibility() & ~light)
                                    | (host.getWindow().getDecorView().getSystemUiVisibility() & light));
                }
                // Center within the entire display, independent of Samsung's dialog
                // placement, system-bar insets, and the keyboard's available area.
                window.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
                if (android.os.Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false);
                WindowManager.LayoutParams attributes = window.getAttributes();
                attributes.gravity = Gravity.CENTER;
                attributes.x = 0;
                attributes.y = 0;
                attributes.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
                attributes.layoutInDisplayCutoutMode = android.os.Build.VERSION.SDK_INT >= 30
                        ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                        : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                window.setAttributes(attributes);
                window.getDecorView().setPadding(0, 0, 0, 0);
                window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT);
            }
        }

        void finishAtQuickAccessConfirmation() {
            Activity target = host;
            if (!alive()) return;
            try {
                Object api = XposedHelpers.newInstance(XposedHelpers.findClass(
                        "com.samsung.android.spay.pass.PassCommonInterfaceImpl", target.getClassLoader()));
                boolean added = (Boolean) XposedHelpers.callMethod(api, "isPassQACardAdded");
                Intent page = added ? null : (Intent) XposedHelpers.callMethod(api, "addPassCardActivity", target);
                cancel();
                if (page != null) {
                    target.startActivity(page);
                    Log.i("OneUIToolboxPass", "native Pass quick access confirmation opened");
                }
            } catch (Throwable error) {
                fail("无法打开快速访问确认页面，请重试。");
            }
        }

        void hideLoading() {
            Dialog dialog = loading;
            loading = null;
            if (dialog != null && dialog.isShowing()) {
                try { dialog.dismiss(); } catch (RuntimeException ignored) { }
            }
        }

        void cancel() {
            MAIN.removeCallbacks(poll);
            hideLoading();
            if (resultHook != null) { resultHook.unhook(); resultHook = null; }
            if (current == this) {
                current = null;
                IO.execute(() -> WalletPassPendingStore.clear(context));
            }
        }

        void fail(String text) {
            cancel();
            if (host != null && !host.isDestroyed() && !host.isFinishing()) {
                new AlertDialog.Builder(host).setMessage(text)
                        .setPositiveButton(android.R.string.ok, null).show();
            }
        }
    }
}
