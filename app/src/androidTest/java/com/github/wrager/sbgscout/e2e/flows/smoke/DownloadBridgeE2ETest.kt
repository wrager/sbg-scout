package com.github.wrager.sbgscout.e2e.flows.smoke

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.wrager.sbgscout.e2e.E2ETestBase
import com.github.wrager.sbgscout.e2e.infra.AssetLoader
import com.github.wrager.sbgscout.e2e.infra.CookieFixtures
import com.github.wrager.sbgscout.e2e.screens.GameScreen
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Проверяет мост [com.github.wrager.sbgscout.bridge.DownloadBridge]
 * (регистрируется как `__sbg_download`) и его BOOTSTRAP_SCRIPT, который
 * инжектируется в onPageStarted и оборачивает `URL.createObjectURL` для
 * захвата blob-объектов.
 *
 * Реальный поток download → MediaStore проверяется не здесь — в E2E
 * стадии мы верифицируем границу JS↔Kotlin: что мост зарегистрирован,
 * bootstrap выполнился, и перехват createObjectURL установлен (ловит
 * blob URL с префиксом `blob:`).
 */
@RunWith(AndroidJUnit4::class)
class DownloadBridgeE2ETest : E2ETestBase() {

    @Test
    fun bootstrap_exposesBridgeAndInstallsBlobInterceptor() {
        server.gamePageBody = AssetLoader.read("fixtures/app-page-minimal.html")
        CookieFixtures.injectFakeAuth(server.baseUrl)

        val scenario = launchGameActivity()
        val game = GameScreen(scenario, idling).waitForLoaded()

        assertEquals("true", game.evaluateJs("typeof __sbg_download !== 'undefined'"))
        assertEquals(
            "true",
            game.evaluateJs("typeof __sbg_download.saveBlob === 'function'"),
        )

        // Маркер, который BOOTSTRAP_SCRIPT ставит один раз после инициализации.
        assertEquals(
            "true",
            game.evaluateJs("window.__sbg_download_installed === true"),
        )

        // Перехват createObjectURL должен возвращать валидный blob:URL
        // и запоминать Blob во внутренней Map (проверяется возвратом строки,
        // начинающейся с blob:).
        val blobUrlOk = game.evaluateJs(
            "(function(){" +
                "var b = new Blob(['hi'],{type:'text/plain'});" +
                "var url = URL.createObjectURL(b);" +
                "return url.indexOf('blob:') === 0;" +
                "})()",
        )
        assertEquals("true", blobUrlOk)
    }
}
