package com.n2k02.seq.audio

import com.n2k02.seq.model.SpatialMode
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.abs

class SeqExclusivePcmTest {
  private fun complete() = SeqExclusiveRouteEvidence(
    userOptIn = true,
    captureConsent = true,
    producerPcmVerified = true,
    exclusiveAudioPolicyRegistered = true,
    originalPlaybackSuppressed = true,
    outputRouteVerified = true,
    rollbackRegistered = true
  )

  @Test fun defaultGateBlocksEveryUnsafePlaybackPath() {
    val none = SeqExclusiveRouteEvidence()
    assertFalse(none.mayReplay)
    assertEquals(7, none.missing().size)
    val complete = complete()
    assertTrue(complete.mayReplay)
    assertTrue(complete.missing().isEmpty())
    assertFalse(complete.copy(originalPlaybackSuppressed = false).mayReplay)
    assertFalse(complete.copy(rollbackRegistered = false).mayReplay)
    assertFalse(complete.copy(captureConsent = false).mayReplay)
  }

  @Test fun ringBufferIsBoundedAndClearsOnUnderflow() {
    val buffer = SeqPcmRingBuffer(256)
    val source = FloatArray(600) { if (it % 2 == 0) .4f else -.4f }
    assertEquals(256, buffer.offer(source))
    assertEquals(256, buffer.availableFrames())
    assertEquals(0, buffer.offer(source))
    val output = FloatArray(600) { 1f }
    assertEquals(256, buffer.poll(output))
    assertEquals(0, buffer.availableFrames())
    assertTrue(output.take(512).all { abs(it) == .4f })
    assertTrue(output.drop(512).all { it == 0f })
    buffer.stop()
    assertEquals(0, buffer.offer(source))
    assertEquals(0, buffer.poll(output))
    assertTrue(output.all { it == 0f })
  }

  @Test fun defaultPipelineNeverEmitsFramesWithoutExclusiveEvidence() {
    val pipeline = SeqExclusivePcmProcessor(48000, SpatialMode.ORBIT_8D)
    val samples = FloatArray(1024) { .2f }
    assertEquals(0, pipeline.submitOwnedPcm(samples, SeqExclusiveRouteEvidence()))
    val output = FloatArray(1024) { 1f }
    assertEquals(0, pipeline.processInto(output, SeqExclusiveRouteEvidence()))
    assertTrue(output.all { it == 0f })
    assertEquals(0, pipeline.submitOwnedPcm(samples, complete()))
  }

  @Test fun ownedPcmSupportsHallOrbitAndRevocation() {
    for (mode in listOf(SpatialMode.HALL, SpatialMode.ORBIT_8D)) {
      val pipeline = SeqExclusivePcmProcessor(48000, mode, capacityFrames = 1024)
      val input = FloatArray(1024) { if (it % 2 == 0) .4f else -.2f }
      val output = FloatArray(1024) { 0f }
      assertEquals(512, pipeline.submitOwnedPcm(input, complete()))
      assertEquals(512, pipeline.processInto(output, complete()))
      assertTrue(output.all { it.isFinite() && abs(it) <= 1f })
      output.fill(1f)
      assertEquals(0, pipeline.processInto(output, complete().copy(outputRouteVerified = false)))
      assertTrue(output.all { it == 0f })
      assertEquals(0, pipeline.submitOwnedPcm(input, complete()))
    }
  }

  private fun source(path: String): String = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
    .first { it.resolve(path).isFile }.resolve(path).readText()

  @Test fun policyAuditIsReadOnlyAndReplayIsNeverEnabledByAdbAlone() {
    val adb = source("app/src/main/java/com/n2k02/seq/adb/LocalAdbDiagnostics.kt")
    val gate = source("app/src/main/java/com/n2k02/seq/audio/SeqPcmPipeline.kt")
    val exclusive = source("app/src/main/java/com/n2k02/seq/audio/SeqExclusivePcmProcessor.kt")
    assertTrue(adb.contains("execVerified(AdbAccessPolicy.EXCLUSIVE_POLICY_AUDIT, 8_000)"))
    assertFalse(adb.contains("registerAudioPolicy("))
    assertFalse(adb.contains("setAllowedCapturePolicy("))
    assertTrue(gate.contains("originalPlaybackSuppressed &&"))
    assertTrue(exclusive.contains("!evidence.mayReplay"))
    assertFalse(exclusive.contains("AudioRecord.Builder"))
    assertFalse(exclusive.contains("AudioTrack.Builder"))
  }
}
