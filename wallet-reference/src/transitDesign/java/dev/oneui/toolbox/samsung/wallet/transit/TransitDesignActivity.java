package dev.oneui.toolbox.samsung.wallet.transit;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import java.lang.reflect.InvocationHandler;
import com.samsung.android.spay.common.ui.SpayBaseActivity;
import androidx.activity.OnBackPressedCallback;

/** Loaded with Wallet as its parent loader; this is not the access-card Activity. */
public final class TransitDesignActivity extends SpayBaseActivity {
    private InvocationHandler controller;
    private final OnBackPressedCallback back = new OnBackPressedCallback(true) {
        @Override public void handleOnBackPressed() {
            if (!Boolean.TRUE.equals(event("back"))) finish();
        }
    };
    public void setController(InvocationHandler value) { controller = value; }
    private Object event(String name, Object... data) {
        Object[] args = new Object[data.length + 1];
        args[0] = name;
        System.arraycopy(data, 0, args, 1, data.length);
        try { return controller.invoke(this, null, args); }
        catch (Throwable error) { throw new IllegalStateException("Transit design lifecycle failed", error); }
    }
    @Override public void onCreate(Bundle state) {
        try {
            int theme = getPackageManager().getActivityInfo(new ComponentName(getPackageName(),
                    "com.samsung.android.spay.vas.dummykey.ui.cardreg.DummyKeyRegistrationActivity"), 0).getThemeResource();
            if (theme != 0) setTheme(theme);
        } catch (Exception ignored) { }
        super.onCreate(state);
        getOnBackPressedDispatcher().addCallback(this, back);
        disableCommonEdgeToEdgeHandling();
        event("create", state);
    }
    @Override public void onActivityResult(int request, int result, Intent data) {
        if (request == 0x6e49) { event("result", request, result, data); return; }
        super.onActivityResult(request, result, data);
    }
    @Override public void onSaveInstanceState(Bundle state) {
        event("saveState", state);
        super.onSaveInstanceState(state);
    }
    @Override public void onDestroy() {
        event("destroy");
        back.remove();
        super.onDestroy();
    }
    @Override public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            if (!Boolean.TRUE.equals(event("back"))) finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
