import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dhizuku app_process 多功能客户端。
 *
 * 设计要点：
 *  1. 不硬编码 IDevicePolicyManager 的 transaction code：
 *     用设备自身的 IDevicePolicyManager$Stub.asInterface(wrapped) 生成代理，
 *     再构造 DevicePolicyManager(Context, IDevicePolicyManager) 实例，
 *     所有调用由设备自己的框架类完成 marshalling —— 自动适配 Android 9~15。
 *  2. Context 用 com.rosan.dhizuku 的 LoadedApk 构造，
 *     使 getOpPackageName()=com.rosan.dhizuku，与 Dhizuku 中继后的调用者 uid 匹配，
 *     通过 DPM 的 getCallerIdentity 校验。
 *  3. 权限申请走 IActivityTaskManager.startActivityAndWait（Android 10+，11/12 参数自适应），
 *     Android 9 回退 IActivityManager；解锁后自动点击“同意”。
 *  4. 一个 dex 包含全部命令，见 help。
 */
public class DhizukuAppProcessClient {
    private static final String TAG = "DhizukuClient";
    private static final String SHELL_PKG = "com.android.shell";
    private static final String DHIZUKU_PKG = "com.rosan.dhizuku";
    private static final String DO_CLS = "com.rosan.dhizuku.server.DhizukuDAReceiver";
    private static final String AUTHORITY = "com.rosan.dhizuku.server.provider";
    private static final String LISTENER_DESCRIPTOR = "com.rosan.dhizuku.aidl.IDhizukuRequestPermissionListener";
    private static final String UI_DUMP = "/data/local/tmp/perm_ui.xml";

    // 强引用，防止权限回调 Binder 被 GC 提前回收
    private static IBinder sPermissionListener;

    public static void main(String[] args) {
        String cmd = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if ("help".equals(cmd) || "-h".equals(cmd) || "--help".equals(cmd)) {
            printHelp();
            return;
        }
        writePidFile();
        try {
            prepareLooper();
            Context ctx = acquireDhizukuContext();
            Class<?> dh = Class.forName("com.rosan.dhizuku.api.Dhizuku");
            IBinder dhizukuBinder = fetchDhizukuBinder(ctx);
            if (dhizukuBinder == null) {
                fail(3, "无法获取 Dhizuku binder（未安装/Dhizuku 进程不可用）");
            }
            ComponentName owner = null;
            try {
                owner = (ComponentName) dh.getMethod("getOwnerComponent", Context.class).invoke(null, ctx);
            } catch (Throwable t) {
                log("getOwnerComponent failed: " + t);
            }
            injectRemote(dh, dhizukuBinder, ctx, owner);
            log("Dhizuku API " + dh.getMethod("getVersionName").invoke(null)
                    + " (" + dh.getMethod("getVersionCode").invoke(null) + ")"
                    + ", sdk=" + Build.VERSION.SDK_INT + ", owner=" + owner);

            if ("status".equals(cmd)) {
                printStatus(dh, owner);
                return;
            }
            if ("perm".equals(cmd)) {
                ensurePermission(dh, ctx);
                return;
            }

            ensurePermission(dh, ctx);

            IBinder dpmBinder = getSystemServiceBinder("device_policy");
            if (dpmBinder == null) fail(3, "device_policy 服务不存在");
            IBinder wrapped = (IBinder) dh.getMethod("binderWrapper", IBinder.class).invoke(null, dpmBinder);
            if (wrapped == null) fail(3, "Dhizuku.binderWrapper 返回 null");
            DevicePolicyManager dpm = makeDpm(ctx, wrapped);
            ComponentName admin = new ComponentName(DHIZUKU_PKG, DO_CLS);
            log("DPM proxy ready (descriptor=" + wrapped.getInterfaceDescriptor() + ")");

            dispatch(cmd, args, dpm, admin);
            System.out.println("[OK] " + cmd + " done");
        } catch (Throwable t) {
            Throwable c = rootCause(t);
            logErr("Fatal: " + c);
            c.printStackTrace();
            System.exit(5);
        }
    }

    // ======================= 基础输出/工具 =======================

