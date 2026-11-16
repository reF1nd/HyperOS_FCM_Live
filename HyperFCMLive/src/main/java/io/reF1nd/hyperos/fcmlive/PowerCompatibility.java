package io.reF1nd.hyperos.fcmlive;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntPredicate;

/** Version-specific power hooks. Missing legacy methods never abort other features. */
final class PowerCompatibility {
    private static final String TAG = "HyperGreeze";
    private static final String GREEZE = "com.miui.server.greeze.GreezeManagerService";
    private static final String POWER_POLICY = "com.miui.whetstone.PowerKeeperPolicy";
    private static final String ALARM_STUB = "com.android.server.alarm.AlarmManagerServiceStubImpl";
    private static final String POWER_STUB = "com.android.server.power.PowerManagerServiceImpl";
    private final Hooker module;
    private final ClassLoader loader;
    private final IntPredicate gmsUid;
    private final Set<String> errors = ConcurrentHashMap.newKeySet();

    PowerCompatibility(Hooker module, ClassLoader loader) {
        this(module, loader, new GmsIdentity()::matches);
    }

    PowerCompatibility(Hooker module, ClassLoader loader, IntPredicate gmsUid) {
        this.module = module;
        this.loader = loader;
        this.gmsUid = gmsUid;
    }

    @FunctionalInterface
    private interface Installation {
        void run() throws ReflectiveOperationException;
    }

    private void install(String feature, Installation installation) {
        try {
            installation.run();
            module.log(Log.INFO, TAG, "Installed " + feature);
        } catch (ReflectiveOperationException e) {
            module.log(Log.INFO, TAG, "Unavailable on this firmware: " + feature + " (" + e + ")");
        } catch (RuntimeException | LinkageError e) {
            module.log(Log.ERROR, TAG, "Failed to install " + feature, e);
        }
    }

    private void callbackError(String feature, Exception error) {
        if (errors.add(feature)) module.log(Log.ERROR, TAG, "Preserving vendor behavior: " + feature, error);
    }

    private Class<?> type(String name) throws ClassNotFoundException {
        return loader.loadClass(name);
    }

    private Method method(String owner, String name, Class<?> result, Class<?>... args)
            throws ReflectiveOperationException {
        var method = type(owner).getDeclaredMethod(name, args);
        if (method.getReturnType() != result) throw new NoSuchMethodException(method.toGenericString());
        method.setAccessible(true);
        return method;
    }

