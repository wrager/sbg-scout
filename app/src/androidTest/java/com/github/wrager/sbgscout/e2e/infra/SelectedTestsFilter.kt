package com.github.wrager.sbgscout.e2e.infra

import android.os.Bundle
import org.junit.runner.Description
import org.junit.runner.manipulation.Filter

/**
 * Оставляет в прогоне только тесты из аргумента раннера [ARGUMENT]: имена
 * `Класс#метод`, разделённые [SEPARATOR]. Через него `scripts/android.ps1 test -AllTests`
 * передаёт шарды полного набора, потому что из списка в аргументе `class` через
 * запятую AGP передаёт раннеру только первый элемент.
 *
 * 🤖 Generated with Claude Opus 5.5
 */
class SelectedTestsFilter(
    arguments: Bundle,
) : Filter() {
    private val selectedTests =
        arguments
            .getString(ARGUMENT)
            .orEmpty()
            .split(SEPARATOR)
            .filter { it.isNotEmpty() }
            .toSet()

    override fun shouldRun(description: Description): Boolean =
        if (description.isTest) {
            "${description.className}#${description.methodName}" in selectedTests
        } else {
            description.children.any { shouldRun(it) }
        }

    override fun describe(): String = "tests listed in $ARGUMENT"

    companion object {
        const val ARGUMENT = "selectedTests"
        const val SEPARATOR = ':'
    }
}
