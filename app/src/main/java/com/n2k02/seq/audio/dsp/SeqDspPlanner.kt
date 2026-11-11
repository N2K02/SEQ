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

  /** Largest boost of the combined curve between 20 Hz and 20 kHz (dB, ≥ 0). */
  fun maxBoostDb(filters: List<EqFilter>, sampleRate: Int): Double =
    (0..240).maxOf { step ->
      val hz = 20.0 * 1000.0.pow(step / 240.0)
      filters.sumOf { EqResponse.db(hz, it, sampleRate.toDouble()) }
    }.coerceAtLeast(0.0)

  /**
   * Smart headroom: full headroom without the limiter; half with it (the lookahead limiter
   * catches the rare remaining peak, so EQ does not make music sound quieter).
   */
  fun preGainDb(i: SeqDspInputs, boostDb: Double): Float {
    val headroom = if (i.peakGuard) boostDb * 0.5 else boostDb
    val profilePreamp = i.correction?.takeIf { it.valid() && !i.eqBypass }?.preampDb ?: 0.0
    val base = minOf(-headroom, profilePreamp)
    val user = if (i.peakGuard) i.preampDb.toDouble() else minOf(0.0, i.preampDb.toDouble())
    return (base + user).coerceIn(-24.0, 6.0).toFloat()
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
