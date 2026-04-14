package com.github.wrager.sbgscout.e2e.infra

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.github.wrager.sbgscout.script.model.ScriptHeader
import com.github.wrager.sbgscout.script.model.ScriptIdentifier
import com.github.wrager.sbgscout.script.model.UserScript
import com.github.wrager.sbgscout.script.storage.ScriptFileStorageImpl
import com.github.wrager.sbgscout.script.storage.ScriptStorage
import com.github.wrager.sbgscout.script.storage.ScriptStorageImpl
import java.io.File

/**
 * Programmatic-доступ к [ScriptStorage] target-приложения из androidTest.
 *
 * Обходит необходимость HTTP-установки скриптов: тест создаёт [UserScript]
 * в памяти, сохраняет через тот же контракт storage, что и прод-код
 * ([ScriptStorageImpl]: SharedPreferences "scripts" + filesDir/scripts/),
 * и после этого GameActivity + ScriptListFragment читают установленные
 * скрипты как обычно.
 */
object ScriptStorageFixture {

    fun storage(): ScriptStorage {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("scripts", Context.MODE_PRIVATE)
        val fileStorage = ScriptFileStorageImpl(File(context.filesDir, "scripts"))
        return ScriptStorageImpl(preferences, fileStorage)
    }

    /**
     * Создаёт минимальный [UserScript] с переданным именем и версией.
     * Поля `content`, `sourceUrl`, `match` заполняются реалистичными значениями,
     * чтобы парсер инжекции и фильтры конфликтов не отказывали при обработке.
     */
    fun minimalScript(
        name: String,
        version: String = "1.0.0",
        enabled: Boolean = false,
        identifierValue: String = "test-$name".replace(" ", "-").lowercase(),
        sourceUrl: String = "https://example.com/$identifierValue.user.js",
        isPreset: Boolean = false,
    ): UserScript = UserScript(
        identifier = ScriptIdentifier(identifierValue),
        header = ScriptHeader(
            name = name,
            version = version,
            namespace = "e2e-tests",
            match = listOf("https://sbg-game.ru/app/*"),
        ),
        sourceUrl = sourceUrl,
        updateUrl = null,
        content = """
            // ==UserScript==
            // @name $name
            // @namespace e2e-tests
            // @version $version
            // @match https://sbg-game.ru/app/*
            // ==/UserScript==
            console.log('e2e fixture script: $name');
        """.trimIndent(),
        enabled = enabled,
        isPreset = isPreset,
    )
}