    private Field field(Class<?> owner, String name) throws NoSuchFieldException {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private void deoptimizeCallers(String owner, String... names) {
        install("callers of " + owner, () -> {
            for (var method : type(owner).getDeclaredMethods()) {
                for (var name : names) {
                    if (method.getName().equals(name) && !module.deoptimize(method)) {
                        module.log(Log.WARN, TAG, "Could not deoptimize " + method.toGenericString());
                    }
                }
            }
        });
    }

    private void forceLimitOff(String owner, String name, Class<?> result)
            throws ReflectiveOperationException {
        var method = method(owner, name, result, boolean.class);
        module.hookE(method).intercept(chain -> {
            var args = chain.getArgs().toArray();
            args[0] = false; // false means unrestricted; still run the vendor update/cleanup.
            return chain.proceed(args);
        });
        module.deoptimize(method);
    }

    void installGmsObserver() {
        var observer = "com.miui.powerkeeper.utils.GmsObserver";
        install("legacy GMS firewall", () -> {
            var method = method("com.miui.powerkeeper.utils.NetdExecutor", "initGmsChain",
                    void.class, String.class, int.class, String.class);
            module.hookE(method).intercept(chain -> {
                var args = chain.getArgs().toArray();
                args[2] = "ACCEPT";
                return chain.proceed(args);
            });
            module.deoptimize(method);
        });
        for (var name : new String[]{"updateGmsAlarm", "updateGmsNetWork", "updateGoogleReletivesWakelock"}) {
            install("legacy " + name, () -> forceLimitOff(observer, name, void.class));
        }
        install("GMS framework network status", () ->
                forceLimitOff(observer, "updateFrameworkGmsNetStatus", boolean.class));
        deoptimizeCallers(observer, "notifyFrameworkGmsNetworkChanged");
    }

    void installSleepWhitelist() {
        install("night sleep network whitelist", () -> {
            var owner = "com.miui.powerkeeper.statemachine.PhoneSleepModeController";
            var getter = method(owner, "getCloudAppsNetWhiteListOfEarthquakeWarn", String[].class);
            var context = field(type(owner), "mContext");
            var packageUid = method("com.miui.powerkeeper.utils.PackageUtil", "getUidByPackageName",
                    int.class, Context.class, String.class);
            // Hook the consumed value, after both local defaults and cloud configuration.
            // Never persist a list that a later cloud/OTA update can overwrite.
            var consumer = method(owner, "whitelistCloudAppsForEarthquakeWarning", void.class);
            var apply = method(owner, "applySleepConfig", void.class, type(owner + "$SleepState"));
            if (!module.deoptimize(consumer) || !module.deoptimize(apply)) {
                throw new IllegalStateException("Cannot deoptimize the sleep whitelist consumers");
            }
            module.hookE(getter).intercept(chain -> {
                var result = (String[]) chain.proceed();
                try {
                    var value = context.get(chain.getThisObject());
                    // Use exactly the consumer's resolver and context, including its user.
                    return CompatibilityPolicy.addToSleepWhitelist(result, name -> {
                        try {
                            return (Integer) packageUid.invoke(null, value, name);
                        } catch (ReflectiveOperationException error) {
                            throw new IllegalStateException("Cannot resolve sleep whitelist UID", error);
                        }
                    });
                } catch (Exception error) {
                    callbackError("night sleep network whitelist", error);
                    return result;
                }
            });
        });
    }

    void installSystemHooks() {
        install("Greeze network status", () -> forceLimitOff(GREEZE, "updateGmsNetStatus", boolean.class));
        install("Greeze initial GMS limit", () -> {
            var owner = type(GREEZE);
            var enabled = field(owner, "mGmsLimitEnabled");
            for (var constructor : owner.getDeclaredConstructors()) {
                module.hookE(constructor).intercept(chain -> {
                    var result = chain.proceed();
                    try {
                        enabled.setBoolean(chain.getThisObject(), false);
                    } catch (Exception error) {
                        callbackError("Greeze initial GMS limit", error);
                    }
                    return result;
                });
                module.deoptimize(constructor);
            }
        });
        install("legacy Greeze limit action", () -> {
            Method trigger;
            try {
                trigger = method(GREEZE, "triggerGMSLimitAction", void.class, boolean.class);
            } catch (NoSuchMethodException ignored) {
                trigger = method(GREEZE, "triggerGMSLimitAction", void.class);
            }
            // The boolean overload on OS3 has no mGmsLimitEnabled field.
            var enabled = trigger.getParameterCount() == 0 ? field(type(GREEZE), "mGmsLimitEnabled") : null;
            module.hookE(trigger).intercept(chain -> {
                var args = chain.getArgs().toArray();
                if (args.length != 0) args[0] = false;
                else {
                    try {
                        enabled.setBoolean(chain.getThisObject(), false);
                    } catch (Exception error) {
                        callbackError("legacy Greeze limit action", error);
                    }
                }
                return chain.proceed(args);
            });
            module.deoptimize(trigger);
        });
        installUidDecision("alarm delivery policy", "isAlarmAllowedLocked", true, 1,
                int.class, int.class, String.class);
        installUidDecision("alarm alignment policy", "isAlarmAlignEnable", false, 0,
                int.class, String.class);
        installUidDecision("wakelock policy", "isWakelockDisabledByPolicy", false, 1,
                String.class, int.class);
        install("alarm pending delay", this::installAlarmPendingExemption);
        for (var name : new String[]{"alignAlarmLocked", "isExemptFromSsru"}) {
            installAlarmDecision(name, name.equals("isExemptFromSsru"), false);
        }
        installAlarmDecision("checkAlarmIsAllowedSend", true, true);
        install("partial wakelock decision", this::installWakeLockDecision);
        for (boolean withReason : new boolean[]{false, true}) {
            install("partial wakelock disable request (reason=" + withReason + ")", () ->
                    installWakeLockDisableRequest(withReason));
        }
        deoptimizeCallers(ALARM_STUB, "proxyAlarm", "timeBasedOnSsruLocked");
        deoptimizeCallers("com.android.server.alarm.AlarmManagerService", "setImplLocked", "deliverAlarmsLocked");
        deoptimizeCallers("com.android.server.power.PowerManagerService", "setWakeLockDisabledStateLocked");
        deoptimizeCallers("com.miui.server.smartpower.AppWakelockResource", "setWakeLockState");
        deoptimizeCallers(POWER_STUB, "isWakelockDisabledByPolicy");
    }

    private void installUidDecision(String feature, String name, boolean result, int uidIndex,
            Class<?>... args) {
        install(feature, () -> {
            var method = method(POWER_POLICY, name, boolean.class, args);
            module.hookE(method).intercept(chain -> {
                if (gmsUid.test((Integer) chain.getArg(uidIndex))) return result;
                return chain.proceed();
            });
            module.deoptimize(method);
        });
    }

    private void installAlarmDecision(String name, boolean result, boolean withContext) {
        install("alarm " + name, () -> {
            var alarm = type("com.android.server.alarm.Alarm");
            var source = field(alarm, "sourcePackage");
            var creator = field(alarm, "creatorUid");
            var method = withContext
                    ? method(ALARM_STUB, name, boolean.class, Context.class, alarm)
                    : method(ALARM_STUB, name, boolean.class, alarm);
            module.hookE(method).intercept(chain -> {
                try {
                    var value = chain.getArg(withContext ? 1 : 0);
                    if (value != null && CompatibilityPolicy.protectAlarm((String) source.get(value),
                            creator.getInt(value), gmsUid)) return result;
                } catch (Exception error) {
                    callbackError("alarm " + name, error);
                }
                return chain.proceed();
            });
            module.deoptimize(method);
        });
    }

    private void installAlarmPendingExemption() throws ReflectiveOperationException {
        var alarm = type("com.android.server.alarm.Alarm");
        var source = field(alarm, "sourcePackage");
        var creator = field(alarm, "creatorUid");
        // Resolve the vendor's index rather than assuming that policy slot 5 is stable.
        int pendingPolicy = field(alarm, "POWER_PENDING_POLICY_INDEX").getInt(null);
        int policyCount = field(alarm, "NUM_POLICIES").getInt(null);
        if (pendingPolicy <= 0 || pendingPolicy >= policyCount) {
            throw new IllegalStateException("Invalid pending alarm policy index: " + pendingPolicy);
        }
        var setPolicy = method(alarm.getName(), "setPolicyElapsed", boolean.class, int.class, long.class);
        var adjust = method(ALARM_STUB, "adjustAlarmLocked", boolean.class, alarm);
        module.hookE(adjust).intercept(chain -> {
            try {
                var value = chain.getArg(0);
                if (value != null && CompatibilityPolicy.protectAlarm((String) source.get(value),
                        creator.getInt(value), gmsUid)) {
                    // An alarm may already be delayed before hot reload or UID resolution.
                    // Clear only the vendor delay; preserve requester, Doze and other policies.
                    // Propagate the real change result so AlarmStore reschedules delivery.
                    return module.getInvoker(setPolicy).invoke(value, pendingPolicy, 0L);
                }
            } catch (Exception error) {
                callbackError("alarm pending delay", error);
            }
            return chain.proceed();
        });
        module.deoptimize(adjust);
    }

    private void installWakeLockDecision() throws ReflectiveOperationException {
        var wakeLock = type("com.android.server.power.PowerManagerService$WakeLock");
        var owners = method(POWER_STUB, "getRealOwners", int[].class, wakeLock);
        var flags = field(wakeLock, "mFlags");
        var method = method(POWER_STUB, "isWakelockDisabledByPolicy", boolean.class, wakeLock);
        module.hookE(method).intercept(chain -> {
            try {
                var value = chain.getArg(0);
                if (value != null && CompatibilityPolicy.protectWakeLock(flags.getInt(value),
                        (int[]) module.getInvoker(owners).invoke(chain.getThisObject(), value),
                        gmsUid)) return false;
            } catch (Exception error) {
                callbackError("partial wakelock decision", error);
            }
            return chain.proceed();
        });
        module.deoptimize(method);
    }

    private void installWakeLockDisableRequest(boolean withReason) throws ReflectiveOperationException {
        var method = withReason
                ? method(POWER_STUB, "setUidPartialWakeLockDisabledState", void.class,
                        int.class, String.class, boolean.class, String.class)
                : method(POWER_STUB, "setUidPartialWakeLockDisabledState", void.class,
                        int.class, String.class, boolean.class);
        module.hookE(method).intercept(chain -> {
            // Ignore a vendor disable request for GMS. Do not turn it into an enable request:
            // enabling could clear an existing disabled state imposed by Android itself.
            if (Boolean.TRUE.equals(chain.getArg(2)) && gmsUid.test((Integer) chain.getArg(0))) return null;
            return chain.proceed();
        });
        module.deoptimize(method);
    }
}
