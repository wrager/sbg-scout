package com.github.wrager.sbgscout.e2e.screens

/** Видимость нативных кнопок "Reload" и "Scout" поверх WebView в GameActivity. */
data class LoadingUiState(
    val reload: Boolean,
    val scout: Boolean,
) {
    companion object {
        /** Страница игры грузится, в том числе после `.fatal-error`: видны обе кнопки. */
        val LOADING = LoadingUiState(reload = true, scout = true)

        /** Страница входа: "Scout" ведёт в настройки, "Reload" перезагружает вход. */
        val LOGIN_PAGE = LoadingUiState(reload = true, scout = true)

        /** Страница вне игры, с которой "Назад" не вернёт в игру: в игру ведёт только "Reload". */
        val OTHER_PAGE_WITHOUT_HISTORY = LoadingUiState(reload = true, scout = false)

        /**
         * "Reload" скрыта: игра загрузилась или открыта страница вне игры,
         * с которой "Назад" вернёт в игру. [scout] - осталась ли нативная "Scout".
         */
        fun hidden(scout: Boolean) = LoadingUiState(reload = false, scout = scout)
    }
}
