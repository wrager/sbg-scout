package com.github.wrager.sbgscout.script.provisioner

import android.content.SharedPreferences
import android.util.Log
import com.github.wrager.sbgscout.script.model.ScriptIdentifier
import com.github.wrager.sbgscout.script.preset.PresetScript
import com.github.wrager.sbgscout.script.preset.PresetScripts
import com.github.wrager.sbgscout.script.storage.ScriptStorage
import com.github.wrager.sbgscout.script.updater.ScriptDownloadResult
import com.github.wrager.sbgscout.script.updater.ScriptDownloader

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
    fun hasPendingScripts(): Boolean = pendingPresets().isNotEmpty()

    /**
     * Загружает все enabledByDefault-пресеты, которые ещё не были обработаны.
     *
     * @param onScriptLoading вызывается перед загрузкой каждого скрипта
     *   с его [displayName][PresetScript.displayName].
     * @param onDownloadProgress вызывается при получении данных с прогрессом 0–100 %.
     * @return `true`, если все скрипты загружены успешно (или нечего загружать).
     */
    suspend fun provision(
        onScriptLoading: (String) -> Unit = {},
        onDownloadProgress: ((Int) -> Unit)? = null,
    ): Boolean {
        val pending = pendingPresets()

        var allSucceeded = true
        for (preset in pending) {
            onScriptLoading(preset.displayName)
            val result = downloader.download(
                preset.downloadUrl,
                isPreset = true,
                onProgress = onDownloadProgress,
            )
            if (result is ScriptDownloadResult.Success) {
                scriptStorage.setEnabled(result.script.identifier, true)
                markProvisioned(preset.identifier)
                Log.i(LOG_TAG, "Автозагрузка: ${preset.displayName}")
            } else if (result is ScriptDownloadResult.Failure) {
                allSucceeded = false
                Log.w(
                    LOG_TAG,
                    "Автозагрузка: не удалось загрузить ${preset.displayName}",
                    result.error,
                )
            }
        }
        return allSucceeded
    }

    /**
     * Возвращает пресеты, которых ещё нет ни в [provisioned_defaults][KEY_PROVISIONED_DEFAULTS],
     * ни в [ScriptStorage].
     *
     * Сравнение с хранилищем идёт по [sourceUrl][UserScript.sourceUrl],
     * а не по [identifier][PresetScript.identifier]: идентификатор пресета
     * (namespace без @name) не совпадает с идентификатором сохранённого скрипта
     * (namespace + "/" + @name), который формирует [ScriptDownloader].
     */
    private fun pendingPresets(): List<PresetScript> {
        val provisioned = preferences.getStringSet(KEY_PROVISIONED_DEFAULTS, emptySet())
            ?: emptySet()
        val installedSourceUrls = scriptStorage.getAll()
            .mapNotNull { it.sourceUrl }
            .toSet()
        return PresetScripts.ALL.filter { preset ->
            preset.enabledByDefault &&
                preset.identifier.value !in provisioned &&
                preset.downloadUrl !in installedSourceUrls
        }
    }

    /** Был ли пресет уже обработан (установлен или пропущен пользователем). */
    fun isProvisioned(identifier: ScriptIdentifier): Boolean {
        val provisioned = preferences.getStringSet(KEY_PROVISIONED_DEFAULTS, emptySet())
            ?: emptySet()
        return identifier.value in provisioned
    }

    /**
     * Помечает пресет как обработанный — provisioning больше не будет его загружать,
     * даже если пользователь удалит скрипт из хранилища.
     */
    fun markProvisioned(identifier: ScriptIdentifier) {
        val current = preferences.getStringSet(KEY_PROVISIONED_DEFAULTS, emptySet())
            ?: emptySet()
        preferences.edit()
            .putStringSet(KEY_PROVISIONED_DEFAULTS, current + identifier.value)
            .apply()
    }

    companion object {
        private const val KEY_PROVISIONED_DEFAULTS = "provisioned_defaults"
        private const val LOG_TAG = "ScriptProvisioner"
    }
}
