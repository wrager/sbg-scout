package com.github.wrager.sbgscout.script.installer

import com.github.wrager.sbgscout.script.model.ScriptIdentifier
import com.github.wrager.sbgscout.script.model.UserScript
import com.github.wrager.sbgscout.script.parser.HeaderParser
import com.github.wrager.sbgscout.script.storage.ScriptStorage

/**
 * Парсит raw-контент юзерскрипта и сохраняет в хранилище.
 *
 * [parse] возвращает [UserScript] **без сохранения** — вызывающий код
 * может дополнить результат (sourceUrl, updateUrl, isPreset) через
 * [UserScript.copy], а затем вызвать [save].
 */
class ScriptInstaller(private val scriptStorage: ScriptStorage) {

    /**
     * Парсит заголовок UserScript и строит [UserScript] без сохранения.
     *
     * Поля `sourceUrl` и `updateUrl` берутся из заголовка (`@downloadURL`,
     * `@updateURL`). Если заголовок их не содержит — значения будут `null`.
     * Вызывающий код при необходимости подставляет fallback через `.copy()`.
     */
    fun parse(content: String): ScriptInstallResult {
        val header = HeaderParser.parse(content)
            ?: return ScriptInstallResult.InvalidHeader

        val identifier = buildIdentifier(header.namespace, header.name)

        val script = UserScript(
            identifier = identifier,
            header = header,
            sourceUrl = header.downloadUrl,
            updateUrl = header.updateUrl ?: header.downloadUrl,
            content = content,
        )

        return ScriptInstallResult.Parsed(script)
    }

    /** Сохраняет скрипт в хранилище. */
    fun save(script: UserScript) {
        scriptStorage.save(script)
    }

    private fun buildIdentifier(namespace: String?, name: String): ScriptIdentifier {
        // `removePrefix` возвращает non-null String, поэтому цепочка `?.` →
        // `let { ... }` с одним null-check на namespace устраняет мёртвую
        // ветку второго `?.`, которую JaCoCo считает непокрытой.
        val prefix = namespace?.let { it.removePrefix("https://").removePrefix("http://") }
        return if (prefix != null) {
            ScriptIdentifier("$prefix/$name")
        } else {
            ScriptIdentifier(name)
        }
    }
}
