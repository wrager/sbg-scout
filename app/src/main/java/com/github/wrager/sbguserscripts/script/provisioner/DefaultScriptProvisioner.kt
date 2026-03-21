package com.github.wrager.sbguserscripts.script.provisioner

import android.content.SharedPreferences
import android.util.Log
import com.github.wrager.sbguserscripts.script.model.ScriptIdentifier
import com.github.wrager.sbguserscripts.script.preset.PresetScript
import com.github.wrager.sbguserscripts.script.preset.PresetScripts
import com.github.wrager.sbguserscripts.script.storage.ScriptStorage
import com.github.wrager.sbguserscripts.script.updater.ScriptDownloadResult
import com.github.wrager.sbguserscripts.script.updater.ScriptDownloader

/**
 * Автоматически загружает и включает пресеты с
 * [enabledByDefault][PresetScript.enabledByDefault].
 *
 * Каждый скрипт загружается однократно: при успехе его идентификатор
 * сохраняется в [SharedPreferences], и повторных попыток не будет.
 * При ошибке загрузки идентификатор не сохраняется — повтор при следующем запуске.
 */
class DefaultScriptProvisioner(
    private val scriptStorage: ScriptStorage,
    private val downloader: ScriptDownloader,
    private val preferences: SharedPreferences,
) {
    /** Есть ли пресеты, которые нужно загрузить. */
    fun hasPendingScripts(): Boolean {
        val provisioned = preferences.getStringSet(KEY_PROVISIONED_DEFAULTS, emptySet())
            ?: emptySet()
        return PresetScripts.ALL.any { preset ->
            preset.enabledByDefault && preset.identifier.value !in provisioned
        }
    }

    /**
     * Загружает все enabledByDefault-пресеты, которые ещё не были обработаны.
     */
    suspend fun provision() {
        val provisioned = preferences.getStringSet(KEY_PROVISIONED_DEFAULTS, emptySet())
            ?: emptySet()

        val pending = PresetScripts.ALL.filter { preset ->
            preset.enabledByDefault && preset.identifier.value !in provisioned
        }

        for (preset in pending) {
            val result = downloader.download(preset.downloadUrl, isPreset = true)
            if (result is ScriptDownloadResult.Success) {
                scriptStorage.setEnabled(result.script.identifier, true)
                markProvisioned(provisioned, preset.identifier)
                Log.i(LOG_TAG, "Автозагрузка: ${preset.displayName}")
            } else if (result is ScriptDownloadResult.Failure) {
                Log.w(
                    LOG_TAG,
                    "Автозагрузка: не удалось загрузить ${preset.displayName}",
                    result.error,
                )
            }
        }
    }

    private fun markProvisioned(current: Set<String>, identifier: ScriptIdentifier) {
        preferences.edit()
            .putStringSet(KEY_PROVISIONED_DEFAULTS, current + identifier.value)
            .apply()
    }

    companion object {
        private const val KEY_PROVISIONED_DEFAULTS = "provisioned_defaults"
        private const val LOG_TAG = "ScriptProvisioner"
    }
}
