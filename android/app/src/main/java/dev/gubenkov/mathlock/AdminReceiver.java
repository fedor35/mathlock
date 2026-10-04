package dev.gubenkov.mathlock;

import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;

/** Нужен только для режима владельца устройства (adb dpm set-device-owner): тогда экран с примерами пинится намертво. */
public class AdminReceiver extends DeviceAdminReceiver {
    public static ComponentName cn(Context c) { return new ComponentName(c, AdminReceiver.class); }

    public static boolean isOwner(Context c) {
        DevicePolicyManager dpm = c.getSystemService(DevicePolicyManager.class);
        return dpm != null && dpm.isDeviceOwnerApp(c.getPackageName());
    }

    /** Разрешить самому себе lock task; вызывать при старте, безвредно без владения. */
    public static void setupLockTask(Context c) {
        if (!isOwner(c)) return;
        DevicePolicyManager dpm = c.getSystemService(DevicePolicyManager.class);
        try {
            dpm.setLockTaskPackages(cn(c), new String[]{c.getPackageName()});
            dpm.setLockTaskFeatures(cn(c), DevicePolicyManager.LOCK_TASK_FEATURE_NONE);
        } catch (Exception ignored) { }
    }
}
