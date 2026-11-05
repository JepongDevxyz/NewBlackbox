package top.niunaijun.blackbox.fake.service;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.fake.provider.FileProviderHandler;
import top.niunaijun.blackbox.utils.ComponentUtils;
import top.niunaijun.blackbox.utils.DiagLog;
import top.niunaijun.blackbox.utils.MethodParameterUtils;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.BuildCompat;
import top.niunaijun.blackbox.utils.compat.StartActivityCompat;

import static android.content.pm.PackageManager.GET_META_DATA;


public class ActivityManagerCommonProxy {
    public static final String TAG = "CommonStub";
    private static final int START_RESULT_UNRESOLVED = Integer.MIN_VALUE;
    private static volatile int sStartIntentNotResolved = START_RESULT_UNRESOLVED;
    private static final String PKG_FACEBOOK_KATANA = "com.facebook.katana";

    // DevxyzClone fix (round 4, ported from the frozen Blacks-BlackBox
    // fork): a clone must never launch the REAL Facebook app. At the real
    // ActivityManager every clone start is attributed to the host package,
    // so Katana validates the HOST key hash and Facebook login fails
    // ("Invalid key hash" / "Something went wrong"). PM-level hiding
    // cannot stop a direct startActivity, so fail the launch in-process
    // here; the SDK then falls back to its web dialog flow.
    //
    // DevxyzClone 1.1.1 (STUDY-KATANA-NATIVE condition A): this block is
    // lifted, together with the IPackageManagerProxy visibility gate,
    // when the native route is active for the caller - Katana CLONED
    // into the caller's virtual user and the caller not a Meta-family
    // app. In that state the block's call site is only reached when
    // virtual resolution already failed, and failing closed would leave
    // a visible-but-unlaunchable Katana; when the route is NOT active
    // the 1.1.0 block applies unchanged, so no Katana intent from a
    // clone ever falls through to the real ActivityManager.
    private static boolean shouldBlockFacebookNativeLaunch(Intent intent) {
        if (intent == null) {
            return false;
        }
        try {
            String callerPackage = BActivityThread.getAppPackageName();
            if (callerPackage == null
                    || PKG_FACEBOOK_KATANA.equals(callerPackage)
                    || callerPackage.equals(BlackBoxCore.getHostPkg())) {
                return false;
            }
            if (PKG_FACEBOOK_KATANA.equals(intent.getPackage())) {
                return true;
            }
            ComponentName cn = intent.getComponent();
            if (cn != null && PKG_FACEBOOK_KATANA.equals(cn.getPackageName())) {
                return true;
            }
            String act = intent.getAction();
            return act != null && (act.startsWith("com.facebook.katana.")
                    || act.startsWith("com.facebook.platform."));
        } catch (Throwable ignored) {
        }
        return false;
    }

    // DevxyzClone 1.1.1: mirror of IPackageManagerProxy's native-route
    // predicate (the two proxies already duplicate the Katana caller
    // exemptions above; keep these in lockstep). Meta-family callers -
    // Muse (com.facebook.aura) above all - never get the lift: their
    // 1.1.0 hidden/blocked behavior is caller-keyed protection, not
    // part of the games' native route.
    private static final String PKG_FACEBOOK_AURA = "com.facebook.aura";
    private static final String PKG_FACEBOOK_ORCA = "com.facebook.orca";
    private static final String PKG_FACEBOOK_WAKIZASHI = "com.facebook.wakizashi";
    // DevxyzClone 1.1.6 (STUDY-WEB-ROUTE §3.1): mirror of
    // IPackageManagerProxy's PKG_FORCE_WEB_ROUTE - Mobile Legends is
    // force-routed to the web OAuth route, so the native route is
    // never active for this caller.
    private static final String PKG_FORCE_WEB_ROUTE = "com.mobile.legends";

    private static boolean isMetaFamilyCaller(String callerPackage) {
        return PKG_FACEBOOK_AURA.equals(callerPackage)
                || PKG_FACEBOOK_ORCA.equals(callerPackage)
                || PKG_FACEBOOK_WAKIZASHI.equals(callerPackage);
    }

    private static boolean isKatanaNativeRouteActive() {
        try {
            String callerPackage = BActivityThread.getAppPackageName();
            if (callerPackage == null
                    || PKG_FACEBOOK_KATANA.equals(callerPackage)
                    || callerPackage.equals(BlackBoxCore.getHostPkg())
                    || PKG_FORCE_WEB_ROUTE.equals(callerPackage)
                    || isMetaFamilyCaller(callerPackage)) {
                return false;
            }
            return BlackBoxCore.getBPackageManager()
                    .isInstalled(PKG_FACEBOOK_KATANA, BActivityThread.getUserId());
        } catch (Throwable ignored) {
        }
        return false;
    }

