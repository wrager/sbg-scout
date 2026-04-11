package com.github.wrager.sbgscout.e2e.screens

/**
 * Базовый маркер для PageObject-классов e2e-тестов.
 *
 * Screen-классы группируют взаимодействия с одним экраном приложения
 * и возвращают себя для chaining. Тесты пишутся в терминах screen-методов,
 * а не `onView(withId(...))` — это изолирует изменения UI в одном месте.
 */
interface Screen {
    fun assertDisplayed()
}
