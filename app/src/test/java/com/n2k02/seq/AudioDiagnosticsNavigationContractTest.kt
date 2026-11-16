package com.n2k02.seq

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Guards against reintroducing audio probes into the ADB connection page. */
class AudioDiagnosticsNavigationContractTest {
    private val root = File("src/main/java/com/n2k02/seq")

    @Test fun audioDiagnosticsIsSeparateSettingsDestination() {
        val source = File(root, "MainActivity.kt").readText()
        assertTrue(source.contains("AUDIO_DIAGNOSTICS(\"Audio diagnostics\")"))
        assertTrue(source.contains("page = Page.AUDIO_DIAGNOSTICS"))
        assertTrue(source.contains("Page.AUDIO_DIAGNOSTICS -> SeqAudioDiagnosticsScreen("))
        assertTrue(source.contains("Page.SHIZUKU -> ShizukuSettingsPage()"))
    }

    @Test fun adbConnectionContainsOnlySharedAdbConnectionSettings() {
        val connection = File(root, "ShizukuSettingsPage.kt").readText()
        assertTrue(connection.contains("Shizuku.checkSelfPermission()"))
        assertFalse(connection.contains("AdbConnectionSettingsPage("))
        assertFalse(connection.contains("Test third-party PCM capture"))
    }

    @Test fun audioDiagnosticsKeepsAllActions() {
        val source = File(root, "SeqAdbDiagnosticsScreen.kt").readText()
        val diagnostics = source.substringAfter("internal fun SeqAudioDiagnosticsScreen(")
        listOf("adb::inspectPlayers", "adb::grantDumpPermission",
            "adb::inspectAudioEffects", "adb::auditExclusivePcm",
            "Test third-party PCM capture", "Stop PCM test",
            "SeqEffectCapabilities.inspect()", "SeqEffectCapabilities.probeAuxReverb()"
        ).forEach { assertTrue("Missing action: $it", diagnostics.contains(it)) }
    }
}
