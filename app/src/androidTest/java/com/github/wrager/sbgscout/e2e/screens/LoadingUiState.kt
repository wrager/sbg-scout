package com.github.wrager.sbgscout.e2e.screens

/**
 * Видимость индикатора загрузки игры поверх WebView в GameActivity:
 * белая подложка, надпись «Инициализация игры…», «Reload» и нативная «Scout».
 */
data class LoadingUiState(
    val overlay: Boolean,
    val label: Boolean,
    val reload: Boolean,
    val scout: Boolean,
) {
    companion object {
        /** Страница игры грузится: видно всё. */
        val LOADING = LoadingUiState(overlay = true, label = true, reload = true, scout = true)

        /** Страница входа: "Scout" ведёт в настройки, "Reload" перезагружает вход. */
        val LOGIN_PAGE = LoadingUiState(overlay = false, label = false, reload = true, scout = true)

        /** Страница вне игры, с которой "Назад" не вернёт в игру: в игру ведёт только "Reload". */
        val OTHER_PAGE_WITHOUT_HISTORY = LoadingUiState(overlay = false, label = false, reload = true, scout = false)

        /**
         * Индикатор загрузки скрыт: игра загрузилась или открыта страница вне
         * игры. [scout] — осталась ли нативная «Scout».
         */
        fun hidden(scout: Boolean) = LoadingUiState(overlay = false, label = false, reload = false, scout = scout)
    }
}
