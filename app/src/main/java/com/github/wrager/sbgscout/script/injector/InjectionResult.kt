package com.github.wrager.sbgscout.script.injector

import com.github.wrager.sbgscout.script.model.ScriptIdentifier

sealed class InjectionResult {
    data class Success(val identifier: ScriptIdentifier) : InjectionResult()
    data class ScriptError(
        val identifier: ScriptIdentifier,
        val scriptName: String,
        val errorMessage: String,
    ) : InjectionResult()
}
