package top.niunaijun.blackbox.fake.service;

import android.content.Context;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import black.android.app.role.BRIRoleManagerStub;
import black.android.os.BRServiceManager;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.service.base.ValueMethodProxy;
import top.niunaijun.blackbox.utils.DiagLog;
import top.niunaijun.blackbox.utils.compat.ParceledListSliceCompat;

/**
 * DevxyzMuse 1.0.2 fix (engine-level, applies to every cloned app),
 * ported verbatim into DevxyzClone 1.1.2 (the centerpiece fix; the
 * original text follows):
 *
 * A cloned app that opens a screen querying role state (e.g. Muse's
 * Settings screen composing its "Default assistant" card, which calls
 * RoleManager.isRoleHeld) talks to the REAL RoleService. RoleService
 * verifies the caller with AppOpsManager.checkPackage(guestPackage,
 * callingUid) - but the calling uid is the HOST app's uid, which does
 * not own the guest package on the real system, so the call dies with
 * SecurityException "Package <guest> does not belong to <host uid>".
 * Thrown inside UI composition, that exception kills the whole guest
 * process instantly (Muse clone: gear tap -> black screen -> back to
 * the clone list, Settings never renders).
 *
 * This proxy answers the role queries at the guest boundary with the
 * answers that are TRUE for a clone: a clone never holds a system role
 * (isRoleHeld = false), the roles themselves exist on the device
 * (isRoleAvailable = true), and no package is reported as a role
 * holder. Unhooked methods pass through to the real service.
 *
 * DevxyzClone 1.1.2 addition (the 1.0.2 original logged nothing):
 * isRoleHeld / isRoleHeldAsUser go through RoleHeldMethodProxy, which
 * returns the same false but writes ONE DiagLog line per (method,
 * role) per process, so a test round can prove the proxy answered the
 * exact call that used to kill the guest.
 */
public class IRoleManagerProxy extends BinderInvocationStub {

    public IRoleManagerProxy() {
        super(BRServiceManager.get().getService(Context.ROLE_SERVICE));
    }

    @Override
    protected Object getWho() {
        return BRIRoleManagerStub.get().asInterface(BRServiceManager.get().getService(Context.ROLE_SERVICE));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService(Context.ROLE_SERVICE);
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @Override
    protected void onBindMethod() {
        super.onBindMethod();
        addMethodHook(new RoleHeldMethodProxy("isRoleHeld"));
        addMethodHook(new RoleHeldMethodProxy("isRoleHeldAsUser"));
        addMethodHook(new ValueMethodProxy("isRoleAvailable", true));
        addMethodHook(new ValueMethodProxy("isRoleAvailableAsUser", true));
        addMethodHook(new ValueMethodProxy("getRoleHolders", ParceledListSliceCompat.create(new ArrayList<>())));
        addMethodHook(new ValueMethodProxy("getRoleHoldersAsUser", ParceledListSliceCompat.create(new ArrayList<>())));
    }

    /**
     * isRoleHeld answer (always false for a clone) with DiagLog
     * evidence, deduped per (method, role) per process so UI
     * recomposition cannot flood the log.
     */
    private static class RoleHeldMethodProxy extends MethodHook {
        private static final Set<String> sLogged = new HashSet<>();

        private final String mName;

        RoleHeldMethodProxy(String name) {
            mName = name;
        }

        @Override
        protected String getMethodName() {
            return mName;
        }

        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            try {
                String role = (args != null && args.length > 0) ? String.valueOf(args[0]) : "?";
                String key = mName + "|" + role;
                synchronized (sLogged) {
                    if (sLogged.add(key)) {
                        DiagLog.log("roleManager " + mName
                                + " caller=" + BActivityThread.getAppPackageName()
                                + " role=" + role
                                + " -> false (1.1.2 proxy)");
                    }
                }
            } catch (Throwable ignored) {
            }
            return false;
        }
    }
}
