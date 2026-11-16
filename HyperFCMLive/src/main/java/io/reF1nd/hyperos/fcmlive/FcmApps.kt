package io.reF1nd.hyperos.fcmlive

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import java.text.Collator

data class FcmApp(val packageInfo: PackageInfo, val label: String) {
    val packageName: String get() = packageInfo.packageName
}

/**
 * Non-system apps that declare an FCM receiver, sorted by label.
 * System FCM apps are always exempt and therefore not listed.
 */
fun loadFcmApps(context: Context): List<FcmApp> {
    val pm = context.packageManager
    val collator = Collator.getInstance()
    return pm.queryBroadcastReceivers(Intent(ACTION_REMOTE_INTENT), 0)
        .mapNotNull { it.activityInfo?.applicationInfo }
        .distinctBy { it.packageName }
        .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
        .mapNotNull { info ->
            runCatching { pm.getPackageInfo(info.packageName, 0) }.getOrNull()
                ?.let { FcmApp(it, info.loadLabel(pm).toString()) }
        }
        .sortedWith { a, b -> collator.compare(a.label, b.label) }
}

private const val ACTION_REMOTE_INTENT = "com.google.android.c2dm.intent.RECEIVE"