    private static void printHelp() {
        System.out.println("Dhizuku app_process 多功能客户端 (DeviceOwner)");
        System.out.println();
        System.out.println("用法: app_process -Djava.class.path=... /system/bin DhizukuAppProcessClient <命令> [参数]");
        System.out.println();
        System.out.println("基础:");
        System.out.println("  status                         显示 SDK / Dhizuku / 权限 / DO 状态");
        System.out.println("  perm                           申请 Dhizuku 权限（必须先解锁屏幕）");
        System.out.println("  help                           显示本帮助");
        System.out.println();
        System.out.println("系统策略:");
        System.out.println("  camera on|off                  相机策略 (on=允许)");
        System.out.println("  screenshot on|off              截屏策略 (on=允许)");
        System.out.println("  statusbar on|off               状态栏 (on=启用)");
        System.out.println("  keyguard on|off                锁屏 (on=启用; 部分 OEM 拒绝关闭)");
        System.out.println("  secure <key> <value>           setSecureSetting（DO 白名单: default_input_method/skip_first_use_hints/location_mode）");
        System.out.println("  global <key> <value>           setGlobalSetting");
        System.out.println("  system <key> <value>           setSystemSetting (Android 9+)");
        System.out.println("  max-time-lock <ms>             最大自动锁屏时间 (0=恢复默认)");
        System.out.println("  password-quality <0-6>         密码质量 (0=不限制)");
        System.out.println("  password-min-length <n>        密码最小长度 (0=不限制; 自动临时提升 quality 至 NUMERIC)");
        System.out.println("  proxy <host:port|none> [excl]  全局 HTTP 代理");
        System.out.println("  locktask <pkg1,pkg2|none>      锁定任务白名单");
        System.out.println();
        System.out.println("用户策略:");
        System.out.println("  restrict <key> on|off          add/clearUserRestriction");
        System.out.println("  restrictions                   列出当前用户限制");
        System.out.println("  user-icon <设备上的png路径>    设置用户头像");
        System.out.println();
        System.out.println("应用管理:");
        System.out.println("  hide <pkg> on|off              setApplicationHidden");
        System.out.println("  suspend <pkg> on|off           setPackagesSuspended");
        System.out.println("  block-uninstall <pkg> on|off   setUninstallBlocked");
        System.out.println("  uninstall <pkg>                解除阻止并卸载 (pm uninstall --user 0; 有活跃 admin 的包不支持)");
        System.out.println("  app-status <pkg>               隐藏/挂起/阻止卸载状态");
        System.out.println("  grant-permission <pkg> <perm> <grant|deny|default>  (CAMERA/MIC 等传感器权限普通 DO 无法 grant，自动回退 pm)");
        System.out.println();
        System.out.println("示例:");
        System.out.println("  ... DhizukuAppProcessClient camera off");
        System.out.println("  ... DhizukuAppProcessClient hide com.example.app on");
        System.out.println("  ... DhizukuAppProcessClient restrict no_add_user on");
    }

    private static void log(String msg) {
        System.out.println("[DhizukuClient] " + msg);
        try { Log.i(TAG, msg); } catch (Throwable ignored) {}
    }

    private static void logErr(String msg) {
        System.err.println("[DhizukuClient][ERROR] " + msg);
        try { Log.e(TAG, msg); } catch (Throwable ignored) {}
    }

    private static void ok(String msg) {
        System.out.println("[OK] " + msg);
    }

    private static void fail(int code, String msg) {
        logErr(msg);
        System.exit(code);
    }

    private static Throwable rootCause(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        return c;
    }

    private static void writePidFile() {
        try {
            java.io.FileOutputStream fos = new java.io.FileOutputStream("/data/local/tmp/dhizuku_client.pid");
            fos.write(String.valueOf(android.os.Process.myPid()).getBytes());
            fos.close();
        } catch (Throwable ignored) {}
    }

    private static void prepareLooper() {
        try {
            Looper.prepareMainLooper();
            log("Looper.prepareMainLooper() OK");
        } catch (Throwable t) {
            try { Looper.prepare(); } catch (Throwable ignored) {}
        }
    }

    private static String arg(String[] a, int i) {
        return i < a.length ? a[i] : null;
    }

    private static String need(String[] a, int i, String usage) {
        String v = arg(a, i);
        if (v == null || v.isEmpty()) fail(1, "参数缺失: " + usage);
        return v;
    }

    private static boolean parseOnOff(String s) {
        if (s == null) fail(1, "缺少 on|off");
        String v = s.toLowerCase(Locale.ROOT);
        if ("on".equals(v) || "true".equals(v) || "enable".equals(v) || "enabled".equals(v) || "1".equals(v)) return true;
        if ("off".equals(v) || "false".equals(v) || "disable".equals(v) || "disabled".equals(v) || "0".equals(v)) return false;
        fail(1, "无法识别的开关值: " + s + " (用 on/off)");
        return false;
    }

    private static class R {
        int rc;
        String out;
        R(int rc, String out) { this.rc = rc; this.out = out; }
    }

    private static R exec(String cmd) {
        try {
            java.lang.Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", cmd});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            int rc = p.waitFor();
            return new R(rc, sb.toString().trim());
        } catch (Throwable t) {
            return new R(-1, "exec failed: " + t);
        }
    }