    // DevxyzClone fix (round 6, ported): Facebook/Meta OAuth launches from
    // a clone must stay inside the container. If the ACTION_VIEW escapes
    // to the real browser, the OAuth redirect back (fb<appid>://...,
    // custom schemes, app links handled only by virtual activities) can
    // never reach the clone, so the login can never complete - this is
    // the visible difference from Dual Space, where the web form opens
    // inside the cloned app.
    private static boolean isOAuthWebEscape(Intent intent) {
        if (intent == null) {
            return false;
        }
        try {
            if (!Intent.ACTION_VIEW.equals(intent.getAction())) {
                return false;
            }
            String callerPackage = BActivityThread.getAppPackageName();
            if (callerPackage == null || callerPackage.equals(BlackBoxCore.getHostPkg())) {
                return false;
            }
            String data = intent.getDataString();
            if (data == null) {
                return false;
            }
            android.net.Uri uri = android.net.Uri.parse(data);
            String scheme = uri.getScheme();
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                return false;
            }
            String host = uri.getHost();
            if (host == null) {
                return false;
            }
            host = host.toLowerCase();
            if (host.endsWith("facebook.com")) {
                String path = uri.getPath() != null ? uri.getPath().toLowerCase() : "";
                return path.contains("oauth") || path.contains("dialog") || path.contains("login");
            }
            if (host.equals("auth.meta.com") || host.endsWith(".meta.com")
                    || host.equals("meta.com") || host.endsWith("oculus.com")) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static void fbDiagLog(String msg) {
        // Round 5 (ported): shared DiagLog sink (see IPackageManagerProxy).
        DiagLog.log(msg);
    }

    private static int getStartIntentNotResolvedCode() {
        int cached = sStartIntentNotResolved;
        if (cached != START_RESULT_UNRESOLVED) {
            return cached;
        }
        int resolved = -1;
        try {
            Field f = ActivityManager.class.getDeclaredField("START_INTENT_NOT_RESOLVED");
            f.setAccessible(true);
            resolved = f.getInt(null);
        } catch (Throwable ignored) {
        }
        sStartIntentNotResolved = resolved;
        return resolved;
    }

    @ProxyMethod("startActivity")
    public static class StartActivity extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            Intent intent = getIntent(args);
            Slog.d(TAG, "Hook in : " + intent);
            assert intent != null;
            
            
            if (intent.getParcelableExtra("_B_|_target_") != null) {
                return method.invoke(who, args);
            }
            if (ComponentUtils.isRequestInstall(intent)) {
                File file = FileProviderHandler.convertFile(BActivityThread.getApplication(), intent.getData());
                
                
                if (file != null && file.exists()) {
                    try {
                        PackageInfo packageInfo = BlackBoxCore.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(), 0);
                        if (packageInfo != null) {
                            String packageName = packageInfo.packageName;
                            String hostPackageName = BlackBoxCore.getHostPkg();
                            if (packageName.equals(hostPackageName)) {
                                Slog.w(TAG, "Blocked attempt to install BlackBox app from within BlackBox: " + packageName);
                                
                                return 0;
                            }
                        }
                    } catch (Exception e) {
                        Slog.w(TAG, "Could not verify if this is BlackBox app: " + e.getMessage());
                    }
                }
                
                if (BlackBoxCore.get().requestInstallPackage(file, BActivityThread.getUserId())) {
                    return 0;
                }
                intent.setData(FileProviderHandler.convertFileUri(BActivityThread.getApplication(), intent.getData()));
                return method.invoke(who, args);
            }
            String dataString = intent.getDataString();
            if (dataString != null && dataString.equals("package:" + BActivityThread.getAppPackageName())) {
                intent.setData(Uri.parse("package:" + BlackBoxCore.getHostPkg()));
            }

            // Round 5 diagnostics (ported): record every cross-package
            // start and every ACTION_VIEW escape, so a sign-in flow that
            // leaves the clone (browser / custom tab / external app) is
            // visible in the log even when it makes no PackageManager
            // calls at all.
            try {
                String callerPkg = BActivityThread.getAppPackageName();
                String tgtPkg = intent.getPackage();
                if (tgtPkg == null && intent.getComponent() != null) {
                    tgtPkg = intent.getComponent().getPackageName();
                }
                boolean viewEscape = Intent.ACTION_VIEW.equals(intent.getAction())
                        && intent.getDataString() != null;
                // DevxyzClone 1.1.2 (F3b; generalized from the DevxyzMuse
                // v1.0.1 diagnostic, which recorded this for the Muse
                // clone only): also record SAME-package launches, for
                // every guest, so the exact activity a settings tap opens
                // is on record even when it never leaves the guest's own
                // package (previously only cross-package starts and VIEW
                // escapes were logged - the blind spot the settings-crash
                // study named). Cross-package starts are still covered by
                // the line below, so no start is logged twice.
                if (tgtPkg != null && callerPkg != null && tgtPkg.equals(callerPkg)) {
                    fbDiagLog("startActivity[same-pkg-probe] caller=" + callerPkg
                            + " cmp=" + intent.getComponent()
                            + " act=" + intent.getAction()
                            + " flags=0x" + Integer.toHexString(intent.getFlags())
                            + " data=" + intent.getDataString());
                }
                if (viewEscape || (tgtPkg != null && callerPkg != null && !tgtPkg.equals(callerPkg))) {
                    fbDiagLog("startActivity caller=" + callerPkg
                            + " -> target=" + tgtPkg
                            + " cmp=" + intent.getComponent()
                            + " act=" + intent.getAction()
                            + " data=" + intent.getDataString());
                }
            } catch (Throwable ignored) {
            }

