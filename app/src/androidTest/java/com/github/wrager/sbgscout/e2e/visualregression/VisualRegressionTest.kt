package com.github.wrager.sbgscout.e2e.visualregression

/**
 * Маркер тестов visual regression.
 *
 * Gradle-задачи `visualRegressionTest` и `updateVisualRegressionBaselines`
 * фильтруют прогон `connectedInstrAndroidTest` по этой аннотации, чтобы не
 * гонять остальные e2e-сценарии. См. extension в `app/build.gradle.kts`.
 *
 * Тесты пишут actual.png в test storage через
 * [VisualRegressionCapture]; host-side задачи [`compareVisualRegressionBaselines`]
 * сравнивают с baseline в `.github/images/visual-regression/`, а
 * [`copyVisualRegressionBaselines`] перезаписывает baseline'ы при намеренном
 * UI-изменении.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class VisualRegressionTest
