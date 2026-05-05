package com.github.wrager.sbgscout.e2e.infra

import androidx.test.platform.app.InstrumentationRegistry

/**
 * Читает текстовые фикстуры из `androidTest/assets/`.
 *
 * Важно: это ассеты instrumentation-процесса (test APK), а не target APK.
 * Используется `getInstrumentation().context`, не `targetContext`.
 */
object AssetLoader {
    fun read(path: String): String {
        val context = InstrumentationRegistry.getInstrumentation().context
        return context.assets.open(path).bufferedReader().use { it.readText() }
    }

    /**
     * Возвращает содержимое ассета или `null`, если файла нет. Используется
     * для fixtures, которые могут отсутствовать (например, `game-snapshot.html`
     * генерируется из gitignored `refs/game/private/` и есть только локально).
     */
    fun readOrNull(path: String): String? {
        return try {
            read(path)
        } catch (e: java.io.IOException) {
            null
        }
    }
}