            // DevxyzClone fix (round 10 / 1.0.14, ported): run the round-6
            // OAuth rewrite BEFORE virtual resolution. In the old base the
            // check sat inside the double-resolution-failure branch, so it
            // only fired when nothing in the clone resolved the intent.
            // GMS auto-on installs com.google.android.setupwizard into
            // every clone, and its WebDialogActivity resolves generic
            // https VIEW intents (including the Facebook OAuth URL), so a
            // post-resolution check is never reached - the OAuth page
            // launches inside SetupWizard and black-screens. Checking
            // first keeps the OAuth flow on the host OAuthWebActivity
            // regardless of what GMS installs.
            // NOTE (port adaptation): the rewrite targets this base's app
            // package, top.niunaijun.blackboxa (the old base used
            // com.onebitmonochrome.blacksbbox).
            if (isOAuthWebEscape(intent)) {
                try {
                    int rc = StartActivityCompat.getRequestCode(args);
                    Intent oauthRewritten = new Intent(intent);
                    oauthRewritten.setComponent(new ComponentName(
                            BlackBoxCore.getHostPkg(),
                            "top.niunaijun.blackboxa.view.clones.OAuthWebActivity"));
                    oauthRewritten.setPackage(null);
                    oauthRewritten.putExtra("oauth_url", intent.getDataString());
                    oauthRewritten.putExtra("oauth_user_id", BActivityThread.getUserId());
                    oauthRewritten.putExtra("oauth_for_result", rc >= 0);
                    replaceIntentArg(args, intent, oauthRewritten);
                    fbDiagLog("startActivity OAuth REWRITTEN to in-container WebView preResolve=true caller="
                            + BActivityThread.getAppPackageName()
                            + " forResult=" + (rc >= 0)
                            + " url=" + intent.getDataString());
                    return method.invoke(who, args);
                } catch (Throwable ignored) {
                }
            }

            ResolveInfo resolveInfo = BlackBoxCore.getBPackageManager().resolveActivity(
                    intent,
                    GET_META_DATA,
                    StartActivityCompat.getResolvedType(args),
                    BActivityThread.getUserId());
            if (resolveInfo == null) {
                String origPackage = intent.getPackage();
                if (intent.getPackage() == null && intent.getComponent() == null) {
                    intent.setPackage(BActivityThread.getAppPackageName());
                } else {
                    origPackage = intent.getPackage();
                }
                resolveInfo = BlackBoxCore.getBPackageManager().resolveActivity(
                        intent,
                        GET_META_DATA,
                        StartActivityCompat.getResolvedType(args),
                        BActivityThread.getUserId());
                if (resolveInfo == null) {
                    intent.setPackage(origPackage);

                    // (The round-6 OAuth rewrite moved to before virtual
                    // resolution in round 10 / 1.0.14 - see above.)

                    // DevxyzClone fix (round 4, ported): block the
                    // fallthrough launch of the real Facebook app (see
                    // shouldBlockFacebookNativeLaunch).
                    // DevxyzClone 1.1.1: the block lifts together with
                    // the IPM gate when the native route is active for
                    // this caller (Katana cloned, non-Meta caller); in
                    // every other state the 1.1.0 block is unchanged.
                    if (shouldBlockFacebookNativeLaunch(intent)) {
                        if (isKatanaNativeRouteActive()) {
                            fbDiagLog("startActivity katana launch ALLOWED caller="
                                    + BActivityThread.getAppPackageName()
                                    + " pkg=" + intent.getPackage()
                                    + " cmp=" + intent.getComponent()
                                    + " act=" + intent.getAction()
                                    + " reason=katana-cloned native-route");
                        } else {
                            fbDiagLog("startActivity BLOCKED katana launch caller="
                                    + BActivityThread.getAppPackageName()
                                    + " pkg=" + intent.getPackage()
                                    + " cmp=" + intent.getComponent()
                                    + " act=" + intent.getAction()
                                    + " reason=native-route-inactive");
                            return getStartIntentNotResolvedCode();
                        }
                    }

                    return method.invoke(who, args);
                }
            }


