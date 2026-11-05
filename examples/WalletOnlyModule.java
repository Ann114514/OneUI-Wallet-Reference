package dev.oneui.wallet.reference;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import dev.oneui.toolbox.samsung.wallet.WalletCampusCardCompat;
import dev.oneui.toolbox.samsung.wallet.WalletModule;

/** Copy this entry into your own LSPosed host module; it is not active in the AAR. */
public final class WalletOnlyModule implements IXposedHookLoadPackage {
    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (!WalletModule.isTarget(param.packageName)) return;
        WalletModule.install(param);
        if ("com.samsung.android.spay".equals(param.packageName)) {
            WalletCampusCardCompat.install(param);
        }
    }
}
