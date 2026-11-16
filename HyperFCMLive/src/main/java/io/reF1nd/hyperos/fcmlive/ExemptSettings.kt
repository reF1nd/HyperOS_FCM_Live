package io.reF1nd.hyperos.fcmlive

import android.content.SharedPreferences

enum class ExemptListType(val modeKey: String, val packagesKey: String) {
    Push(ExemptConfig.PUSH_MODE, ExemptConfig.PUSH_PACKAGES),
    ForceStop(ExemptConfig.FORCE_STOP_MODE, ExemptConfig.FORCE_STOP_PACKAGES)
}

data class ExemptListSettings(
    val mode: String = ExemptConfig.DEFAULT,
    val packages: Set<String> = emptySet()
)

data class ExemptSettings(
    val push: ExemptListSettings = ExemptListSettings(),
    val forceStop: ExemptListSettings = ExemptListSettings()
)

operator fun ExemptSettings.get(type: ExemptListType): ExemptListSettings = when (type) {
    ExemptListType.Push -> push
    ExemptListType.ForceStop -> forceStop
}

fun ExemptSettings.with(type: ExemptListType, value: ExemptListSettings): ExemptSettings =
    when (type) {
        ExemptListType.Push -> copy(push = value)
        ExemptListType.ForceStop -> copy(forceStop = value)
    }

fun readExemptList(preferences: SharedPreferences, type: ExemptListType): ExemptListSettings =
    runCatching {
        ExemptListSettings(
            ExemptConfig.normalizeMode(preferences.getString(type.modeKey, ExemptConfig.DEFAULT)),
            preferences.getStringSet(type.packagesKey, emptySet())?.toSet() ?: emptySet()
        )
    }.getOrElse { ExemptListSettings() }

fun readExemptSettings(preferences: SharedPreferences): ExemptSettings = runCatching {
    ExemptSettings(
        readExemptList(preferences, ExemptListType.Push),
        readExemptList(preferences, ExemptListType.ForceStop)
    )
}.getOrElse { ExemptSettings() }
