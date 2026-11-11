package com.n2k02.seq.audio.dsp

import com.n2k02.seq.model.EqFilter
import com.n2k02.seq.model.FilterKind
import com.n2k02.seq.model.SpatialMode
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class SeqDspChainTest {
  private val rate = 48000
  private val block = 240

  private fun plan(filters: List<EqFilter> = emptyList(), preGain: Float = 1f,
    spatial: SpatialMode = SpatialMode.OFF, strength: Float = 0.6f,
    limiter: Boolean = false, ceiling: Float = 1f): SeqDspPlan {
    val c = DoubleArray(filters.size * 5)
    filters.forEachIndexed { i, f -> SeqBiquad.coefficients(f, rate).copyInto(c, i * 5) }
    return SeqDspPlan(rate, c, preGain, spatial, strength, false, 1f, -18f, limiter, ceiling)
  }

  /** Runs a stereo sine through the chain in real-time sized blocks; returns the output. */
  private fun run(chain: SeqDspChain, hz: Double, amplitude: Float, seconds: Double,
    right: Float = amplitude): FloatArray {
    val frames = (rate * seconds).toInt() / block * block
    val out = FloatArray(frames * 2)
    val buffer = FloatArray(block * 2)
    var n = 0
    while (n < frames) {
      for (i in 0 until block) {
        val s = sin(2 * PI * hz * (n + i) / rate).toFloat()
        buffer[i * 2] = s * amplitude
        buffer[i * 2 + 1] = s * right
      }
      chain.process(buffer, 0, block)
      buffer.copyInto(out, n * 2)
      n += block
    }
    return out
  }

  private fun rms(x: FloatArray, from: Int, channel: Int = 0): Double {
    var sum = 0.0; var count = 0
    var i = from - from % 2 + channel
    while (i < x.size) { sum += x[i].toDouble() * x[i]; count++; i += 2 }
    return sqrt(sum / count)
  }

  @Test fun flatPlanIsTransparentAfterTheFadeIn() {
    val chain = SeqDspChain(rate)
    chain.update(plan())
    chain.reset()
    val out = run(chain, 440.0, 0.5f, 0.5)
    assertTrue("fade starts silent", abs(out[0]) < 1e-3f)
    val expected = 0.5 / sqrt(2.0)
    assertEquals(expected, rms(out, out.size / 2), expected * 0.01)
  }

  @Test fun peakingFilterAddsItsGainAtTheCentre() {
    val chain = SeqDspChain(rate)
    chain.update(plan(listOf(EqFilter(FilterKind.PEAK, 1000.0, 6.0, 1.41))))
    chain.reset()
    val out = run(chain, 1000.0, 0.1f, 1.0)
    val gain = rms(out, out.size / 2) / (0.1 / sqrt(2.0))
    assertEquals(1.995, gain, 0.06) // +6 dB
  }

  @Test fun lookaheadLimiterHoldsTheCeiling() {
    val chain = SeqDspChain(rate)
    chain.update(plan(limiter = true, ceiling = 0.5f))
    chain.reset()
    val out = run(chain, 1000.0, 0.9f, 0.6)
    val peak = (out.size / 2 until out.size).maxOf { abs(out[it]) }
    assertTrue("peak=$peak", peak <= 0.51f)
    assertTrue("limiter must not mute", peak > 0.4f)
  }

  @Test fun antiClippingProtectionNeverExceedsFullScaleOrEmitsNaN() {
    val chain = SeqDspChain(rate)
    chain.update(plan(preGain = 4f))
    chain.reset()
    val out = run(chain, 200.0, 0.9f, 0.3)
    assertTrue(out.all { it.isFinite() && abs(it) <= 1f })
    assertTrue(chain.clampedSamples > 0)
    val nan = FloatArray(block * 2) { Float.NaN }
    chain.process(nan)
    assertTrue(nan.all { it == 0f })
  }

  @Test fun headphoneCrossfeedKeepsMonoCentredAndBassLevel() {
    val chain = SeqDspChain(rate)
    chain.update(plan(spatial = SpatialMode.HEADPHONE_3D, strength = 1f))
    chain.reset()
    val out = run(chain, 60.0, 0.3f, 0.6)
    for (i in out.size / 2 until out.size step 2) assertEquals(out[i], out[i + 1], 1e-6f)
    assertEquals(0.3 / sqrt(2.0), rms(out, out.size / 2), 0.3 / sqrt(2.0) * 0.05)
  }

  @Test fun everySpatialModeStaysBoundedAndFinite() {
    for (mode in SpatialMode.entries) {
      val chain = SeqDspChain(rate)
      chain.update(plan(spatial = mode, strength = 1f, limiter = true, ceiling = 0.95f))
      chain.reset()
      val out = run(chain, 330.0, 0.8f, 0.4, right = -0.2f)
      assertTrue(mode.name, out.all { it.isFinite() && abs(it) <= 1f })
      assertTrue(mode.name, rms(out, out.size / 2) > 0.01)
    }
  }

  @Test fun reEnablingTheLimiterNeverReplaysStaleLookaheadAudio() {
    val chain = SeqDspChain(rate)
    chain.update(plan(limiter = true, ceiling = 0.9f))
    chain.reset()
    run(chain, 500.0, 0.8f, 0.2)
    chain.update(plan(limiter = false))
    run(chain, 500.0, 0.0f, 0.05)
    chain.update(plan(limiter = true, ceiling = 0.9f))
    val silence = FloatArray(block * 2)
    chain.process(silence)
    assertTrue(silence.all { it == 0f })
  }

  @Test fun evenVolumeLevellerIsBoundedToSixDecibels() {
    val chain = SeqDspChain(rate)
    chain.update(SeqDspPlan(rate, DoubleArray(0), 1f, SpatialMode.OFF, 0f, false, 1f, -18f,
      limiter = false, ceiling = 1f, leveler = true))
    chain.reset()
    val quiet = run(chain, 440.0, 0.01f, 8.0)
    val gain = rms(quiet, quiet.size * 3 / 4) / (0.01 / sqrt(2.0))
    assertTrue("gain=$gain", gain in 1.5..2.05)
  }

  @Test fun planSwapIsAllocationSafeAndValidated() {
    val chain = SeqDspChain(rate)
    assertThrows(IllegalArgumentException::class.java) { chain.update(SeqDspPlan.passthrough(44100)) }
    assertThrows(IllegalArgumentException::class.java) {
      SeqDspPlan(rate, DoubleArray(4), 1f, SpatialMode.OFF, 0f, false, 1f, -18f, true, 1f)
    }
    assertThrows(IllegalArgumentException::class.java) { chain.process(FloatArray(3), 2, 2) }
    chain.update(plan(listOf(EqFilter(FilterKind.LOW_SHELF, 110.0, 4.0, 0.7071))))
    run(chain, 100.0, 0.2f, 0.1)
    chain.update(plan())
    val out = run(chain, 100.0, 0.2f, 0.2)
    assertTrue(out.all { it.isFinite() })
  }
}
