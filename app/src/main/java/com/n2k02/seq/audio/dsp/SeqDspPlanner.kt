package com.n2k02.seq.audio.dsp

import com.n2k02.seq.model.EqFilter
import com.n2k02.seq.model.EqResponse
import com.n2k02.seq.model.FilterKind
import com.n2k02.seq.model.HeadphoneProfile
import com.n2k02.seq.model.SpatialMode
import com.n2k02.seq.model.SystemEqCurveBuilder
import com.n2k02.seq.model.Tone
import kotlin.math.pow

/** Everything the PCM engine needs, already resolved from settings, route and volume. */
data class SeqDspInputs(
  val tone: Tone,
  val manualBands: List<Float>?,
  val bassDb: Float,
  val preampDb: Float,
  val correction: HeadphoneProfile?,
  val peakGuard: Boolean,
  val ceilingDb: Float,
  val spatial: SpatialMode,
  val spatialStrength: Float,
  val compressor: Boolean,
  val compressorRatio: Float,
  val compressorThresholdDb: Float,
  val leveler: Boolean = false,
  /** Device-side EQ (FreeBuds) already shapes the sound: keep 3D/limiter only. */
  val eqBypass: Boolean = false
)

/**
 * Pure builder: settings → real sample-level biquads. Unlike the 10-band platform fallback,
 * AutoEQ/parametric corrections run as true parametric filters here.
 */
object SeqDspPlanner {
  /** One-octave graphic bands. */
  private const val GRAPHIC_Q = 1.41

  fun filters(i: SeqDspInputs): List<EqFilter> {
    if (i.eqBypass) return emptyList()
    val base = i.manualBands?.let { bands ->
      val centres = SystemEqCurveBuilder.centres(SystemEqCurveBuilder.BAND_COUNT)
      SystemEqCurveBuilder.resample(bands, SystemEqCurveBuilder.BAND_COUNT).mapIndexed { index, gain ->
        EqFilter(FilterKind.PEAK, centres[index], gain.toDouble(), GRAPHIC_Q)
      }
    } ?: EqResponse.toneFilters(i.tone)
    val correction = i.correction?.takeIf { it.valid() }?.filters.orEmpty()
    val bass = if (i.bassDb != 0f) listOf(EqFilter(FilterKind.LOW_SHELF, 110.0, i.bassDb.toDouble(), 0.7071))
      else emptyList()
    return (base + correction + bass).filter { it.enabled && it.gain != 0.0 && it.valid() }
      .take(SeqDspChain.MAX_FILTERS)
  }

  /** Response scan includes exact narrow-filter centres and the usable spectrum endpoints. */
  fun maxBoostDb(filters: List<EqFilter>, sampleRate: Int): Double {
    if (filters.isEmpty()) return 0.0
    val top = minOf(20000.0, sampleRate * 0.5)
    val frequencies = (0..1024).map { 10.0 * (top / 10.0).pow(it / 1024.0) } +
      listOf(0.0, sampleRate * 0.5) + filters.flatMap { filter ->
        val centre = filter.frequency.coerceIn(10.0, sampleRate * 0.475)
        // Local samples cover interacting narrow peaks displaced from their centres.
        (-8..8).map { centre * (1.0 + it / (32.0 * filter.q)) }
          .filter { it in 0.0..(sampleRate * 0.5) }
      }
    // Prepare coefficients once; trig depends on the probe frequency, not each filter.
    val coefficients = filters.map { SeqBiquad.coefficients(it, sampleRate) }
    val boost = frequencies.maxOf { hz ->
      val w = 2.0 * kotlin.math.PI * hz / sampleRate
      val c = kotlin.math.cos(w); val s = kotlin.math.sin(w)
      val c2 = kotlin.math.cos(2 * w); val s2 = kotlin.math.sin(2 * w)
      coefficients.sumOf { f ->
        val nr = f[0] + f[1] * c + f[2] * c2; val ni = -f[1] * s - f[2] * s2
        val dr = 1 + f[3] * c + f[4] * c2; val di = -f[3] * s - f[4] * s2
        10.0 * kotlin.math.log10((nr * nr + ni * ni) / (dr * dr + di * di))
      }
    }.coerceAtLeast(0.0)
    // Preserve exact flat/cut-only bypass, with a small reserve for scan interpolation.
    return if (boost < 0.01) 0.0 else boost + 0.5
  }

  /**
   * Full combined EQ and spatial headroom. The limiter catches transients rather than
   * continuously repairing EQ boosts. An explicit positive user preamp remains an override
   * when the limiter is enabled.
   */
  fun preGainDb(i: SeqDspInputs, boostDb: Double): Float {
    val strength = i.spatialStrength.coerceIn(0f, 1f)
    val spatialBound = when (i.spatial) {
      SpatialMode.WIDE -> 1f + 0.6f * strength
      SpatialMode.CINEMA -> (1f + 0.8f * strength) * (1f + 0.16f * strength)
      SpatialMode.HALL -> 1f + 0.3f * strength
      else -> 1f
    }
    val headroom = boostDb + 20 * kotlin.math.log10(spatialBound.toDouble())
    val profilePreamp = i.correction?.takeIf { it.valid() && !i.eqBypass }?.preampDb ?: 0.0
    val base = minOf(-headroom, profilePreamp)
    val user = if (i.peakGuard) i.preampDb.toDouble() else minOf(0.0, i.preampDb.toDouble())
    return (base + user).coerceIn(-96.0, 6.0).toFloat()
  }

  fun plan(sampleRate: Int, i: SeqDspInputs): SeqDspPlan {
    val filters = filters(i)
    val boost = maxBoostDb(filters, sampleRate)
    val preDb = preGainDb(i, boost)
    val coefficients = DoubleArray(filters.size * 5)
    filters.forEachIndexed { index, filter ->
      SeqBiquad.coefficients(filter, sampleRate).copyInto(coefficients, index * 5)
    }
    val ceiling = 10.0.pow(i.ceilingDb.coerceIn(-6f, 0f) / 20.0).toFloat()
    return SeqDspPlan(
      sampleRate = sampleRate,
      coefficients = coefficients,
      preGain = 10.0.pow(preDb / 20.0).toFloat(),
      spatial = i.spatial,
      spatialStrength = i.spatialStrength.coerceIn(0f, 1f),
      compressor = i.compressor,
      compressorRatio = i.compressorRatio.coerceIn(1f, 8f),
      compressorThresholdDb = i.compressorThresholdDb.coerceIn(-48f, 0f),
      limiter = i.peakGuard,
      ceiling = ceiling.coerceIn(0.1f, 1f),
      leveler = i.leveler,
      description = "${filters.size} filters · pre ${"%.1f".format(java.util.Locale.ROOT, preDb)} dB"
    )
  }

  /** 10 display values (dB) at the graphic centres, for the Home response graph. */
  fun response(i: SeqDspInputs, sampleRate: Int = 48000): List<Float> {
    val filters = filters(i)
    val pre = preGainDb(i, maxBoostDb(filters, sampleRate))
    return SystemEqCurveBuilder.centres(SystemEqCurveBuilder.BAND_COUNT).map { hz ->
      (filters.sumOf { EqResponse.db(hz, it, sampleRate.toDouble()) } + pre).toFloat()
    }
  }
}
