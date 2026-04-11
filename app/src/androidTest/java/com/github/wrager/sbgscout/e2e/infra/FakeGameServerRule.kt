package com.github.wrager.sbgscout.e2e.infra

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * JUnit-правило: поднимает [FakeGameServer], устанавливает [HttpRewriterFixture]
 * для подмены GitHub-хостов на loopback и гарантированно убирает всё в `after`.
 *
 * Используется в тестах, которые не наследуются от [E2ETestBase] (например,
 * ScriptManager*-тесты на LauncherActivity), но всё равно нуждаются в
 * перехвате HTTP-трафика к github.com / api.github.com и fake-ответах для
 * скачивания скриптов / проверки обновлений / GithubReleaseProvider.
 *
 * Обычное использование:
 * ```
 * class MyTest {
 *     @get:Rule val fakeServer = FakeGameServerRule()
 *     val server get() = fakeServer.server
 *
 *     @Test fun something() {
 *         server.stubScriptAsset(...)
 *         ActivityScenario.launch(LauncherActivity::class.java).use { scenario ->
 *             // ...
 *         }
 *     }
 * }
 * ```
 */
class FakeGameServerRule : TestRule {

    val server = FakeGameServer()

    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                server.start()
                HttpRewriterFixture.install(server.baseUrl)
                try {
                    base.evaluate()
                } finally {
                    HttpRewriterFixture.clear()
                    server.shutdown()
                }
            }
        }
}
