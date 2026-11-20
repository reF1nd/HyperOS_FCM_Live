package io.reF1nd.hyperos.fcmlive;

import android.content.Context;
import io.github.libxposed.api.XposedInterface;
import org.junit.Before;
import org.junit.Test;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import static org.junit.Assert.*;

/** Registration and callbacks against deliberately different firmware interfaces. */
public class PowerCompatibilityTest {
    public static class CurrentObserver {
        boolean updateFrameworkGmsNetStatus(boolean limit) { return !limit; }
        void notifyFrameworkGmsNetworkChanged(boolean reachable) { }
    }
    public static class LegacyObserver {
        void updateGmsAlarm(boolean limit) { }
        void updateGmsNetWork(boolean limit) { }
        void updateGoogleReletivesWakelock(boolean limit) { }
    }
    public static class PartialLegacyObserver {
        void updateGmsAlarm(boolean limit) { }
        void updateGoogleReletivesWakelock(boolean limit) { }
    }
    public static class LegacyNetd {
        static void initGmsChain(String chain, int uid, String rule) { }
    }
    public static class SleepController {
        private Context mContext;
        enum SleepState { NIGHT }
        String[] getCloudAppsNetWhiteListOfEarthquakeWarn() { return null; }
        void whitelistCloudAppsForEarthquakeWarning() { }
        void applySleepConfig(SleepState state) { }
    }
    public static class SleepNetworkPolicy {
        private final Set<Integer> mSleepModeWhitelistUids = new LinkedHashSet<>();
        private void setSleepModeWhitelistUidRules() {}
    }
    public static class PackageUtil {
        static final Map<String, Integer> installed = new HashMap<>();
        static boolean failLookup;
        static int getUidByPackageName(Context context, String name) {
            if (failLookup) throw new IllegalStateException("Package manager unavailable");
            return installed.getOrDefault(name, -1);
        }
    }
    @Before public void resetPackages() {
        PackageUtil.installed.clear();
        PackageUtil.installed.putAll(Map.of(CompatibilityPolicy.GMS, 10135,
                "vendor.a", 1000, "vendor.b", 1001, "cloud.new.app", 10200));
        PackageUtil.failLookup = false;
    }
    public static class Alarm {
        static final int POWER_PENDING_POLICY_INDEX = 5;
        static final int NUM_POLICIES = 6;
        String sourcePackage;
        int creatorUid;
        final long[] policyWhen = new long[NUM_POLICIES];
        boolean failSetPolicy;
        Alarm(String source, int uid) { sourcePackage = source; creatorUid = uid; }
        // Stateful contract: delivery is constrained by the latest policy deadline.
        long getWhenElapsed() { return Arrays.stream(policyWhen).max().orElseThrow(); }
        boolean setPolicyElapsed(int index, long elapsed) {
            if (failSetPolicy) throw new IllegalStateException("Vendor policy failure");
            long before = getWhenElapsed();
            policyWhen[index] = elapsed;
            return before != getWhenElapsed();
        }
    }
    public static class AlarmStub {
        boolean adjustAlarmLocked(Alarm alarm) { return true; }
        boolean alignAlarmLocked(Alarm alarm) { return true; }
        boolean isExemptFromSsru(Alarm alarm) { return false; }
        boolean checkAlarmIsAllowedSend(Context context, Alarm alarm) { return false; }
    }
    public static class Policy {
        boolean isAlarmAllowedLocked(int pid, int uid, String tag) { return false; }
        boolean isAlarmAlignEnable(int uid, String tag) { return true; }
        boolean isWakelockDisabledByPolicy(String tag, int uid) { return true; }
    }
    public static class WakeLock {
        int mFlags = 1;
        int[] owners;
        WakeLock(int... uids) { owners = uids; }
    }
    public static class PowerStub {
        int[] getRealOwners(WakeLock lock) { return lock.owners; }
        boolean isWakelockDisabledByPolicy(WakeLock lock) { return true; }
        void setUidPartialWakeLockDisabledState(int uid, String tag, boolean disabled) { }
        void setUidPartialWakeLockDisabledState(int uid, String tag, boolean disabled, String reason) { }
    }
    public static class CurrentGreeze {
        boolean mGmsLimitEnabled = true;
        void triggerGMSLimitAction() { }
        boolean updateGmsNetStatus(boolean limit) { return !limit; }
    }
    public static class LegacyGreeze {
        void triggerGMSLimitAction(boolean limit) { }
    }

