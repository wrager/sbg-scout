package com.github.wrager.sbgscout.e2e.flows.smoke

import android.os.SystemClock
import androidx.preference.PreferenceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Проверяет мост [com.github.wrager.sbgscout.bridge.GameSettingsBridge]
 * (регистрируется как `__sbg_settings`) вместе с инжектируемой JS-обёрткой
 * `LOCAL_STORAGE_WRAPPER`.
 *
 * Контракт: любая запись `localStorage.setItem('settings', JSON_STRING)`
 * должна приводить к вызову `__sbg_settings.onSettingsChanged(JSON_STRING)`,
 * GameActivity парсит JSON через GameSettingsReader и сохраняет применённые
 * тему и язык в `PreferenceManager.getDefaultSharedPreferences` под ключами
 * `applied_game_theme` / `applied_game_language`.
 */
@RunWith(AndroidJUnit4::class)
class GameSettingsBridgeE2ETest : E2ETestBase() {

    @Test
    fun settingsChange_inLocalStorage_updatesAppliedThemeAndLanguagePrefs() {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)

        val scenario = launchGameActivity()
        val game = GameScreen(scenario, idling).waitForLoaded()

        // Симуляция изменения игровых настроек: JS пишет в localStorage,
        // LOCAL_STORAGE_WRAPPER перехватывает и зовёт __sbg_settings.
        game.evaluateJs(
            "localStorage.setItem('settings', JSON.stringify({theme:'dark', lang:'ru'}))",
        )

        val prefs = PreferenceManager.getDefaultSharedPreferences(
            InstrumentationRegistry.getInstrumentation().targetContext,
        )
        // applyGameSettings идёт через runOnUiThread → prefs обновляются
        // асинхронно. Опрос по 50ms до появления ожидаемого значения.
        val deadline = SystemClock.uptimeMillis() + TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val theme = prefs.getString("applied_game_theme", null)
            val language = prefs.getString("applied_game_language", null)
            if (theme == "DARK" && language == "ru") return
            Thread.sleep(POLL_INTERVAL_MS)
        }
        fail(
            "applied_game_theme=DARK и applied_game_language=ru не появились " +
                "в SharedPreferences за ${TIMEOUT_MS}ms",
        )
    }

    private companion object {
        const val TIMEOUT_MS = 3_000L
        const val POLL_INTERVAL_MS = 50L
    }
}
