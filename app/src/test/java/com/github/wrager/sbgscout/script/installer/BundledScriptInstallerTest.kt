package com.github.wrager.sbgscout.script.installer

import com.github.wrager.sbgscout.script.model.ScriptHeader
import com.github.wrager.sbgscout.script.model.ScriptIdentifier
import com.github.wrager.sbgscout.script.model.UserScript
import com.github.wrager.sbgscout.script.preset.PresetScripts
import com.github.wrager.sbgscout.script.provisioner.DefaultScriptProvisioner
import com.github.wrager.sbgscout.script.storage.ScriptStorage
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.Before
import org.junit.Test

class BundledScriptInstallerTest {

    private lateinit var scriptInstaller: ScriptInstaller
    private lateinit var scriptStorage: ScriptStorage
    private lateinit var scriptProvisioner: DefaultScriptProvisioner
    private val assetContents = mutableMapOf<String, String>()

    @Before
    fun setUp() {
        scriptInstaller = mockk()
        scriptStorage = mockk()
        scriptProvisioner = mockk()
        every { scriptProvisioner.markProvisioned(any()) } just Runs
        every { scriptProvisioner.isProvisioned(any()) } returns false
        every { scriptStorage.setEnabled(any(), any()) } just Runs
    }

    @Test
    fun `installs bundled script when not already installed`() {
        every { scriptStorage.getAll() } returns emptyList()
        val parsedScript = svpScript()
        every { scriptInstaller.parse(SVP_CONTENT) } returns ScriptInstallResult.Parsed(parsedScript)
        every { scriptInstaller.save(any()) } just Runs
        assetContents["scripts/sbg-vanilla-plus.user.js"] = SVP_CONTENT

        createInstaller().installBundled()

        verify {
            scriptInstaller.save(
                match {
                    it.isPreset &&
                        it.sourceUrl == PresetScripts.SVP.downloadUrl &&
                        it.updateUrl == PresetScripts.SVP.updateUrl
                },
            )
        }
    }

    @Test
    fun `enables bundled script when preset has enabledByDefault`() {
        every { scriptStorage.getAll() } returns emptyList()
        val parsedScript = svpScript()
        every { scriptInstaller.parse(SVP_CONTENT) } returns ScriptInstallResult.Parsed(parsedScript)
        every { scriptInstaller.save(any()) } just Runs
        assetContents["scripts/sbg-vanilla-plus.user.js"] = SVP_CONTENT

        createInstaller().installBundled()

        verify { scriptStorage.setEnabled(parsedScript.identifier, true) }
    }

    @Test
    fun `marks preset as provisioned after install`() {
        every { scriptStorage.getAll() } returns emptyList()
        val parsedScript = svpScript()
        every { scriptInstaller.parse(SVP_CONTENT) } returns ScriptInstallResult.Parsed(parsedScript)
        every { scriptInstaller.save(any()) } just Runs
        assetContents["scripts/sbg-vanilla-plus.user.js"] = SVP_CONTENT

        createInstaller().installBundled()

        verify { scriptProvisioner.markProvisioned(PresetScripts.SVP.identifier) }
    }

    @Test
    fun `skips already provisioned preset even if deleted from storage`() {
        every { scriptStorage.getAll() } returns emptyList()
        every { scriptProvisioner.isProvisioned(PresetScripts.SVP.identifier) } returns true
        assetContents["scripts/sbg-vanilla-plus.user.js"] = SVP_CONTENT

        createInstaller().installBundled()

        verify(exactly = 0) { scriptInstaller.parse(any()) }
        verify(exactly = 0) { scriptInstaller.save(any()) }
    }

    @Test
    fun `skips already installed script by sourceUrl`() {
        val existingScript = svpScript().copy(
            sourceUrl = PresetScripts.SVP.downloadUrl,
            isPreset = true,
        )
        every { scriptStorage.getAll() } returns listOf(existingScript)
        assetContents["scripts/sbg-vanilla-plus.user.js"] = SVP_CONTENT

        createInstaller().installBundled()

        verify(exactly = 0) { scriptInstaller.parse(any()) }
        verify(exactly = 0) { scriptInstaller.save(any()) }
    }

    @Test
    fun `continues when asset file is missing`() {
        every { scriptStorage.getAll() } returns emptyList()
        // assetContents не содержит файл — assetReader бросит исключение

        createInstaller().installBundled()

        verify(exactly = 0) { scriptInstaller.save(any()) }
    }

    @Test
    fun `continues when parse returns InvalidHeader`() {
        every { scriptStorage.getAll() } returns emptyList()
        assetContents["scripts/sbg-vanilla-plus.user.js"] = "bad content"
        every { scriptInstaller.parse("bad content") } returns ScriptInstallResult.InvalidHeader

        createInstaller().installBundled()

        verify(exactly = 0) { scriptInstaller.save(any()) }
    }

    @Test
    fun `skips entry whose identifier is not in PresetScripts ALL`() {
        // Покрывает ветку `PresetScripts.ALL.find { ... } ?: continue` — когда
        // кастомный assetMap содержит идентификатор, не зарегистрированный
        // в PresetScripts.ALL.
        every { scriptStorage.getAll() } returns emptyList()

        createInstaller(
            assetMap = mapOf(
                ScriptIdentifier("unknown/preset") to "scripts/unknown.user.js",
            ),
        ).installBundled()

        verify(exactly = 0) { scriptInstaller.parse(any()) }
        verify(exactly = 0) { scriptInstaller.save(any()) }
    }

    @Test
    fun `does not call setEnabled when preset enabledByDefault is false`() {
        // Покрывает ветку `if (preset.enabledByDefault)` = false — EUI в
        // PresetScripts объявлен без enabledByDefault (дефолт = false).
        every { scriptStorage.getAll() } returns emptyList()
        val euiScript = svpScript().copy(
            identifier = PresetScripts.EUI.identifier,
        )
        every { scriptInstaller.parse(EUI_CONTENT) } returns ScriptInstallResult.Parsed(euiScript)
        every { scriptInstaller.save(any()) } just Runs
        assetContents["scripts/eui.user.js"] = EUI_CONTENT

        createInstaller(
            assetMap = mapOf(PresetScripts.EUI.identifier to "scripts/eui.user.js"),
        ).installBundled()

        verify { scriptInstaller.save(any()) }
        verify(exactly = 0) { scriptStorage.setEnabled(any(), any()) }
    }

    private fun createInstaller(
        assetMap: Map<ScriptIdentifier, String> = mapOf(
            PresetScripts.SVP.identifier to "scripts/sbg-vanilla-plus.user.js",
        ),
    ) = BundledScriptInstaller(
        scriptInstaller,
        scriptStorage,
        scriptProvisioner,
        assetReader = { path ->
            assetContents[path] ?: throw java.io.FileNotFoundException("Asset not found: $path")
        },
        assetMap = assetMap,
    )

    private fun svpScript() = UserScript(
        identifier = ScriptIdentifier("github.com/wrager/sbg-vanilla-plus/SBG Vanilla+"),
        header = ScriptHeader(
            name = "SBG Vanilla+",
            version = "6.0.0",
            namespace = "https://github.com/wrager/sbg-vanilla-plus",
        ),
        sourceUrl = null,
        updateUrl = null,
        content = SVP_CONTENT,
    )

    companion object {
        private const val SVP_CONTENT = "// ==UserScript==\n// @name SBG Vanilla+\n// ==/UserScript=="
        private const val EUI_CONTENT = "// ==UserScript==\n// @name SBG Enhanced UI\n// ==/UserScript=="
    }
}
