package com.github.wrager.sbgscout.e2e.visualregression

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice

/**
 * Включает SystemUI demo mode на эмуляторе - фиксирует часы, batteryUI,
 * иконки сети и скрывает notifications в status bar. Без этого время в часах
 * (HH:MM) меняется между прогонами и pixel-diff фейлит на всех VR-тестах,
 * где status bar попадает в область captureFullNative или верхний frame
 * scroll-stitch'а.
 *
 * Реализация - стандартный broadcast-протокол SystemUI demo mode:
 * <https://android.googlesource.com/platform/frameworks/base/+/master/packages/SystemUI/docs/demo_mode.md>.
 *
 * Demo mode требует один раз `settings put global sysui_demo_allowed 1` -
 * это persistent setting устройства, выживает перезагрузку. Тесты выставляют
 * его всегда, чтобы работать на свежей AVD из CI без preconfigure.
 *
 * `enable()` вызывать в `@Before` ДО launchActivity, `disable()` в `@After`.
 * Без disable() следующий e2e-тест получит фейковые иконки status bar - не
 * страшно для assertion-логики, но шумит в визуальной отладке.
 */
object StatusBarDemoMode {

    fun enable() {
        executeShellCommands(
            "settings put global sysui_demo_allowed 1",
            "am broadcast -a com.android.systemui.demo -e command enter",
            "am broadcast -a com.android.systemui.demo -e command clock -e hhmm $FIXED_HHMM",
            "am broadcast -a com.android.systemui.demo " +
                "-e command battery -e level $FIXED_BATTERY -e plugged false",
            "am broadcast -a com.android.systemui.demo " +
                "-e command network -e wifi show -e level 4",
            "am broadcast -a com.android.systemui.demo " +
                "-e command network -e mobile show -e datatype none -e level 4",
            "am broadcast -a com.android.systemui.demo -e command notifications -e visible false",
        )
    }

    fun disable() {
        executeShellCommands(
            "am broadcast -a com.android.systemui.demo -e command exit",
        )
    }

    private fun executeShellCommands(vararg commands: String) {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        for (command in commands) {
            device.executeShellCommand(command)
        }
    }

    private const val FIXED_HHMM = "1200"
    private const val FIXED_BATTERY = "100"
}
