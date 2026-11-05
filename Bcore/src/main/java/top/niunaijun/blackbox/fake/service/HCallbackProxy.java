package top.niunaijun.blackbox.fake.service;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.util.Log;

import androidx.annotation.NonNull;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import black.android.app.ActivityThreadActivityClientRecordContext;
import black.android.app.BRActivityClient;
import black.android.app.BRActivityClientActivityClientControllerSingleton;
import black.android.app.BRActivityManagerNative;
import black.android.app.BRActivityThread;
import black.android.app.BRActivityThreadActivityClientRecord;
import black.android.app.BRActivityThreadCreateServiceData;
import black.android.app.BRActivityThreadH;
import black.android.app.BRIActivityManager;
import black.android.app.servertransaction.BRActivityResultItem;
import black.android.app.servertransaction.BRClientTransaction;
import black.android.app.servertransaction.BRLaunchActivityItem;
import black.android.app.servertransaction.LaunchActivityItemContext;
import black.android.os.BRHandler;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.fake.hook.IInjectHook;
import top.niunaijun.blackbox.proxy.ProxyManifest;
import top.niunaijun.blackbox.proxy.record.ProxyActivityRecord;
import top.niunaijun.blackbox.utils.DiagLog;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.utils.compat.BuildCompat;



public class HCallbackProxy implements IInjectHook, Handler.Callback {
    public static final String TAG = "HCallbackStub";
    private Handler.Callback mOtherCallback;
    private AtomicBoolean mBeing = new AtomicBoolean(false);

    private Handler.Callback getHCallback() {
        return BRHandler.get(getH()).mCallback();
    }

    private Handler getH() {
        Object currentActivityThread = BlackBoxCore.mainThread();
        return BRActivityThread.get(currentActivityThread).mH();
    }

    @Override
    public void injectHook() {
        mOtherCallback = getHCallback();
        if (mOtherCallback != null && (mOtherCallback == this || mOtherCallback.getClass().getName().equals(this.getClass().getName()))) {
            mOtherCallback = null;
        }
        BRHandler.get(getH())._set_mCallback(this);
    }

    @Override
    public boolean isBadEnv() {
        Handler.Callback hCallback = getHCallback();
        return hCallback != null && hCallback != this;
    }

    @Override
    public boolean handleMessage(@NonNull Message msg) {
        if (!mBeing.getAndSet(true)) {
            try {
                if (BuildCompat.isPie()) {
                    if (msg.what == BRActivityThreadH.get().EXECUTE_TRANSACTION()) {
                        diagActivityResults(msg.obj);
                        if (handleLaunchActivity(msg.obj)) {
                            getH().sendMessageAtFrontOfQueue(Message.obtain(msg));
                            return true;
                        }
                    }
                } else {
                    if (msg.what == BRActivityThreadH.get().LAUNCH_ACTIVITY()) {
                        if (handleLaunchActivity(msg.obj)) {
                            getH().sendMessageAtFrontOfQueue(Message.obtain(msg));
                            return true;
                        }
                    }
                }
                if (msg.what == BRActivityThreadH.get().CREATE_SERVICE()) {
                    return handleCreateService(msg.obj);
                }
                if (mOtherCallback != null) {
                    return mOtherCallback.handleMessage(msg);
                }
                return false;
            } finally {
                mBeing.set(false);
            }
        }
        return false;
    }

    private Object getLaunchActivityItem(Object clientTransaction) {
        List<Object> mActivityCallbacks = BRClientTransaction.get(clientTransaction).mActivityCallbacks();

        if (mActivityCallbacks == null) {
            Slog.e(TAG, "mActivityCallbacks is null for clientTransaction: " + clientTransaction);
            return null;
        }

        for (Object obj : mActivityCallbacks) {
            if (BRLaunchActivityItem.getRealClass().getName().equals(obj.getClass().getCanonicalName())) {
                return obj;
            }
        }
        return null;
    }

