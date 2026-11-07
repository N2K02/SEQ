package com.n2k02.seq.model

import kotlin.math.roundToInt
import kotlin.math.ln
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
   * band's geometric centre. Input gain reserves headroom for the largest modelled boost.
   */
  fun build(tone: Tone, preset: HeadphoneProfile?, count: Int = BAND_COUNT): SystemEqCurve {
    require(count in 2..64) { "Unsupported band count" }
    val bands = (0 until count).map { i ->
      val lower = LOW_HZ * SPAN.pow(i / count.toDouble())
      val upper = cutoffHz(i, count)
      val gain = EqResponse.totalDb(sqrt(lower * upper), tone, preset).coerceIn(-24.0, 24.0)
      SystemEqBand(upper.toFloat(), round2(gain))
    }
    val boost = (0..400).maxOf { i ->
      EqResponse.totalDb(LOW_HZ * SPAN.pow(i / 400.0), tone, preset) }.coerceAtLeast(0.0)
    val preamp = -(preset?.preampDb ?: 0.0)
    val headroom = maxOf(boost, preamp).coerceIn(0.0, 24.0)
    return SystemEqCurve(round2(-headroom) + 0f, bands)
  }
  const val MANUAL_LIMIT_DB = 6f

  const val COMPACT_BAND_COUNT = 10
  private val compactCentres = listOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
    .map(Int::toDouble)
  private val precisionCentres = List(BAND_COUNT) { i ->
    val lower = if (i == 0) LOW_HZ else cutoffHz(i - 1)
    sqrt(lower * cutoffHz(i))
  }

  fun centres(count: Int): List<Double> = when (count) {
    COMPACT_BAND_COUNT -> compactCentres
    BAND_COUNT -> precisionCentres
    else -> error("Unsupported manual EQ band count")
  }

  /** Interpolate dB gain on a logarithmic frequency axis, holding the end bands flat. */
  fun resample(gains: List<Float>, count: Int): List<Float> {
    val source = centres(gains.size)
    if (gains.size == count) return gains
    return centres(count).map { hz ->
      val upper = source.indexOfFirst { it >= hz }
      when (upper) {
        -1 -> gains.last()
        0 -> gains.first()
        else -> {
          val lower = source[upper - 1]
          val fraction = ln(hz / lower) / ln(source[upper] / lower)
          (gains[upper - 1] + (gains[upper] - gains[upper - 1]) * fraction).toFloat()
        }
      }
    }
  }

  fun parseManual(text: String?): List<Float>? {
    if (text == null) return null
    val bands = text.split(',')
    if (bands.size != BAND_COUNT && bands.size != COMPACT_BAND_COUNT) return null
    val gains = bands.map { it.toFloatOrNull() ?: return null }
    return gains.takeIf { it.all { gain -> gain in -MANUAL_LIMIT_DB..MANUAL_LIMIT_DB } }
  }

  fun manual(gains: List<Float>): SystemEqCurve {
    require(gains.all { it in -MANUAL_LIMIT_DB..MANUAL_LIMIT_DB })
    val bands = resample(gains, BAND_COUNT).mapIndexed { i, gain -> SystemEqBand(cutoffHz(i).toFloat(), gain) }
    return SystemEqCurve(-gains.max().coerceAtLeast(0f) + 0f, bands)
  }

  fun withOutputControls(curve: SystemEqCurve, bassDb: Float, preampDb: Float,
    allowBoost: Boolean): SystemEqCurve {
    if (bassDb == 0f && preampDb == 0f) return curve
    val shelf = EqFilter(FilterKind.LOW_SHELF, 110.0, bassDb.toDouble(), 0.7071)
    val bands = curve.bands.mapIndexed { i, band ->
      band.copy(gainDb = band.gainDb + EqResponse.db(precisionCentres[i], shelf).toFloat())
    }
    val boostBefore = curve.bands.maxOf { it.gainDb }.coerceAtLeast(0f)
    val boostAfter = bands.maxOf { it.gainDb }.coerceAtLeast(0f)
    val headroom = curve.inputGainDb - (boostAfter - boostBefore).coerceAtLeast(0f)
    return SystemEqCurve(headroom + if (allowBoost) preampDb else preampDb.coerceAtMost(0f), bands)
  }

  fun configured(tone: Tone, manualBands: List<Float>?, bassDb: Float, preampDb: Float,
    allowBoost: Boolean): SystemEqCurve = withOutputControls(
    manualBands?.let(::manual) ?: build(tone, null), bassDb, preampDb, allowBoost
  )

  /** Reserve headroom for either curve while Android applies band parameters individually. */
  fun transitionGain(previous: SystemEqCurve?, next: SystemEqCurve): Float {
    val previousPeak = previous?.bands?.maxOf { it.gainDb } ?: 0f
    val nextPeak = next.bands.maxOf { it.gainDb }
    return minOf(previous?.inputGainDb ?: 0f, next.inputGainDb, -maxOf(0f, previousPeak, nextPeak))
  }

  fun bassGain(s: EngineSettings, route: RouteKind, volume: Float): Float {
    val remaining = 1f - volume.coerceIn(0f, 1f)
    val fixed = if (route == RouteKind.SPEAKER) 2f * remaining else 6f
    return s.bassBoost * fixed + if (s.dynamicBass) s.bassIntensity * 3f * remaining else 0f
  }

  /**
   * OEM Equalizer has no separate input gain or limiter. Shift its entire curve down and
   * retain cuts where the hardware range allows. The programmed bands never add gain.
   */
  fun oemLevels(tone: Tone, centreHz: List<Float>, range: IntRange,
    manual: SystemEqCurve? = null): List<Short> {
    val gains = centreHz.map { hz ->
      manual?.levelAt(hz)?.toDouble() ?: EqResponse.totalDb(hz.toDouble(), tone, null)
    }
    val headroom = (gains.maxOrNull() ?: 0.0).coerceAtLeast(0.0)
    return gains.map { ((it - headroom) * 100).roundToInt().coerceIn(range).toShort() }
  }

  private fun round2(v: Double): Float = (Math.round(v * 100.0) / 100.0).toFloat()
}