            // Round 10 / 1.0.14 diagnostics (ported): name the winner
            // whenever an http(s) VIEW intent resolves to a different
            // package than the caller's, so "which activity ate my link"
            // is answerable from the DiagLog without another study round.
            try {
                if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
                    String scheme = intent.getData().getScheme();
                    if (("http".equals(scheme) || "https".equals(scheme))
                            && !resolveInfo.activityInfo.packageName.equals(BActivityThread.getAppPackageName())) {
                        fbDiagLog("startActivity VIEW resolved caller=" + BActivityThread.getAppPackageName()
                                + " -> " + resolveInfo.activityInfo.packageName
                                + "/" + resolveInfo.activityInfo.name
                                + " data=" + intent.getData().getHost() + intent.getData().getPath());
                    }
                }
            } catch (Throwable ignored) {
            }

            intent.setExtrasClassLoader(who.getClass().getClassLoader());
            intent.setComponent(new ComponentName(resolveInfo.activityInfo.packageName, resolveInfo.activityInfo.name));
            BlackBoxCore.getBActivityManager().startActivityAms(BActivityThread.getUserId(),
                    StartActivityCompat.getIntent(args),
                    StartActivityCompat.getResolvedType(args),
                    StartActivityCompat.getResultTo(args),
                    StartActivityCompat.getResultWho(args),
                    StartActivityCompat.getRequestCode(args),
                    StartActivityCompat.getFlags(args),
                    StartActivityCompat.getOptions(args));
            return 0;
        }

        private void replaceIntentArg(Object[] args, Intent oldIntent, Intent newIntent) {
            if (args == null || newIntent == null) {
                return;
            }
            for (int i = 0; i < args.length; i++) {
                if (args[i] == oldIntent) {
                    args[i] = newIntent;
                    return;
                }
            }
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof Intent) {
                    args[i] = newIntent;
                    return;
                }
            }
        }

        private Intent getIntent(Object[] args) {
            int index;
            if (BuildCompat.isR()) {
                index = 3;
            } else {
                index = 2;
            }
            if (args[index] instanceof Intent) {
                return (Intent) args[index];
            }
            for (Object arg : args) {
                if (arg instanceof Intent) {
                    return (Intent) arg;
                }
            }
            return null;
        }
    }

    @ProxyMethod("startActivities")
    public static class StartActivities extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int index = getIntents();
            Intent[] intents = (Intent[]) args[index++];
            String[] resolvedTypes = (String[]) args[index++];
            IBinder resultTo = (IBinder) args[index++];
            Bundle options = (Bundle) args[index];
            
            if (!ComponentUtils.isSelf(intents)) {
                return method.invoke(who, args);
            }

            for (Intent intent : intents) {
                intent.setExtrasClassLoader(who.getClass().getClassLoader());
            }
            return BlackBoxCore.getBActivityManager().startActivities(BActivityThread.getUserId(),
                    intents, resolvedTypes, resultTo, options);
        }

        public int getIntents() {
            if (BuildCompat.isR()) {
                return 3;
            }
            return 2;
        }
    }

    @ProxyMethod("startIntentSenderForResult")
    public static class StartIntentSenderForResult extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("activityResumed")
    public static class ActivityResumed extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            BlackBoxCore.getBActivityManager().onActivityResumed((IBinder) args[0]);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("activityDestroyed")
    public static class ActivityDestroyed extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            BlackBoxCore.getBActivityManager().onActivityDestroyed((IBinder) args[0]);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("finishActivity")
    public static class FinishActivity extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            // DevxyzClone 1.1.2 (F3b; generalized from the DevxyzMuse
            // v1.0.1 diagnostic, Muse-only there): a silent finish of a
            // guest activity with no CRASH line in DiagLog is itself the
            // fingerprint of a self-exit path, distinct from an exception
            // death - record it for every guest.
            try {
                fbDiagLog("finishActivity caller=" + BActivityThread.getAppPackageName()
                        + " token=" + args[0]);
            } catch (Throwable ignored) {
            }
            BlackBoxCore.getBActivityManager().onFinishActivity((IBinder) args[0]);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getAppTasks")
    public static class GetAppTasks extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getCallingPackage")
    public static class getCallingPackage extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return BlackBoxCore.getBActivityManager().getCallingPackage((IBinder) args[0], BActivityThread.getUserId());
        }
    }

    @ProxyMethod("getCallingActivity")
    public static class getCallingActivity extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return BlackBoxCore.getBActivityManager().getCallingActivity((IBinder) args[0], BActivityThread.getUserId());
        }
    }
}
