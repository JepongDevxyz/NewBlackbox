package top.niunaijun.blackbox.fake.service;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.util.Log;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import black.android.app.BRActivityThread;
import black.android.app.BRContextImpl;
import black.android.app.ContextImpl;
import black.android.content.pm.BRPackageManager;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.core.env.AppSystemEnv;
import top.niunaijun.blackbox.fake.FakeCore;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.fake.service.base.PkgMethodProxy;
import top.niunaijun.blackbox.fake.service.base.ValueMethodProxy;
import top.niunaijun.blackbox.utils.DiagLog;
import top.niunaijun.blackbox.utils.MethodParameterUtils;
import top.niunaijun.blackbox.utils.Reflector;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.BuildCompat;
import top.niunaijun.blackbox.utils.compat.ParceledListSliceCompat;


public class IPackageManagerProxy extends BinderInvocationStub {
    public static final String TAG = "PackageManagerStub";

    // ===== DevxyzClone Facebook/Meta stack (ported from the frozen
    // Blacks-BlackBox fork, rounds 2/4/7 + 1.0.10) =====
    // Facebook SDKs inside a clone prefer the native Katana flow when the
    // real Facebook app is visible; that flow validates the CALLING app
    // from OUTSIDE the container, where the caller is the host's proxy
    // activity, so the key hash Facebook computes is the host's and login
    // is rejected ("Invalid key hash" / "Something went wrong"). When
    // Katana is invisible, SDKs fall back to the web dialog flow, which
    // ActivityManagerCommonProxy rewrites into the in-container
    // OAuthWebActivity. The cloned Facebook app itself still sees itself
    // (caller == katana), and the host process is unaffected
    // (caller == host).
    //
    // DevxyzClone 1.1.1 (native Katana route; see STUDY-KATANA-NATIVE):
    // the hiding above exists to keep SDKs off the REAL Facebook app.
    // When Katana itself is CLONED into the caller's virtual user, the
    // native flow never leaves the container: launches are named with
    // the guest's own package (ActivityManagerCommonProxy) and Katana
    // validates the caller from the virtual registry, which answers
    // with the caller's genuine signatures - the exact property the
    // Dual Space-style route relies on. So for non-Meta callers the
    // gate below now lifts when Katana is cloned (native route), and
    // stays exactly as in 1.1.0 when it is not (OAuth WebView route,
    // Custom Tabs still killed, no fallthrough to the real app). Meta's
    // own apps (Muse / Messenger / FB Lite) are caller-keyed OUT of the
    // lift: their Family SSO probes keep the 1.1.0 hidden/blocked
    // behavior in every state, and IActivityManagerProxy's Meta
    // provider block is unchanged.
    private static final String PKG_FACEBOOK_KATANA = "com.facebook.katana";
    private static final String PKG_FACEBOOK_ORCA = "com.facebook.orca";
    private static final String PKG_FACEBOOK_WAKIZASHI = "com.facebook.wakizashi";
    private static final String PKG_FACEBOOK_AURA = "com.facebook.aura";
    // DevxyzClone 1.1.6 (STUDY-WEB-ROUTE §3.1): Mobile Legends is
    // force-routed to the web OAuth route. The native Katana route
    // failed on-device for this caller in 1.1.2-1.1.5 despite the
    // delivery fixes (D1-D4) and the Katana-side fixes, while the web
    // route carries no Katana-side validation at all and its return
    // runs entirely through container-owned components. Caller-keyed,
    // in the same style as the Meta-family protection above: for this
    // caller the native route is never active. Mirrored in
    // ActivityManagerCommonProxy and IActivityManagerProxy - keep the
    // three predicate copies in lockstep.
    private static final String PKG_FORCE_WEB_ROUTE = "com.mobile.legends";
    private static final String ACTION_CUSTOM_TABS_SERVICE = "android.support.customtabs.action.CustomTabsService";

    private static boolean shouldHideFacebookApp(String queriedPackageName) {
        if (!PKG_FACEBOOK_KATANA.equals(queriedPackageName)) {
            return false;
        }
        return isFacebookAppHiddenFromCurrentApp();
    }

    private static boolean shouldHideFacebookApp(Intent intent) {
        if (intent == null) {
            return false;
        }
        try {
            String targetPackage = intent.getPackage();
            if (targetPackage == null) {
                ComponentName cn = intent.getComponent();
                if (cn != null) {
                    targetPackage = cn.getPackageName();
                }
            }
            return shouldHideFacebookApp(targetPackage);
        } catch (Throwable ignored) {
        }
        return false;
    }

    // DevxyzClone 1.1.1: Meta's own apps keep the 1.1.0 hidden/blocked
    // behavior as CALLERS in every state (their Family SSO probes are
    // what the hiding protects against; see the header comment). Muse
    // (com.facebook.aura) is the proven case; Orca/Wakizashi callers are
    // kept on the same side of the line for the same reason.
    private static boolean isMetaFamilyCaller(String callerPackage) {
        return PKG_FACEBOOK_AURA.equals(callerPackage)
                || PKG_FACEBOOK_ORCA.equals(callerPackage)
                || PKG_FACEBOOK_WAKIZASHI.equals(callerPackage);
    }

    // DevxyzClone 1.1.1: is Katana installed INSIDE the container for
    // this virtual user (a clone), as opposed to merely present on the
    // real device? Only the clone counts - the native route runs
    // entirely against the cloned app (STUDY-KATANA-NATIVE condition A).
    private static boolean isKatanaClonedForUser(int userId) {
        try {
            return BlackBoxCore.getBPackageManager()
                    .isInstalled(PKG_FACEBOOK_KATANA, userId);
        } catch (Throwable ignored) {
        }
        return false;
    }

