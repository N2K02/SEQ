package com.n2k02.seq.model

import kotlin.math.pow
import kotlin.math.sqrt

data class SystemEqBand(val cutoffHz: Float, val gainDb: Float)

/** Platform-effect version of SEQ's exact biquad response: input gain + graphic bands. */
data class SystemEqCurve(val inputGainDb: Float, val bands: List<SystemEqBand>) {
  /** Absolute level (band gain + input gain) applied at [hz]; used by fixed-band fallbacks. */
  fun levelAt(hz: Float): Float =
    (bands.firstOrNull { hz <= it.cutoffHz } ?: bands.last()).gainDb + inputGainDb
  val isFlat: Boolean get() = inputGainDb == 0f && bands.all { it.gainDb == 0f }
}

object SystemEqCurveBuilder {
  /** 1/3-octave resolution across 20 Hz – 20 kHz. */
  const val BAND_COUNT = 31
  private const val LOW_HZ = 20.0
  private const val SPAN = 1000.0 // 20 Hz × 1000 = 20 kHz

  fun cutoffHz(index: Int, count: Int = BAND_COUNT): Double = LOW_HZ * SPAN.pow((index + 1) / count.toDouble())

  /**
   * Each band covers (previous cutoff, cutoff]; its gain is the exact filter response at the
   * band's geometric centre. Input gain removes the largest boost so the effect cannot clip.
   */
  fun build(tone: Tone, preset: HeadphoneProfile?, count: Int = BAND_COUNT): SystemEqCurve {
    require(count in 2..64) { "Unsupported band count" }
    val bands = (0 until count).map { i ->
      val lower = LOW_HZ * SPAN.pow(i / count.toDouble())
      val upper = cutoffHz(i, count)
      val gain = EqResponse.totalDb(sqrt(lower * upper), tone, preset).coerceIn(-24.0, 24.0)
      SystemEqBand(upper.toFloat(), round2(gain))
    }
    val headroom = EqResponse.headroomDb(tone, preset).coerceIn(0.0, 24.0)
    return SystemEqCurve(round2(-headroom) + 0f, bands)
  }
  private fun round2(v: Double): Float = (Math.round(v * 100.0) / 100.0).toFloat()
}
