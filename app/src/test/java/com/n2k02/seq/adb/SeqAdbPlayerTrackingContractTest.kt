package com.n2k02.seq.adb

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** ADB enhances diagnostics; it is never proof of access to another app's PCM. */
class SeqAdbPlayerTrackingContractTest {
  private fun source(path: String): String = listOf(
    File("src/main/$path"), File("app/src/main/$path")
  ).first { it.isFile }.readText()

  @Test fun onlyVerifiedShellRunsBoundedReadOnlyPlayerProbe() {
    val adb = source("java/com/n2k02/seq/adb/LocalAdbDiagnostics.kt")
    val command = adb.substringAfter("const val PLAYER_PROBE =").trimStart()
      .substringBefore("\n").trim()
    assertTrue(command.contains("dumpsys media.audio_flinger"))
    assertTrue(command.contains("head -n 35"))
    assertFalse(command.contains("pm grant"))
    assertFalse(command.contains("appops set"))
    assertTrue(adb.contains("sharedCore.execVerified(AdbAccessPolicy.PLAYER_PROBE, 8_000)"))
    assertTrue(adb.contains("effect path unverified"))
  }

  @Test fun dumpIsExplicitlyUserInitiatedAndNeverRequiredForPlayback() {
    val manifest = source("AndroidManifest.xml")
    val ui = source("java/com/n2k02/seq/SeqAdbDiagnosticsScreen.kt")
    val adb = source("java/com/n2k02/seq/adb/LocalAdbDiagnostics.kt")
    val monitor = source("java/com/n2k02/seq/audio/SeqPlaybackMonitor.kt")
    assertTrue(manifest.contains("android.permission.DUMP"))
    assertTrue(ui.contains("onClick = adb::grantDumpPermission"))
    assertTrue(ui.contains("onClick = adb::inspectPlayers"))
    assertTrue(adb.contains("pm grant com.n2k02.seq android.permission.DUMP"))
    assertFalse(monitor.contains("grantDumpPermission"))
    assertFalse(monitor.contains("PLAYER_PROBE"))
  }
}
