package dev.oneui.toolbox.samsung.wallet;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textview.MaterialTextView;
import dev.oneui.toolbox.R;

/** Opens configured component download pages; no APK installation is performed. */
public final class WalletDownloadsActivity extends AppCompatActivity {
    private LinearLayout root;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarContrastEnforced(false);
        boolean light = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                != Configuration.UI_MODE_NIGHT_YES;
        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(getWindow(),
                getWindow().getDecorView());
        bars.setAppearanceLightStatusBars(light);
        bars.setAppearanceLightNavigationBars(light);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(role(com.google.android.material.R.attr.colorSurface));
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            androidx.core.graphics.Insets safe = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom);
            return insets;
        });
        MaterialToolbar toolbar = new MaterialToolbar(this);
        toolbar.setTitle("组件下载");
        toolbar.setTitleTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_TitleLarge);
        toolbar.setTitleTextColor(role(com.google.android.material.R.attr.colorOnSurface));
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back);
        toolbar.setNavigationIconTint(role(com.google.android.material.R.attr.colorOnSurface));
        toolbar.setNavigationContentDescription("返回");
        toolbar.setNavigationOnClickListener(view -> finish());
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(64)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout content = column();
        content.setPadding(dp(16), dp(16), dp(16), dp(32));
        add(content, text("请使用国行版本及匹配的配套组件。",
                com.google.android.material.R.style.TextAppearance_Material3_BodyLarge,
                com.google.android.material.R.attr.colorOnSurfaceVariant), 0);
        add(content, component("三星钱包", "国行钱包应用，提供银行卡、交通卡和门卡等功能。",
                R.string.wallet_app_download_url), 20);
        add(content, component("钱包框架", "国行钱包所需的配套服务框架。",
                R.string.wallet_framework_download_url), 12);
        add(content, component("银联组件", "钱包配套的银联可信服务组件。",
                R.string.wallet_unionpay_download_url), 12);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        ViewCompat.requestApplyInsets(root);
    }

    private MaterialCardView component(String title, String description, int addressResource) {
        MaterialCardView card = new MaterialCardView(this);
        card.setCardBackgroundColor(role(com.google.android.material.R.attr.colorSurfaceContainerHighest));
        card.setStrokeWidth(0);
        card.setCardElevation(0);
        card.setContentPadding(dp(20), dp(20), dp(20), dp(20));
        LinearLayout body = column();
        add(body, text(title, com.google.android.material.R.style.TextAppearance_Material3_TitleMedium,
                com.google.android.material.R.attr.colorOnSurface), 0);
        add(body, text(description, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
                com.google.android.material.R.attr.colorOnSurfaceVariant), 8);
        String address = getString(addressResource).trim();
        Uri download = downloadUri(address);
        if (download == null) add(body, text(address.isEmpty() ? "下载地址待提供" : "下载地址暂不可用",
                com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
                com.google.android.material.R.attr.colorOnSurfaceVariant), 12);
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END);
        MaterialButton button = new MaterialButton(this);
        button.setText("下载");
        button.setContentDescription("下载" + title);
        button.setMinHeight(dp(48));
        button.setEnabled(download != null);
        if (download != null) button.setOnClickListener(view -> openDownload(download));
        actions.addView(button, new LinearLayout.LayoutParams(-2, -2));
        add(body, actions, 12);
        card.addView(body);
        return card;
    }

    private static Uri downloadUri(String address) {
        if (address.isEmpty()) return null;
        try {
            Uri uri = Uri.parse(address).normalizeScheme();
            String scheme = uri.getScheme();
            return ("https".equals(scheme) || "http".equals(scheme))
                    && uri.getHost() != null && !uri.getHost().isEmpty() ? uri : null;
        } catch (RuntimeException error) { return null; }
    }

    private void openDownload(Uri download) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, download).addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (ActivityNotFoundException | SecurityException error) {
            Snackbar.make(root, "暂时无法打开下载页面，请稍后重试。", Snackbar.LENGTH_LONG).show();
        }
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private MaterialTextView text(String value, int appearance, int colorAttribute) {
        MaterialTextView view = new MaterialTextView(this);
        view.setTextAppearance(this, appearance);
        view.setTextColor(role(colorAttribute));
        view.setText(value);
        return view;
    }

    private void add(LinearLayout parent, View child, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top);
        parent.addView(child, params);
    }

    private int role(int attribute) {
        return MaterialColors.getColor(this, attribute, WalletDownloadsActivity.class.getSimpleName());
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