    private static String readFile(String path) {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static IBinder getSystemServiceBinder(String name) throws Exception {
        return (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, name);
    }

    private static IBinder getServiceOrNull(String name) {
        try {
            return getSystemServiceBinder(name);
        } catch (Throwable t) {
            return null;
        }
    }

    // ======================= Context / Dhizuku 初始化 =======================

    private static Context acquireDhizukuContext() throws Exception {
        Class<?> atClass = Class.forName("android.app.ActivityThread");
        Object at = atClass.getMethod("systemMain").invoke(null);
        Context sysCtx = (Context) atClass.getMethod("getSystemContext").invoke(at);

        // 用 Dhizuku 包的 LoadedApk：getOpPackageName() 必须是 com.rosan.dhizuku，
        // 因为真正到达 system_server 的 uid 是 Dhizuku（中继者）的 uid。
        ApplicationInfo ai = sysCtx.getPackageManager().getApplicationInfo(DHIZUKU_PKG, 0);
        Class<?> compatClass = Class.forName("android.content.res.CompatibilityInfo");
        Object defaultCompat = compatClass.getField("DEFAULT_COMPATIBILITY_INFO").get(null);
        Method getPkgInfoNoCheck = atClass.getDeclaredMethod("getPackageInfoNoCheck",
                ApplicationInfo.class, compatClass);
        getPkgInfoNoCheck.setAccessible(true);
        Object loadedApk = getPkgInfoNoCheck.invoke(at, ai, defaultCompat);

        Class<?> contextImplClass = Class.forName("android.app.ContextImpl");
        Class<?> loadedApkClass = Class.forName("android.app.LoadedApk");
        Method createAppContext = contextImplClass.getDeclaredMethod("createAppContext",
                atClass, loadedApkClass);
        createAppContext.setAccessible(true);
        Context ctx = (Context) createAppContext.invoke(null, at, loadedApk);
        log("context pkg=" + ctx.getPackageName() + " opPkg=" + ctx.getOpPackageName()
                + " uid=" + android.os.Process.myUid());
        return ctx;
    }

    private static Object getProviderHolder() throws Exception {
        IBinder amBinder = getSystemServiceBinder("activity");
        if (amBinder == null) throw new IllegalStateException("activity service missing");
        Object am = Class.forName("android.app.IActivityManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, amBinder);
        Class<?> ia = Class.forName("android.app.IActivityManager");

        IBinder token = new Binder();
        Method four = null;
        Method three = null;
        for (Method m : ia.getMethods()) {
            if (!"getContentProviderExternal".equals(m.getName())) continue;
            int n = m.getParameterTypes().length;
            if (n == 4) { four = m; break; }
            if (n == 3) three = m;
        }
        if (four != null) {
            return four.invoke(am, AUTHORITY, 0, token, SHELL_PKG);
        }
        if (three != null) {
            return three.invoke(am, AUTHORITY, 0, token);
        }
        throw new IllegalStateException("getContentProviderExternal not found");
    }

    private static IBinder fetchDhizukuBinder(Context ctx) throws Exception {
        Object holder = getProviderHolder();
        if (holder == null) {
            for (int i = 1; i <= 3 && holder == null; i++) {
                log("provider holder null, unfreeze attempt " + i);
                exec("/system/bin/am start -n " + DHIZUKU_PKG + "/.ui.activity.SettingsActivity");
                Thread.sleep(2000);
                holder = getProviderHolder();
            }
        }
        log("provider holder = " + holder);
        if (holder == null) return null;

        Object provider = holder.getClass().getField("provider").get(holder);
        if (provider == null) return null;

        Bundle extras = new Bundle();
        extras.putBinder("client", obtainClientBinder());

        Object result;
        Method callAttribution = null;
        Method callString = null;
        for (Method m : provider.getClass().getMethods()) {
            if (!"call".equals(m.getName())) continue;
            Class<?>[] pt = m.getParameterTypes();
            if (pt.length != 5) continue;
            if ("android.content.AttributionSource".equals(pt[0].getName())) callAttribution = m;
            else if (pt[0] == String.class) callString = m;
        }
        if (callAttribution != null) {
            Object attribution = ctx.getClass().getMethod("getAttributionSource").invoke(ctx);
            result = callAttribution.invoke(provider, attribution, AUTHORITY, "client", null, extras);
        } else if (callString != null) {
            result = callString.invoke(provider, SHELL_PKG, AUTHORITY, "client", null, extras);
        } else {
            throw new IllegalStateException("IContentProvider.call not found");
        }
        Bundle bundle = (Bundle) result;
        return bundle == null ? null : bundle.getBinder("dhizuku_binder");
    }

    private static IBinder obtainClientBinder() throws Exception {
        Class<?> dh = Class.forName("com.rosan.dhizuku.api.Dhizuku");
        for (Field f : dh.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) continue;
            if (!IBinder.class.isAssignableFrom(f.getType())) continue;
            f.setAccessible(true);
            Object v = f.get(null);
            if (v instanceof IBinder) return (IBinder) v;
        }
        Class<?> dc = Class.forName("com.rosan.dhizuku.api.DhizukuClient");
        Object inst = dc.getDeclaredConstructor().newInstance();
        return (IBinder) inst;
    }

    private static void injectRemote(Class<?> dh, IBinder binder, Context ctx, ComponentName owner) throws Exception {
        Object remote = Class.forName("com.rosan.dhizuku.aidl.IDhizuku$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
        boolean injected = false;
        for (Field f : dh.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) continue;
            f.setAccessible(true);
            if ("com.rosan.dhizuku.aidl.IDhizuku".equals(f.getType().getName())) {
                f.set(null, remote);
                injected = true;
            } else if (f.getType() == Context.class && f.get(null) == null) {
                f.set(null, ctx);
            } else if (f.getType() == ComponentName.class && owner != null) {
                f.set(null, owner);
            }
        }
        if (!injected) throw new IllegalStateException("Dhizuku.remote field not found");
        log("Dhizuku.remote injected");
    }

    private static void printStatus(Class<?> dh, ComponentName owner) throws Exception {
        System.out.println("sdk=" + Build.VERSION.SDK_INT + " (" + Build.VERSION.RELEASE + ")");
        System.out.println("device=" + Build.MANUFACTURER + " " + Build.MODEL);
        System.out.println("process.uid=" + android.os.Process.myUid());
        System.out.println("dhizuku.versionCode=" + dh.getMethod("getVersionCode").invoke(null));
        System.out.println("dhizuku.versionName=" + dh.getMethod("getVersionName").invoke(null));
        System.out.println("dhizuku.owner=" + owner);
        System.out.println("permission.granted=" + dh.getMethod("isPermissionGranted").invoke(null));
    }

    // ======================= 权限申请 =======================

    private static void ensurePermission(Class<?> dh, Context ctx) throws Exception {
        Method isPerm = dh.getMethod("isPermissionGranted");
        if (Boolean.TRUE.equals(isPerm.invoke(null))) {
            log("permission granted");
            return;
        }
        if (isKeyguardLocked(ctx)) {
            fail(2, "设备处于锁屏状态：Dhizuku 授权弹窗会被立即拒绝，请先解锁屏幕再重试");
        }
        log("permission not granted, requesting...");
        for (int i = 1; i <= 5; i++) {
            log("--- permission attempt " + i + " ---");
            launchPermissionActivity();
            if (waitForPermission(isPerm, 20)) {
                log("permission granted");
                return;
            }
            log("attempt " + i + " failed");
        }
        fail(2, "权限未授予：请解锁屏幕后重试，或手动打开 Dhizuku 应用授权 " + SHELL_PKG);
    }

    private static boolean isKeyguardLocked(Context ctx) {
        try {
            Object km = ctx.getSystemService(Context.KEYGUARD_SERVICE);
            if (km == null) return false;
            return Boolean.TRUE.equals(km.getClass().getMethod("isKeyguardLocked").invoke(km));
        } catch (Throwable t) {
            log("keyguard check failed: " + t);
            return false;
        }
    }

    private static void launchPermissionActivity() {
        try {
            Intent intent = buildPermissionIntent();
            IBinder atmsBinder = getServiceOrNull("activity_task");
            if (atmsBinder != null) {
                Object atms = Class.forName("android.app.IActivityTaskManager$Stub")
                        .getMethod("asInterface", IBinder.class).invoke(null, atmsBinder);
                Class<?> iface = Class.forName("android.app.IActivityTaskManager");
                Method m12 = findStartActivityAndWait(iface, 12);
                Method m11 = findStartActivityAndWait(iface, 11);
                Object res;
                if (m12 != null) {
                    res = m12.invoke(atms, null, SHELL_PKG, null, intent, null, null, null,
                            0, 0, null, null, 0);
                } else if (m11 != null) {
                    res = m11.invoke(atms, null, SHELL_PKG, intent, null, null, null,
                            0, 0, null, null, 0);
                } else {
                    logErr("startActivityAndWait not found on IActivityTaskManager");
                    return;
                }
                logLaunchResult(res);
            } else {
                // Android 9: ActivityTaskManager 尚未从 ActivityManager 拆分
                IBinder amBinder = getSystemServiceBinder("activity");
                Object am = Class.forName("android.app.IActivityManager$Stub")
                        .getMethod("asInterface", IBinder.class).invoke(null, amBinder);
                Class<?> iface = Class.forName("android.app.IActivityManager");
                Method m11 = findStartActivityAndWait(iface, 11);
                if (m11 == null) {
                    logErr("startActivityAndWait not found on IActivityManager");
                    return;
                }
                Object res = m11.invoke(am, null, SHELL_PKG, intent, null, null, null,
                        0, 0, null, null, 0);
                logLaunchResult(res);
            }
            // 抢在 15 秒自动拒绝之前点击“同意”
            for (int i = 0; i < 5; i++) {
                Thread.sleep(600);
                if (tapGrantIfVisible()) return;
            }
        } catch (Throwable t) {
            logErr("launchPermissionActivity failed: " + rootCause(t));
        }
    }

    private static Method findStartActivityAndWait(Class<?> iface, int paramCount) {
        for (Method m : iface.getMethods()) {
            if (!"startActivityAndWait".equals(m.getName())) continue;
            Class<?>[] pt = m.getParameterTypes();
            if (pt.length != paramCount) continue;
            // 12 参：第 3 个参数是 callingFeatureId(String)；11 参：第 3 个是 Intent
            if (paramCount == 12 && pt[2] == String.class) return m;
            if (paramCount == 11 && pt[2] == Intent.class) return m;
        }
        return null;
    }

    private static void logLaunchResult(Object res) {
        int launchResult = -1;
        if (res != null) {
            try { launchResult = res.getClass().getField("result").getInt(res); } catch (Throwable ignored) {}
        }
        log("startActivityAndWait -> result=" + launchResult);
    }

    private static Intent buildPermissionIntent() {
        IBinder listener = new Binder() {
            @Override
            protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                if (code == 1) {
                    data.enforceInterface(LISTENER_DESCRIPTOR);
                    int grantResult = data.readInt();
                    log("permission callback grantResult=" + grantResult);
                }
                return true;
            }
        };
        sPermissionListener = listener;

        Bundle bundle = new Bundle();
        bundle.putInt("uid", android.os.Process.myUid());
        bundle.putBinder("request_permission_binder", listener);

        return new Intent("com.rosan.dhizuku.action.request.permission")
                .setPackage(DHIZUKU_PKG)
                .putExtras(bundle)
                .putExtra("bundle", bundle)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private static boolean tapGrantIfVisible() {
        try {
            exec("/system/bin/uiautomator dump " + UI_DUMP);
            String xml = readFile(UI_DUMP);
            if (xml == null || xml.isEmpty()) return false;
            String[] nodes = xml.split("<node ");
            for (String n : nodes) {
                Matcher tm = Pattern.compile("text=\"([^\"]*)\"").matcher(n);
                if (!tm.find()) continue;
                String text = tm.group(1);
                if (!"同意".equals(text) && !"Grant".equals(text) && !"允许".equals(text)
                        && !"Agree".equals(text) && !"Allow".equals(text)) continue;
                Matcher bm = Pattern.compile("bounds=\"\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]\"").matcher(n);
                if (!bm.find()) continue;
                int cx = (Integer.parseInt(bm.group(1)) + Integer.parseInt(bm.group(3))) / 2;
                int cy = (Integer.parseInt(bm.group(2)) + Integer.parseInt(bm.group(4))) / 2;
                log("tap grant button '" + text + "' at " + cx + "," + cy);
                exec("/system/bin/input tap " + cx + " " + cy);
                return true;
            }
        } catch (Throwable t) {
            log("tapGrantIfVisible failed: " + t);
        }
        return false;
    }

    private static boolean waitForPermission(Method isPerm, int seconds) {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (Boolean.TRUE.equals(isPerm.invoke(null))) return true;
            } catch (Throwable ignored) {}
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return false;
    }

