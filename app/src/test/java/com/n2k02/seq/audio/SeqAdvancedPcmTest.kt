package com.n2k02.seq.audio

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class SeqAdvancedPcmTest {
  @Test fun disabledProcessingIsTransparent() {
    val processor = SeqAdvancedPcm(48000)
    val samples = FloatArray(4096) { if (it % 2 == 0) .3f else -.2f }
    val original = samples.clone()
    processor.process(samples)
    assertArrayEquals(original, samples, .00001f)
  }

  @Test fun advancedProcessingIsBoundedAndFiniteAtFullScale() {
    val processor = SeqAdvancedPcm(48000, SeqAdvancedPcmConfig(
      stereoWidth = 1.8f, tubeDrive = 1f, bassGainDb = 6f,
      compressorRatio = 4f, convolutionWet = .5f
    ), floatArrayOf(.7f, .3f))
    val samples = FloatArray(48000 * 2) { if (it % 2 == 0) 1f else -1f }
    processor.process(samples)
    assertTrue(samples.all { it.isFinite() && abs(it) <= 1f })
    processor.reset()
    val silence = FloatArray(1024)
    processor.process(silence)
    assertTrue(silence.all { it == 0f })
  }

  @Test fun firImpulseIsBoundedAndResetClearsHistory() {
    val fir = SeqStereoConvolver(floatArrayOf(.75f, .25f))
    fir.process(1f, .5f)
    assertEquals(.75f, fir.outLeft, .0001f)
    assertEquals(.375f, fir.outRight, .0001f)
    fir.process(0f, 0f)
    assertEquals(.25f, fir.outLeft, .0001f)
    fir.reset()
    fir.process(0f, 0f)
    assertEquals(0f, fir.outLeft, 0f)
    assertThrows(IllegalArgumentException::class.java) { SeqStereoConvolver(FloatArray(0)) }
    assertThrows(IllegalArgumentException::class.java) { SeqStereoConvolver(floatArrayOf(Float.NaN)) }
    assertThrows(IllegalArgumentException::class.java) { SeqStereoConvolver(FloatArray(4097)) }
  }

  @Test fun invalidAndExtremeConfigIsClamped() {
    val options = SeqAdvancedPcmConfig(
      stereoWidth = 99f, tubeDrive = -1f, compressorRatio = 99f,
      convolutionWet = 4f, bassGainDb = 50f
    ).safe()
    assertEquals(1.8f, options.stereoWidth, 0f)
    assertEquals(0f, options.tubeDrive, 0f)
    assertEquals(8f, options.compressorRatio, 0f)
    assertEquals(.7f, options.convolutionWet, 0f)
    assertEquals(6f, options.bassGainDb, 0f)
  }
}
