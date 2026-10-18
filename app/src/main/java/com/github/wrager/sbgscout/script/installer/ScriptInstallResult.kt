package com.github.wrager.sbgscout.script.installer

import com.github.wrager.sbgscout.script.model.UserScript

/**
 * Результат парсинга юзерскрипта из raw-контента.
 *
 * [Parsed] содержит [UserScript] с безопасными defaults
 * (`isPreset = false`, `enabled = false`). Вызывающий код может
 * дополнить его через [UserScript.copy] перед сохранением.
 */
sealed class ScriptInstallResult {
    data class Parsed(
        val script: UserScript,
    ) : ScriptInstallResult()

    data object InvalidHeader : ScriptInstallResult()
}