    // DevxyzClone 1.1.1: the native Katana route is active for the
    // current caller exactly when the caller is an ordinary clone app
    // (not Katana itself, not the host, not a Meta-family caller) AND
    // Katana is cloned into the caller's virtual user. This single
    // predicate drives the IPM gate, the Custom Tabs decoupling and
    // (mirrored) the ActivityManagerCommonProxy launch block, so the
    // three can never drift into the incoherent visible-but-
    // unlaunchable state the study warns about.
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
            return isKatanaClonedForUser(BActivityThread.getUserId());
        } catch (Throwable ignored) {
        }
        return false;
    }

    // DevxyzClone 1.1.1: the Custom Tabs kill guards the WebView OAuth
    // return seam (STUDY-ML-FB-RETURN), so it is keyed to the OAuth
    // route being in play for this caller - NOT to Katana visibility.
    // It stays active for every clone caller on the OAuth route (Katana
    // not cloned, or a Meta-family caller) and lifts only when the
    // native route above is active, where Custom Tabs plays no part.
    // Host/Katana processes keep their 1.1.0 no-kill behavior.
    private static boolean isCustomTabsDisabledInClone() {
        try {
            String callerPackage = BActivityThread.getAppPackageName();
            if (callerPackage == null
                    || PKG_FACEBOOK_KATANA.equals(callerPackage)
                    || callerPackage.equals(BlackBoxCore.getHostPkg())) {
                return false;
            }
            return !isKatanaNativeRouteActive();
        } catch (Throwable ignored) {
        }
        return false;
    }

    // DevxyzClone 1.1.1 diagnostics: emit the Katana visibility decision
    // once per (caller, decision, reason) per process, so the DiagLog
    // shows which route each app was on without flooding on every PM
    // call. The per-process statics are fine: each clone runs its own
    // copy of this class.
    private static volatile String sLastKatanaVisibilityKey;

    private static void diagKatanaVisibility(String callerPackage, boolean hidden, String reason) {
        try {
            String key = callerPackage + "|" + hidden + "|" + reason;
            if (key.equals(sLastKatanaVisibilityKey)) {
                return;
            }
            sLastKatanaVisibilityKey = key;
            diagLog("katanaVisibility caller=" + callerPackage
                    + " katana=" + (hidden ? "hidden" : "visible")
                    + " reason=" + reason);
        } catch (Throwable ignored) {
        }
    }

    private static boolean isFacebookAppHiddenFromCurrentApp() {
        try {
            String callerPackage = BActivityThread.getAppPackageName();
            if (callerPackage == null
                    || PKG_FACEBOOK_KATANA.equals(callerPackage)
                    || callerPackage.equals(BlackBoxCore.getHostPkg())) {
                return false;
            }
            // DevxyzClone 1.1.1: caller-keyed protection - Meta-family
            // callers (Muse above all) stay hidden in every state.
            if (isMetaFamilyCaller(callerPackage)) {
                diagKatanaVisibility(callerPackage, true, "meta-caller");
                return true;
            }
            // DevxyzClone 1.1.6: ML is hidden in every state too, but
            // by force-routing to the web OAuth route (see
            // PKG_FORCE_WEB_ROUTE), not by the route check below -
            // give it its own reason so the DiagLog does not claim
            // Katana is merely not cloned for this caller.
            if (PKG_FORCE_WEB_ROUTE.equals(callerPackage)) {
                diagKatanaVisibility(callerPackage, true, "ml-web-forced");
                return true;
            }
            // DevxyzClone 1.1.1: coherent un-hide gated on Katana being
            // cloned into this caller's virtual user (condition A).
            if (isKatanaNativeRouteActive()) {
                diagKatanaVisibility(callerPackage, false, "katana-cloned");
                return false;
            }
            diagKatanaVisibility(callerPackage, true, "katana-not-cloned");
            return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean isMetaPackage(String pkg) {
        return PKG_FACEBOOK_KATANA.equals(pkg) || PKG_FACEBOOK_ORCA.equals(pkg)
                || PKG_FACEBOOK_WAKIZASHI.equals(pkg);
    }

    private static boolean isMetaProviderAuthority(String authority) {
        return authority != null && (authority.startsWith(PKG_FACEBOOK_KATANA)
                || authority.startsWith(PKG_FACEBOOK_ORCA)
                || authority.startsWith(PKG_FACEBOOK_WAKIZASHI));
    }

    // Is this intent a PackageManager probe for a Facebook Login redirect
    // URI (fb<appid>://authorize or fbconnect://cct.<package>)? The SDK's
    // Validate.hasCustomTabRedirectActivity runs exactly this query.
    // Since 1.0.10 these probes are answered truthfully by the virtual
    // registry (hiding them made the SDK conclude NO login handler
    // exists); the predicate now only drives DiagLog lines.
    private static boolean isFacebookRedirectQuery(Intent intent) {
        if (intent == null || !isFacebookAppHiddenFromCurrentApp()) {
            return false;
        }
        try {
            android.net.Uri data = intent.getData();
            if (data == null) {
                return false;
            }
            String scheme = data.getScheme();
            if (scheme == null) {
                return false;
            }
            if (scheme.length() > 2 && scheme.charAt(0) == 'f'
                    && scheme.charAt(1) == 'b' && Character.isDigit(scheme.charAt(2))) {
                return true;
            }
            if ("fbconnect".equals(scheme)) {
                String host = data.getHost();
                return host != null && host.startsWith("cct.");
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean isCustomTabsServiceIntent(Intent intent) {
        return intent != null && ACTION_CUSTOM_TABS_SERVICE.equals(intent.getAction());
    }

    private static boolean shouldHideFacebookService(Intent intent) {
        if (intent == null) {
            return false;
        }
        if (shouldHideFacebookApp(intent)) {
            return true;
        }
        try {
            String act = intent.getAction();
            return act != null && (act.startsWith("com.facebook.katana.")
                    || act.startsWith("com.facebook.platform."))
                    && isFacebookAppHiddenFromCurrentApp();
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static List<ResolveInfo> filterOutFacebookServices(List<ResolveInfo> resolves) {
        if (resolves == null || resolves.isEmpty()) {
            return resolves;
        }
        ArrayList<ResolveInfo> filtered = new ArrayList<>(resolves.size());
        for (ResolveInfo ri : resolves) {
            if (ri == null) {
                continue;
            }
            ServiceInfo si = ri.serviceInfo;
            if (si != null && PKG_FACEBOOK_KATANA.equals(si.packageName)) {
                continue;
            }
            filtered.add(ri);
        }
        return filtered;
    }

    private static List<ResolveInfo> filterOutFacebookActivities(List<ResolveInfo> resolves) {
        if (resolves == null || resolves.isEmpty()) {
            return resolves;
        }
        ArrayList<ResolveInfo> filtered = new ArrayList<>(resolves.size());
        for (ResolveInfo ri : resolves) {
            if (ri == null) {
                continue;
            }
            ActivityInfo ai = ri.activityInfo;
            if (ai != null && PKG_FACEBOOK_KATANA.equals(ai.packageName)) {
                continue;
            }
            filtered.add(ri);
        }
        return filtered;
    }

    // DevxyzClone diagnostics: one line per relevant PackageManager event
    // inside cloned apps, into the shared DiagLog sink, so the exact
    // identity answers given to SDKs can be inspected after a phone test.
    private static void diagLog(String msg) {
        DiagLog.log(msg);
    }

    private static String diagKeyHash(android.content.pm.Signature[] sigs) {
        if (sigs == null || sigs.length == 0) {
            return "none";
        }
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(sigs[0].toByteArray());
            return android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP);
        } catch (Throwable t) {
            return "err";
        }
    }

    private static String diagSigningInfoHash(android.content.pm.SigningInfo si) {
        if (si == null) {
            return "none";
        }
        try {
            return diagKeyHash(si.getApkContentsSigners());
        } catch (Throwable t) {
            return "err";
        }
    }

    private static boolean diagIsFacebookIntent(Intent intent) {
        if (intent == null) {
            return false;
        }
        try {
            StringBuilder sb = new StringBuilder();
            if (intent.getPackage() != null) {
                sb.append(intent.getPackage()).append(' ');
            }
            if (intent.getComponent() != null) {
                sb.append(intent.getComponent().flattenToShortString()).append(' ');
            }
            if (intent.getAction() != null) {
                sb.append(intent.getAction()).append(' ');
            }
            if (intent.getDataString() != null) {
                sb.append(intent.getDataString());
            }
            String s = sb.toString().toLowerCase();
            return s.contains("facebook") || s.contains("katana") || s.contains("customtab");
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static String diagIntentSummary(Intent intent) {
        try {
            return "pkg=" + intent.getPackage()
                    + " cmp=" + (intent.getComponent() != null ? intent.getComponent().flattenToShortString() : null)
                    + " act=" + intent.getAction()
                    + " data=" + intent.getDataString();
        } catch (Throwable t) {
            return "err";
        }
    }

    public IPackageManagerProxy() {
        super(BRActivityThread.get().sPackageManager().asBinder());
    }

    @Override
    protected Object getWho() {
        return BRActivityThread.get().sPackageManager();
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        BRActivityThread.get()._set_sPackageManager(proxyInvocation);
        replaceSystemService("package");
        Object systemContext = BRActivityThread.get(BlackBoxCore.mainThread()).getSystemContext();
        BRContextImpl.get(systemContext).getPackageManager();
        PackageManager packageManager = BRContextImpl.get(systemContext).mPackageManager();
        if (packageManager != null) {
            BRPackageManager.get().disableApplicationInfoCache();
            try {
                Reflector.on("android.app.ApplicationPackageManager")
                        .field("mPM")
                        .set(packageManager, proxyInvocation);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @Override
    protected void onBindMethod() {
        super.onBindMethod();
        addMethodHook(new ValueMethodProxy("addOnPermissionsChangeListener", 0));
        addMethodHook(new ValueMethodProxy("removeOnPermissionsChangeListener", 0));
        addMethodHook(new SimpleAudioPermissionHook());
        addMethodHook(new CheckSelfPermission());
        addMethodHook(new ShouldShowRequestPermissionRationale());
        addMethodHook(new RequestPermissions());
        addMethodHook(new DisableIconLoading());
        addMethodHook(new SetSplashScreenTheme());
        addMethodHook(new XiaomiSecurityBypass());
    }

    // ===== DevxyzClone 1.1.2 (F2): query-call containment at the PM
    // stub dispatch =====
    // Mirrors the safe-default pattern IActivityManagerProxy.invoke()
    // already applies to the AM stub: an exception escaping a QUERY-TYPE
    // PackageManager call must not propagate into the guest, where an
    // unarmored caller (framework code running inside UI composition is
    // the case that killed the Muse clone) dies on the spot. Query
    // calls are pure reads with well-defined "absent" answers, so
    // returning the method's safe default is semantically safe.
    // Mutating / install-type calls are deliberately NOT covered: their
    // exceptions propagate exactly as in 1.1.1.
    private static boolean isContainedQueryMethod(String name) {
        return "getPackageInfo".equals(name)
                || "getPackageInfoVersioned".equals(name)
                || "getApplicationInfo".equals(name)
                || "getProviderInfo".equals(name)
                || "getComponentEnabledSetting".equals(name)
                || "resolveIntent".equals(name)
                || "resolveService".equals(name)
                || "resolveContentProvider".equals(name)
                || "queryIntentActivities".equals(name)
                || "queryIntentServices".equals(name)
                || "queryIntentReceivers".equals(name)
                || "queryContentProviders".equals(name)
                || "checkSignatures".equals(name);
    }

    private static Object querySafeDefault(String name) {
        if ("getComponentEnabledSetting".equals(name)) {
            return PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
        }
        if ("checkSignatures".equals(name)) {
            return PackageManager.SIGNATURE_NO_MATCH;
        }
        return null;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        try {
            return super.invoke(proxy, method, args);
        } catch (Throwable e) {
            String name = method.getName();
            if (!isContainedQueryMethod(name)) {
                throw e;
            }
            diagLog("pmQueryContained method=" + name
                    + " caller=" + BActivityThread.getAppPackageName()
                    + " err=" + e.getClass().getSimpleName()
                    + " -> safe default (1.1.2)");
            return querySafeDefault(name);
        }
    }

    @ProxyMethod("resolveIntent")
    public static class ResolveIntent extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Intent intent = (Intent) args[0];

            // DevxyzClone fix (round 2, ported): intents aimed at the real
            // Facebook app must not resolve for clones.
            if (shouldHideFacebookApp(intent)) {
                diagLog("resolveIntent caller=" + BlackBoxCore.getAppPackageName()
                        + " " + diagIntentSummary(intent) + " -> null (katana hidden)");
                return null;
            }

            // DevxyzClone 1.0.10 (ported): FB redirect-URI probes are
            // answered truthfully by the virtual registry below (hiding
            // them made the SDK conclude no login handler exists); this
            // line records the probe so the log shows what followed.
            if (isFacebookRedirectQuery(intent)) {
                diagLog("resolveIntent caller=" + BlackBoxCore.getAppPackageName()
                        + " " + diagIntentSummary(intent)
                        + " (FB redirect probe: passing through to virtual registry)");
            }

            String resolvedType = (String) args[1];
            int flags = MethodParameterUtils.toInt(args[2]);
            ResolveInfo resolveInfo = BlackBoxCore.getBPackageManager().resolveIntent(intent, resolvedType, flags, BlackBoxCore.getUserId());
            if (resolveInfo != null) {
                if (diagIsFacebookIntent(intent)) {
                    diagLog("resolveIntent caller=" + BlackBoxCore.getAppPackageName()
                            + " " + diagIntentSummary(intent) + " -> virtual "
                            + (resolveInfo.activityInfo != null ? resolveInfo.activityInfo.packageName : null));
                }
                return resolveInfo;
            }
            MethodParameterUtils.replaceUserIdIfNeeded(args, args.length - 1);
            Object hostOut = method.invoke(who, args);
            // Implicit intents can still land on Katana through the host
            // result; suppress that too so the SDK takes the web flow.
            if (hostOut instanceof ResolveInfo && isFacebookAppHiddenFromCurrentApp()) {
                ResolveInfo hostRi = (ResolveInfo) hostOut;
                if (hostRi.activityInfo != null
                        && PKG_FACEBOOK_KATANA.equals(hostRi.activityInfo.packageName)) {
                    diagLog("resolveIntent caller=" + BlackBoxCore.getAppPackageName()
                            + " " + diagIntentSummary(intent) + " -> null (katana host result suppressed)");
                    return null;
                }
            }
            if (diagIsFacebookIntent(intent)) {
                diagLog("resolveIntent caller=" + BlackBoxCore.getAppPackageName()
                        + " " + diagIntentSummary(intent) + " -> "
                        + (hostOut instanceof ResolveInfo && ((ResolveInfo) hostOut).activityInfo != null
                            ? ((ResolveInfo) hostOut).activityInfo.packageName : String.valueOf(hostOut)));
            }
            return hostOut;
        }
    }

    @ProxyMethod("resolveService")
    public static class ResolveService extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Intent intent = (Intent) args[0];
            String resolvedType = (String) args[1];
            int flags = MethodParameterUtils.toInt(args[2]);
            // Round 4 (ported): Katana services stay hidden, and Custom
            // Tabs must look unsupported inside clones (its OAuth redirect
            // cannot return here).
            // DevxyzClone 1.1.1: the two kills are decoupled - the Katana
            // service kill follows the visibility gate (lifts when the
            // native route is active), the Custom Tabs kill follows the
            // OAuth route being in play for this caller.
            if (shouldHideFacebookService(intent)) {
                diagLog("resolveService caller=" + BlackBoxCore.getAppPackageName()
                        + " " + diagIntentSummary(intent) + " -> null (katana hidden)");
                return null;
            }
            if (isCustomTabsServiceIntent(intent) && isCustomTabsDisabledInClone()) {
                diagLog("resolveService caller=" + BlackBoxCore.getAppPackageName()
                        + " " + diagIntentSummary(intent) + " -> null (custom tabs disabled in clones)");
                return null;
            }
            ResolveInfo resolveInfo = BlackBoxCore.getBPackageManager().resolveService(intent, flags, resolvedType, BlackBoxCore.getUserId());
            if (resolveInfo != null) {
                return resolveInfo;
            }
            MethodParameterUtils.replaceUserIdIfNeeded(args, args.length - 1);
            Object hostOut = method.invoke(who, args);
            if (hostOut instanceof ResolveInfo && isFacebookAppHiddenFromCurrentApp()) {
                ResolveInfo hostRi = (ResolveInfo) hostOut;
                if (hostRi.serviceInfo != null
                        && PKG_FACEBOOK_KATANA.equals(hostRi.serviceInfo.packageName)) {
                    diagLog("resolveService caller=" + BlackBoxCore.getAppPackageName()
                            + " " + diagIntentSummary(intent) + " -> null (katana host result suppressed)");
                    return null;
                }
            }
            return hostOut;
        }
    }

    // Ported: this hook does not exist in stock NewBlackbox, so without it
    // a clone's queryIntentActivities was answered by the REAL
    // PackageManager (Katana and SetupWizard fully visible to SDK probes).
    @ProxyMethod("queryIntentActivities")
    public static class QueryIntentActivities extends MethodHook {
        @Override
        @SuppressWarnings("unchecked")
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Intent intent = MethodParameterUtils.getFirstParam(args, Intent.class);
            String type = MethodParameterUtils.getFirstParam(args, String.class);
            Integer flags = MethodParameterUtils.getFirstParam(args, Integer.class);

            // DevxyzClone 1.0.10 (ported): FB redirect probes pass through
            // to the virtual registry (see ResolveIntent); log only.
            if (isFacebookRedirectQuery(intent)) {
                diagLog("queryIntentActivities caller=" + BlackBoxCore.getAppPackageName()
                        + " " + diagIntentSummary(intent)
                        + " (FB redirect probe: passing through to virtual registry)");
            }

            List<ResolveInfo> resolves = BlackBoxCore.getBPackageManager()
                    .queryIntentActivities(intent, flags, type, BActivityThread.getUserId());

            if (resolves != null && !resolves.isEmpty()) {
                // DevxyzClone fix (round 2, ported): strip the real
                // Facebook app from results.
                if (isFacebookAppHiddenFromCurrentApp()) {
                    resolves = filterOutFacebookActivities(resolves);
                }
                if (diagIsFacebookIntent(intent)) {
                    StringBuilder pkgs = new StringBuilder();
                    for (ResolveInfo ri : resolves) {
                        if (ri != null && ri.activityInfo != null) {
                            pkgs.append(ri.activityInfo.packageName).append(',');
                        }
                    }
                    diagLog("queryIntentActivities caller=" + BlackBoxCore.getAppPackageName()
                            + " " + diagIntentSummary(intent) + " -> virtual [" + pkgs + "]");
                }
                if (BuildCompat.isN()) {
                    return ParceledListSliceCompat.create(resolves);
                }
                return resolves;
            }

            MethodParameterUtils.replaceUserIdIfNeeded(args, args.length - 1);
            Object hostOut = method.invoke(who, args);

            if (!isFacebookAppHiddenFromCurrentApp()) {
                return hostOut;
            }

            List<ResolveInfo> hostList = null;
            if (hostOut instanceof List) {
                hostList = (List<ResolveInfo>) hostOut;
            } else if (ParceledListSliceCompat.isParceledListSlice(hostOut)) {
                try {
                    hostList = Reflector.with(hostOut).method("getList").call();
                } catch (Throwable ignored) {
                }
            }

            hostList = filterOutFacebookActivities(hostList);
            if (diagIsFacebookIntent(intent)) {
                StringBuilder pkgs = new StringBuilder();
                if (hostList != null) {
                    for (ResolveInfo ri : hostList) {
                        if (ri != null && ri.activityInfo != null) {
                            pkgs.append(ri.activityInfo.packageName).append(',');
                        }
                    }
                }
                diagLog("queryIntentActivities caller=" + BlackBoxCore.getAppPackageName()
                        + " " + diagIntentSummary(intent) + " -> host [" + pkgs + "]");
            }
            if (hostList == null) {
                return hostOut;
            }
            if (BuildCompat.isN()) {
                return ParceledListSliceCompat.create(hostList);
            }
            return hostList;
        }
    }

    // Ported: also absent from stock NewBlackbox.
    @ProxyMethod("queryIntentServices")
    public static class QueryIntentServices extends MethodHook {
        @Override
        @SuppressWarnings("unchecked")
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Intent intent = MethodParameterUtils.getFirstParam(args, Intent.class);
            // DevxyzClone 1.1.1: decoupled like ResolveService - Katana
            // service queries follow the visibility gate, Custom Tabs
            // service queries follow the OAuth-route predicate.
            if (intent != null && isFacebookAppHiddenFromCurrentApp()
                    && shouldHideFacebookService(intent)) {
                diagLog("queryIntentServices caller=" + BlackBoxCore.getAppPackageName()
                        + " " + diagIntentSummary(intent) + " -> [] (hidden in clones)");
                if (BuildCompat.isN()) {
                    return ParceledListSliceCompat.create(new ArrayList<ResolveInfo>());
                }
                return new ArrayList<ResolveInfo>();
            }
            if (intent != null && isCustomTabsServiceIntent(intent)
                    && isCustomTabsDisabledInClone()) {
                diagLog("queryIntentServices caller=" + BlackBoxCore.getAppPackageName()
                        + " " + diagIntentSummary(intent) + " -> [] (custom tabs disabled in clones)");
                if (BuildCompat.isN()) {
                    return ParceledListSliceCompat.create(new ArrayList<ResolveInfo>());
                }
                return new ArrayList<ResolveInfo>();
            }
            MethodParameterUtils.replaceUserIdIfNeeded(args, args.length - 1);
            Object hostOut = method.invoke(who, args);
            if (!isFacebookAppHiddenFromCurrentApp()) {
                return hostOut;
            }
            List<ResolveInfo> hostList = null;
            if (hostOut instanceof List) {
                hostList = (List<ResolveInfo>) hostOut;
            } else if (ParceledListSliceCompat.isParceledListSlice(hostOut)) {
                try {
                    hostList = Reflector.with(hostOut).method("getList").call();
                } catch (Throwable ignored) {
                }
            }
            if (hostList == null) {
                return hostOut;
            }
            hostList = filterOutFacebookServices(hostList);
            if (BuildCompat.isN()) {
                return ParceledListSliceCompat.create(hostList);
            }
            return hostList;
        }
    }

    @ProxyMethod("setComponentEnabledSetting")
    public static class SetComponentEnabledSetting extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return 0;
        }
    }

    @ProxyMethod("getPackageInfo")
    public static class GetPackageInfo extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String packageName = (String) args[0];
            int flags = MethodParameterUtils.toInt(args[1]);

            // DevxyzClone fix (round 2, ported): the real Facebook app is
            // invisible to clones.
            if (shouldHideFacebookApp(packageName)) {
                return null;
            }

            if ("com.android.vending".equals(packageName)) {
                return createFakeGooglePlayServicesPackageInfo();
            }
            
            PackageInfo packageInfo = BlackBoxCore.getBPackageManager().getPackageInfo(packageName, flags, BlackBoxCore.getUserId());
            if (packageInfo != null) {
                
                // DevxyzClone 1.1.1 (STUDY-KATANA-NATIVE condition B;
                // ported back from the frozen fork's round 1, where this
                // exact block shipped in GetPackageInfo): a virtual app
                // that asks for the HOST package's info is almost always
                // asking about ITSELF through the proxy identity (its
                // framework package name is the host's). SDKs such as
                // Facebook Login compute the app's key hash from these
                // signatures; returning the host's real signatures makes
                // the SDK present the host's identity and the login is
                // rejected ("Invalid key hash"). Substitute the current
                // virtual app's own signatures so the app presents its
                // genuine signing identity, exactly as on a real device.
                if (packageName != null && packageName.equals(BlackBoxCore.getHostPkg())
                        && shouldAttachSigningInfo(flags)) {
                    replaceSignaturesWithCurrentApp(packageInfo, flags);
                }

                if (packageInfo.requestedPermissions != null && packageInfo.requestedPermissionsFlags != null) {
                    for (int i = 0; i < packageInfo.requestedPermissions.length; i++) {
                        String perm = packageInfo.requestedPermissions[i];
                        if (perm != null && (perm.equals(android.Manifest.permission.RECORD_AUDIO)
                                || perm.equals("android.permission.FOREGROUND_SERVICE_MICROPHONE")
                                || perm.equals(android.Manifest.permission.MODIFY_AUDIO_SETTINGS)
                                || perm.equals(android.Manifest.permission.CAPTURE_AUDIO_OUTPUT))) {
                            packageInfo.requestedPermissionsFlags[i] |= PackageInfo.REQUESTED_PERMISSION_GRANTED;
                        }
                    }
                }
                // DevxyzClone 1.1.5 (STUDY-KATANA-SIDE §5, observation
                // hook 3): the signature answer actually returned for the
                // two packages caller validation depends on, hashed the
                // way Facebook computes an app key hash. Genuine ML =
                // 1lnFOXqPKltvNInlRqOJylT9KPw= ; the re-signed lab APK =
                // 3yzO9SY58ZfUVehKbaQvtxQbMj8= ; Katana's own hash under
                // q=<hostPkg> from a katana caller = the §2 trap caught
                // in the act.
                if (shouldAttachSigningInfo(flags) && packageName != null
                        && ("com.mobile.legends".equals(packageName)
                        || packageName.equals(BlackBoxCore.getHostPkg()))) {
                    diagLog("sigAnswer caller=" + BlackBoxCore.getAppPackageName()
                            + " q=" + packageName
                            + " hash=" + diagKeyHash(packageInfo.signatures));
                }
                return packageInfo;
            }
            if (AppSystemEnv.isOpenPackage(packageName)) {
                Object result = method.invoke(who, args);
                // DevxyzClone 1.1.1 (condition B): same self-identity fix
                // for the passthrough path - never let the host's real
                // signatures reach a virtual app asking about itself.
                if (result instanceof PackageInfo && packageName != null
                        && packageName.equals(BlackBoxCore.getHostPkg())
                        && shouldAttachSigningInfo(flags)) {
                    replaceSignaturesWithCurrentApp((PackageInfo) result, flags);
                }
                return result;
            }
            return null;
        }
        
        private boolean shouldAttachSigningInfo(int flags) {
            // GET_SIGNATURES is deprecated but still used by some libs.
            final int GET_SIGNATURES = 0x00000040;
            // GET_SIGNING_CERTIFICATES replaces GET_SIGNATURES on P+.
            final int GET_SIGNING_CERTIFICATES = 0x08000000;
            return (flags & (GET_SIGNATURES | GET_SIGNING_CERTIFICATES)) != 0;
        }

        private void attachSigningInfo(PackageInfo target, PackageInfo source) {
            if (target == null || source == null) {
                return;
            }
            try {
                if (source.signingInfo != null) {
                    target.signingInfo = source.signingInfo;
                }
            } catch (Throwable ignored) {
            }
            try {
                if (source.signatures != null && source.signatures.length > 0) {
                    target.signatures = source.signatures;
                }
            } catch (Throwable ignored) {
            }
        }

        // DevxyzClone fix (round 1 in the frozen fork, ported back in
        // 1.1.1 per STUDY-KATANA-NATIVE condition B): replace a
        // PackageInfo's signatures with the current virtual app's own
        // signatures (see the call sites in hook() above). No-op in the
        // host process itself, where the "current app" IS the host.
        private void replaceSignaturesWithCurrentApp(PackageInfo target, int flags) {
            if (target == null) {
                return;
            }
            try {
                String hostPkg = BlackBoxCore.getHostPkg();
                String currentPkg = BlackBoxCore.getAppPackageName();
                if (hostPkg == null || currentPkg == null || hostPkg.equals(currentPkg)) {
                    return;
                }
                // DevxyzClone 1.1.5 (STUDY-KATANA-SIDE §2, belt-and-
                // braces for §5 #1): this helper is only ever invoked for
                // HOST-package answers. A Meta-family querier asking
                // about the host is not asking about itself - it is
                // validating a CALLER whose identity collapsed to the
                // host (ActivityStack's getCallingPackage fallback).
                // Substituting the querier's own certs would hand Katana
                // its own key hash as the "caller's", which Facebook's
                // server rejects at the token mint. Never substitute for
                // a Meta-family querier (katana/orca/wakizashi/aura).
                if (isMetaFamilyCaller(currentPkg)
                        || PKG_FACEBOOK_KATANA.equals(currentPkg)) {
                    diagLog("sigSubst SKIPPED meta-querier caller=" + currentPkg
                            + " q=" + hostPkg);
                    return;
                }
                PackageInfo own = BlackBoxCore.getBPackageManager()
                        .getPackageInfo(currentPkg, flags, BlackBoxCore.getUserId());
                if (own == null) {
                    return;
                }
                attachSigningInfo(target, own);
                // DevxyzClone 1.1.1 diagnostics: record that a host-
                // package answer left with the caller's own identity.
                diagLog("getPackageInfo sigSubst applied caller=" + currentPkg
                        + " q=" + hostPkg
                        + " sigHash=" + diagKeyHash(target.signatures)
                        + " signingInfoHash=" + diagSigningInfoHash(target.signingInfo));
            } catch (Throwable ignored) {
            }
        }

        private PackageInfo createFakeGooglePlayServicesPackageInfo() {
            PackageInfo packageInfo = new PackageInfo();
            packageInfo.packageName = "com.android.vending";
            packageInfo.versionName = "33.8.16-21";
            packageInfo.versionCode = 83381621;
            
            ApplicationInfo appInfo = new ApplicationInfo();
            appInfo.packageName = "com.android.vending";
            appInfo.name = "Google Play Store";
            appInfo.flags = ApplicationInfo.FLAG_SYSTEM;
            appInfo.uid = 10001; 
            packageInfo.applicationInfo = appInfo;
            
            Slog.d(TAG, "GetPackageInfo: Providing fake Google Play Services info");
            return packageInfo;
        }
    }

    // Ported: absent from stock NewBlackbox - this AIDL entry point
    // otherwise bypasses the Katana hiding entirely with real signatures.
    // (The old base's host-signature substitution block was deliberately
    // NOT ported in 1.1.0; DevxyzClone 1.1.1 ports it back per
    // STUDY-KATANA-NATIVE condition B - see the inline block below and
    // GetPackageInfo's replaceSignaturesWithCurrentApp.)
    @ProxyMethod("getPackageInfoVersioned")
    public static class GetPackageInfoVersioned extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            android.content.pm.VersionedPackage vp =
                    (android.content.pm.VersionedPackage) args[0];
            String packageName = vp != null ? vp.getPackageName() : null;
            int flags = MethodParameterUtils.toInt(args[1]);
            if (packageName == null) {
                return method.invoke(who, args);
            }
            if (shouldHideFacebookApp(packageName)) {
                diagLog("getPackageInfoVersioned caller=" + BlackBoxCore.getAppPackageName()
                        + " q=" + packageName + " -> null (katana hidden)");
                return null;
            }
            PackageInfo packageInfo = BlackBoxCore.getBPackageManager()
                    .getPackageInfo(packageName, flags, BlackBoxCore.getUserId());
            if (packageInfo != null) {
                // DevxyzClone 1.1.1 (condition B): same host-signature
                // substitution as GetPackageInfo, in the frozen fork's
                // inline form for this entry point - host-package answers
                // carry the caller's own signatures.
                if (packageName.equals(BlackBoxCore.getHostPkg())
                        && (flags & (0x00000040 | 0x08000000)) != 0) {
                    try {
                        String cur = BlackBoxCore.getAppPackageName();
                        String host = BlackBoxCore.getHostPkg();
                        if (cur != null && host != null && !host.equals(cur)) {
                            // DevxyzClone 1.1.5 (STUDY-KATANA-SIDE §2):
                            // identical meta-querier skip as GetPackageInfo's
                            // replaceSignaturesWithCurrentApp - the
                            // substitution is inlined at this entry point,
                            // so the guard is inlined with it (see
                            // CHANGES-1.1.5.md).
                            if (isMetaFamilyCaller(cur)
                                    || PKG_FACEBOOK_KATANA.equals(cur)) {
                                diagLog("sigSubst SKIPPED meta-querier caller=" + cur
                                        + " q=" + packageName);
                            } else {
                                PackageInfo own = BlackBoxCore.getBPackageManager()
                                        .getPackageInfo(cur, flags, BlackBoxCore.getUserId());
                                if (own != null) {
                                    if (own.signingInfo != null) {
                                        packageInfo.signingInfo = own.signingInfo;
                                    }
                                    if (own.signatures != null && own.signatures.length > 0) {
                                        packageInfo.signatures = own.signatures;
                                    }
                                    diagLog("getPackageInfoVersioned sigSubst applied caller=" + cur
                                            + " q=" + packageName
                                            + " sigHash=" + diagKeyHash(packageInfo.signatures)
                                            + " signingInfoHash=" + diagSigningInfoHash(packageInfo.signingInfo));
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
                // DevxyzClone 1.1.5 (STUDY-KATANA-SIDE §5, observation
                // hook 3): same sigAnswer line as GetPackageInfo - this
                // entry point returns the same BPMS answers, so the
                // study's insertion point (after the BPMS answer, before
                // return) applies identically. The real-PM passthrough
                // branch below is not a BPMS answer and stays unlogged.
                if ((flags & (0x00000040 | 0x08000000)) != 0
                        && ("com.mobile.legends".equals(packageName)
                        || packageName.equals(BlackBoxCore.getHostPkg()))) {
                    diagLog("sigAnswer caller=" + BlackBoxCore.getAppPackageName()
                            + " q=" + packageName
                            + " hash=" + diagKeyHash(packageInfo.signatures));
                }
                return packageInfo;
            }
            if (AppSystemEnv.isOpenPackage(packageName)) {
                MethodParameterUtils.replaceUserIdIfNeeded(args, args.length - 1);
                return method.invoke(who, args);
            }
            return null;
        }
    }

    // Ported: absent from stock NewBlackbox.
    @ProxyMethod("checkSignatures")
    public static class CheckSignatures extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String p1 = (String) args[0];
            String p2 = (String) args[1];
            if (shouldHideFacebookApp(p1) || shouldHideFacebookApp(p2)) {
                diagLog("checkSignatures caller=" + BlackBoxCore.getAppPackageName()
                        + " " + p1 + " vs " + p2 + " -> NO_MATCH (katana hidden)");
                return PackageManager.SIGNATURE_NO_MATCH;
            }
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getPackageUid")
    public static class GetPackageUid extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getProviderInfo")
    public static class GetProviderInfo extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            ComponentName componentName = (ComponentName) args[0];
            int flags = MethodParameterUtils.toInt(args[1]);
            // Round 4 (ported): Meta provider components stay hidden from
            // clones.
            if (componentName != null && shouldHideFacebookApp(componentName.getPackageName())) {
                diagLog("getProviderInfo caller=" + BlackBoxCore.getAppPackageName()
                        + " cmp=" + componentName.flattenToShortString() + " -> null (katana hidden)");
                return null;
            }
            ProviderInfo providerInfo = BlackBoxCore.getBPackageManager().getProviderInfo(componentName, flags, BlackBoxCore.getUserId());
            if (providerInfo != null)
                return providerInfo;
            if (AppSystemEnv.isOpenPackage(componentName)) {
                return method.invoke(who, args);
            }
            return null;
        }
    }

    @ProxyMethod("getReceiverInfo")
    public static class GetReceiverInfo extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            ComponentName componentName = (ComponentName) args[0];
            int flags = MethodParameterUtils.toInt(args[1]);
            ActivityInfo receiverInfo = BlackBoxCore.getBPackageManager().getReceiverInfo(componentName, flags, BlackBoxCore.getUserId());
            if (receiverInfo != null)
                return receiverInfo;
            if (AppSystemEnv.isOpenPackage(componentName)) {
                return method.invoke(who, args);
            }
            return null;
        }
    }

    @ProxyMethod("getActivityInfo")
    public static class GetActivityInfo extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            ComponentName componentName = (ComponentName) args[0];
            int flags = MethodParameterUtils.toInt(args[1]);
            ActivityInfo activityInfo = BlackBoxCore.getBPackageManager().getActivityInfo(componentName, flags, BlackBoxCore.getUserId());
            if (activityInfo != null)
                return activityInfo;
            if (AppSystemEnv.isOpenPackage(componentName)) {
                return method.invoke(who, args);
            }
            return null;
        }
    }

    @ProxyMethod("getServiceInfo")
    public static class GetServiceInfo extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            ComponentName componentName = (ComponentName) args[0];
            int flags = MethodParameterUtils.toInt(args[1]);
            ServiceInfo serviceInfo = BlackBoxCore.getBPackageManager().getServiceInfo(componentName, flags, BlackBoxCore.getUserId());
            if (serviceInfo != null)
                return serviceInfo;
            if (AppSystemEnv.isOpenPackage(componentName)) {
                return method.invoke(who, args);
            }
            return null;
        }
    }

    @ProxyMethod("getInstalledApplications")
    public static class GetInstalledApplications extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int flags = MethodParameterUtils.toInt(args[0]);
            List<ApplicationInfo> installedApplications = BlackBoxCore.getBPackageManager().getInstalledApplications(flags, BlackBoxCore.getUserId());
            return ParceledListSliceCompat.create(installedApplications);
        }
    }

    @ProxyMethod("getInstalledPackages")
    public static class GetInstalledPackages extends MethodHook {

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int flags = MethodParameterUtils.toInt(args[0]);
            List<PackageInfo> installedPackages = BlackBoxCore.getBPackageManager().getInstalledPackages(flags, BlackBoxCore.getUserId());
            return ParceledListSliceCompat.create(installedPackages);
        }
    }

    @ProxyMethod("getApplicationInfo")
    public static class GetApplicationInfo extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String packageName = (String) args[0];

            int flags = MethodParameterUtils.toInt(args[1]);

            // DevxyzClone fix (round 2, ported): the real Facebook app is
            // invisible to clones.
            if (shouldHideFacebookApp(packageName)) {
                return null;
            }

            if (PKG_FACEBOOK_KATANA.equals(packageName) || packageName.equals(BlackBoxCore.getHostPkg())
                    || packageName.equals(BlackBoxCore.getAppPackageName())) {
                diagLog("getApplicationInfo caller=" + BlackBoxCore.getAppPackageName()
                        + " q=" + packageName + " flags=" + Integer.toHexString(flags));
            }

            ApplicationInfo applicationInfo = BlackBoxCore.getBPackageManager().getApplicationInfo(packageName, flags, BlackBoxCore.getUserId());
            if (applicationInfo != null) {
                return applicationInfo;
            }
            if (AppSystemEnv.isOpenPackage(packageName)) {
                return method.invoke(who, args);
            }
            return null;
        }
    }

    @ProxyMethod("queryContentProviders")
    public static class QueryContentProviders extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int flags = MethodParameterUtils.toInt(args[2]);
            List<ProviderInfo> providers = BlackBoxCore.getBPackageManager().
                    queryContentProviders(BlackBoxCore.getAppProcessName(), BlackBoxCore.getBUid(), flags, BlackBoxCore.getUserId());
            return ParceledListSliceCompat.create(providers);
        }
    }

    @ProxyMethod("queryIntentReceivers")
    public static class QueryBroadcastReceivers extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            Intent intent = MethodParameterUtils.getFirstParam(args, Intent.class);
            String type = MethodParameterUtils.getFirstParam(args, String.class);
            Integer flags = MethodParameterUtils.getFirstParam(args, Integer.class);
            List<ResolveInfo> resolves = BlackBoxCore.getBPackageManager().queryBroadcastReceivers(intent, flags, type, BActivityThread.getUserId());
            Slog.d(TAG, "queryIntentReceivers: " + resolves);

            
            if (BuildCompat.isN()) {
                return ParceledListSliceCompat.create(resolves);
            }

            
            return resolves;
        }
    }

    @ProxyMethod("resolveContentProvider")
    public static class ResolveContentProvider extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String authority = (String) args[0];
            int flags = MethodParameterUtils.toInt(args[1]);
            // Round 4 (ported): the real Katana PlatformProvider must not
            // leak. The stock fallthrough returns the REAL ProviderInfo
            // for every non-virtual authority, which by itself tells SDKs
            // the Facebook app exists.
            if (isMetaProviderAuthority(authority) && isFacebookAppHiddenFromCurrentApp()) {
                diagLog("resolveContentProvider caller=" + BlackBoxCore.getAppPackageName()
                        + " auth=" + authority + " -> null (meta provider hidden)");
                return null;
            }
            ProviderInfo providerInfo = BlackBoxCore.getBPackageManager().resolveContentProvider(authority, flags, BActivityThread.getUserId());
            if (providerInfo == null) {
                // Port adaptation: the old base re-queried the real PM and
                // suppressed Meta results; this base's fallthrough is the
                // raw method call, so capture its result and apply the
                // same suppression before returning it.
                Object hostOut = method.invoke(who, args);
                if (hostOut instanceof ProviderInfo
                        && isMetaPackage(((ProviderInfo) hostOut).packageName)
                        && isFacebookAppHiddenFromCurrentApp()) {
                    diagLog("resolveContentProvider caller=" + BlackBoxCore.getAppPackageName()
                            + " auth=" + authority + " -> null (meta provider suppressed)");
                    return null;
                }
                return hostOut;
            }
            return providerInfo;
        }
    }

    @ProxyMethod("canRequestPackageInstalls")
    public static class CanRequestPackageInstalls extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            MethodParameterUtils.replaceFirstAppPkg(args);
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getPackagesForUid")
    public static class GetPackagesForUid extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            int uid = (Integer) args[0];
            if (uid == BlackBoxCore.getHostUid()) {
                args[0] = BActivityThread.getBUid();
                uid = (int) args[0];
            }
            String[] packagesForUid = BlackBoxCore.getBPackageManager().getPackagesForUid(uid);
            Slog.d(TAG, args[0] + " , " + BActivityThread.getAppProcessName() + " GetPackagesForUid: " + Arrays.toString(packagesForUid));
            return packagesForUid;
        }
    }

    @ProxyMethod("getInstallerPackageName")
    public static class GetInstallerPackageName extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            
            return "com.android.vending";
        }
    }

    @ProxyMethod("getSharedLibraries")
    public static class GetSharedLibraries extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            
            return ParceledListSliceCompat.create(new ArrayList<>());
        }
    }

    @ProxyMethod("getComponentEnabledSetting")
    public static class getComponentEnabledSetting extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            ComponentName componentName = (ComponentName) args[0];
            String packageName = componentName.getPackageName();
            ApplicationInfo applicationInfo = BlackBoxCore.getBPackageManager().getApplicationInfo(packageName,0, BActivityThread.getUserId());





            if(applicationInfo != null){
                return PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
            }
            if (AppSystemEnv.isOpenPackage(componentName)) {
                return method.invoke(who, args);
            }
            // DevxyzClone 1.1.2 (F1; STUDY-MUSE-SETTINGS-CRASH candidate
            // 1a): this was a bare `throw new IllegalArgumentException()` -
            // the only bare throw in the whole PM hook surface, where
            // every sibling hook returns null or a default. A routine
            // miss must never throw into a guest: a caller that does not
            // armor this specific PM call (e.g. framework code running
            // during UI composition) dies instantly on it. Answer the
            // same default the first branch above already returns for
            // virtual packages, and leave a DiagLog line on record.
            diagLog("getComponentEnabledSetting caller=" + BActivityThread.getAppPackageName()
                    + " q=" + packageName
                    + " cmp=" + componentName.flattenToShortString()
                    + " -> COMPONENT_ENABLED_STATE_DEFAULT (1.1.2: default returned, no throw)");
            return PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
        }
    }





    @ProxyMethod("checkPermission")
    public static class SimpleAudioPermissionHook extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String permission = (String) args[0];
            String packageName = (String) args[1];
            
            
            if (isAudioPermission(permission)) {
                Slog.d(TAG, "SimpleAudioPermissionHook: Granting audio permission: " + permission + " to " + packageName);
                return PackageManager.PERMISSION_GRANTED;
            }

            
            if (isStorageOrMediaPermission(permission)) {
                Slog.d(TAG, "SimpleAudioPermissionHook: Granting storage/media permission: " + permission + " to " + packageName);
                return PackageManager.PERMISSION_GRANTED;
            }
            
            
            if (isNotificationOrXiaomiPermission(permission)) {
                Slog.d(TAG, "SimpleAudioPermissionHook: Granting notification/Xiaomi permission: " + permission + " to " + packageName);
                return PackageManager.PERMISSION_GRANTED;
            }
            
            
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("checkSelfPermission")
    public static class CheckSelfPermission extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String permission = (String) args[0];
            String packageName = (String) args[1];
            
            
            if (isAudioPermission(permission)) {
                Slog.d(TAG, "CheckSelfPermission: Granting audio permission: " + permission + " to " + packageName);
                return PackageManager.PERMISSION_GRANTED;
            }

            
            if (isStorageOrMediaPermission(permission)) {
                Slog.d(TAG, "CheckSelfPermission: Granting storage/media permission: " + permission + " to " + packageName);
                return PackageManager.PERMISSION_GRANTED;
            }
            
            
            if (isNotificationOrXiaomiPermission(permission)) {
                Slog.d(TAG, "CheckSelfPermission: Granting notification/Xiaomi permission: " + permission + " to " + packageName);
                return PackageManager.PERMISSION_GRANTED;
            }


            // DevxyzClone 1.1.7 (STUDY-RESEARCH-GAPS Lead A / PR #48 port):
            // the network branch this hook never had - without it a guest's
            // checkSelfPermission(INTERNET, guestPkg) falls through to the
            // real PM under the guest's own package name, and a DENIED
            // answer there is the documented cause of in-guest WebView
            // ERR_CACHE_MISS / blocked network loads on Android 15/16.
            if (isNetworkPermission(permission)) {
                Slog.d(TAG, "CheckSelfPermission: Granting network permission: " + permission + " to " + packageName);
                diagLog("checkSelfPermission INTERNET granted perm=" + permission + " pkg=" + packageName);
                return PackageManager.PERMISSION_GRANTED;
            }


            return method.invoke(who, args);
        }
    }

    @ProxyMethod("shouldShowRequestPermissionRationale")
    public static class ShouldShowRequestPermissionRationale extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String permission = (String) args[0];
            String packageName = (String) args[1];
            
            
            if (isAudioPermission(permission)) {
                Slog.d(TAG, "ShouldShowRequestPermissionRationale: Not showing rationale for audio permission: " + permission);
                return false;
            }

            
            if (isStorageOrMediaPermission(permission)) {
                Slog.d(TAG, "ShouldShowRequestPermissionRationale: Not showing rationale for storage/media permission: " + permission);
                return false;
            }
            
            
            if (isNotificationOrXiaomiPermission(permission)) {
                Slog.d(TAG, "ShouldShowRequestPermissionRationale: Not showing rationale for notification/Xiaomi permission: " + permission);
                return false;
            }
            
            
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("requestPermissions")
    public static class RequestPermissions extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String[] permissions = (String[]) args[0];
            String packageName = (String) args[1];
            
            
            
            if (permissions != null) {
                Slog.d(TAG, "RequestPermissions: Allowing permission request flow for: " + java.util.Arrays.toString(permissions));
            }
            
            return method.invoke(who, args);
        }
    }

    @ProxyMethod("getDrawable")
    public static class DisableIconLoading extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            
            Slog.d(TAG, "Blocking icon loading to prevent resource errors");
            return null; 
        }
    }

    
    private static boolean isStorageOrMediaPermission(String permission) {
        if (permission == null) return false;
        
        if (permission.equals(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                || permission.equals(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            return true;
        }
        
        if (permission.equals(android.Manifest.permission.READ_MEDIA_AUDIO)
                || permission.equals(android.Manifest.permission.READ_MEDIA_VIDEO)
                || permission.equals(android.Manifest.permission.READ_MEDIA_IMAGES)
                || permission.equals("android.permission.READ_MEDIA_VISUAL")
                || permission.equals("android.permission.READ_MEDIA_AURAL")
                || permission.equals(android.Manifest.permission.ACCESS_MEDIA_LOCATION)) {
            return true;
        }
        
        if (permission.equals("android.permission.READ_MEDIA_AUDIO_USER_SELECTED")
                || permission.equals("android.permission.READ_MEDIA_VIDEO_USER_SELECTED")
                || permission.equals("android.permission.READ_MEDIA_IMAGES_USER_SELECTED")
                || permission.equals("android.permission.READ_MEDIA_VISUAL_USER_SELECTED")
                || permission.equals("android.permission.READ_MEDIA_AURAL_USER_SELECTED")) {
            return true;
        }
        return false;
    }

    
    // DevxyzClone 1.1.7 (STUDY-RESEARCH-GAPS Lead A / PR #48 port):
    // the network permission set, mirroring the isNetworkPermission()
    // helper reinstated in IActivityManagerProxy.
    private static boolean isNetworkPermission(String permission) {
        if (permission == null) return false;
        return permission.equals(android.Manifest.permission.INTERNET)
                || permission.equals(android.Manifest.permission.ACCESS_NETWORK_STATE)
                || permission.equals(android.Manifest.permission.ACCESS_WIFI_STATE)
                || permission.equals(android.Manifest.permission.CHANGE_NETWORK_STATE)
                || permission.equals(android.Manifest.permission.CHANGE_WIFI_STATE)
                || permission.equals(android.Manifest.permission.CHANGE_WIFI_MULTICAST_STATE);
    }


    private static boolean isAudioPermission(String permission) {
        if (permission == null) return false;
        return permission.equals(android.Manifest.permission.RECORD_AUDIO)
                || permission.equals(android.Manifest.permission.CAPTURE_AUDIO_OUTPUT)
                || permission.equals(android.Manifest.permission.MODIFY_AUDIO_SETTINGS)
                || permission.equals("android.permission.FOREGROUND_SERVICE_MICROPHONE")
                || permission.equals("android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION")
                || permission.equals("android.permission.FOREGROUND_SERVICE_CAMERA")
                || permission.equals("android.permission.FOREGROUND_SERVICE_LOCATION")
                || permission.equals("android.permission.FOREGROUND_SERVICE_HEALTH")
                || permission.equals("android.permission.FOREGROUND_SERVICE_DATA_SYNC")
                || permission.equals("android.permission.FOREGROUND_SERVICE_SPECIAL_USE")
                || permission.equals("android.permission.FOREGROUND_SERVICE_SYSTEM_EXEMPTED")
                || permission.equals("android.permission.FOREGROUND_SERVICE_PHONE_CALL")
                || permission.equals("android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE");
    }
    
    
    private static boolean isNotificationOrXiaomiPermission(String permission) {
        if (permission == null) return false;
        
        
        if (permission.equals("android.permission.POST_NOTIFICATIONS")) {
            return true;
        }
        
        
        if (permission.equals("miui.permission.USE_INTERNAL_GENERAL_API") ||
            permission.equals("miui.permission.OPTIMIZE_POWER") ||
            permission.equals("miui.permission.RUN_IN_BACKGROUND") ||
            permission.equals("miui.permission.POST_NOTIFICATIONS") ||
            permission.equals("miui.permission.AUTO_START") ||
            permission.equals("miui.permission.BACKGROUND_POPUP_WINDOW") ||
            permission.equals("miui.permission.SHOW_WHEN_LOCKED") ||
            permission.equals("miui.permission.TURN_SCREEN_ON")) {
            return true;
        }
        
        return false;
    }

    @ProxyMethod("setSplashScreenTheme")
    public static class SetSplashScreenTheme extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            
            
            
            String packageName = args.length > 0 ? (String) args[0] : "unknown";
            Slog.d(TAG, "SetSplashScreenTheme: Bypassing UID check for package: " + packageName);
            
            
            boolean isXiaomi = BuildCompat.isMIUI() || 
                              Build.MANUFACTURER.toLowerCase().contains("xiaomi") ||
                              Build.BRAND.toLowerCase().contains("xiaomi") ||
                              Build.DISPLAY.toLowerCase().contains("hyperos");
                              
            if (isXiaomi) {
                Slog.d(TAG, "SetSplashScreenTheme: Detected Xiaomi/HyperOS, using enhanced bypass");
                
                return null;
            }
            
            
            try {
                return method.invoke(who, args);
            } catch (SecurityException e) {
                Slog.w(TAG, "SetSplashScreenTheme: SecurityException caught, bypassing: " + e.getMessage());
                return null;
            } catch (Exception e) {
                
                if (e.getCause() instanceof SecurityException) {
                    Slog.w(TAG, "SetSplashScreenTheme: SecurityException (wrapped) caught, bypassing: " + e.getCause().getMessage());
                    return null;
                }
                throw e; 
            }
        }
    }

    
    public static class XiaomiSecurityBypass extends MethodHook {
        private static final String[] XIAOMI_SECURITY_METHODS = {
            "setApplicationEnabledSetting",
            "setComponentEnabledSetting", 
            "setInstallLocation",
            "setInstallerPackageName",
            "setPackageStoppedState",
            "setSystemAppState",
            "setApplicationCategoryHint",
            "setApplicationHiddenSettingAsUser",
            "setBlockUninstallForUser",
            "setDefaultBrowserPackageNameAsUser",
            "setDistractingPackageRestrictionsAsUser",
            "setPackagesSuspendedAsUser",
            "setUpdateAvailable",
            "setRequiredForSystemUser",
            "setSystemAppHiddenUntilInstalled",
            "setHarmfulAppWarningEnabled",
            "setKeepUninstalledPackages",
            "verifyIntentFilter",
            "verifyPendingInstall",
            "extendVerificationTimeout",
            "setDefaultHomeActivity",
            "resetApplicationPreferences",
            "clearApplicationProfileData",
            "clearApplicationUserData",
            "deleteApplicationCacheFiles",
            "deleteApplicationCacheFilesAsUser",
            "freeStorageAndNotify",
            "freeStorage",
            "movePackage",
            "movePackageToSd",
            "movePrimaryStorage"
        };

        @Override
        public boolean isEnable() {
            
            return BuildCompat.isMIUI() || 
                   Build.MANUFACTURER.toLowerCase().contains("xiaomi") ||
                   Build.BRAND.toLowerCase().contains("xiaomi") ||
                   Build.DISPLAY.toLowerCase().contains("hyperos");
        }

        @Override
        public String getMethodName() {
            return null; 
        }

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            String methodName = method.getName();
            
            
            for (String securityMethod : XIAOMI_SECURITY_METHODS) {
                if (securityMethod.equals(methodName)) {
                    Slog.d(TAG, "XiaomiSecurityBypass: Intercepting " + methodName + " on Xiaomi/HyperOS");
                    
                    
                    if (method.getReturnType() == void.class) {
                        return null;
                    }
                    
                    else if (method.getReturnType() == boolean.class) {
                        return true;
                    }
                    
                    else if (method.getReturnType() == int.class) {
                        return 0;
                    }
                    
                    else {
                        return null;
                    }
                }
            }
            
            
            try {
                return method.invoke(who, args);
            } catch (SecurityException e) {
                Slog.w(TAG, "XiaomiSecurityBypass: SecurityException in " + methodName + ", bypassing: " + e.getMessage());
                
                if (method.getReturnType() == boolean.class) {
                    return false;
                } else if (method.getReturnType() == int.class) {
                    return -1;
                } else {
                    return null;
                }
            } catch (Exception e) {
                if (e.getCause() instanceof SecurityException) {
                    Slog.w(TAG, "XiaomiSecurityBypass: SecurityException (wrapped) in " + methodName + ", bypassing: " + e.getCause().getMessage());
                    if (method.getReturnType() == boolean.class) {
                        return false;
                    } else if (method.getReturnType() == int.class) {
                        return -1;
                    } else {
                        return null;
                    }
                }
                throw e;
            }
        }
    }
}
