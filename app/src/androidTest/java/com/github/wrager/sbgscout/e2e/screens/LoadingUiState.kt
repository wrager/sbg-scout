package com.github.wrager.sbgscout.e2e.screens

/** Видимость надписи о загрузке игры и нативных кнопок "Reload" и "Scout" поверх WebView в GameActivity. */
data class LoadingUiState(
    val label: Boolean,
    val reload: Boolean,
    val scout: Boolean,
) {
    companion object {
        /** Страница игры грузится, в том числе после `.fatal-error`: видны надпись и обе кнопки. */
        val LOADING = LoadingUiState(label = true, reload = true, scout = true)

        /** Страница входа: "Scout" ведёт в настройки, "Reload" перезагружает вход. */
        val LOGIN_PAGE = LoadingUiState(label = false, reload = true, scout = true)

        /** Страница вне игры, с которой "Назад" не вернёт в игру: в игру ведёт только "Reload". */
        val OTHER_PAGE_WITHOUT_HISTORY = LoadingUiState(label = false, reload = true, scout = false)

        /**
         * Надпись и "Reload" скрыты: игра загрузилась или открыта страница вне игры,
         * с которой "Назад" вернёт в игру. [scout] - осталась ли нативная "Scout".
         */
        fun hidden(scout: Boolean) = LoadingUiState(label = false, reload = false, scout = scout)
    }
}
