package com.github.wrager.sbgscout

/** Страница в WebView и ход загрузки игры на ней. */
internal enum class PageLoadState {
    /**
     * Страница игры грузится, в том числе застряла на упавшем этапе загрузки
     * или показала `.fatal-error`, например при отказе в геолокации.
     */
    GAME_LOADING,

    /** Игра убрала свой экран загрузки. */
    GAME_READY,

    /** Страница входа. */
    LOGIN,

    /** Другая страница вне игры: правила, магазин и т.п. */
    OTHER_PAGE,
}

/**
 * Блок кнопок `topButtonsContainer` поверх WebView в GameActivity.
 *
 * @property reloadButton "Reload": загрузка страницы игры.
 * @property scoutButton нативная "Scout": экран настроек приложения.
 * @property atTop блок у верхнего края экрана; иначе по центру.
 *
 * 🤖 Generated with Claude Opus 5.5
 */
internal data class TopButtonsState(
    val reloadButton: Boolean,
    val scoutButton: Boolean,
    val atTop: Boolean,
)

/**
 * Блок кнопок для страницы [page]. [scoutButtonReplaced] - игра загрузилась,
 * и в её настройки вставлена HTML-кнопка вместо "Scout"; [canGoBack] - "Назад"
 * вернёт на предыдущую страницу WebView.
 *
 * По центру экрана блок стоит только в загруженной игре. Пока игра грузится,
 * по центру "Reload" ложилась бы на кнопку игры "Хочу" в её экране загрузки,
 * текст `.fatal-error` игра выводит по центру экрана, а на странице входа
 * "Scout" закрывала бы кнопку входа через Telegram. Вне игры, кроме входа,
 * "Reload" нужна, только когда "Назад" не вернёт в игру, например на странице,
 * открытой по ссылке из другого приложения: иначе кнопки перекрывали бы страницу.
 *
 * 🤖 Generated with Claude Opus 5.5
 */
internal fun topButtonsState(
    page: PageLoadState,
    scoutButtonReplaced: Boolean,
    canGoBack: Boolean,
): TopButtonsState =
    when (page) {
        PageLoadState.GAME_LOADING -> TopButtonsState(reloadButton = true, scoutButton = true, atTop = true)
        PageLoadState.GAME_READY ->
            TopButtonsState(reloadButton = false, scoutButton = !scoutButtonReplaced, atTop = false)
        PageLoadState.LOGIN -> TopButtonsState(reloadButton = true, scoutButton = true, atTop = true)
        PageLoadState.OTHER_PAGE -> TopButtonsState(reloadButton = !canGoBack, scoutButton = false, atTop = true)
    }
