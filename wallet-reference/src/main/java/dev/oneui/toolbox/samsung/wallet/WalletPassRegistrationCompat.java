package dev.oneui.toolbox.samsung.wallet;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ContentProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.KeyStore;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONArray;
import org.json.JSONObject;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Registers a separate Wallet identity for Pass, then resumes the native migration. */
final class WalletPassRegistrationCompat {
    private static final String WALLET = "com.samsung.android.spay";
    private static final String PASS = "com.samsung.android.samsungpass";
    private static final String TAG = "OneUIToolboxPassReg";
    private static final String STORE = "toolbox_pass_registration";
    private static volatile WeakReference<Activity> resumed = new WeakReference<>(null);

    private WalletPassRegistrationCompat() { }

    static boolean hasRegistration(Context context, ClassLoader loader) {
        try {
            return saved(context, loader) != null;
        } catch (Exception error) {
            return false;
        }
    }

    static void install(Context context, ClassLoader loader) throws Exception {
        Class<?> provider = Class.forName(WALLET + ".swalletsdk.provider.SWalletSdkProvider", false, loader);
        XposedBridge.hookMethod(provider.getDeclaredMethod("query", Uri.class, String[].class,
                String.class, String[].class, String.class), new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam call) {
                if (call.hasThrowable() || !(call.getResult() instanceof Cursor)) return;
                ContentProvider sdk = (ContentProvider) call.thisObject;
                try {
                    if (!PASS.equals(sdk.getCallingPackage()) || !(call.args[0] instanceof Uri)) return;
                    JSONObject registration = saved(context, loader);
                    if (registration == null) return;
                    String value;
                    String path = ((Uri) call.args[0]).getLastPathSegment();
                    if ("country_iso_code".equals(path)) value = "HK";
                    else if ("master_id".equals(path)) value = registration.getString("masterId");
                    else if ("device_udid".equals(path)) value = registration.getString("deviceId");
                    else if ("get_dmid".equals(path)) value = registration.getString("deviceMasterId");
                    else if ("get_auth_token".equals(path)) value = registration.getString("accessToken");
                    else if ("service_type_country".equals(path)) value = "1";
                    else return;
                    MatrixCursor replacement = new MatrixCursor(new String[]{"value"});
                    replacement.addRow(new Object[]{value});
                    ((Cursor) call.getResult()).close();
                    call.setResult(replacement);
                } catch (Throwable error) {
                    Log.i(TAG, "Pass registration read unavailable: " + error.getClass().getSimpleName());
                }
            }
        });
        XposedBridge.hookAllMethods(Instrumentation.class, "callActivityOnResume", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam call) {
                if (call.args[0] instanceof Activity) {
                    Activity activity = (Activity) call.args[0];
                    if (WALLET.equals(activity.getPackageName())) resumed = new WeakReference<>(activity);
                    if (WALLET.equals(activity.getPackageName())) WalletPassConnectFlow.onWalletResumed(activity);
                }
            }
        });
        XposedBridge.hookAllMethods(Instrumentation.class, "execStartActivity", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam call) {
                Intent migration = null;
                for (Object argument : call.args) {
                    if (argument instanceof Intent) migration = (Intent) argument;
                }
                if (migration == null || migration.getData() == null
                        || !"walletpass".equals(migration.getData().getScheme())
                        || !("migration".equals(migration.getData().getHost())
                        || "migrationlinkonly".equals(migration.getData().getHost()))) return;
                ComponentName destination = migration.resolveActivity(context.getPackageManager());
                if (destination == null || !PASS.equals(destination.getPackageName())) return;
                if (migration.getBooleanExtra("toolbox_prepared_connect", false)) return;
                String caller = migration.getData().getQueryParameter("migration_caller");
                if (caller == null) caller = migration.getStringExtra("migration_caller");
                // Native onboarding caller 16 attempts a silent link before Pass setup/restore.
                // Report cancellation honestly and let the explicit card entry prepare Pass.
                if ("16".equals(caller)) {
                    call.setResult(((Method) call.method).getReturnType() == Instrumentation.ActivityResult.class
                            ? new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null) : null);
                    Log.i(TAG, "early background Pass link deferred to prepared card entry");
                    return;
                }
                Activity host = null;
                for (Object argument : call.args) {
                    if (argument instanceof Activity) host = (Activity) argument;
                }
                if (host == null) host = resumed.get();
                if (host == null || host.isFinishing() || host.isDestroyed()
                        || !WALLET.equals(host.getPackageName())) return;
                Activity activity = host;
                call.setResult(null);
                WalletPassConnectFlow.start(activity, new Intent(migration));
            }
        });
        Log.i(TAG, "independent registration bridge ready");
    }

    static void ensureRegistration(Activity activity) throws Exception {
        String stage = "account";
        try {
            Context context = activity.getApplicationContext();
            ClassLoader loader = activity.getClassLoader();
            if (saved(context, loader) != null) return;
            String account = accountKey(context, loader);
            stage = "discovery";
            String host = discoverHongKongHost();
            stage = "payload";
            JSONObject body = signupBody(context, loader);
            String device = body.getString("deviceId");
            if (device.isEmpty()) throw new RegistrationFailure("钱包设备标识尚未就绪");
            stage = "headers";
            Map<String, String> headers = signupHeaders(loader, host);
            String success = (String) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("com.xshield.dc", loader), "ȓʎ̍Ȍ", 1063566089);
            String signInPath = (String) XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("com.xshield.dc", loader), "ɔƍȒ͏", 724231733);
            if (!signInPath.matches("common/v[0-9.]+/users/[a-z-]+")) {
                throw new RegistrationFailure("无法识别三星原生登录接口");
            }
            stage = "signup";
            JSONObject response = new JSONObject(request(new URL("https", host,
                    "/common/v2.0/users/signup"), headers, body.toString()));
            if (!success.equals(response.optString("resultCode"))) {
                throw new RegistrationFailure("注册返回 " + publicCode(response.optString("resultCode")));
            }
            String master = response.optString("masterId");
            String deviceMaster = response.optString("deviceMasterId");
            if (master.isEmpty() || deviceMaster.isEmpty()) throw new RegistrationFailure("注册响应缺少设备记录");
            Log.i(TAG, "independent HK signup accepted");
            headers.put("x-smps-mid", master);
            headers.put("x-smps-dmid", deviceMaster);
            stage = "signin";
            JSONObject signIn = new JSONObject(request(new URL("https", host, "/" + signInPath),
                    headers, signInBody(body).toString()));
            if (!success.equals(signIn.optString("resultCode")) || signIn.optString("accessToken").isEmpty()) {
                throw new RegistrationFailure("钱包登录返回 " + publicCode(signIn.optString("resultCode")));
            }
            String signedDevice = signIn.optString("deviceMasterId");
            if (!signedDevice.isEmpty() && !deviceMaster.equals(signedDevice)) {
                throw new RegistrationFailure("钱包登录返回的设备记录不一致");
            }
            if (!account.equals(accountKey(context, loader))) throw new RegistrationFailure("账号状态已变化，请重新打开钱包");
            stage = "storage";
            JSONObject registration = new JSONObject().put("account", account).put("deviceId", device)
                    .put("masterId", master).put("deviceMasterId", deviceMaster)
                    .put("accessToken", signIn.getString("accessToken"));
            if (!context.getSharedPreferences(STORE, Context.MODE_PRIVATE).edit()
                    .putString("registration", encrypt(registration.toString())).commit()) {
                throw new RegistrationFailure("无法保存 Pass 注册结果");
            }
            Log.i(TAG, "independent HK registration accepted");
        } catch (RegistrationFailure error) {
            Log.i(TAG, "registration failed stage=" + stage);
            throw error;
        } catch (Throwable error) {
            Log.i(TAG, "registration unavailable stage=" + stage + " type=" + error.getClass().getSimpleName());
            throw new RegistrationFailure("注册未完成（" + error.getClass().getSimpleName() + "）");
        }
    }

    private static JSONObject saved(Context context, ClassLoader loader) throws Exception {
        SharedPreferences preferences = context.getSharedPreferences(STORE, Context.MODE_PRIVATE);
        String stored = preferences.getString("registration", null);
        if (stored == null) return null;
        JSONObject registration = new JSONObject(decrypt(stored));
        String device = (String) nativeCall(loader, "common.util.DeviceIdUtil", "d");
        if (!accountKey(context, loader).equals(registration.optString("account"))
                || device == null || !device.equals(registration.optString("deviceId"))
                || registration.optString("masterId").isEmpty()
                || registration.optString("deviceMasterId").isEmpty()
                || registration.optString("accessToken").isEmpty()) return null;
        return registration;
    }

    static String registrationIdentity(Context context) throws Exception {
        JSONObject registration = saved(context, context.getClassLoader());
        return registration == null ? null : registration.getString("account");
    }

    private static JSONObject signupBody(Context context, ClassLoader loader) throws Exception {
        JSONObject body = new JSONObject();
        String token = accountValue(context, loader, "access_token");
        String user = accountValue(context, loader, "sa_user_id");
        if (token == null || token.isEmpty() || user == null || user.isEmpty()) {
            throw new RegistrationFailure("请先完成国行钱包登录");
        }
        Object helper = nativeCall(loader, "common.samsungaccount.SamsungAccountHelper", "getInstance");
        body.put("accessToken", token);
        body.put("guid", nativeCall(loader, "common.util.RSA", "c", user));
        body.put("saDevicePhysicalAddressText", accountValue(context, loader, "samsung_account_physical_address_text"));
        body.put("saUrl", accountValue(context, loader, "samsung_account_server_url"));
        body.put("saAppId", XposedHelpers.callMethod(helper, "getAppId"));
        nativeCall(loader, "common.util.DeviceIdUtil", "c");
        body.put("deviceId", nativeCall(loader, "common.util.DeviceIdUtil", "d"));
        body.put("deviceModelName", android.os.Build.MODEL);
        Object payload = XposedHelpers.newInstance(XposedHelpers.findClass(
                WALLET + ".common.provisioning.data.WalletSignUpJsReq", loader));
        body.put("appInstallType", XposedHelpers.getObjectField(payload, "appInstallType"));
        body.put("termsServiceType", nativeCall(loader, "common.provisioning.ProvUtil", "g"));
        String general = (String) XposedHelpers.callStaticMethod(XposedHelpers.findClass("com.xshield.dc", loader),
                "ɔƍȒ͏", 728637837);
        body.put("checkTerms", new JSONArray().put(new JSONObject().put("termsCode", general)));
        body.put("entryTermsType", general);
        Object certificate = nativeCall(loader, "provisioning.wallet.AbstractWalletProvInterfaceImpl", "d");
        if (certificate == null) throw new RegistrationFailure("设备注册证书尚未就绪");
        body.put("chainType", XposedHelpers.getObjectField(certificate, "a"));
        body.put("chain1", XposedHelpers.getObjectField(certificate, "b"));
        body.put("chain2", XposedHelpers.getObjectField(certificate, "c"));
        boolean child = (boolean) XposedHelpers.callMethod(helper, "isChildAccount", context);
        if (child) throw new RegistrationFailure("儿童账号需要通过三星原生监护人确认流程");
        body.put("parentalCare", new JSONObject().put("isSupported",
                XposedHelpers.callMethod(helper, "isParentalCareSupport", context)).put("isChild", false));
        String birthday = (String) nativeCall(loader, "common.util.pref.SamsungAccountPref", "c", context);
        String country = accountValue(context, loader, "samsung_account_cc");
        body.put("saUserInfo", new JSONObject().put("birthday", birthday).put("cc3", country));
        body.put("needUserType", birthday != null && !birthday.isEmpty());
        String verifiedBirthday = (String) nativeCall(loader, "common.idnv.util.IdnvCommonUtil", "f", context);
        if (verifiedBirthday == null || verifiedBirthday.isEmpty()) verifiedBirthday = birthday;
        if (verifiedBirthday != null && !verifiedBirthday.isEmpty()) {
            boolean full = (boolean) nativeCall(loader, "common.provisioning.WalletUserTypeFeatureUtil",
                    "isActivateUserTypeByServer");
            body.put("childAccountSupportType", full ? "FULL_FEATURE" : "PARTIAL_FEATURE");
            body.put("idnvUserInfo", new JSONObject().put("birthday", verifiedBirthday));
        }
        return body;
    }

    private static Map<String, String> signupHeaders(ClassLoader loader, String host) {
        Object request = XposedHelpers.newInstance(XposedHelpers.findClass(
                WALLET + ".common.volleyhelper.CMNRequest", loader), 1,
                "https://" + host + "/common/v2.0/users/signup", null, false);
        @SuppressWarnings("unchecked")
        Map<String, String> original = (Map<String, String>) XposedHelpers.callMethod(request, "getHeaders");
        Map<String, String> headers = new HashMap<>(original);
        headers.keySet().removeIf(name -> name.equalsIgnoreCase("Authorization")
                || name.equalsIgnoreCase("x-smps-mid") || name.equalsIgnoreCase("x-smps-dmid"));
        headers.put("x-smps-cc2", "HK");
        return headers;
    }

    private static JSONObject signInBody(JSONObject signup) throws Exception {
        JSONObject body = new JSONObject();
        String[] fields = {"accessToken", "guid", "saUrl", "saAppId", "parentalCare", "saUserInfo",
                "termsServiceType", "checkTerms", "entryTermsType", "chainType", "chain1", "chain2",
                "childAccountSupportType", "idnvUserInfo"};
        for (String field : fields) {
            if (signup.has(field)) body.put(field, signup.get(field));
        }
        body.put("isSkipTerms", "N");
        return body;
    }

    private static String discoverHongKongHost() throws Exception {
        String discovery = request(new URL("https://gld.push.samsungosp.com/gld/sPay?iso=HK"),
                new HashMap<>(), null);
        Map<String, String> parts = new HashMap<>();
        for (String part : discovery.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2) parts.put(pair[0], URLDecoder.decode(pair[1], "UTF-8"));
        }
        if (!"1000".equals(parts.get("resultcode")) || !parts.containsKey("serverUrl")) {
            throw new RegistrationFailure("三星钱包服务地址查询失败");
        }
        String host = new JSONObject(parts.get("serverUrl")).getJSONObject("PRD")
                .getJSONObject("Pay").getJSONObject("pri").getString("url");
        if (!host.matches("api-[a-z0-9-]+\\.mpay\\.samsung\\.com")) {
            throw new RegistrationFailure("三星钱包服务地址无法识别");
        }
        return host;
    }

    private static String request(URL url, Map<String, String> headers, String body) throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Accept-Encoding", "identity");
        try {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getValue() != null) connection.setRequestProperty(entry.getKey(), entry.getValue());
            }
            if (body != null) {
                byte[] data = body.getBytes(StandardCharsets.UTF_8);
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json;charset=utf-8");
                connection.setFixedLengthStreamingMode(data.length);
                try (OutputStream stream = connection.getOutputStream()) { stream.write(data); }
            }
            int status = connection.getResponseCode();
            InputStream response = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String text = "";
            if (response != null) {
                try (InputStream stream = response; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = stream.read(buffer)) != -1) {
                        if (output.size() + read > 1024 * 1024) throw new RegistrationFailure("注册响应过大");
                        output.write(buffer, 0, read);
                    }
                    text = output.toString("UTF-8");
                }
            }
            if (status < 200 || status >= 300) {
                String code = "";
                try { code = publicCode(new JSONObject(text).optString("resultCode")); }
                catch (org.json.JSONException ignored) { }
                throw new RegistrationFailure("注册 HTTP " + status + (code.isEmpty() ? "" : " / " + code));
            }
            return text;
        } finally {
            connection.disconnect();
        }
    }

    private static String accountValue(Context context, ClassLoader loader, String key) {
        return (String) nativeCall(loader, "common.util.pref.SamsungAccountPref", "b", context, key);
    }

    private static String accountKey(Context context, ClassLoader loader) throws Exception {
        String user = accountValue(context, loader, "sa_user_id");
        if (user == null || user.isEmpty()) throw new RegistrationFailure("请先登录三星钱包");
        return Base64.encodeToString(MessageDigest.getInstance("SHA-256")
                .digest(user.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }

    private static Object nativeCall(ClassLoader loader, String type, String method, Object... args) {
        return XposedHelpers.callStaticMethod(XposedHelpers.findClass(WALLET + "." + type, loader), method, args);
    }

    private static String publicCode(String code) {
        return code.matches("[A-Za-z0-9_.-]{1,64}") ? code : "";
    }

    private static SecretKey storageKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        String alias = "oneui.toolbox.pass.registration";
        if (store.containsAlias(alias)) return (SecretKey) store.getKey(alias, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }

    static String encrypt(String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, storageKey());
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(ByteBuffer.allocate(cipher.getIV().length + encrypted.length)
                .put(cipher.getIV()).put(encrypted).array(), Base64.NO_WRAP);
    }

    static String decrypt(String value) throws Exception {
        byte[] data = Base64.decode(value, Base64.NO_WRAP);
        if (data.length < 28) throw new RegistrationFailure("Pass 注册缓存无效");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, storageKey(), new GCMParameterSpec(128, data, 0, 12));
        return new String(cipher.doFinal(data, 12, data.length - 12), StandardCharsets.UTF_8);
    }

    private static final class RegistrationFailure extends Exception {
        RegistrationFailure(String message) { super(message); }
    }
}
