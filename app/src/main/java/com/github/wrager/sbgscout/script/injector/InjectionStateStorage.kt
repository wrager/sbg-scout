package com.github.wrager.sbgscout.script.injector

import android.content.SharedPreferences
import com.github.wrager.sbgscout.script.model.UserScript

class InjectionStateStorage(private val preferences: SharedPreferences) {

    fun saveSnapshot(scripts: List<UserScript>) {
        val entries = scripts.map { buildSnapshotEntry(it) }.toSet()
        preferences.edit().putStringSet(KEY_LAST_INJECTED, entries).apply()
    }

    // null — игра ни разу не загружалась (ключ отсутствует)
    // emptySet() — игра загружалась, но скрипты не были включены
    fun getSnapshot(): Set<String>? {
        return preferences.getStringSet(KEY_LAST_INJECTED, null)
    }

    companion object {
        private const val KEY_LAST_INJECTED = "last_injected_state"

        /**
         * Формирует ключ снапшота для скрипта: идентификатор, версия и хеш контента.
         *
         * Хеш контента позволяет обнаружить переустановку скрипта из файла
         * с той же версией, но другим содержимым.
         */
        fun buildSnapshotEntry(script: UserScript): String =
            "${script.identifier.value}::${script.header.version ?: ""}::${script.content.hashCode()}"
    }
}
