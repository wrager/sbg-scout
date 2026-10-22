package com.github.wrager.sbgscout.e2e.screens

/**
 * Блок кнопок в левом верхнем углу (`topButtonsContainer`) рядом с параметрами
 * индикатора загрузки страницы в правом (`pageLoadingIndicator`), в пикселях.
 * Индикатор описан параметрами разметки: вне загрузки страницы он скрыт и не
 * размещается на экране.
 *
 * @property buttonsStart отступ блока от левого края содержимого `rootLayout`.
 * @property buttonsTop отступ блока от верха содержимого `rootLayout`.
 * @property indicatorEnd отступ индикатора от правого края.
 * @property indicatorTop отступ индикатора сверху.
 * @property buttonsScreenTop верх блока на экране.
 * @property safeTop нижняя граница зоны статус-бара и выреза экрана, в том числе
 *   скрытого статус-бара в полноэкранном режиме.
 * @property buttonSizes ширина и высота видимых кнопок блока.
 * @property indicatorSize ширина и высота индикатора.
 * @property buttonsStacked видимые кнопки стоят одна под другой без наложения.
 *
 * 🤖 Generated with Claude Opus 5.5
 */
data class TopCornerGeometry(
    val buttonsStart: Int,
    val buttonsTop: Int,
    val indicatorEnd: Int,
    val indicatorTop: Int,
    val buttonsScreenTop: Int,
    val safeTop: Int,
    val buttonSizes: List<Pair<Int, Int>>,
    val indicatorSize: Pair<Int, Int>,
    val buttonsStacked: Boolean,
) {
    /** Блок зеркален индикатору: те же отступы и размер, ниже статус-бара и выреза экрана. */
    val mirrorsIndicator: Boolean
        get() =
            buttonsStart == indicatorEnd &&
                buttonsTop == indicatorTop &&
                buttonsScreenTop >= safeTop &&
                buttonSizes.all { it == indicatorSize } &&
                buttonsStacked
}
