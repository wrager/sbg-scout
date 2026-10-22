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
 * Кнопки блока `topButtonsContainer` в левом верхнем углу поверх WebView.
 *
 * @property reloadButton "Reload": загрузка страницы игры.
 * @property scoutButton нативная "Scout": экран настроек приложения.
 */
internal data class TopButtonsState(
    val reloadButton: Boolean,
    val scoutButton: Boolean,
)

/**
 * Кнопки для страницы [page]. [scoutButtonReplaced] - игра загрузилась, и в её
 * настройки вставлена HTML-кнопка вместо "Scout"; [canGoBack] - "Назад" вернёт
 * на предыдущую страницу WebView.
 *
 * Вне игры, кроме входа, "Reload" нужна, только когда "Назад" не вернёт в игру,
 * например на странице, открытой по ссылке из другого приложения: иначе кнопки
 * перекрывали бы страницу.
 *
 * 🤖 Generated with Claude Opus 5.5
 */
internal fun topButtonsState(
    page: PageLoadState,
    scoutButtonReplaced: Boolean,
    canGoBack: Boolean,
): TopButtonsState =
    when (page) {
        PageLoadState.GAME_LOADING -> TopButtonsState(reloadButton = true, scoutButton = true)
        PageLoadState.GAME_READY -> TopButtonsState(reloadButton = false, scoutButton = !scoutButtonReplaced)
        PageLoadState.LOGIN -> TopButtonsState(reloadButton = true, scoutButton = true)
        PageLoadState.OTHER_PAGE -> TopButtonsState(reloadButton = !canGoBack, scoutButton = false)
    }
