package com.github.wrager.sbgscout.e2e

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.preference.PreferenceManager
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.IdlingRegistry
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.github.wrager.sbgscout.GameActivity
import com.github.wrager.sbgscout.config.GameUrls
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.infra.FakeGameServer
import com.github.wrager.sbgscout.e2e.infra.HttpRewriterFixture
import com.github.wrager.sbgscout.e2e.infra.WebViewIdlingResource
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import org.junit.After
import org.junit.Before
import org.junit.Rule

/**
 * Базовый класс e2e-тестов.
 *
 * Отвечает за жизненный цикл fake-сервера, IdlingResource, runtime-override
 * URL в [com.github.wrager.sbgscout.config.GameUrls], grant location permission.
 * Activity НЕ запускается автоматически — каждый тест делает это сам, т.к.
 * разным сценариям нужен разный setup (логин/не-логин, разные фикстуры HTML).
 *
 * Типовое использование:
 * ```
 * class MyE2ETest : E2ETestBase() {
 *     @Test fun something() {
 *         server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
 *         CookieFixtures.injectFakeAuth(server.baseUrl)
 *         val scenario = launchGameActivity()
 *         val game = GameScreen(scenario, idling).waitForLoaded()
 *         // ...
 *     }
 * }
 * ```
 */
abstract class E2ETestBase {

    protected val server = FakeGameServer()
    protected val idling = WebViewIdlingResource()

    @get:Rule
    val permissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )

    private var activeScenario: ActivityScenario<GameActivity>? = null
    private var savedClip: ClipData? = null

    @Before
    fun saveClipboard() {
        // ClipboardBridgeE2ETest и ReportBugE2ETest пишут в системный clipboard
        // эмулятора. Emulator по умолчанию синхронизирует clipboard с хостом
        // (Windows/macOS), что затирает буфер пользователя во время тестов.
        // Снимаем содержимое до теста и восстанавливаем в @After, чтобы тесты
        // не имели побочных эффектов на host clipboard.
        val manager = clipboardManager() ?: return
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            savedClip = manager.primaryClip
        }
    }

    @Before
    fun setUpE2E() {
        // Порядок важен: сначала старт сервера, потом чтение server.baseUrl
        // (оно же под капотом вызывает MockWebServer.hostName/port). Обратный
        // порядок триггерит ленивый MockWebServer.before() → start() на случайном
        // порту, и последующий явный start() падает с "start() already called".
        server.start()
        val baseUrl = server.baseUrl.trimEnd('/')
        GameUrls.appUrlOverride = "$baseUrl/app"
        GameUrls.loginUrlOverride = "$baseUrl/login"
        GameUrls.hostMatchOverride = "127.0.0.1"
        // HTTP rewriter: все сетевые запросы приложения к GitHub-хостам
        // направляются на fake-сервер. Устанавливается даже если конкретный
        // тест не использует GitHub — безопасно и даёт единое место контроля.
        HttpRewriterFixture.install(baseUrl)
        disableAutoUpdateCheck()
        IdlingRegistry.getInstance().register(idling)
    }

    /**
     * Подавляет фоновую проверку обновлений в [GameActivity.scheduleAutoUpdateCheck].
     * Без этого GameActivity ходит на github.com на старте, а при наличии более
     * свежего релиза показывает AlertDialog, который блокирует Espresso и ломает
     * e2e-тесты. Orchestrator clearPackageData стирает prefs перед каждым тестом,
     * поэтому выставляем значения заново в @Before.
     */
    private fun disableAutoUpdateCheck() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        PreferenceManager.getDefaultSharedPreferences(targetContext)
            .edit()
            .putBoolean("auto_check_updates", false)
            .putLong("last_update_check", System.currentTimeMillis())
            .apply()
    }

    @After
    fun tearDownE2E() {
        activeScenario?.close()
        activeScenario = null
        IdlingRegistry.getInstance().unregister(idling)
        CookieFixtures.clearAll()
        GameUrls.appUrlOverride = null
        GameUrls.loginUrlOverride = null
        GameUrls.hostMatchOverride = null
        HttpRewriterFixture.clear()
        server.shutdown()
    }

    @After
    fun restoreClipboard() {
        // Возвращаем clipboard к состоянию до теста. setPrimaryClip(null) —
        // NPE, поэтому для пустого исходного буфера ставим пустой ClipData
        // (работает с API 1, в отличие от clearPrimaryClip, доступной только
        // с API 28).
        val manager = clipboardManager() ?: return
        val snapshot = savedClip ?: ClipData.newPlainText("", "")
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            manager.setPrimaryClip(snapshot)
        }
        savedClip = null
    }

    private fun clipboardManager(): ClipboardManager? {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    }

    /**
     * Запускает [GameActivity] и подписывает idling на `onGamePageFinished`.
     * После возврата можно использовать [GameScreen.waitForLoaded].
     */
    protected fun launchGameActivity(): ActivityScenario<GameActivity> {
        val scenario = ActivityScenario.launch(GameActivity::class.java)
        attachIdling(scenario)
        activeScenario = scenario
        return scenario
    }

    /**
     * Запускает [GameActivity] с произвольным [intent] — используется для
     * проверки deep-link сценария (`ACTION_VIEW` + `data=sbg-game.ru/...`).
     * Компонент выставляется явно, чтобы `ActivityScenario` разрешил запуск
     * независимо от intent-filter манифеста (в instr-сборке host отличается
     * от прод-фильтра `sbg-game.ru`).
     */
    protected fun launchGameActivityWithIntent(
        intent: Intent,
    ): ActivityScenario<GameActivity> {
        intent.setClass(
            InstrumentationRegistry.getInstrumentation().targetContext,
            GameActivity::class.java,
        )
        val scenario = ActivityScenario.launch<GameActivity>(intent)
        attachIdling(scenario)
        activeScenario = scenario
        return scenario
    }

    private fun attachIdling(scenario: ActivityScenario<GameActivity>) {
        scenario.onActivity { activity ->
            activity.sbgWebViewClient.onGamePageFinished = {
                idling.markLoaded()
            }
        }
    }
}