    // ======================= DPM 代理 =======================

    private static DevicePolicyManager makeDpm(Context ctx, IBinder wrapped) throws Exception {
        Object proxy = Class.forName("android.app.admin.IDevicePolicyManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, wrapped);
        Class<?> dpmClass = Class.forName("android.app.admin.DevicePolicyManager");
        Class<?> iface = Class.forName("android.app.admin.IDevicePolicyManager");
        try {
            Constructor<?> ctor = dpmClass.getDeclaredConstructor(Context.class, iface);
            ctor.setAccessible(true);
            DevicePolicyManager dpm = (DevicePolicyManager) ctor.newInstance(ctx, proxy);
            log("DPM instance via (Context, IDevicePolicyManager) ctor");
            return dpm;
        } catch (Throwable t) {
            log("DPM ctor failed (" + rootCause(t) + "), fallback to mService swap");
            DevicePolicyManager sys = (DevicePolicyManager) ctx.getSystemService(Context.DEVICE_POLICY_SERVICE);
            Field f = dpmClass.getDeclaredField("mService");
            f.setAccessible(true);
            f.set(sys, proxy);
            return sys;
        }
    }

    private static Object invokeHidden(DevicePolicyManager dpm, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = dpm.getClass().getMethod(name, types);
        return m.invoke(dpm, args);
    }

    private static final Object TIMEOUT = new Object();

    private interface Callable0 {
        Object call() throws Exception;
    }

    private static Object callWithTimeout(Callable0 c, int seconds) throws Exception {
        FutureTask<Object> task = new FutureTask<Object>(new java.util.concurrent.Callable<Object>() {
            @Override
            public Object call() {
                try {
                    return c.call();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });
        Thread t = new Thread(task, "dpm-call");
        t.setDaemon(true);
        t.start();
        try {
            return task.get(seconds, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            return TIMEOUT;
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            if (cause instanceof RuntimeException && cause.getCause() instanceof Exception) {
                throw (Exception) cause.getCause();
            }
            throw ee;
        }
    }

    private static boolean isSecureDoAllowed(String key) {
        return "default_input_method".equals(key)
                || "skip_first_use_hints".equals(key)
                || "location_mode".equals(key);
    }

    // ======================= 命令分发 =======================

    private static void dispatch(String cmd, String[] a, DevicePolicyManager dpm, ComponentName admin) {
        try {
            switch (cmd) {
                case "camera": {
                    boolean on = parseOnOff(need(a, 1, "camera on|off"));
                    dpm.setCameraDisabled(admin, !on);
                    ok("camera " + (on ? "on" : "off") + " -> getCameraDisabled=" + dpm.getCameraDisabled(admin));
                    break;
                }
                case "screenshot": {
                    boolean on = parseOnOff(need(a, 1, "screenshot on|off"));
                    dpm.setScreenCaptureDisabled(admin, !on);
                    ok("screenshot " + (on ? "on" : "off") + " -> getScreenCaptureDisabled=" + dpm.getScreenCaptureDisabled(admin));
                    break;
                }
                case "statusbar": {
                    boolean on = parseOnOff(need(a, 1, "statusbar on|off"));
                    boolean r = dpm.setStatusBarDisabled(admin, !on);
                    ok("statusbar " + (on ? "on" : "off") + " -> accepted=" + r);
                    break;
                }
                case "keyguard": {
                    boolean on = parseOnOff(need(a, 1, "keyguard on|off"));
                    boolean r = dpm.setKeyguardDisabled(admin, !on);
                    ok("keyguard " + (on ? "on" : "off") + " -> accepted=" + r);
                    break;
                }
                case "secure": {
                    String key = need(a, 1, "secure <key> <value>");
                    String value = need(a, 2, "secure <key> <value>");
                    if (!isSecureDoAllowed(key)) {
                        fail(4, "DPMS 限制：DeviceOwner 只能修改 Secure 白名单设置 "
                                + "(default_input_method / skip_first_use_hints / location_mode)，"
                                + key + " 会被系统拒绝；请改用 global/system 或应用内设置");
                    }
                    dpm.setSecureSetting(admin, key, "null".equals(value) ? null : value);
                    ok("secure " + key + "=" + value);
                    break;
                }
                case "global": {
                    String key = need(a, 1, "global <key> <value>");
                    String value = need(a, 2, "global <key> <value>");
                    dpm.setGlobalSetting(admin, key, "null".equals(value) ? null : value);
                    ok("global " + key + "=" + value);
                    break;
                }
                case "system": {
                    String key = need(a, 1, "system <key> <value>");
                    String value = need(a, 2, "system <key> <value>");
                    dpm.setSystemSetting(admin, key, "null".equals(value) ? null : value);
                    ok("system " + key + "=" + value);
                    break;
                }
                case "max-time-lock": {
                    long ms = Long.parseLong(need(a, 1, "max-time-lock <ms>"));
                    dpm.setMaximumTimeToLock(admin, ms);
                    ok("max-time-lock=" + ms);
                    break;
                }
                case "password-quality": {
                    int q = Integer.parseInt(need(a, 1, "password-quality <0-6>"));
                    dpm.setPasswordQuality(admin, q);
                    ok("password-quality=" + q);
                    break;
                }
                case "password-min-length": {
                    int n = Integer.parseInt(need(a, 1, "password-min-length <n>"));
                    final int NUMERIC = DevicePolicyManager.PASSWORD_QUALITY_NUMERIC;
                    int q = dpm.getPasswordQuality(admin);
                    boolean raised = false;
                    if (q < NUMERIC) {
                        // Android 13+ 的 DPMS 要求 quality >= NUMERIC 才允许设置最小长度
                        dpm.setPasswordQuality(admin, NUMERIC);
                        raised = true;
                        log("auto-raised password-quality to NUMERIC(0x20000), was 0x" + Integer.toHexString(q));
                    }
                    dpm.setPasswordMinimumLength(admin, n);
                    if (raised && n <= 0) {
                        dpm.setPasswordQuality(admin, q);
                        log("restored password-quality to 0x" + Integer.toHexString(q));
                    }
                    ok("password-min-length=" + n + " quality=0x" + Integer.toHexString(dpm.getPasswordQuality(admin)));
                    break;
                }
                case "proxy": {
                    String spec = need(a, 1, "proxy <host:port|none> [exclusion]");
                    String excl = arg(a, 2);
                    List<String> exclusions = excl == null ? null : Arrays.asList(excl.split(","));
                    Proxy proxy;
                    if ("none".equalsIgnoreCase(spec)) {
                        proxy = Proxy.NO_PROXY;
                    } else {
                        int idx = spec.lastIndexOf(':');
                        if (idx <= 0) fail(1, "代理格式应为 host:port");
                        String host = spec.substring(0, idx);
                        int port = Integer.parseInt(spec.substring(idx + 1));
                        proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port));
                    }
                    Object prev = invokeHidden(dpm, "setGlobalProxy",
                            new Class<?>[]{ComponentName.class, Proxy.class, List.class},
                            admin, proxy, exclusions);
                    ok("proxy=" + spec + " previousAdmin=" + prev);
                    break;
                }
                case "locktask": {
                    String v = need(a, 1, "locktask <pkg1,pkg2|none>");
                    String[] pkgs = "none".equalsIgnoreCase(v) ? new String[0] : v.split(",");
                    dpm.setLockTaskPackages(admin, pkgs);
                    ok("locktask=" + Arrays.toString(pkgs));
                    break;
                }
                case "restrict": {
                    String key = need(a, 1, "restrict <key> on|off");
                    boolean on = parseOnOff(need(a, 2, "restrict <key> on|off"));
                    if (on) dpm.addUserRestriction(admin, key);
                    else dpm.clearUserRestriction(admin, key);
                    ok("restriction " + key + "=" + on);
                    break;
                }
                case "restrictions": {
                    Bundle b = dpm.getUserRestrictions(admin);
                    if (b == null || b.isEmpty()) {
                        ok("restrictions: (none)");
                    } else {
                        List<String> keys = new ArrayList<>(b.keySet());
                        java.util.Collections.sort(keys);
                        for (String k : keys) ok("restriction " + k + "=" + b.get(k));
                    }
                    break;
                }
                case "user-icon": {
                    String path = need(a, 1, "user-icon <png path>");
                    Bitmap bmp = BitmapFactory.decodeFile(path);
                    if (bmp == null) fail(1, "无法解码图片: " + path);
                    dpm.setUserIcon(admin, bmp);
                    ok("user-icon set from " + path + " (" + bmp.getWidth() + "x" + bmp.getHeight() + ")");
                    break;
                }
                case "hide": {
                    String pkg = need(a, 1, "hide <pkg> on|off");
                    boolean on = parseOnOff(need(a, 2, "hide <pkg> on|off"));
                    boolean changed = dpm.setApplicationHidden(admin, pkg, on);
                    ok("hide " + pkg + "=" + on + " -> isApplicationHidden=" + dpm.isApplicationHidden(admin, pkg) + " (changed=" + changed + ")");
                    break;
                }
                case "suspend": {
                    String pkg = need(a, 1, "suspend <pkg> on|off");
                    boolean on = parseOnOff(need(a, 2, "suspend <pkg> on|off"));
                    String[] failed = dpm.setPackagesSuspended(admin, new String[]{pkg}, on);
                    ok("suspend " + pkg + "=" + on + " -> notSuspended=" + Arrays.toString(failed)
                            + " isPackageSuspended=" + isPackageSuspendedSafe(dpm, admin, pkg));
                    break;
                }
                case "block-uninstall": {
                    String pkg = need(a, 1, "block-uninstall <pkg> on|off");
                    boolean on = parseOnOff(need(a, 2, "block-uninstall <pkg> on|off"));
                    dpm.setUninstallBlocked(admin, pkg, on);
                    ok("block-uninstall " + pkg + "=" + on + " -> isUninstallBlocked=" + dpm.isUninstallBlocked(admin, pkg));
                    break;
                }
                case "uninstall": {
                    uninstall(dpm, admin, need(a, 1, "uninstall <pkg>"));
                    break;
                }
                case "app-status": {
                    String pkg = need(a, 1, "app-status <pkg>");
                    ok("app " + pkg + " hidden=" + dpm.isApplicationHidden(admin, pkg)
                            + " suspended=" + isPackageSuspendedSafe(dpm, admin, pkg)
                            + " uninstallBlocked=" + dpm.isUninstallBlocked(admin, pkg));
                    break;
                }
                case "grant-permission": {
                    final String pkg = need(a, 1, "grant-permission <pkg> <permission> <grant|deny|default>");
                    final String perm = need(a, 2, "grant-permission <pkg> <permission> <grant|deny|default>");
                    final String mode = need(a, 3, "grant-permission <pkg> <permission> <grant|deny|default>");
                    final int state;
                    if ("grant".equalsIgnoreCase(mode)) state = DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED;
                    else if ("deny".equalsIgnoreCase(mode)) state = DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED;
                    else if ("default".equalsIgnoreCase(mode)) state = DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT;
                    else { fail(1, "mode 必须是 grant|deny|default"); return; }

                    // 部分 ROM（如 vivo）PermissionController 不回调，DPM 的同步等待会永久阻塞
                    Object result = callWithTimeout(new Callable0() {
                        @Override
                        public Object call() throws Exception {
                            return dpm.setPermissionGrantState(admin, pkg, perm, state);
                        }
                    }, 10);
                    if (result != TIMEOUT) {
                        ok("grant-permission " + pkg + " " + perm + " " + mode + " -> accepted=" + result);
                        R check = exec("dumpsys package " + pkg + " | grep -F '" + perm + ": granted'");
                        boolean grantedNow = check.out.contains("granted=true");
                        String fallback = null;
                        if ("grant".equalsIgnoreCase(mode) && !grantedNow) {
                            fallback = "pm grant " + pkg + " " + perm;
                        } else if ("deny".equalsIgnoreCase(mode) && grantedNow) {
                            fallback = "pm revoke " + pkg + " " + perm;
                        }
                        if (fallback == null) break;
                        logErr("DPM 返回成功但未生效（传感器权限受普通 DO 限制），回退: " + fallback);
                        R fr = exec(fallback);
                        log("fallback `" + fallback + "` rc=" + fr.rc + " out=" + fr.out);
                        if (fr.rc != 0) fail(4, "grant-permission fallback 失败");
                        R check2 = exec("dumpsys package " + pkg + " | grep -F '" + perm + ": granted'");
                        ok("grant-permission " + mode + " via pm -> "
                                + (check2.out.isEmpty() ? "(no permission line)" : check2.out.trim()));
                        break;
                    }
                    logErr("DPM setPermissionGrantState 10 秒无回调（PermissionController 兼容性问题），回退 pm 命令");
                    String pmCmd;
                    if ("grant".equalsIgnoreCase(mode)) {
                        pmCmd = "pm grant " + pkg + " " + perm;
                    } else if ("deny".equalsIgnoreCase(mode)) {
                        pmCmd = "pm revoke " + pkg + " " + perm;
                    } else {
                        pmCmd = "pm clear-permission-flags " + pkg + " " + perm + " user-set user-fixed";
                    }
                    R r = exec(pmCmd);
                    log("fallback `" + pmCmd + "` rc=" + r.rc + " out=" + r.out);
                    if (r.rc != 0) fail(4, "grant-permission fallback 失败");
                    R check = exec("dumpsys package " + pkg + " | grep -F '" + perm + ": granted'");
                    ok("grant-permission " + mode + " via pm -> "
                            + (check.out.isEmpty() ? "(no permission line)" : check.out.trim()));
                    break;
                }
                default:
                    fail(1, "未知命令: " + cmd + "（用 help 查看）");
            }
        } catch (Throwable t) {
            fail(4, cmd + " 失败: " + rootCause(t));
        }
    }

    private static boolean isPackageSuspendedSafe(DevicePolicyManager dpm, ComponentName admin, String pkg) {
        try {
            return dpm.isPackageSuspended(admin, pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (Throwable t) {
            log("isPackageSuspended failed: " + t);
            return false;
        }
    }

    private static void uninstall(DevicePolicyManager dpm, ComponentName admin, String pkg) {
        try {
            dpm.setUninstallBlocked(admin, pkg, false);
            log("cleared uninstall block for " + pkg);
        } catch (Throwable t) {
            log("clear uninstall block failed: " + t);
        }
        R r = exec("/system/bin/pm uninstall --user 0 " + pkg);
        log("pm uninstall rc=" + r.rc + " out=" + r.out);
        if (r.rc == 0 && r.out.toLowerCase(Locale.ROOT).contains("success")) {
            ok("uninstalled " + pkg);
            return;
        }
        // 有活跃 admin 的包需要走 DPM 的专用卸载通道
        try {
            invokeHidden(dpm, "uninstallPackageWithActiveAdmins", new Class<?>[]{String.class}, pkg);
            ok("DPM uninstallPackageWithActiveAdmins requested (async): " + pkg);
        } catch (Throwable t) {
            logErr("DPM uninstall failed: " + rootCause(t));
            fail(4, "卸载失败: " + pkg);
        }
    }
}
