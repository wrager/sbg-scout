package com.github.wrager.sbgscout

/** Страница в WebView и ход загрузки игры на ней. */
internal enum class PageLoadState {
    /** Страница игры грузится, в том числе застряла на упавшем этапе загрузки. */
    GAME_LOADING,

    /** Игра убрала свой экран загрузки. */
    GAME_READY,

    /** Страница входа. */
    LOGIN,

    /** Другая страница вне игры: правила, магазин и т.п. */
    OTHER_PAGE,
}

/**
 * Что показывает GameActivity поверх WebView: белую подложку и блок кнопок
 * `topButtonsContainer`.
 *
 * @property loadingOverlay белая подложка.
 * @property initializingLabel надпись "Инициализация игры...".
 * @property reloadButton "Reload": загрузка страницы игры.
 * @property scoutButton нативная "Scout": экран настроек приложения.
 * @property atTop блок у верхнего края экрана; иначе по центру.
 *
 * 🤖 Generated with Claude Opus 5.5
 */
internal data class TopButtonsState(
    val loadingOverlay: Boolean,
    val initializingLabel: Boolean,
    val reloadButton: Boolean,
    val scoutButton: Boolean,
    val atTop: Boolean,
)

/**
 * Подложка и блок кнопок для страницы [page]. [scoutButtonReplaced] - игра
 * загрузилась, и в её настройки вставлена HTML-кнопка вместо "Scout";
 * [canGoBack] - "Назад" вернёт на предыдущую страницу WebView.
 *
 * Вне игры блок у верхнего края: по центру "Scout" закрывала бы кнопку входа
 * через Telegram. Вне игры, кроме входа, "Reload" нужна, только когда "Назад"
 * не вернёт в игру, например на странице, открытой по ссылке из другого
 * приложения: иначе кнопки перекрывали бы страницу.
 *
 * 🤖 Generated with Claude Opus 5.5
 */
internal fun topButtonsState(
    page: PageLoadState,
    scoutButtonReplaced: Boolean,
    canGoBack: Boolean,
): TopButtonsState =
    when (page) {
        PageLoadState.GAME_LOADING ->
            TopButtonsState(
                loadingOverlay = true,
                initializingLabel = true,
                reloadButton = true,
                scoutButton = true,
                atTop = false,
            )
        PageLoadState.GAME_READY ->
            TopButtonsState(
                loadingOverlay = false,
                initializingLabel = false,
                reloadButton = false,
                scoutButton = !scoutButtonReplaced,
                atTop = false,
            )
        PageLoadState.LOGIN ->
            TopButtonsState(
                loadingOverlay = false,
                initializingLabel = false,
                reloadButton = true,
                scoutButton = true,
                atTop = true,
            )
        PageLoadState.OTHER_PAGE ->
            TopButtonsState(
                loadingOverlay = false,
                initializingLabel = false,
                reloadButton = !canGoBack,
                scoutButton = false,
                atTop = true,
            )
    }