    private static class Firmware extends ClassLoader {
        final Class<?> observer;
        final boolean netd;
        final Class<?> greeze;
        Firmware(Class<?> observer, boolean netd) { this(observer, netd, CurrentGreeze.class); }
        Firmware(Class<?> observer, boolean netd, Class<?> greeze) {
            this.observer = observer; this.netd = netd; this.greeze = greeze;
        }
        @Override public Class<?> loadClass(String name) throws ClassNotFoundException {
            return switch (name) {
                case "com.miui.powerkeeper.utils.GmsObserver" -> observer;
                case "com.miui.powerkeeper.utils.NetdExecutor" -> {
                    if (!netd) throw new ClassNotFoundException(name);
                    yield LegacyNetd.class;
                }
                case "com.miui.powerkeeper.statemachine.PhoneSleepModeController" -> SleepController.class;
                case "com.miui.powerkeeper.statemachine.PhoneSleepModeController$SleepState" -> SleepController.SleepState.class;
                case "com.miui.powerkeeper.utils.PackageUtil" -> PackageUtil.class;
                case "com.miui.whetstone.PowerKeeperPolicy" -> Policy.class;
                case "com.android.server.net.MiuiNetworkPolicyManagerService" -> SleepNetworkPolicy.class;
                case "com.android.server.alarm.Alarm" -> Alarm.class;
                case "com.android.server.alarm.AlarmManagerServiceStubImpl" -> AlarmStub.class;
                case "com.android.server.power.PowerManagerServiceImpl" -> PowerStub.class;
                case "com.android.server.power.PowerManagerService$WakeLock" -> WakeLock.class;
                case "com.miui.server.greeze.GreezeManagerService" -> greeze;
                default -> super.loadClass(name);
            };
        }
    }

