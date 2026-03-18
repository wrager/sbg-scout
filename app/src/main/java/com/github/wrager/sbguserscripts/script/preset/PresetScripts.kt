package com.github.wrager.sbguserscripts.script.preset

import com.github.wrager.sbguserscripts.script.model.ScriptIdentifier

object PresetScripts {

    val SVP = PresetScript(
        identifier = ScriptIdentifier("github.com/wrager/sbg-vanilla-plus"),
        displayName = "SBG Vanilla+",
        downloadUrl =
            "https://github.com/wrager/sbg-vanilla-plus/releases/latest/download/sbg-vanilla-plus.user.js",
        updateUrl =
            "https://github.com/wrager/sbg-vanilla-plus/releases/latest/download/sbg-vanilla-plus.meta.js",
    )

    val EUI = PresetScript(
        identifier = ScriptIdentifier("github.com/egorantonov/sbg-enhanced"),
        displayName = "SBG Enhanced UI",
        downloadUrl =
            "https://github.com/egorantonov/sbg-enhanced/releases/latest/download/eui.user.js",
        updateUrl =
            "https://github.com/egorantonov/sbg-enhanced/releases/latest/download/eui.user.js",
    )

    val CUI = PresetScript(
        identifier = ScriptIdentifier("github.com/nicko-v/sbg-cui"),
        displayName = "SBG CUI",
        downloadUrl =
            "https://github.com/egorantonov/sbg-enhanced/releases/latest/download/cui.user.js",
        updateUrl =
            "https://github.com/egorantonov/sbg-enhanced/releases/latest/download/cui.user.js",
    )

    val ANMILES = PresetScript(
        identifier = ScriptIdentifier("anmiles.net/sbg-plus"),
        displayName = "SBG plus",
        downloadUrl = "https://anmiles.net/userscripts/sbg.plus.user.js",
        updateUrl = "https://anmiles.net/userscripts/sbg.plus.user.js",
        fallbackDownloadUrl =
            "https://github.com/anmiles/userscripts/raw/refs/heads/main/public/sbg.plus.user.js",
    )

    val ALL: List<PresetScript> = listOf(SVP, EUI, CUI, ANMILES)
}
