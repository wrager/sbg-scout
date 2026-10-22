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
 * Надпись и кнопки блока `nativeButtonsContainer` внизу экрана поверх WebView.
 *
 * @property loadingLabel надпись о загрузке игры.
 * @property reloadButton "Reload": загрузка страницы игры.
 * @property scoutButton нативная "Scout": экран настроек приложения.
 */
internal data class NativeButtonsState(
    val loadingLabel: Boolean,
    val reloadButton: Boolean,
    val scoutButton: Boolean,
)

/**
 * Надпись и кнопки для страницы [page]. [scoutButtonReplaced] - игра загрузилась, и в её
 * настройки вставлена HTML-кнопка вместо "Scout"; [canGoBack] - "Назад" вернёт
 * на предыдущую страницу WebView.
 *
 * Надпись видна только на странице игры, пока игра грузится: вне игры загрузку
 * страницы показывает индикатор в правом нижнем углу.
 *
 * Вне игры, кроме входа, "Reload" нужна, только когда "Назад" не вернёт в игру,
 * например на странице, открытой по ссылке из другого приложения: иначе кнопки
 * перекрывали бы страницу.
 *
 * 🤖 Generated with Claude Opus 5.5
 */
internal fun nativeButtonsState(
    page: PageLoadState,
    scoutButtonReplaced: Boolean,
    canGoBack: Boolean,
): NativeButtonsState =
    when (page) {
        PageLoadState.GAME_LOADING ->
            NativeButtonsState(loadingLabel = true, reloadButton = true, scoutButton = true)
        PageLoadState.GAME_READY ->
            NativeButtonsState(loadingLabel = false, reloadButton = false, scoutButton = !scoutButtonReplaced)
        PageLoadState.LOGIN ->
            NativeButtonsState(loadingLabel = false, reloadButton = true, scoutButton = true)
        PageLoadState.OTHER_PAGE ->
            NativeButtonsState(loadingLabel = false, reloadButton = !canGoBack, scoutButton = false)
    }