    private static class Framework {
        final Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        final Map<String, String> ids = new HashMap<>();
        final Map<Executable, XposedInterface.Hooker> allHooks = new HashMap<>();
        boolean canDeoptimize = true;
        final Hooker module = new Hooker();
        Framework(int version) {
            var api = (XposedInterface) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{XposedInterface.class}, (self, method, args) -> switch (method.getName()) {
                        case "getApiVersion" -> version;
                        case "deoptimize" -> canDeoptimize;
                        case "hook" -> builder((Executable) args[0]);
                        case "getInvoker" -> Proxy.newProxyInstance(getClass().getClassLoader(),
                                new Class<?>[]{XposedInterface.Invoker.class}, (invoker, call, values) -> {
                                    if (call.getName().equals("invoke")) {
                                        var target = (Method) args[0];
                                        target.setAccessible(true);
                                        return target.invoke(values[0], (Object[]) values[1]);
                                    }
                                    return invoker;
                                });
                        default -> null;
                    });
            module.attachFramework(api, () -> { });
        }
        private XposedInterface.HookBuilder builder(Executable target) {
            return (XposedInterface.HookBuilder) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{XposedInterface.HookBuilder.class}, (self, method, args) -> {
                        if (method.getName().equals("setId")) ids.put(target.getName(), (String) args[0]);
                        if (method.getName().equals("intercept")) {
                            hooks.put(target.getName(), (XposedInterface.Hooker) args[0]);
                            allHooks.put(target, (XposedInterface.Hooker) args[0]);
                            return null;
                        }
                        return self;
                    });
        }
    }

    private static class Chain implements XposedInterface.Chain {
        final Method method;
        final Object result;
        final Object[] args;
        int calls;
        Object[] supplied;
        Throwable failure;
        Object receiver;
        Chain(Method method, Object result, Object... args) {
            this.method = method; this.result = result; this.args = args;
        }
        @Override public Executable getExecutable() { return method; }
        @Override public Object getThisObject() { return receiver; }
        @Override public List<Object> getArgs() { return Arrays.asList(args); }
        @Override public Object getArg(int index) { return args[index]; }
        @Override public Object proceed() throws Throwable { return proceed(args); }
        @Override public Object proceed(Object[] value) throws Throwable {
            calls++; supplied = value;
            if (failure != null) throw failure;
            return result;
        }
        @Override public Object proceedWith(Object receiver) throws Throwable { return proceed(); }
        @Override public Object proceedWith(Object receiver, Object[] value) throws Throwable { return proceed(value); }
    }

    @Test public void currentHookSurvivesAllMissingLegacyMethods() throws Throwable {
        var fw = new Framework(102);
        new PowerCompatibility(fw.module, new Firmware(CurrentObserver.class, false)).installGmsObserver();
        assertEquals(1, fw.hooks.size());
        var target = CurrentObserver.class.getDeclaredMethod("updateFrameworkGmsNetStatus", boolean.class);
        var chain = new Chain(target, true, true);
        assertEquals(true, fw.hooks.get(target.getName()).intercept(chain));
        assertEquals(1, chain.calls);
        assertArrayEquals(new Object[]{false}, chain.supplied);
        assertEquals(target.toGenericString(), fw.ids.get(target.getName()));
    }

    @Test public void legacyHooksRemainAvailableWithoutCurrentInterface() throws Throwable {
        var fw = new Framework(102);
        new PowerCompatibility(fw.module, new Firmware(LegacyObserver.class, true)).installGmsObserver();
        assertEquals(4, fw.hooks.size());
        assertEquals(4, fw.ids.size());
        for (var method : LegacyObserver.class.getDeclaredMethods()) {
            var chain = new Chain(method, null, true);
            fw.hooks.get(method.getName()).intercept(chain);
            assertEquals(1, chain.calls);
            assertEquals(false, chain.supplied[0]);
        }
        var chain = new Chain(LegacyNetd.class.getDeclaredMethods()[0], null, "gms", 10135, "REJECT");
        fw.hooks.get("initGmsChain").intercept(chain);
        assertArrayEquals(new Object[]{"gms", 10135, "ACCEPT"}, chain.supplied);
    }

    @Test public void missingOneLegacyMethodDoesNotAbortOthers() {
        var fw = new Framework(102);
        new PowerCompatibility(fw.module, new Firmware(PartialLegacyObserver.class, false)).installGmsObserver();
        assertEquals(2, fw.hooks.size());
        assertTrue(fw.hooks.containsKey("updateGoogleReletivesWakelock"));
    }

    @Test public void originalFailureIsPropagatedWithoutRetry() throws Throwable {
        var fw = new Framework(102);
        new PowerCompatibility(fw.module, new Firmware(CurrentObserver.class, false)).installGmsObserver();
        var chain = new Chain(CurrentObserver.class.getDeclaredMethod("updateFrameworkGmsNetStatus", boolean.class), null, true);
        var original = new IllegalStateException("vendor failure");
        chain.failure = original;
        try {
            fw.hooks.get("updateFrameworkGmsNetStatus").intercept(chain);
            fail("Expected vendor failure");
        } catch (IllegalStateException failure) {
            assertSame(original, failure);
            assertEquals(1, chain.calls);
        }
    }

    @Test public void sleepHookSupplementsEveryCloudResult() throws Throwable {
        var fw = new Framework(102);
        new PowerCompatibility(fw.module, new Firmware(CurrentObserver.class, false)).installSleepWhitelist();
        var method = SleepController.class.getDeclaredMethod("getCloudAppsNetWhiteListOfEarthquakeWarn");
        for (var original : new String[][]{{"vendor.a"}, {"vendor.b", "cloud.new.app"}}) {
            var chain = new Chain(method, original);
            chain.receiver = new SleepController();
            var result = (String[]) fw.hooks.get(method.getName()).intercept(chain);
            assertEquals(original.length + 1, result.length);
            assertArrayEquals(original, Arrays.copyOf(result, original.length));
            assertEquals(CompatibilityPolicy.GMS, result[original.length]);
            assertEquals(1, chain.calls);
        }
    }

    @Test public void sleepHookRechecksInstalledUidsAfterPackagesChange() throws Throwable {
        var fw = new Framework(102);
        new PowerCompatibility(fw.module, new Firmware(CurrentObserver.class, false)).installSleepWhitelist();
        var method = SleepController.class.getDeclaredMethod("getCloudAppsNetWhiteListOfEarthquakeWarn");
        var original = new String[]{"vendor.a", "already.uninstalled"};
        var chain = new Chain(method, original);
        chain.receiver = new SleepController();
        var hook = fw.hooks.get(method.getName());
        assertEquals(3, ((String[]) hook.intercept(chain)).length);
        PackageUtil.installed.remove("vendor.a");
        assertSame(original, hook.intercept(chain));
        PackageUtil.installed.put("vendor.a", 1000);
        PackageUtil.installed.remove(CompatibilityPolicy.GMS);
        assertSame(original, hook.intercept(chain));
        assertEquals(3, chain.calls);
    }

    @Test public void sleepResolutionFailurePreservesOriginalWithoutRetry() throws Throwable {
        var fw = new Framework(102);
        new PowerCompatibility(fw.module, new Firmware(CurrentObserver.class, false)).installSleepWhitelist();
        var method = SleepController.class.getDeclaredMethod("getCloudAppsNetWhiteListOfEarthquakeWarn");
        var original = new String[]{"vendor.a"};
        var chain = new Chain(method, original);
        chain.receiver = new SleepController();
        PackageUtil.failLookup = true;
        assertSame(original, fw.hooks.get(method.getName()).intercept(chain));
        assertEquals(1, chain.calls);
    }

    @Test public void failedDeoptimizationLeavesSleepVendorPathUntouched() {
        var fw = new Framework(102);
        fw.canDeoptimize = false;
        new PowerCompatibility(fw.module, new Firmware(CurrentObserver.class, false)).installSleepWhitelist();
        assertTrue(fw.hooks.isEmpty());
    }

    @Test public void addsOnlyTheOwnerOfTheNetworkSelectedForEachWhitelistedGmsUid() throws Throwable {
        var fw = new Framework(102);
        var routes = new HashMap<Integer, Integer>();
        var ownerGms = 10138;
        var profileGms = 1010138;
        var vpnOwner = 10530;
        var profileVpnOwner = 1010530;
        routes.put(ownerGms, vpnOwner);
        var lookupUids = new LinkedHashSet<Integer>();
        new PowerCompatibility(fw.module, new Firmware(CurrentObserver.class, false),
                uid -> uid == ownerGms || uid == profileGms).installSleepVpnHook(uid -> {
                    lookupUids.add(uid);
                    return routes.getOrDefault(uid, -1); // -1 includes direct / split-tunnel bypass.
                });
        var policy = new SleepNetworkPolicy();
        var uids = policy.mSleepModeWhitelistUids;
        var method = SleepNetworkPolicy.class.getDeclaredMethod("setSleepModeWhitelistUidRules");
        var chain = new Chain(method, null);
        chain.receiver = policy;
        var hook = fw.allHooks.get(method);
        hook.intercept(chain);
        assertTrue(uids.isEmpty());
        uids.add(1000);
        hook.intercept(chain);
        assertEquals(Set.of(1000), uids);
        assertTrue(lookupUids.isEmpty());
        uids.addAll(List.of(ownerGms, profileGms));
        hook.intercept(chain);
        assertEquals(Set.of(ownerGms, profileGms), lookupUids);
        assertEquals(Set.of(1000, ownerGms, profileGms, vpnOwner), uids);

        // Vendor clears this same set on exit. Next night only the profile uses its VPN.
        uids.clear();
        uids.addAll(List.of(ownerGms, profileGms, profileVpnOwner));
        routes.clear();
        routes.put(profileGms, profileVpnOwner);
        hook.intercept(chain);
        assertEquals(Set.of(ownerGms, profileGms, profileVpnOwner), uids);
        assertEquals(4, chain.calls);
    }

    @Test public void missingVpnLookupKeepsVendorRulesAndOtherProfiles() throws Throwable {
        var fw = new Framework(102);
        new PowerCompatibility(fw.module, new Firmware(CurrentObserver.class, false),
                uid -> uid == 10138 || uid == 1010138).installSleepVpnHook(uid -> {
                    if (uid == 10138) throw new NoSuchMethodException("Firmware changed");
                    return 1010530;
                });
        var policy = new SleepNetworkPolicy();
        policy.mSleepModeWhitelistUids.addAll(List.of(1000, 10138, 1010138));
        var method = SleepNetworkPolicy.class.getDeclaredMethod("setSleepModeWhitelistUidRules");
        var chain = new Chain(method, null);
        chain.receiver = policy;
        fw.allHooks.get(method).intercept(chain);
        assertEquals(Set.of(1000, 10138, 1010138, 1010530), policy.mSleepModeWhitelistUids);
        assertEquals(1, chain.calls);
    }

    private Framework systemFramework() {
        var fw = new Framework(102);
        new PowerCompatibility(fw.module, new Firmware(CurrentObserver.class, false), uid -> uid == 10135)
                .installSystemHooks();
        return fw;
    }

    @Test public void alarmTimingExemptsGmsAndPreservesOtherDecisions() throws Throwable {
        var fw = systemFramework();
        for (var name : new String[]{"adjustAlarmLocked", "alignAlarmLocked", "isExemptFromSsru"}) {
            var method = AlarmStub.class.getDeclaredMethod(name, Alarm.class);
            boolean exemption = name.equals("isExemptFromSsru");
            for (var alarm : new Alarm[]{new Alarm(CompatibilityPolicy.GMS, 10135),
                    new Alarm(CompatibilityPolicy.GMS, 10136), new Alarm("com.android.deskclock", 10135)}) {
                var chain = new Chain(method, !exemption, alarm);
                boolean gms = alarm.creatorUid == 10135 && CompatibilityPolicy.GMS.equals(alarm.sourcePackage);
                assertEquals(gms ? exemption : !exemption, fw.allHooks.get(method).intercept(chain));
                assertEquals(gms ? 0 : 1, chain.calls);
            }
        }
    }

    @Test public void pendingAlarmFromBeforeHotReloadIsRescheduledWithoutDroppingDoze() throws Throwable {
        var fw = systemFramework();
        var alarm = new Alarm(CompatibilityPolicy.GMS, 10135);
        // Requester, app standby, Doze, battery saver, SSRU, vendor pending delay.
        long[] previous = {1000, 2000, 7000, 3000, 4000, 259201000};
        System.arraycopy(previous, 0, alarm.policyWhen, 0, previous.length);
        var method = AlarmStub.class.getDeclaredMethod("adjustAlarmLocked", Alarm.class);
        var chain = new Chain(method, false, alarm);
        var hook = fw.allHooks.get(method);
        assertEquals(true, hook.intercept(chain));
        assertEquals(7000, alarm.getWhenElapsed());
        assertArrayEquals(new long[]{1000, 2000, 7000, 3000, 4000, 0}, alarm.policyWhen);
        assertEquals(false, hook.intercept(chain));
        assertEquals(0, chain.calls);
    }

    @Test public void pendingPolicyRemovalReportsNoChangeWhenOtherPolicyStillDominates() throws Throwable {
        var fw = systemFramework();
        var alarm = new Alarm(CompatibilityPolicy.GMS, 10135);
        alarm.policyWhen[0] = 1000;
        alarm.policyWhen[2] = 9000;
        alarm.policyWhen[Alarm.POWER_PENDING_POLICY_INDEX] = 5000;
        var method = AlarmStub.class.getDeclaredMethod("adjustAlarmLocked", Alarm.class);
        var chain = new Chain(method, true, alarm);
        assertEquals(false, fw.allHooks.get(method).intercept(chain));
        assertEquals(0, alarm.policyWhen[Alarm.POWER_PENDING_POLICY_INDEX]);
        assertEquals(9000, alarm.getWhenElapsed());
        assertEquals(0, chain.calls);
    }

    @Test public void unrelatedAlarmKeepsExistingPendingDelay() throws Throwable {
        var fw = systemFramework();
        var method = AlarmStub.class.getDeclaredMethod("adjustAlarmLocked", Alarm.class);
        for (var alarm : new Alarm[]{new Alarm(CompatibilityPolicy.GMS, 10136),
                new Alarm("other.app", 10135)}) {
            alarm.policyWhen[Alarm.POWER_PENDING_POLICY_INDEX] = 259201000;
            var chain = new Chain(method, true, alarm);
            assertEquals(true, fw.allHooks.get(method).intercept(chain));
            assertEquals(259201000, alarm.getWhenElapsed());
            assertEquals(1, chain.calls);
        }
    }

    @Test public void pendingPolicyFailureFallsBackToVendor() throws Throwable {
        var fw = systemFramework();
        var alarm = new Alarm(CompatibilityPolicy.GMS, 10135);
        alarm.failSetPolicy = true;
        var method = AlarmStub.class.getDeclaredMethod("adjustAlarmLocked", Alarm.class);
        var chain = new Chain(method, true, alarm);
        assertEquals(true, fw.allHooks.get(method).intercept(chain));
        assertEquals(1, chain.calls);
    }

    @Test public void policyHooksPreserveUnrelatedUids() throws Throwable {
        var fw = systemFramework();
        for (var name : new String[]{"isAlarmAllowedLocked", "isAlarmAlignEnable", "isWakelockDisabledByPolicy"}) {
            var method = Arrays.stream(Policy.class.getDeclaredMethods()).filter(m -> m.getName().equals(name)).findFirst().orElseThrow();
            boolean exemption = name.equals("isAlarmAllowedLocked");
            for (int uid : new int[]{10135, 10136}) {
                Object[] args = switch (name) {
                    case "isAlarmAllowedLocked" -> new Object[]{123, uid, "tag"};
                    case "isAlarmAlignEnable" -> new Object[]{uid, "tag"};
                    default -> new Object[]{"tag", uid};
                };
                var chain = new Chain(method, !exemption, args);
                assertEquals(uid == 10135 ? exemption : !exemption, fw.allHooks.get(method).intercept(chain));
                assertEquals(uid == 10135 ? 0 : 1, chain.calls);
            }
        }
    }

    @Test public void wakeLockDecisionPreservesMixedAttribution() throws Throwable {
        var fw = systemFramework();
        var method = PowerStub.class.getDeclaredMethod("isWakelockDisabledByPolicy", WakeLock.class);
        for (var owners : new int[][]{{10135}, {10135, 10136}, {1000}}) {
            var chain = new Chain(method, true, new WakeLock(owners));
            chain.receiver = new PowerStub();
            boolean gms = owners.length == 1 && owners[0] == 10135;
            assertEquals(!gms, fw.allHooks.get(method).intercept(chain));
            assertEquals(gms ? 0 : 1, chain.calls);
        }
    }

    @Test public void disableRequestsNeverBecomeEnableRequests() throws Throwable {
        var fw = systemFramework();
        for (var method : PowerStub.class.getDeclaredMethods()) {
            if (!method.getName().equals("setUidPartialWakeLockDisabledState")) continue;
            for (int uid : new int[]{10135, 10136}) {
                for (boolean disabled : new boolean[]{true, false}) {
                    Object[] args = method.getParameterCount() == 3 ? new Object[]{uid, "tag", disabled}
                            : new Object[]{uid, "tag", disabled, "vendor"};
                    var chain = new Chain(method, null, args);
                    fw.allHooks.get(method).intercept(chain);
                    if (uid == 10135 && disabled) assertEquals(0, chain.calls);
                    else {
                        assertEquals(1, chain.calls);
                        assertArrayEquals(args, chain.supplied);
                    }
                }
            }
        }
    }

    @Test public void legacyGreezeDoesNotRequireNewLimitField() throws Throwable {
        var fw = new Framework(102);
        new PowerCompatibility(fw.module, new Firmware(LegacyObserver.class, true, LegacyGreeze.class))
                .installSystemHooks();
        var method = LegacyGreeze.class.getDeclaredMethod("triggerGMSLimitAction", boolean.class);
        var chain = new Chain(method, null, true);
        fw.allHooks.get(method).intercept(chain);
        assertEquals(1, chain.calls);
        assertEquals(false, chain.supplied[0]);
    }

    @Test public void currentGreezeClearsLimitAndRunsOriginal() throws Throwable {
        var fw = systemFramework();
        var method = CurrentGreeze.class.getDeclaredMethod("triggerGMSLimitAction");
        var chain = new Chain(method, null);
        var receiver = new CurrentGreeze();
        chain.receiver = receiver;
        fw.allHooks.get(method).intercept(chain);
        assertFalse(receiver.mGmsLimitEnabled);
        assertEquals(1, chain.calls);
        var update = CurrentGreeze.class.getDeclaredMethod("updateGmsNetStatus", boolean.class);
        var status = new Chain(update, true, true);
        assertEquals(true, fw.allHooks.get(update).intercept(status));
        assertEquals(false, status.supplied[0]);
        assertEquals(1, status.calls);
    }
}
