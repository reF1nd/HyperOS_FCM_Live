package io.reF1nd.hyperos.fcmlive;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;
import java.util.function.ToIntFunction;
import static org.junit.Assert.*;

public class CompatibilityPolicyTest {
    private static final String GMS = CompatibilityPolicy.GMS;
    private static final IntPredicate GMS_UID = uid -> uid == 10135 || uid == 1010135;
    private static final ToIntFunction<String> PACKAGE_UID = name -> Map.of(
            GMS, 10135, "com.xiaomi.xmsf", 1000, "cloud.new.app", 10200).getOrDefault(name, -1);

    @Test public void sleepWhitelistPreservesEntriesAndOrder() {
        var original = new String[]{"com.xiaomi.xmsf", "cloud.new.app", null, "cloud.new.app"};
        assertArrayEquals(new String[]{"com.xiaomi.xmsf", "cloud.new.app", null, "cloud.new.app", GMS},
                CompatibilityPolicy.addToSleepWhitelist(original, PACKAGE_UID));
        assertEquals(4, original.length);
    }

    @Test public void sleepWhitelistDoesNotActivateInactiveFirewall() {
        assertNull(CompatibilityPolicy.addToSleepWhitelist(null, PACKAGE_UID));
        for (var original : new String[][]{new String[0], {""}, {null, " ", "\t"}}) {
            assertSame(original, CompatibilityPolicy.addToSleepWhitelist(original, PACKAGE_UID));
        }
    }

    @Test public void sleepWhitelistIsIdempotent() {
        var original = new String[]{"vendor", GMS};
        assertSame(original, CompatibilityPolicy.addToSleepWhitelist(original, PACKAGE_UID));
    }

    @Test public void nonemptyNamesWithoutInstalledPackagesDoNotEnableFirewall() {
        var original = new String[]{"uninstalled.app", "cloud.removed.app", null, " "};
        assertSame(original, CompatibilityPolicy.addToSleepWhitelist(original, PACKAGE_UID));
    }

    @Test public void missingEntriesBeforeValidPackageStillAllowExemption() {
        var original = new String[]{"uninstalled.app", "com.xiaomi.xmsf"};
        assertArrayEquals(new String[]{"uninstalled.app", "com.xiaomi.xmsf", GMS},
                CompatibilityPolicy.addToSleepWhitelist(original, PACKAGE_UID));
    }

    @Test public void packageResolutionUsesExactNamesAndAcceptsSystemUids() {
        var padded = new String[]{" com.xiaomi.xmsf "};
        assertSame(padded, CompatibilityPolicy.addToSleepWhitelist(padded, PACKAGE_UID));
        var system = new String[]{"system.app"};
        assertArrayEquals(new String[]{"system.app", GMS},
                CompatibilityPolicy.addToSleepWhitelist(system, name -> GMS.equals(name) ? 10135 : 0));
    }

    @Test public void missingGmsLeavesWhitelistUnchanged() {
        var original = new String[]{"com.xiaomi.xmsf"};
        assertSame(original, CompatibilityPolicy.addToSleepWhitelist(original,
                name -> GMS.equals(name) ? -1 : PACKAGE_UID.applyAsInt(name)));
    }

    @Test public void dozeWhitelistHandlesImmutableAndEmptyLists() {
        var original = List.of("vendor");
        assertEquals(List.of("vendor", GMS), CompatibilityPolicy.addToDozeWhitelist(original));
        assertEquals(List.of("vendor"), original);
        assertEquals(List.of(GMS), CompatibilityPolicy.addToDozeWhitelist(List.of()));
        var present = Arrays.asList(null, GMS);
        assertSame(present, CompatibilityPolicy.addToDozeWhitelist(present));
    }

    @Test public void identityIncludesSecondaryUsers() {
        assertTrue(CompatibilityPolicy.isGmsUid(10135, 10135, new String[]{GMS}));
        assertTrue(CompatibilityPolicy.isGmsUid(1010135, 10135, new String[]{GMS}));
    }

    @Test public void identityRejectsUidReuseAndUnknownPackages() {
        assertFalse(CompatibilityPolicy.isGmsUid(10135, 10135, new String[]{"other.app"}));
        assertFalse(CompatibilityPolicy.isGmsUid(10136, 10135, new String[]{GMS}));
        assertFalse(CompatibilityPolicy.isGmsUid(1000, 10135, new String[]{GMS}));
        assertFalse(CompatibilityPolicy.isGmsUid(10135, -1, new String[]{GMS}));
        assertFalse(CompatibilityPolicy.isGmsUid(10135, 10135, null));
    }

    @Test public void alarmRequiresBothRealUidAndGmsSource() {
        assertTrue(CompatibilityPolicy.protectAlarm(GMS, 10135, GMS_UID));
        assertTrue(CompatibilityPolicy.protectAlarm(GMS, 1010135, GMS_UID));
        assertFalse(CompatibilityPolicy.protectAlarm(GMS, 10136, GMS_UID));
        assertFalse(CompatibilityPolicy.protectAlarm("com.android.deskclock", 10135, GMS_UID));
        assertFalse(CompatibilityPolicy.protectAlarm(null, 10135, GMS_UID));
    }

    @Test public void partialWakeLockUsesRealWorkAttribution() {
        assertTrue(CompatibilityPolicy.protectWakeLock(1, new int[]{10135}, GMS_UID));
        assertTrue(CompatibilityPolicy.protectWakeLock(0x40000001, new int[]{10135, 1010135}, GMS_UID));
        assertFalse(CompatibilityPolicy.protectWakeLock(1, new int[]{10135, 10136}, GMS_UID));
        assertFalse(CompatibilityPolicy.protectWakeLock(1, new int[]{1000}, GMS_UID));
    }

    @Test public void screenAndUnknownWakeLocksKeepVendorPolicy() {
        assertFalse(CompatibilityPolicy.protectWakeLock(26, new int[]{10135}, GMS_UID));
        assertFalse(CompatibilityPolicy.protectWakeLock(32, new int[]{10135}, GMS_UID));
        assertFalse(CompatibilityPolicy.protectWakeLock(1, new int[0], GMS_UID));
        assertFalse(CompatibilityPolicy.protectWakeLock(1, null, GMS_UID));
    }
}
