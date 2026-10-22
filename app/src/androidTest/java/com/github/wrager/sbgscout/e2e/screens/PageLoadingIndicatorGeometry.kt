package com.github.wrager.sbgscout.e2e.screens

/**
 * Положение индикатора загрузки страницы в правом верхнем углу, в пикселях.
 *
 * @property shown индикатор виден: у скрытого (`GONE`) нет положения на экране.
 * @property screenTop верх индикатора на экране.
 * @property safeTop нижняя граница зоны статус-бара и выреза экрана, в том числе
 *   скрытого статус-бара в полноэкранном режиме.
 * @property end отступ индикатора от правого края содержимого `rootLayout`.
 * @property margin отступ индикатора от края и от зоны статус-бара (`page_loading_indicator_margin`).
 *
 * 🤖 Generated with Claude Opus 5.5
 */
data class PageLoadingIndicatorGeometry(
    val shown: Boolean,
    val screenTop: Int,
    val safeTop: Int,
    val end: Int,
    val margin: Int,
) {
    val isInPlace: Boolean
        get() = shown && screenTop == safeTop + margin && end == margin
}
