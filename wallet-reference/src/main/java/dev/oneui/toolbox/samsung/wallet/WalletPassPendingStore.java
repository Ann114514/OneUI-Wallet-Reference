package dev.oneui.toolbox.samsung.wallet;

import android.content.Context;
import android.content.Intent;
import org.json.JSONObject;

/** Encrypted, account-bound continuation metadata; never stores password records. */
final class WalletPassPendingStore {
    private static final String STORE = "toolbox_pass_pending";
    static final long LIFETIME = 15 * 60_000L;

    static void save(Context context, Intent request, long until, boolean binding) throws Exception {
        String identity = WalletPassRegistrationCompat.registrationIdentity(context);
        if (identity == null) return;
        String data = new JSONObject().put("identity", identity).put("intent", request.toUri(0))
                .put("until", until).put("binding", binding).toString();
        context.getSharedPreferences(STORE, 0).edit().putString("pending",
                WalletPassRegistrationCompat.encrypt(data)).commit();
    }

    static Entry load(Context context) throws Exception {
        String encrypted = context.getSharedPreferences(STORE, 0).getString("pending", null);
        if (encrypted == null) return null;
        JSONObject data = new JSONObject(WalletPassRegistrationCompat.decrypt(encrypted));
        long until = data.getLong("until");
        if (until <= System.currentTimeMillis() || until > System.currentTimeMillis() + LIFETIME + 60_000) {
            clear(context);
            return null;
        }
        String identity = WalletPassRegistrationCompat.registrationIdentity(context);
        if (identity == null) return null;
        if (!identity.equals(data.getString("identity"))) { clear(context); return null; }
        Intent request = Intent.parseUri(data.getString("intent"), 0);
        if (request.getData() == null || !"walletpass".equals(request.getData().getScheme())
                || !("migration".equals(request.getData().getHost())
                || "migrationlinkonly".equals(request.getData().getHost()))) {
            clear(context);
            return null;
        }
        request.setPackage("com.samsung.android.samsungpass");
        return new Entry(request, until, data.optBoolean("binding"));
    }

    static void clear(Context context) {
        context.getSharedPreferences(STORE, 0).edit().remove("pending").commit();
    }

    static final class Entry {
        final Intent request;
        final long until;
        final boolean binding;
        Entry(Intent request, long until, boolean binding) {
            this.request = request; this.until = until; this.binding = binding;
        }
    }
}
