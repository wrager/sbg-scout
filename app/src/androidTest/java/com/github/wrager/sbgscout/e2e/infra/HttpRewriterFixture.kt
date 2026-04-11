package com.github.wrager.sbgscout.e2e.infra

import com.github.wrager.sbgscout.script.updater.DefaultHttpFetcher

/**
 * Фикстура для подмены сетевых обращений приложения на локальный [FakeGameServer].
 *
 * Работает через [DefaultHttpFetcher.urlRewriter] — статический test-hook,
 * который в проде всегда `null`. В androidTest его устанавливают перед launch
 * Activity и сбрасывают после теста.
 *
 * Правила переписывания:
 * - `https://github.com/...` → `<baseUrl>/gh-web/...`
 * - `https://api.github.com/...` → `<baseUrl>/gh-api/...`
 * - `https://raw.githubusercontent.com/...` → `<baseUrl>/raw/...`
 * - `https://objects.githubusercontent.com/...` → `<baseUrl>/objects/...`
 *
 * FakeGameDispatcher обрабатывает `/gh-web/` и `/gh-api/`; `/raw/` и `/objects/`
 * зарезервированы на будущее для прямых raw-загрузок / GitHub CDN.
 */
object HttpRewriterFixture {

    fun install(baseUrl: String) {
        DefaultHttpFetcher.urlRewriter = { url ->
            when {
                url.startsWith("https://github.com/") ->
                    baseUrl + "/gh-web/" + url.removePrefix("https://github.com/")
                url.startsWith("https://api.github.com/") ->
                    baseUrl + "/gh-api/" + url.removePrefix("https://api.github.com/")
                url.startsWith("https://raw.githubusercontent.com/") ->
                    baseUrl + "/raw/" + url.removePrefix("https://raw.githubusercontent.com/")
                url.startsWith("https://objects.githubusercontent.com/") ->
                    baseUrl + "/objects/" + url.removePrefix("https://objects.githubusercontent.com/")
                else -> url
            }
        }
    }

    fun clear() {
        DefaultHttpFetcher.urlRewriter = null
    }
}
