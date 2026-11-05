package top.niunaijun.blackbox.core.system.am;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Binder;
import android.os.IBinder;

import top.niunaijun.blackbox.core.system.ProcessRecord;



public class ActivityRecord extends Binder {
    public TaskRecord task;
    public IBinder token;
    public IBinder resultTo;
    public ActivityInfo info;
    public ComponentName component;
    // DevxyzClone 1.1.5 (STUDY-KATANA-SIDE §5 #1): the caller captured at
    // record-creation time, when ActivityStack.startActivityLocked has
    // just resolved it from the launch's resultTo. getCallingPackage /
    // getCallingActivity answer from these stamps first, so the answer
    // survives later pruning of the caller's record (the old resultTo
    // re-lookup collapsed to the host package on any miss). Null when
    // the caller was unknown at creation time.
    public String callerPackage;
    public ComponentName callerComponent;
    public Intent intent;
    public int userId;
    public boolean finished;
    public ProcessRecord processRecord;

    public static ActivityRecord create(Intent intent, ActivityInfo info, IBinder resultTo, int userId) {
        ActivityRecord record = new ActivityRecord();
        record.intent = intent;
        record.info = info;
        record.component = new ComponentName(info.packageName, info.name);
        record.resultTo = resultTo;
        record.userId = userId;
        return record;
    }


}
