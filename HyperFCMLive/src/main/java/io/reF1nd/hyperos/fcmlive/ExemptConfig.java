package io.reF1nd.hyperos.fcmlive;

import java.util.Collections;
import java.util.Set;

/**
 * Keys and exemption policy shared by the hooks (system_server) and the settings UI.
 */
public final class ExemptConfig {
    public static final String PREFERENCES_NAME = "conf";

    public static final String DEFAULT = "default";
    public static final String BLACKLIST = "blacklist";
    public static final String WHITELIST = "whitelist";

    public static final String PUSH_MODE = "push_mode";
    public static final String PUSH_PACKAGES = "push_packages";
    public static final String FORCE_STOP_MODE = "force_stop_mode";
    public static final String FORCE_STOP_PACKAGES = "force_stop_packages";

    private ExemptConfig() {
    }

    public static String normalizeMode(String mode) {
        if (BLACKLIST.equals(mode)) return BLACKLIST;
        return WHITELIST.equals(mode) ? WHITELIST : DEFAULT;
    }

    /**
     * Only apps that declare an FCM receiver can be exempt. System FCM apps are always exempt,
     * other FCM apps follow the list mode.
     */
    public static boolean shouldExempt(String mode, Set<String> packages, String packageName,
                                       boolean isFcmApp, boolean isSystemApp) {
        if (packageName == null || !isFcmApp) return false;
        if (isSystemApp) return true;
        if (packages == null) packages = Collections.emptySet();
        var normalizedMode = normalizeMode(mode);
        if (WHITELIST.equals(normalizedMode)) return packages.contains(packageName);
        return !BLACKLIST.equals(normalizedMode) || !packages.contains(packageName);
    }
}
