package com.n2k02.seq.adb

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Safe shell probing must never turn into surprise audio capture or route changes. */
class SeqRoutingLabContractTest {
  private fun source(path: String): String = listOf(
    File("src/main/java/com/n2k02/seq/$path"),
    File("app/src/main/java/com/n2k02/seq/$path")
  ).first { it.isFile }.readText()

  @Test fun onlyReadOnlyRoutingCommandIsExposed() {
    val adb = source("adb/LocalAdbDiagnostics.kt")
    val line = adb.substringAfter("const val ROUTING_PROBE =")
      .substringBefore("\n").trim()
    assertTrue(line.contains("id -u"))
    assertTrue(line.contains("dumpsys media.audio_policy"))
    listOf("setprop", "settings put", "appops set", "pm grant", "cmd audio set",
      "su ", "am start").forEach { assertFalse(line.contains(it)) }
    assertTrue(adb.contains("fun inspectRouting()"))
    assertTrue(adb.contains("sharedCore.execVerified(AdbAccessPolicy.ROUTING_PROBE"))
    assertTrue(adb.contains("exclusive route NOT verified"))
  }

  @Test fun onlyExplicitButtonCanRequestRoutingProbe() {
    val ui = source("SeqAdvancedAudioScreen.kt")
    assertTrue(ui.contains("adb.inspectRouting()"))
    assertTrue(ui.contains("Probe audio routing (ADB)"))
    assertFalse(ui.contains("LaunchedEffect"))
    assertTrue(ui.contains("PCM replay remains disabled for other apps"))
  }

  @Test fun pcmRoutingNeverReplaysCaptureCopies() {
    val pcm = source("audio/pcm/AdvancedPcmProcessor.kt")
    assertTrue(pcm.contains("source == Source.OWN_PLAYER"))
    assertTrue(pcm.contains("exclusiveReroutingConfirmed"))
    assertFalse(pcm.contains("MediaProjectionManager"))
    assertFalse(pcm.contains("AudioRecord.Builder"))
    assertFalse(pcm.contains("AudioTrack.Builder"))
  }
}
