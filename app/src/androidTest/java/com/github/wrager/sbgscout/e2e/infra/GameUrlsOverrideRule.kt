package com.github.wrager.sbgscout.e2e.infra

import com.github.wrager.sbgscout.config.GameUrls
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Перенаправляет [GameUrls] на localhost-базу fake-сервера перед тестом
 * и сбрасывает override'ы после теста.
 *
 * База URL должна быть готова к моменту `apply` — передавать через lambda,
 * которая вычисляется на старте (fake-сервер уже поднят).
 */
class GameUrlsOverrideRule(
    private val baseUrlProvider: () -> String,
) : TestRule {

    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                val baseUrl = baseUrlProvider().trimEnd('/')
                GameUrls.appUrlOverride = "$baseUrl/app"
                GameUrls.loginUrlOverride = "$baseUrl/login"
                GameUrls.hostMatchOverride = "127.0.0.1"
                try {
                    base.evaluate()
                } finally {
                    GameUrls.appUrlOverride = null
                    GameUrls.loginUrlOverride = null
                    GameUrls.hostMatchOverride = null
                }
            }
        }
}
