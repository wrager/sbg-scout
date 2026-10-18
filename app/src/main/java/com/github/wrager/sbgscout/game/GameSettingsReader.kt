package com.github.wrager.sbgscout.game

import org.json.JSONException
import org.json.JSONObject

/**
 * Парсит JSON настроек игры из `localStorage['settings']`.
 *
 * Игра хранит настройки в формате:
 * ```json
 * {"lang": "sys", "theme": "auto", ...}
 * ```
 */
class GameSettingsReader {
    enum class ThemeMode { AUTO, DARK, LIGHT }

    data class GameSettings(
        val theme: ThemeMode,
        val language: String,
    )

    fun parse(json: String?): GameSettings? {
        if (json.isNullOrBlank() || json == "null") return null
        return try {
            val obj = JSONObject(json)
            val theme =
                when (obj.optString("theme", "auto")) {
                    "dark" -> ThemeMode.DARK
                    "light" -> ThemeMode.LIGHT
                    else -> ThemeMode.AUTO
                }
            val language = obj.optString("lang", "sys")
            GameSettings(theme, language)
        } catch (
            @Suppress("SwallowedException") _: JSONException,
        ) {
            null
        }
    }
}
