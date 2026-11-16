package io.reF1nd.hyperos.fcmlive

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

// activity-alias declared in AndroidManifest.xml
private fun launcherComponent(context: Context) =
    ComponentName(context.packageName, "io.reF1nd.hyperos.fcmlive.Launcher")

fun isLauncherIconShown(context: Context): Boolean =
    context.packageManager.getComponentEnabledSetting(launcherComponent(context)) !=
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED

fun setLauncherIconShown(context: Context, shown: Boolean) {
    context.packageManager.setComponentEnabledSetting(
        launcherComponent(context),
        if (shown) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.DONT_KILL_APP
    )
}
