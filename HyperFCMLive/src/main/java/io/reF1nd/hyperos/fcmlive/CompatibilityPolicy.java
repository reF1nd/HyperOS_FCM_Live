package io.reF1nd.hyperos.fcmlive;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntPredicate;
import java.util.function.ToIntFunction;

/** Add exemptions without replacing the vendor's other entries or decisions. */
final class CompatibilityPolicy {
    static final String GMS = "com.google.android.gms";

    static String[] addToSleepWhitelist(String[] original, ToIntFunction<String> packageUid) {
        if (original == null) return null;
        if (Arrays.asList(original).contains(GMS)) return original;
        boolean active = false;
        for (String entry : original) {
            if (entry != null && !entry.trim().isEmpty() && packageUid.applyAsInt(entry) >= 0) {
                active = true;
                break;
            }
        }
        // The firewall is enabled by a nonempty UID set, not a nonempty package-name list.
        // Missing packages must not turn an inactive firewall into a GMS-only firewall.
        if (!active || packageUid.applyAsInt(GMS) < 0) return original;
        var result = Arrays.copyOf(original, original.length + 1);
        result[original.length] = GMS;
        return result;
    }

    static List<String> addToDozeWhitelist(List<String> original) {
        if (original.contains(GMS)) return original;
        // Some firmware versions return an immutable list or share it with callers.
        var result = new ArrayList<>(original);
        result.add(GMS);
        return result;
    }

    static boolean isGmsUid(int uid, int appId, String[] packages) {
        if (uid < 10000 || appId < 10000 || uid % 100000 != appId || packages == null) return false;
        return Arrays.asList(packages).contains(GMS);
    }

    static boolean protectAlarm(String sourcePackage, int creatorUid, IntPredicate gmsUid) {
        return GMS.equals(sourcePackage) && gmsUid.test(creatorUid);
    }

    static boolean protectWakeLock(int flags, int[] owners, IntPredicate gmsUid) {
        if ((flags & 0xffff) != 1 || owners == null || owners.length == 0) return false;
        for (int uid : owners) if (!gmsUid.test(uid)) return false;
        return true;
    }

    private CompatibilityPolicy() { }
}
