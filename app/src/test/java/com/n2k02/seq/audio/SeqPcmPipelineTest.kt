package com.n2k02.seq.audio

import com.n2k02.seq.model.SpatialMode
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SeqPcmPipelineTest {
  private val verified = SeqExclusiveRouteEvidence(
    userOptIn = true, captureConsent = true, producerPcmVerified = true,
    exclusiveAudioPolicyRegistered = true, originalPlaybackSuppressed = true,
    outputRouteVerified = true, rollbackRegistered = true
  )

  @Test fun exclusiveRouteNeedsEveryIndependentProof() {
    assertFalse(SeqExclusiveRouteEvidence().mayReplay)
    assertEquals(7, SeqExclusiveRouteEvidence().missing().size)
    assertTrue(verified.mayReplay)
    assertFalse(verified.copy(originalPlaybackSuppressed = false).mayReplay)
    assertFalse(verified.copy(rollbackRegistered = false).mayReplay)
    assertFalse(verified.copy(captureConsent = false).mayReplay)
  }

  @Test fun bufferPreservesStereoOrderAndSilencesUnderflow() {
    val q = SeqPcmRingBuffer(256)
    val source = FloatArray(200) { it.toFloat() / 200f }
    assertEquals(100, q.offer(source))
    val destination = FloatArray(240) { -1f }
    assertEquals(100, q.poll(destination))
    assertArrayEquals(source, destination.copyOfRange(0, 200), 0f)
    assertTrue(destination.drop(200).all { it == 0f })
    assertEquals(0, q.availableFrames())
  }

  @Test fun bufferNeverOverwritesUnreadSamples() {
    val q = SeqPcmRingBuffer(256)
    assertEquals(256, q.offer(FloatArray(600) { .25f }))
    assertEquals(0, q.offer(FloatArray(20)))
    val out = FloatArray(512)
    assertEquals(256, q.poll(out))
    assertTrue(out.all { it == .25f })
    q.stop()
    assertEquals(0, q.offer(FloatArray(8)))
    assertEquals(0, q.poll(out))
    assertTrue(out.all { it == 0f })
  }

  @Test fun pcmProcessorCannotStartWithoutExclusiveEvidence() {
    val processor = SeqExclusivePcmProcessor(48000, SpatialMode.HALL)
    val pcm = FloatArray(256) { if (it == 0 || it == 1) .8f else 0f }
    assertEquals(0, processor.submitOwnedPcm(pcm, SeqExclusiveRouteEvidence()))
    val out = FloatArray(256) { 1f }
    assertEquals(0, processor.processInto(out, SeqExclusiveRouteEvidence()))
    assertTrue(out.all { it == 0f })
    assertEquals(0, processor.submitOwnedPcm(pcm, verified))
  }

  @Test fun verifiedOwnedPcmCanUseHallOrOrbitWithoutPlayingAudio() {
    for (mode in listOf(SpatialMode.HALL, SpatialMode.ORBIT_8D)) {
      val processor = SeqExclusivePcmProcessor(48000, mode)
      val pcm = FloatArray(512) { if (it % 2 == 0) .4f else -.3f }
      assertEquals(256, processor.submitOwnedPcm(pcm, verified))
      val output = FloatArray(512)
      assertEquals(256, processor.processInto(output, verified))
      assertTrue(output.all { it.isFinite() && it in -1f..1f })
      processor.close()
      assertEquals(0, processor.submitOwnedPcm(pcm, verified))
    }
  }

  @Test fun routingAuditRemainsReadOnly() {
    val path = "app/src/main/java/com/n2k02/seq/adb/LocalAdbDiagnostics.kt"
    val root = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
      .first { it.resolve(path).isFile }
    val source = root.resolve(path).readText()
    assertTrue(source.contains("EXCLUSIVE_POLICY_AUDIT"))
    assertTrue(source.contains("execVerified(AdbAccessPolicy.EXCLUSIVE_POLICY_AUDIT"))
    assertFalse(source.contains("registerAudioPolicy("))
    assertFalse(source.contains("setAllowedCapturePolicy("))
  }
}
