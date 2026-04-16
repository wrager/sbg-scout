package com.github.wrager.sbgscout.script.installer

import android.content.SharedPreferences
import android.util.Log
import com.github.wrager.sbgscout.script.model.ScriptIdentifier
import com.github.wrager.sbgscout.script.preset.PresetScript
import com.github.wrager.sbgscout.script.preset.PresetScripts
import com.github.wrager.sbgscout.script.provisioner.DefaultScriptProvisioner
import com.github.wrager.sbgscout.script.storage.ScriptStorage

/**
 * Устанавливает юзерскрипты, бандлированные в APK (assets), при первом запуске.
 *
 * Пресет пропускается, если он уже помечен как provisioned (пользователь
 * мог удалить скрипт вручную) **или** уже есть в хранилище по sourceUrl.
 *
 * @param assetReader абстракция чтения из assets для тестируемости
 */
class BundledScriptInstaller(
    private val scriptInstaller: ScriptInstaller,
    private val scriptStorage: ScriptStorage,
    private val scriptProvisioner: DefaultScriptProvisioner,
    private val preferences: SharedPreferences,
    private val assetReader: (String) -> String,
    // Параметр для тестируемости: по умолчанию — дефолтный маппинг пресетов в ассеты.
    // В unit-тестах подставляется произвольная map для покрытия edge case'ов
    // (неизвестный идентификатор, preset с enabledByDefault=false).
    private val assetMap: Map<ScriptIdentifier, String> = DEFAULT_ASSET_MAP,
) {
    /**
     * Устанавливает бандлированные скрипты для пресетов, которые ещё не установлены.
     *
     * Для каждого установленного скрипта: включает если [PresetScript.enabledByDefault],
     * помечает как provisioned (чтобы [DefaultScriptProvisioner] не пытался скачать из сети).
     */
    fun installBundled() {
        val installedSourceUrls = scriptStorage.getAll()
            .mapNotNull { it.sourceUrl }
            .toSet()

        for ((presetIdentifier, assetPath) in assetMap) {
            val preset = PresetScripts.ALL.find { it.identifier == presetIdentifier }
                ?: continue
            val alreadyHandled = scriptProvisioner.isProvisioned(preset.identifier) ||
                preset.downloadUrl in installedSourceUrls
            if (!alreadyHandled) {
                installPreset(preset, assetPath)
            }
        }
    }

    private fun installPreset(preset: PresetScript, assetPath: String) {
        val content = try {
            assetReader(assetPath)
        } catch (@Suppress("TooGenericExceptionCaught") exception: Exception) {
            Log.w(LOG_TAG, "Не удалось прочитать бандлированный скрипт: $assetPath", exception)
            return
        }

        val parseResult = scriptInstaller.parse(content)
        if (parseResult !is ScriptInstallResult.Parsed) {
            Log.w(LOG_TAG, "Невалидный заголовок в бандлированном скрипте: $assetPath")
            return
        }

        val script = parseResult.script.copy(
            sourceUrl = preset.downloadUrl,
            updateUrl = preset.updateUrl,
            isPreset = true,
        )
        scriptInstaller.save(script)

        if (preset.enabledByDefault) {
            scriptStorage.setEnabled(script.identifier, true)
        }
        scriptProvisioner.markProvisioned(preset.identifier)
        saveBundledVersion(preset.identifier, script.header.version)

        Log.i(LOG_TAG, "Установлен бандлированный скрипт: ${preset.displayName}")
    }

    /**
     * Сохраняет версию бандлированного скрипта для [BundledScriptBeacon].
     *
     * Beacon должен пинговать только ту версию, которая установлена из бандла,
     * а не текущую версию в storage (которая может быть обновлена пользователем
     * через диалог обновления). Иначе — double counting: реальный download при
     * обновлении + ping beacon'а на ту же версию.
     */
    private fun saveBundledVersion(identifier: ScriptIdentifier, version: String?) {
        if (version == null) return
        val current = preferences.getStringSet(KEY_BUNDLED_VERSIONS, emptySet()) ?: emptySet()
        val key = "${identifier.value}:$version"
        if (key !in current) {
            preferences.edit()
                .putStringSet(KEY_BUNDLED_VERSIONS, current + key)
                .apply()
        }
    }

    companion object {
        private const val LOG_TAG = "BundledInstaller"
        const val KEY_BUNDLED_VERSIONS = "bundled_script_versions"

        /** Маппинг идентификатор пресета → путь в assets. */
        private val DEFAULT_ASSET_MAP: Map<ScriptIdentifier, String> = mapOf(
            PresetScripts.SVP.identifier to "scripts/sbg-vanilla-plus.user.js",
        )
    }
}
