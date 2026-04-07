package com.github.wrager.sbgscout.script.preset

import com.github.wrager.sbgscout.script.model.ScriptIdentifier

object PresetScripts {

    val SVP = PresetScript(
        identifier = ScriptIdentifier("github.com/wrager/sbg-vanilla-plus"),
        displayName = "SBG Vanilla+",
        downloadUrl =
            "https://github.com/wrager/sbg-vanilla-plus/releases/latest/download/sbg-vanilla-plus.user.js",
        updateUrl =
            "https://github.com/wrager/sbg-vanilla-plus/releases/latest/download/sbg-vanilla-plus.meta.js",
        enabledByDefault = true,
        description = "Добавляет самые необходимые улучшения и фичи.",
    )

    val EUI = PresetScript(
        identifier = ScriptIdentifier("github.com/egorantonov/sbg-enhanced"),
        displayName = "SBG Enhanced UI",
        downloadUrl =
            "https://github.com/egorantonov/sbg-enhanced/releases/latest/download/eui.user.js",
        updateUrl =
            "https://github.com/egorantonov/sbg-enhanced/releases/latest/download/eui.user.js",
        description = "Улучшает интерфейс и удобство.",
    )

    val CUI = PresetScript(
        identifier = ScriptIdentifier("github.com/nicko-v/sbg-cui"),
        displayName = "SBG CUI",
        downloadUrl =
            "https://github.com/egorantonov/sbg-enhanced/releases/latest/download/cui.user.js",
        updateUrl =
            "https://github.com/egorantonov/sbg-enhanced/releases/latest/download/cui.user.js",
        description = "Добавляет множество улучшений и фич, сильно меняет интерфейс игры. " +
            "Не рекомендуется для новичков.",
    )

    val ALL: List<PresetScript> = listOf(SVP, EUI, CUI)
}
