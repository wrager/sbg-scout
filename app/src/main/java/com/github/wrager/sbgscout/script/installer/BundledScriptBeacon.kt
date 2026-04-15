package com.github.wrager.sbgscout.script.installer

import android.content.SharedPreferences
import android.util.Log
import com.github.wrager.sbgscout.script.model.UserScript
import com.github.wrager.sbgscout.script.preset.PresetScript
import com.github.wrager.sbgscout.script.preset.PresetScripts
import com.github.wrager.sbgscout.script.storage.ScriptStorage
import com.github.wrager.sbgscout.script.updater.HttpFetcher

/**
 * После установки бандлированного скрипта уведомляет GitHub release counter,
 * что доставлена конкретная версия.
 *
 * Фоновая проверка обновлений ходит в `api.github.com` и счётчик downloads
 * не инкрементирует — а при установке из бандла никакого сетевого запроса
 * вообще нет. Чтобы автор вшитого скрипта видел в статистике реальные
 * установки именно той версии, которая доставлена пользователю, beacon
 * делает один GET на `releases/download/v{version}/{asset}`: pinned URL
 * инкрементирует `download_count` у конкретного тега, а не у latest
 * (который может быть уже другой версии, если бандл отстал).
 *
 * Идемпотентен по паре (identifier, version): каждая новая версия в бандле
 * пингуется один раз на устройство. При обновлении APK с новой версией
 * SVP beacon отправит новый ping для 0.9.0, оставив запись для 0.8.1 в
 * состоянии. При сетевой ошибке ключ в состоянии НЕ сохраняется — следующий
 * старт повторит попытку, чтобы все реальные установки в итоге учлись.
 *
 * Состояние хранится в [SharedPreferences] под ключом [KEY_PINGED] как
 * [Set] строк `"{identifier.value}:{version}"`.
 */
class BundledScriptBeacon(
    private val httpFetcher: HttpFetcher,
    private val scriptStorage: ScriptStorage,
    private val preferences: SharedPreferences,
    private val bundledPresets: List<PresetScript> = PresetScripts.BUNDLED,
) {
    suspend fun ping() {
        val alreadyPinged = preferences.getStringSet(KEY_PINGED, emptySet()) ?: emptySet()
        val installedBySourceUrl = scriptStorage.getAll()
            .mapNotNull { script -> script.sourceUrl?.let { url -> url to script } }
            .toMap()

        val pending = bundledPresets.mapNotNull { preset ->
            toPendingPing(preset, installedBySourceUrl, alreadyPinged)
        }

        val newlyPinged = mutableSetOf<String>()
        for (pendingPing in pending) {
            if (sendPing(pendingPing)) {
                newlyPinged += pendingPing.key
            }
        }

        if (newlyPinged.isNotEmpty()) {
            preferences.edit()
                .putStringSet(KEY_PINGED, alreadyPinged + newlyPinged)
                .apply()
        }
    }

    private fun toPendingPing(
        preset: PresetScript,
        installedBySourceUrl: Map<String, UserScript>,
        alreadyPinged: Set<String>,
    ): PendingPing? {
        val script = installedBySourceUrl[preset.downloadUrl] ?: return null
        val version = script.header.version?.removePrefix("v")?.takeIf { it.isNotBlank() }
            ?: return null
        val key = "${preset.identifier.value}:$version"
        if (key in alreadyPinged) return null
        val pinnedUrl = preset.downloadUrl.replace(
            "/releases/latest/download/",
            "/releases/download/v$version/",
        )
        return PendingPing(preset, version, key, pinnedUrl)
    }

    private suspend fun sendPing(pendingPing: PendingPing): Boolean {
        return try {
            httpFetcher.fetch(pendingPing.pinnedUrl)
            Log.i(LOG_TAG, "Beacon: ${pendingPing.preset.displayName} v${pendingPing.version}")
            true
        } catch (@Suppress("TooGenericExceptionCaught") exception: Exception) {
            // Не возвращаем ключ в state — retry на следующем старте.
            Log.w(
                LOG_TAG,
                "Beacon failed: ${pendingPing.preset.displayName} v${pendingPing.version}",
                exception,
            )
            false
        }
    }

    private data class PendingPing(
        val preset: PresetScript,
        val version: String,
        val key: String,
        val pinnedUrl: String,
    )

    companion object {
        const val KEY_PINGED = "bundled_script_beacon_pinged"
        private const val LOG_TAG = "BundledBeacon"
    }
}
