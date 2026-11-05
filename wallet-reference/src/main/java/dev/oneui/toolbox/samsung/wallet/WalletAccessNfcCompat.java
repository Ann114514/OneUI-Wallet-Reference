package dev.oneui.toolbox.samsung.wallet;

import android.content.Context;
import android.nfc.NfcAdapter;
import android.os.Build;
import android.os.Process;
import android.util.Log;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** Distinguishes support for the reader-option control from its actual enabled state. */
public final class WalletAccessNfcCompat {
    private static final String PACKAGE = "com.samsung.android.spay";
    private static final String TAG = "OneUIToolboxNfc";
    private static final String SCOPE_KEY = "toolbox.accessNfc.readScreen";
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final ThreadLocal<String> READ_SCREEN = new ThreadLocal<>();
    private static final Set<String> REPORTED = new HashSet<>();

    private WalletAccessNfcCompat() { }

    public static void install(Context context, ClassLoader loader) {
        try {
            if (context == null || loader == null || !PACKAGE.equals(context.getPackageName())
                    || Process.myUid() / 100000 != 0 || Build.VERSION.SDK_INT < 35
                    || !"SM-S9180".equals(Build.MODEL)
                    || !"samsung".equalsIgnoreCase(Build.MANUFACTURER)
                    || context.getPackageManager().getPackageInfo(PACKAGE, 0).getLongVersionCode()
                    != 559700323L || !INSTALLED.compareAndSet(false, true)) return;
            try {
                new State(loader).install();
                report("wallet card reader-state compatibility ready");
            } catch (Throwable error) {
                INSTALLED.set(false);
                report("access-card compatibility unavailable type=" + error.getClass().getSimpleName());
            }
        } catch (Throwable error) {
            report("access-card target unavailable; native retained");
        }
    }

    private static final class State {
        final Method onResume, readerCheck;
        final ClassLoader loader;
        final Field adapterField;
        volatile boolean active;

        State(ClassLoader loader) throws Exception {
            this.loader = loader;
            Class<?> fragment = Class.forName(PACKAGE + ".vas.dummykey.ui.cardreg.ReadFragment", false, loader);
            onResume = fragment.getDeclaredMethod("onResume");
            Class<?> controller = Class.forName(PACKAGE + ".common.util.NfcController", false, loader);
            readerCheck = controller.getDeclaredMethod("i");
            adapterField = controller.getDeclaredField("d");
            adapterField.setAccessible(true);
            if (onResume.getReturnType() != void.class || Modifier.isStatic(onResume.getModifiers())
                    || readerCheck.getReturnType() != boolean.class || Modifier.isStatic(readerCheck.getModifiers())
                    || adapterField.getType() != NfcAdapter.class || !Modifier.isStatic(adapterField.getModifiers()))
                throw new NoSuchMethodException();
        }

        void install() throws Throwable {
            List<XC_MethodHook.Unhook> hooks = new ArrayList<>();
            try {
                hooks.add(XposedBridge.hookMethod(onResume, scope("access")));
                hooks.add(XposedBridge.hookMethod(readerCheck, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam call) {
                        try {
                            String screen = READ_SCREEN.get();
                            if (!active || screen == null || call.hasThrowable()
                                    || !Boolean.FALSE.equals(call.getResult())) return;
                            Object value = adapterField.get(null);
                            if (!(value instanceof NfcAdapter)) return;
                            NfcAdapter adapter = (NfcAdapter) value;
                            boolean enabled = adapter.isEnabled();
                            boolean supported = adapter.isReaderOptionSupported();
                            boolean readerEnabled = adapter.isReaderOptionEnabled();
                            report(screen + " reader check enabled=" + enabled + " optionSupported=" + supported
                                    + " readerEnabled=" + readerEnabled);
                            // A missing settings control must not negate an enabled reader.
                            // Real NFC/reader-off conditions still follow the original warning.
                            if (enabled && !supported && readerEnabled) {
                                call.setResult(Boolean.TRUE);
                                report(screen + " false reader warning corrected");
                            }
                        } catch (Throwable error) {
                            report("reader state unavailable; native retained type="
                                    + error.getClass().getSimpleName());
                        }
                    }
                }));
                active = true;
            } catch (Throwable error) {
                active = false;
                for (XC_MethodHook.Unhook hook : hooks)
                    try { hook.unhook(); } catch (Throwable ignored) { }
                throw error;
            }
            // Bank selection has the same native check but an independent lifecycle.
            // This optional scope must not roll back the accepted access-card repair.
            try {
                Class<?> bank = Class.forName(PACKAGE + ".tokenV2.ui.cardreg.BankSelectFragment", false, loader);
                Method onStart = bank.getDeclaredMethod("onStart");
                if (onStart.getReturnType() != void.class || Modifier.isStatic(onStart.getModifiers()))
                    throw new NoSuchMethodException();
                XposedBridge.hookMethod(onStart, scope("bank"));
                report("bank-selection reader-state scope ready");
            } catch (Throwable error) {
                report("bank-selection scope unavailable type=" + error.getClass().getSimpleName());
            }
        }

        XC_MethodHook scope(final String screen) {
            return new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam call) {
                    if (!active) return;
                    call.setObjectExtra(SCOPE_KEY, READ_SCREEN.get());
                    call.setObjectExtra(SCOPE_KEY + ".saved", Boolean.TRUE);
                    READ_SCREEN.set(screen);
                }
                @Override protected void afterHookedMethod(MethodHookParam call) {
                    if (!Boolean.TRUE.equals(call.getObjectExtra(SCOPE_KEY + ".saved"))) return;
                    Object prior = call.getObjectExtra(SCOPE_KEY);
                    if (prior instanceof String) READ_SCREEN.set((String) prior);
                    else READ_SCREEN.remove();
                }
            };
        }
    }

    private static synchronized void report(String message) {
        if (REPORTED.size() >= 10 || !REPORTED.add(message)) return;
        try { Log.i(TAG, message); } catch (Throwable ignored) { }
        try { XposedBridge.log(TAG + ": " + message); } catch (Throwable ignored) { }
    }
}
