package com.github.wrager.sbgscout.script.installer

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
    private val assetReader: (String) -> String,
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

        for ((presetIdentifier, assetPath) in ASSET_MAP) {
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

        Log.i(LOG_TAG, "Установлен бандлированный скрипт: ${preset.displayName}")
    }

    companion object {
        private const val LOG_TAG = "BundledInstaller"

        /** Маппинг идентификатор пресета → путь в assets. */
        private val ASSET_MAP: Map<ScriptIdentifier, String> = mapOf(
            PresetScripts.SVP.identifier to "scripts/sbg-vanilla-plus.user.js",
        )
    }
}
