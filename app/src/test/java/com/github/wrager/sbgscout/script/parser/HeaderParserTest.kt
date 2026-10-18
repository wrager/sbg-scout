package com.github.wrager.sbgscout.script.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HeaderParserTest {
    @Test
    fun `parses SVP header correctly`() {
        val header = HeaderParser.parse(SVP_HEADER)

        assertNotNull(header)
        assertEquals("SBG Vanilla+", header!!.name)
        assertEquals("0.4.1", header.version)
        assertEquals("wrager", header.author)
        assertEquals("UI/UX enhancements for SBG (SBG v0.6.0)", header.description)
        assertEquals("https://github.com/wrager/sbg-vanilla-plus", header.namespace)
        assertEquals(listOf("https://sbg-game.ru/app/*"), header.match)
        assertEquals(listOf("none"), header.grant)
        assertEquals(
            "https://github.com/wrager/sbg-vanilla-plus/releases/latest/download/sbg-vanilla-plus.user.js",
            header.downloadUrl,
        )
        assertEquals(
            "https://github.com/wrager/sbg-vanilla-plus/releases/latest/download/sbg-vanilla-plus.meta.js",
            header.updateUrl,
        )
        assertEquals("document-idle", header.runAt)
    }

    @Test
    fun `parses EUI header correctly`() {
        val header = HeaderParser.parse(EUI_HEADER)

        assertNotNull(header)
        assertEquals("SBG Enhanced UI", header!!.name)
        assertEquals("8.1.0", header.version)
        assertEquals("https://github.com/egorantonov", header.author)
        assertEquals("Enhanced UI for SBG", header.description)
        assertEquals(listOf("none"), header.grant)
        assertEquals(
            "https://github.com/egorantonov/sbg-enhanced/releases/latest/download/eui.user.js",
            header.downloadUrl,
        )
        assertEquals(
            "https://github.com/egorantonov/sbg-enhanced/releases/latest/download/eui.user.js",
            header.updateUrl,
        )
    }

    @Test
    fun `parses CUI header correctly`() {
        val header = HeaderParser.parse(CUI_HEADER)

        assertNotNull(header)
        assertEquals("SBG CUI fix", header!!.name)
        assertEquals("26.1.7", header.version)
        assertEquals("NV", header.author)
        assertEquals(
            "https://nicko-v.github.io/sbg-cui/assets/img/tm_script_logo.png",
            header.iconUrl,
        )
        assertEquals("document-idle", header.runAt)
    }

    @Test
    fun `parses Anmiles header correctly`() {
        val header = HeaderParser.parse(ANMILES_HEADER)

        assertNotNull(header)
        assertEquals("SBG plus", header!!.name)
        assertEquals("1.0.12", header.version)
        assertEquals("Anatoliy Oblaukhov", header.author)
        assertEquals("sbg", header.namespace)
        assertEquals(listOf("https://sbg-game.ru/*"), header.match)
        assertEquals("document-start", header.runAt)
        assertEquals(
            "https://anmiles.net/userscripts/sbg.plus.user.js",
            header.downloadUrl,
        )
        assertEquals(
            "https://anmiles.net/userscripts/sbg.plus.user.js",
            header.updateUrl,
        )
    }

    @Test
    fun `parses multi-value keys`() {
        val script =
            """
            // ==UserScript==
            // @name Multi Match Script
            // @match https://sbg-game.ru/app/*
            // @match https://sbg-game.ru/login/*
            // @grant GM_getValue
            // @grant GM_setValue
            // ==/UserScript==
            """.trimIndent()

        val header = HeaderParser.parse(script)

        assertNotNull(header)
        assertEquals(
            listOf("https://sbg-game.ru/app/*", "https://sbg-game.ru/login/*"),
            header!!.match,
        )
        assertEquals(listOf("GM_getValue", "GM_setValue"), header.grant)
    }

    @Test
    fun `returns null for script without header`() {
        val script = "console.log('hello');\nvar x = 1;"

        assertNull(HeaderParser.parse(script))
    }

    @Test
    fun `returns null for empty string`() {
        assertNull(HeaderParser.parse(""))
    }

    @Test
    fun `parses minimal header with only name`() {
        val script =
            """
            // ==UserScript==
            // @name Minimal Script
            // ==/UserScript==
            """.trimIndent()

        val header = HeaderParser.parse(script)

        assertNotNull(header)
        assertEquals("Minimal Script", header!!.name)
        assertNull(header.version)
        assertNull(header.description)
        assertEquals(emptyList<String>(), header.match)
    }

    @Test
    fun `returns null when header has no name`() {
        val script =
            """
            // ==UserScript==
            // @version 1.0.0
            // @description No name
            // ==/UserScript==
            """.trimIndent()

        assertNull(HeaderParser.parse(script))
    }

    @Test
    fun `preserves unknown keys in rawEntries`() {
        val script =
            """
            // ==UserScript==
            // @name Test Script
            // @license MIT
            // @homepage https://example.com
            // ==/UserScript==
            """.trimIndent()

        val header = HeaderParser.parse(script)

        assertNotNull(header)
        assertEquals(listOf("MIT"), header!!.rawEntries["license"])
        assertEquals(listOf("https://example.com"), header.rawEntries["homepage"])
    }

    @Test
    fun `trims whitespace from values`() {
        val script =
            """
            // ==UserScript==
            // @name         Padded Name
            // @version      1.0.0
            // ==/UserScript==
            """.trimIndent()

        val header = HeaderParser.parse(script)

        assertNotNull(header)
        assertEquals("Padded Name", header!!.name)
        assertEquals("1.0.0", header.version)
    }

    @Test
    fun `content after end marker is ignored`() {
        val script =
            """
            // ==UserScript==
            // @name Test Script
            // @version 1.0.0
            // ==/UserScript==
            // @version 2.0.0
            console.log('code');
            """.trimIndent()

        val header = HeaderParser.parse(script)

        assertNotNull(header)
        assertEquals("1.0.0", header!!.version)
    }

    @Test
    fun `lines without at sign inside header are skipped`() {
        val script =
            """
            // ==UserScript==
            // @name Test Script
            // This is a comment line
            // @version 1.0.0
            // ==/UserScript==
            """.trimIndent()

        val header = HeaderParser.parse(script)

        assertNotNull(header)
        assertEquals("Test Script", header!!.name)
        assertEquals("1.0.0", header.version)
    }

    @Test
    fun `icon64URL maps to iconUrl`() {
        val script =
            """
            // ==UserScript==
            // @name Test Script
            // @icon64URL https://example.com/icon64.png
            // ==/UserScript==
            """.trimIndent()

        val header = HeaderParser.parse(script)

        assertNotNull(header)
        assertEquals("https://example.com/icon64.png", header!!.iconUrl)
    }

    @Test
    fun `iconURL takes priority over icon64URL`() {
        val script =
            """
            // ==UserScript==
            // @name Test Script
            // @iconURL https://example.com/icon.png
            // @icon64URL https://example.com/icon64.png
            // ==/UserScript==
            """.trimIndent()

        val header = HeaderParser.parse(script)

        assertNotNull(header)
        assertEquals("https://example.com/icon.png", header!!.iconUrl)
    }

    @Test
    fun `handles content before header block`() {
        val script =
            """
            /* eslint-disable no-console */
            // ==UserScript==
            // @name SBG plus
            // @version 1.0.12
            // ==/UserScript==
            window.__sbg_plus_version = '1.0.12';
            """.trimIndent()

        val header = HeaderParser.parse(script)

        assertNotNull(header)
        assertEquals("SBG plus", header!!.name)
        assertEquals("1.0.12", header.version)
    }

    companion object {
        private val SVP_HEADER =
            """
            // ==UserScript==
            // @name         SBG Vanilla+
            // @namespace    https://github.com/wrager/sbg-vanilla-plus
            // @version      0.4.1
            // @author       wrager
            // @description  UI/UX enhancements for SBG (SBG v0.6.0)
            // @license      MIT
            // @homepage     https://github.com/wrager/sbg-vanilla-plus
            // @homepageURL  https://github.com/wrager/sbg-vanilla-plus
            // @source       https://github.com/wrager/sbg-vanilla-plus.git
            // @downloadURL  https://github.com/wrager/sbg-vanilla-plus/releases/latest/download/sbg-vanilla-plus.user.js
            // @updateURL    https://github.com/wrager/sbg-vanilla-plus/releases/latest/download/sbg-vanilla-plus.meta.js
            // @match        https://sbg-game.ru/app/*
            // @grant        none
            // @run-at       document-idle
            // ==/UserScript==
            """.trimIndent()

        private val EUI_HEADER =
            """
            // ==UserScript==
            // @name SBG Enhanced UI
            // @description Enhanced UI for SBG
            // @version 8.1.0
            // @author https://github.com/egorantonov
            // @homepage https://github.com/egorantonov/sbg-enhanced#readme
            // @supportURL https://github.com/egorantonov/sbg-enhanced/issues
            // @match https://sbg-game.ru/app/*
            // @downloadURL https://github.com/egorantonov/sbg-enhanced/releases/latest/download/eui.user.js
            // @grant none
            // @license MIT
            // @namespace https://github.com/egorantonov/sbg-enhanced
            // @updateURL https://github.com/egorantonov/sbg-enhanced/releases/latest/download/eui.user.js
            // ==/UserScript==
            """.trimIndent()

        private val CUI_HEADER =
            """
            // ==UserScript==
            // @name         SBG CUI fix
            // @namespace    https://sbg-game.ru/app/
            // @version      26.1.7
            // @downloadURL  https://github.com/egorantonov/sbg-enhanced/releases/latest/download/cui.user.js
            // @updateURL    https://github.com/egorantonov/sbg-enhanced/releases/latest/download/cui.user.js
            // @description  SBG Custom UI
            // @author       NV
            // @match        https://sbg-game.ru/app/*
            // @run-at       document-idle
            // @grant        none
            // @iconURL      https://nicko-v.github.io/sbg-cui/assets/img/tm_script_logo.png
            // ==/UserScript==
            """.trimIndent()

        private val ANMILES_HEADER =
            """
            /* eslint-disable no-console */
            // ==UserScript==
            // @name           SBG plus
            // @namespace      sbg
            // @version        1.0.12
            // @updateURL      https://anmiles.net/userscripts/sbg.plus.user.js
            // @downloadURL    https://anmiles.net/userscripts/sbg.plus.user.js
            // @description    Extended functionality for SBG
            // @description:ru Расширенная функциональность для SBG
            // @author         Anatoliy Oblaukhov
            // @match          https://sbg-game.ru/*
            // @run-at         document-start
            // @grant          none
            // ==/UserScript==
            """.trimIndent()
    }
}
