package dev.oneui.toolbox.samsung.wallet;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.Switch;
import android.widget.TextView;

/** Native transit-detail group; placement and gallery actions belong to the caller. */
final class WalletTransitCardArtPanel {
    private static final String PKG = "com.samsung.android.spay";
    final ViewGroup group;
    final View designRow;
    final View resetRow;
    final View logoRow;
    final CompoundButton logoSwitch;

    private WalletTransitCardArtPanel(ViewGroup group, View designRow, View resetRow,
            View logoRow, CompoundButton logoSwitch) {
        this.group = group;
        this.designRow = designRow;
        this.resetRow = resetRow;
        this.logoRow = logoRow;
        this.logoSwitch = logoSwitch;
    }

    static WalletTransitCardArtPanel create(Activity activity, ViewGroup nativeGroup) throws Exception {
        if (activity == null || nativeGroup == null)
            throw new IllegalArgumentException("Native transit group is required");
        int layout = activity.getResources().getIdentifier("transit_card_detail_header", "layout", PKG);
        int rowId = activity.getResources().getIdentifier("switch_default_card_area", "id", PKG);
        int textId = activity.getResources().getIdentifier("switch_default_card_text", "id", PKG);
        if (layout == 0 || rowId == 0 || textId == 0)
            throw new IllegalStateException("Native transit group resources unavailable");
        if (nativeGroup.findViewById(rowId) == null)
            throw new IllegalArgumentException("Unexpected native transit group");

        View template = activity.getLayoutInflater().inflate(layout, null, false);
        View row = template.findViewById(rowId);
        if (row == null || !(row.getParent() instanceof ViewGroup))
            throw new IllegalStateException("Native transit selection row unavailable");
        View label = row.findViewById(textId);
        if (!(label instanceof TextView))
            throw new IllegalStateException("Native transit selection label unavailable");
        ViewGroup group = (ViewGroup) row.getParent();
        if (!(group.getParent() instanceof ViewGroup))
            throw new IllegalStateException("Native transit group is detached");
        ((ViewGroup) group.getParent()).removeView(group);
        for (int index = group.getChildCount() - 1; index >= 0; index--)
            if (group.getChildAt(index) != row) group.removeViewAt(index);

        ViewGroup.LayoutParams original = nativeGroup.getLayoutParams();
        if (original instanceof LinearLayout.LayoutParams)
            group.setLayoutParams(new LinearLayout.LayoutParams((LinearLayout.LayoutParams) original));
        else if (original instanceof ViewGroup.MarginLayoutParams)
            group.setLayoutParams(new ViewGroup.MarginLayoutParams((ViewGroup.MarginLayoutParams) original));
        else if (original != null)
            group.setLayoutParams(new ViewGroup.LayoutParams(original));

        ((TextView) label).setText("恢复默认卡面");
        replaceIds(row);

        View designTemplate = activity.getLayoutInflater().inflate(layout, null, false);
        View designRow = designTemplate.findViewById(rowId);
        TextView designLabel = designRow.findViewById(textId);
        ((ViewGroup) designRow.getParent()).removeView(designRow);
        replaceIds(designRow);
        designLabel.setText("更改设计");
        designRow.setVisibility(View.VISIBLE);
        group.addView(designRow, 0);

        View logoTemplate = activity.getLayoutInflater().inflate(layout, null, false);
        View logoRow = logoTemplate.findViewById(rowId);
        if (!(logoRow instanceof RelativeLayout) || !(logoRow.getParent() instanceof ViewGroup))
            throw new IllegalStateException("Native transit logo row unavailable");
        TextView logoLabel = logoRow.findViewById(textId);
        if (logoLabel == null) throw new IllegalStateException("Native transit logo label unavailable");
        ((ViewGroup) logoRow.getParent()).removeView(logoRow);
        replaceIds(logoRow);
        logoLabel.setText("交通联合Logo");

        Switch logoSwitch = new Switch(activity);
        logoSwitch.setId(View.generateViewId());
        logoSwitch.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        logoSwitch.setContentDescription("交通联合Logo");
        RelativeLayout.LayoutParams switchParams = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        switchParams.addRule(RelativeLayout.ALIGN_PARENT_END);
        switchParams.addRule(RelativeLayout.CENTER_VERTICAL);
        ((RelativeLayout) logoRow).addView(logoSwitch, switchParams);
        RelativeLayout.LayoutParams labelParams = new RelativeLayout.LayoutParams(
                (RelativeLayout.LayoutParams) logoLabel.getLayoutParams());
        labelParams.addRule(RelativeLayout.ALIGN_PARENT_START);
        labelParams.addRule(RelativeLayout.CENTER_VERTICAL);
        labelParams.addRule(RelativeLayout.START_OF, logoSwitch.getId());
        labelParams.setMarginEnd(Math.round(12 * activity.getResources().getDisplayMetrics().density));
        logoLabel.setLayoutParams(labelParams);

        group.setId(View.NO_ID);
        group.setVisibility(View.VISIBLE);
        row.setVisibility(View.VISIBLE);
        logoRow.setVisibility(View.VISIBLE);
        return new WalletTransitCardArtPanel(group, designRow, row, logoRow, logoSwitch);
    }

    /** Native settings rows for the independent transit design page. */
    static WalletTransitCardArtPanel createSettings(Activity activity) throws Exception {
        int layout = activity.getResources().getIdentifier("transit_card_detail_header", "layout", PKG);
        int rowId = activity.getResources().getIdentifier("switch_default_card_area", "id", PKG);
        View template = activity.getLayoutInflater().inflate(layout, null, false);
        View anchor = template.findViewById(rowId);
        if (anchor == null || !(anchor.getParent() instanceof ViewGroup))
            throw new IllegalStateException("Native transit settings group unavailable");
        WalletTransitCardArtPanel panel = create(activity, (ViewGroup) anchor.getParent());
        panel.group.removeView(panel.designRow);
        panel.group.addView(panel.logoRow);
        panel.group.setClipToOutline(true);
        panel.applyRipple(panel.resetRow, "settings_round_corner_up_with_ripple");
        panel.applyRipple(panel.logoRow, "settings_round_corner_down_with_ripple");
        return panel;
    }

    void showEntryOnly() {
        group.removeView(resetRow);
        group.setClipToOutline(true);
        applyRipple(designRow, "settings_round_corner_with_ripple");
    }

    private void applyRipple(View row, String name) {
        int id = row.getResources().getIdentifier(name, "drawable", PKG);
        if (id == 0) return;
        // The copied row's rectangular selector must not remain under the rounded
        // foreground. The native parent still supplies the group surface color.
        int start = row.getPaddingStart(), top = row.getPaddingTop();
        int end = row.getPaddingEnd(), bottom = row.getPaddingBottom();
        row.setBackground(null);
        row.setForeground(row.getContext().getDrawable(id));
        row.setPaddingRelative(start, top, end, bottom);
    }

    private static void replaceIds(View view) {
        view.setId(View.generateViewId());
        if (view instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) view;
            for (int index = 0; index < parent.getChildCount(); index++) replaceIds(parent.getChildAt(index));
        }
    }
}