    // DevxyzClone 1.1.5 (STUDY-KATANA-SIDE §5, observation hook 2):
    // log every ActivityResultItem a client transaction carries into
    // this guest process, so a device round shows the exact result a
    // callee returned - resultCode=0 (with error keys) reaching ML's
    // process proves Katana finished CANCELED and closes the delivery
    // question; resultCode=-1 with token extras would move the hunt
    // inside ML; absence of the line reopens the delivery chain.
    // Observation only: the transaction is never modified, and every
    // failure is swallowed - a logging failure must never break the
    // transaction it observes.
    private void diagActivityResults(Object clientTransaction) {
        try {
            if (clientTransaction == null) {
                return;
            }
            List<Object> callbacks = BRClientTransaction.get(clientTransaction).mActivityCallbacks();
            if (callbacks == null) {
                return;
            }
            String resultItemName = BRActivityResultItem.getRealClass().getName();
            for (Object item : callbacks) {
                try {
                    if (item == null
                            || !resultItemName.equals(item.getClass().getCanonicalName())) {
                        continue;
                    }
                    List resultInfos = BRActivityResultItem.get(item).mResultInfoList();
                    if (resultInfos == null) {
                        continue;
                    }
                    for (Object resultInfo : resultInfos) {
                        try {
                            if (resultInfo == null) {
                                continue;
                            }
                            Object who = readResultField(resultInfo, "mResultWho");
                            Object rc = readResultField(resultInfo, "mRequestCode");
                            Object code = readResultField(resultInfo, "mResultCode");
                            Object dataObj = readResultField(resultInfo, "mData");
                            Intent data = dataObj instanceof Intent ? (Intent) dataObj : null;
                            String keys = "none";
                            if (data != null && data.getExtras() != null) {
                                keys = String.valueOf(data.getExtras().keySet());
                            }
                            DiagLog.log("sendResult pkg=" + BActivityThread.getAppPackageName()
                                    + " who=" + (who != null ? String.valueOf(who) : "null")
                                    + " rc=" + (rc instanceof Number
                                            ? String.valueOf(((Number) rc).intValue()) : "?")
                                    + " resultCode=" + (code instanceof Number
                                            ? String.valueOf(((Number) code).intValue()) : "?")
                                    + " hasData=" + (data != null)
                                    + " keys=" + keys);
                        } catch (Throwable ignored) {
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
    }

    // Plain reflection for android.app.ResultInfo's fields - no
    // BlackReflection interface for ResultInfo exists in-tree (only
    // ActivityResultItem's), so its fields are read directly; callers
    // treat a null return as "unreadable".
    private static Object readResultField(Object target, String name) {
        try {
            java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean handleLaunchActivity(Object client) {
        Object r;
        if (BuildCompat.isPie()) {
            
            r = getLaunchActivityItem(client);
        } else {
            
            r = client;
        }
        if (r == null)
            return false;

        Intent intent;
        IBinder token;
        if (BuildCompat.isPie()) {
            intent = BRLaunchActivityItem.get(r).mIntent();
            token = BRClientTransaction.get(client).mActivityToken();
        } else {
            ActivityThreadActivityClientRecordContext clientRecordContext = BRActivityThreadActivityClientRecord.get(r);
            intent = clientRecordContext.intent();
            token = clientRecordContext.token();
        }

        if (intent == null)
            return false;

        ProxyActivityRecord stubRecord = ProxyActivityRecord.create(intent);
        ActivityInfo activityInfo = stubRecord.mActivityInfo;
        if (activityInfo != null) {
            if (BActivityThread.getAppConfig() == null) {
                BlackBoxCore.getBActivityManager().restartProcess(activityInfo.packageName, activityInfo.processName, stubRecord.mUserId);

                Intent launchIntentForPackage = BlackBoxCore.getBPackageManager().getLaunchIntentForPackage(activityInfo.packageName, stubRecord.mUserId);
                intent.setExtrasClassLoader(this.getClass().getClassLoader());
                ProxyActivityRecord.saveStub(intent, launchIntentForPackage, stubRecord.mActivityInfo, stubRecord.mActivityRecord, stubRecord.mUserId);
                if (BuildCompat.isPie()) {
                    LaunchActivityItemContext launchActivityItemContext = BRLaunchActivityItem.get(r);
                    launchActivityItemContext._set_mIntent(intent);
                    launchActivityItemContext._set_mInfo(activityInfo);
                } else {
                    ActivityThreadActivityClientRecordContext clientRecordContext = BRActivityThreadActivityClientRecord.get(r);
                    clientRecordContext._set_intent(intent);
                    clientRecordContext._set_activityInfo(activityInfo);
                }
                return true;
            }
            
            if (!BActivityThread.currentActivityThread().isInit()) {
                BActivityThread.currentActivityThread().bindApplication(activityInfo.packageName,
                        activityInfo.processName);
                return true;
            }

            int taskId = BRIActivityManager.get(BRActivityManagerNative.get().getDefault()).getTaskForActivity(token, false);
            BlackBoxCore.getBActivityManager().onActivityCreated(taskId, token, stubRecord.mActivityRecord);

            diagRemoveLaunchSecurityProtection(stubRecord.mTarget);

            if (BuildCompat.isS()) {
                // DevxyzClone 1.1.7 (STUDY-RESEARCH-GAPS Lead B, ZiTanIOI
                // fork port): one unified modern branch, replacing the
                // isTiramisu()/isS() pair under which Android 16 (SDK 36)
                // took an item-only swap - no launching-record swap and no
                // checkActivityClient(). The fork's shape: swap the
                // launching-activity record first, null-guarded (their
                // Android 16 NPE fix - getLaunchingActivity() can return
                // null where the transaction is item-driven), then ALSO
                // swap the LaunchActivityItem itself (on recent Android
                // its execute() builds a fresh ActivityClientRecord from
                // the item's own fields, so the item must carry the
                // target for the target activity to be instantiated),
                // then wrap the live ActivityClientController via
                // checkActivityClient() so result-carrying calls
                // (finishActivity carries resultCode/data out of the
                // guest) go through the proxy.
                Object record = BRActivityThread.get(BlackBoxCore.mainThread()).getLaunchingActivity(token);
                if (record != null) {
                    ActivityThreadActivityClientRecordContext clientRecordContext = BRActivityThreadActivityClientRecord.get(record);
                    clientRecordContext._set_intent(stubRecord.mTarget);
                    clientRecordContext._set_activityInfo(activityInfo);
                    clientRecordContext._set_packageInfo(BActivityThread.currentActivityThread().getPackageInfo());
                }

                LaunchActivityItemContext launchActivityItemContext = BRLaunchActivityItem.get(r);
                launchActivityItemContext._set_mIntent(stubRecord.mTarget);
                launchActivityItemContext._set_mInfo(activityInfo);

                checkActivityClient();
            } else if (BuildCompat.isPie()) {
                LaunchActivityItemContext launchActivityItemContext = BRLaunchActivityItem.get(r);
                launchActivityItemContext._set_mIntent(stubRecord.mTarget);
                launchActivityItemContext._set_mInfo(activityInfo);
            } else {
                ActivityThreadActivityClientRecordContext clientRecordContext = BRActivityThreadActivityClientRecord.get(r);
                clientRecordContext._set_intent(stubRecord.mTarget);
                clientRecordContext._set_activityInfo(activityInfo);
            }
        }
        return false;
    }

    private boolean handleCreateService(Object data) {
        if (BActivityThread.getAppConfig() != null) {
            String appPackageName = BActivityThread.getAppPackageName();
            assert appPackageName != null;

            ServiceInfo serviceInfo = BRActivityThreadCreateServiceData.get(data).info();
            if (!serviceInfo.name.equals(ProxyManifest.getProxyService(BActivityThread.getAppPid()))
                    && !serviceInfo.name.equals(ProxyManifest.getProxyJobService(BActivityThread.getAppPid()))) {
                Slog.d(TAG, "handleCreateService: " + data);
                Intent intent = new Intent();
                intent.setComponent(new ComponentName(appPackageName, serviceInfo.name));
                BlackBoxCore.getBActivityManager().startService(intent, null, false, BActivityThread.getUserId());
                return true;
            }
        }
        return false;
    }

    // DevxyzClone 1.1.7 (STUDY-RESEARCH-GAPS Lead D, diagnostic only):
    // Android 16's intent-redirection hardening can refuse to launch a
    // sub-level intent that arrived inside another intent's extras -
    // which is literally the shape of stub forwarding (the guest target
    // rides in the shadow's "_B_|_target_" extra and is extracted here).
    // The study rates the fit LOW (stub forwarding is same-UID
    // end-to-end and results do not travel as nested intents), so this
    // is a cheap A/B diagnostic, not a fix: on API 36+ the platform
    // opt-out is applied to the target intent before it is installed
    // into the launch item/record, reflectively (the host's targetSdk
    // is 28, so the API is not directly callable; Google's guidance
    // sanctions the reflective call for targetSdk <= 35) and fully
    // try/caught - any failure leaves the launch exactly as it was.
    private void diagRemoveLaunchSecurityProtection(Intent target) {
        try {
            if (target == null || android.os.Build.VERSION.SDK_INT < 36) {
                return;
            }
            java.lang.reflect.Method m = Intent.class.getMethod("removeLaunchSecurityProtection");
            m.invoke(target);
            DiagLog.log("launchSecProtection removed (A16 diagnostic) cmp="
                    + (target.getComponent() != null
                            ? target.getComponent().flattenToShortString()
                            : String.valueOf(target.getAction())));
        } catch (Throwable ignored) {
        }
    }

    private void checkActivityClient() {
        try {
            Object activityClientController = BRActivityClient.get().getActivityClientController();
            if (!(activityClientController instanceof Proxy)) {
                IActivityClientProxy iActivityClientProxy = new IActivityClientProxy(activityClientController);
                iActivityClientProxy.onlyProxy(true);
                iActivityClientProxy.injectHook();
                Object instance = BRActivityClient.get().getInstance();
                Object o = BRActivityClient.get(instance).INTERFACE_SINGLETON();
                BRActivityClientActivityClientControllerSingleton.get(o)._set_mKnownInstance(iActivityClientProxy.getProxyInvocation());
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
}
