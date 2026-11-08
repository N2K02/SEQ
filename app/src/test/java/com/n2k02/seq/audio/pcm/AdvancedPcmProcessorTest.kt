package com.n2k02.seq.audio.pcm

import org.junit.Assert.*
import org.junit.Test

class AdvancedPcmProcessorTest {
  @Test fun identityImpulseKeepsStereoIndependent() {
    val processor = AdvancedPcmProcessor(ceiling = 0.9f)
    val data = floatArrayOf(0.2f, -0.3f, 0.4f, -0.1f)
    processor.process(data)
    assertEquals(0.2f, data[0], 0.0001f)
    assertEquals(-0.3f, data[1], 0.0001f)
    assertEquals(0.4f, data[2], 0.0001f)
    assertEquals(-0.1f, data[3], 0.0001f)
  }

  @Test fun shortConvolutionPreservesHistoryAcrossBlocks() {
    val engine = AdvancedPcmProcessor(floatArrayOf(0.5f, 0.5f), floatArrayOf(1f))
    val first = floatArrayOf(0.4f, 0f, 0f, 0f)
    val second = floatArrayOf(0f, 0f)
    engine.process(first)
    engine.process(second)
    assertEquals(0.2f, first[0], 0.001f)
    assertEquals(0.2f, first[2], 0.001f)
    assertEquals(0f, second[0], 0.001f)
  }

  @Test fun peakGuardNeverOutputsSamplesAboveCeiling() {
    val engine = AdvancedPcmProcessor(floatArrayOf(1.5f), ceiling = 0.7f)
    val input = FloatArray(512) { if (it % 2 == 0) 1f else -1f }
    engine.process(input)
    assertTrue(input.all { it in -0.70001f..0.70001f })
    engine.reset()
    val quiet = floatArrayOf(0.2f, -0.2f)
    engine.process(quiet)
    assertEquals(0.3f, quiet[0], 0.001f)
  }

  @Test fun invalidConvolutionIsRejected() {
    assertThrows(IllegalArgumentException::class.java) {
      AdvancedPcmProcessor(FloatArray(0))
    }
    assertThrows(IllegalArgumentException::class.java) {
      AdvancedPcmProcessor(FloatArray(257))
    }
    assertThrows(IllegalArgumentException::class.java) {
      AdvancedPcmProcessor(floatArrayOf(Float.NaN))
    }
  }

  @Test fun captureCopyNeverPermitsReplayingOverOriginal() {
    assertFalse(PcmRoutingPolicy.canReplaceOriginal(
      PcmRoutingPolicy.Source.PLAYBACK_CAPTURE_COPY, true))
    assertFalse(PcmRoutingPolicy.canReplaceOriginal(
      PcmRoutingPolicy.Source.PROVEN_EXCLUSIVE_AUDIO_POLICY, false))
    assertTrue(PcmRoutingPolicy.canReplaceOriginal(
      PcmRoutingPolicy.Source.PROVEN_EXCLUSIVE_AUDIO_POLICY, true))
    assertTrue(PcmRoutingPolicy.canReplaceOriginal(
      PcmRoutingPolicy.Source.OWN_PLAYER, false))
  }
}
