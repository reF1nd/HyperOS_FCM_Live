package io.reF1nd.hyperos.fcmlive;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.SystemClock;

/** Resolve installed GMS rather than trusting an alarm's package name or a fixed UID. */
final class GmsIdentity {
    private volatile Context context;
    private volatile int appId = -1;
    private volatile long refreshed = -30001;

    boolean matches(int uid) {
        if (uid < 10000) return false;
        try {
            var value = context;
            if (value == null) {
                var activityThread = Class.forName("android.app.ActivityThread");
                var thread = activityThread.getMethod("currentActivityThread").invoke(null);
                if (thread == null) return false;
                value = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
                if (value == null) return false;
                context = value;
            }
            var pm = value.getPackageManager();
            long now = SystemClock.elapsedRealtime();
            if (now - refreshed > 30000) {
                try {
                    appId = pm.getApplicationInfo(CompatibilityPolicy.GMS, 0).uid % 100000;
                } catch (PackageManager.NameNotFoundException ignored) {
                    appId = -1;
                }
                refreshed = now;
            }
            if (appId < 10000 || uid % 100000 != appId) return false;
            // Recheck candidates: a stale appId must not exempt a recycled UID.
            return CompatibilityPolicy.isGmsUid(uid, appId, pm.getPackagesForUid(uid));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // During early boot or a package-manager failure, retain the vendor decision.
            return false;
        }
    }
}
