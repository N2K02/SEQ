package com.n2k02.seq.audio.dsp

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SeqDspSessionPolicyTest {
  private fun p(sid: Int, uid: Int, usage: String = "USAGE_MEDIA", started: Boolean = true,
    blocked: Boolean = false) = SeqPlayback(sid, uid, usage, started, blocked)

  @Test fun capturableMediaIsMutedAndEverythingElseIsUntouchedOrFallsBack() {
    val players = listOf(p(1, 10100), p(2, 10101, blocked = true), p(3, 10102),
      p(4, 10103, usage = "USAGE_NOTIFICATION"), p(5, 1000), p(6, 10050), p(7, 10104, started = false))
    val d = SeqDspSessionPolicy.decide(players, ownUid = 10050, excludedUids = setOf(10102)) { it != 10104 }
    assertEquals(setOf(1), d.mute)
    assertEquals(setOf(2, 3, 7), d.fallback)
    assertEquals(setOf(10100), d.apps)
    assertTrue(d.playing)
  }

  @Test fun pausedOnlySessionsLetTheEngineIdle() {
    val d = SeqDspSessionPolicy.decide(listOf(p(1, 10100, started = false)), 10050, emptySet()) { true }
    assertEquals(setOf(1), d.mute)
    assertFalse(d.playing)
  }

  @Test fun silentCaptureWatchdogOnlyFiresInItsWindow() {
    assertTrue(SeqDspSessionPolicy.captureSilent(1_000, 5_000, 0, true))
    assertFalse(SeqDspSessionPolicy.captureSilent(1_000, 5_000, 1_500, true)) // signal arrived
    assertFalse(SeqDspSessionPolicy.captureSilent(1_000, 2_000, 0, true)) // too early
    assertFalse(SeqDspSessionPolicy.captureSilent(1_000, 30_000, 0, true)) // too late
    assertFalse(SeqDspSessionPolicy.captureSilent(1_000, 5_000, 0, false)) // paused
  }

  private fun source(path: String): String =
    generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
      .first { it.resolve(path).isFile }.resolve(path).readText()

  @Test fun engineNeverCapturesItselfAndAlwaysRestoresDirectSound() {
    val engine = source("app/src/main/java/com/n2k02/seq/audio/dsp/SeqDspEngine.kt")
    val global = source("app/src/main/java/com/n2k02/seq/audio/SeqGlobalEffect.kt")
    val service = source("app/src/main/java/com/n2k02/seq/audio/SeqEngineService.kt")
    val muter = source("app/src/main/java/com/n2k02/seq/audio/dsp/SeqSessionMuter.kt")
    assertTrue(engine.contains("ALLOW_CAPTURE_BY_NONE"))
    assertTrue(engine.contains("captureExcludes = setOf(ownUid)"))
    assertTrue(engine.contains("THREAD_PRIORITY_URGENT_AUDIO"))
    assertTrue(engine.contains("SPATIALIZATION_BEHAVIOR_NEVER"))
    assertFalse(engine.contains("setAllowedCapturePolicy(uid"))
    // Unmute before tearing down replay, and never leave a race-muted app.
    val stop = engine.substringAfter("fun stop(reason: String)")
    assertTrue(stop.indexOf("muter.releaseAll()") < stop.indexOf("captureThread?.interrupt()"))
    assertTrue(engine.contains("stop() raced with this scan"))
    assertTrue(muter.contains("setInputGainAllChannelsTo(MUTE_DB)"))
    assertTrue(global.contains("if (SeqDspRuntime.active)"))
    assertTrue(service.contains("FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION"))
    assertTrue(service.contains("SeqGlobalEffect.sync(this)"))
  }
}
