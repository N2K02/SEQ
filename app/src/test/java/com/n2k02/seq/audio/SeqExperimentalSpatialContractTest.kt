package com.n2k02.seq.audio

import com.n2k02.seq.model.SpatialMode
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.abs

class SeqExperimentalSpatialContractTest {
  @Test fun hallSilenceStaysSilentAndResetClearsHistory() {
    val dsp = SeqHallPcm(48000)
    val silence = FloatArray(1024)
    dsp.process(silence)
    assertTrue(silence.all { it == 0f })
    val impulse = FloatArray(48000 * 2)
    impulse[0] = 1f
    impulse[1] = 1f
    dsp.process(impulse)
    assertTrue(impulse.all { it.isFinite() && abs(it) <= 1f })
    assertTrue(impulse.drop(2000).any { abs(it) > 0.0001f })
    dsp.reset()
    val after = FloatArray(6000)
    dsp.process(after)
    assertTrue(after.all { it == 0f })
  }

  @Test fun orbitUsesBoundedStereoPcmWithoutCreatingPlayer() {
    val dsp = SeqOrbitPcm(48000)
    dsp.strength = 0f
    val input = FloatArray(2048) { if (it % 2 == 0) .3f else -.3f }
    val copy = input.copyOf()
    dsp.process(input)
    assertArrayEquals(copy, input, 0f)
    dsp.strength = .9f
    dsp.revolutionsPerMinute = 15f
    dsp.reset()
    val varying = FloatArray(10000) { if (it % 2 == 0) .6f else -.2f }
    dsp.process(varying)
    assertTrue(varying.all { it.isFinite() && abs(it) <= 1f })
    assertTrue(varying.indices.step(2).any { abs(varying[it] - .6f) > .001f })
  }

  @Test fun malformedPcmBufferIsRejected() {
    assertThrows(IllegalArgumentException::class.java) { SeqOrbitPcm(1) }
    assertThrows(IllegalArgumentException::class.java) { SeqHallPcm(1) }
    assertThrows(IllegalArgumentException::class.java) { SeqOrbitPcm(48000).process(FloatArray(3), 2, 2) }
    assertThrows(IllegalArgumentException::class.java) { SeqHallPcm(48000).process(FloatArray(3), 2, 2) }
  }

  private fun source(path: String): String =
    generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
      .first { it.resolve(path).isFile }.resolve(path).readText()

  @Test fun noCaptureOrReplayAndUnsupportedModesRemainGuarded() {
    val orbit = source("app/src/main/java/com/n2k02/seq/audio/SeqOrbitPcm.kt")
    val hall = source("app/src/main/java/com/n2k02/seq/audio/SeqHallPcm.kt")
    val adb = source("app/src/main/java/com/n2k02/seq/adb/LocalAdbDiagnostics.kt")
    val global = source("app/src/main/java/com/n2k02/seq/audio/SeqGlobalEffect.kt")
    assertFalse(SpatialMode.HALL.platformSupported)
    assertFalse(SpatialMode.ORBIT_8D.platformSupported)
    for (file in listOf(orbit, hall)) {
      assertFalse(file.contains("AudioTrack("))
      assertFalse(file.contains("AudioRecord("))
      assertFalse(file.contains("MediaProjectionManager"))
    }
    assertFalse(global.contains("SeqOrbitPcm("))
    assertFalse(global.contains("SeqHallPcm("))
    assertTrue(adb.contains("execVerified(AdbAccessPolicy.EFFECTS_PROBE"))
    assertTrue(adb.contains("execVerified(AdbAccessPolicy.POLICY_PROBE"))
    assertTrue(adb.contains("no PCM access or exclusive effect route confirmed"))
  }
}
