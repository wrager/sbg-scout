package com.github.wrager.sbgscout.e2e.screens

/**
 * Столбец `topButtonsContainer` в правом верхнем углу, в пикселях: пустое место
 * размером с кнопку, затем "Scout", "Reload" и индикатор загрузки страницы.
 * Скрытый элемент остаётся на своём месте, поэтому положения проверяются
 * у всех элементов независимо от их видимости.
 *
 * @property columnScreenTop верх столбца на экране.
 * @property safeTop нижняя граница зоны статус-бара и выреза экрана, в том числе
 *   скрытого статус-бара в полноэкранном режиме.
 * @property columnEnd отступ столбца от правого края содержимого `rootLayout`.
 * @property margin отступ столбца от края и от зоны статус-бара (`top_corner_margin`).
 * @property itemTops верх "Scout", "Reload" и индикатора внутри столбца.
 * @property itemSizes ширина и высота тех же элементов.
 * @property itemsLaidOut ни один элемент не убран из разметки (`GONE`).
 * @property size размер кнопки (`top_corner_size`).
 * @property step размер кнопки вместе с зазором до следующей (`top_buttons_gap`).
 *
 * 🤖 Generated with Claude Opus 5.5
 */
data class TopButtonsColumnGeometry(
    val columnScreenTop: Int,
    val safeTop: Int,
    val columnEnd: Int,
    val margin: Int,
    val itemTops: List<Int>,
    val itemSizes: List<Pair<Int, Int>>,
    val itemsLaidOut: Boolean,
    val size: Int,
    val step: Int,
) {
    /** Элементы стоят в своих местах столбца: первое место пустое, дальше "Scout", "Reload", индикатор. */
    val isInPlace: Boolean
        get() =
            columnScreenTop == safeTop + margin &&
                columnEnd == margin &&
                itemTops == listOf(step, 2 * step, 3 * step) &&
                itemSizes.all { it == size to size } &&
                itemsLaidOut
}
