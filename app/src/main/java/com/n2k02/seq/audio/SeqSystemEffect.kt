package com.n2k02.seq.audio

import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import com.n2k02.seq.model.SystemEqCurveBuilder
import com.n2k02.seq.model.Tone
import java.io.Closeable

/**
 * Platform audio effect used for audio SEQ does not decode itself.
 * Prefers DynamicsProcessing (31 bands at exact SEQ response + input headroom + limiter);
 * falls back to the OEM Equalizer only when DynamicsProcessing is unavailable.
 */
class SeqSystemEffect private constructor(
  private val dynamics: DynamicsProcessing?,
  private val equalizer: Equalizer?
) : Closeable {
  private val effect: AudioEffect = dynamics ?: equalizer!!
  val engine: String get() = if (dynamics != null) "31-band DynamicsProcessing" else "OEM EQ (no limiter)"
  val limiterAvailable: Boolean get() = dynamics != null
  val hasControl: Boolean get() = runCatching { effect.hasControl() }.getOrDefault(false)

  companion object {
    fun open(priority: Int, session: Int, tone: Tone, peakGuard: Boolean, ceilingDb: Float,
      manualBands: List<Float>?, onControl: ((SeqSystemEffect, Boolean) -> Unit)? = null): SeqSystemEffect? {
      val created = configure(runCatching { dynamics(priority, session) }.getOrNull(),
        tone, peakGuard, ceilingDb, manualBands)
        ?: configure(runCatching { equalizer(priority, session) }.getOrNull(),
          tone, peakGuard, ceilingDb, manualBands)
      if (created != null && onControl != null) runCatching {
        created.effect.setControlStatusListener { _, granted -> onControl(created, granted) }
      }
      return created
    }

    private fun configure(candidate: SeqSystemEffect?, tone: Tone, peakGuard: Boolean,
      ceilingDb: Float, manualBands: List<Float>?): SeqSystemEffect? {
      candidate ?: return null
      val ready = runCatching {
        candidate.apply(tone, peakGuard, ceilingDb, manualBands)
        candidate.enable()
      }.getOrDefault(false)
      if (ready) return candidate
      candidate.close()
      return null
    }

    private fun dynamics(priority: Int, session: Int): SeqSystemEffect? {
      val config = DynamicsProcessing.Config.Builder(
        DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
        true, SystemEqCurveBuilder.BAND_COUNT, false, 0, false, 0, true
      ).build()
      val dp = DynamicsProcessing(priority, session, config)
      if (!dp.hasControl()) { dp.release(); return null }
      return SeqSystemEffect(dp, null)
    }
    private fun equalizer(priority: Int, session: Int): SeqSystemEffect? {
      val eq = Equalizer(priority, session)
      if (!eq.hasControl()) { eq.release(); return null }
      return SeqSystemEffect(null, eq)
    }
  }

  private data class Tuning(val tone: Tone, val peakGuard: Boolean, val ceilingDb: Float,
    val manualBands: List<Float>?)
  private var tuning: Tuning? = null

  fun apply(tone: Tone, peakGuard: Boolean, ceilingDb: Float, manualBands: List<Float>?) {
    val next = Tuning(if (manualBands == null) tone else Tone(), peakGuard, ceilingDb, manualBands)
    if (next == tuning) return
    program(next)
    tuning = next
  }

  fun invalidateTuning() { tuning = null }

  private fun program(tuning: Tuning) {
    val manual = tuning.manualBands?.let(SystemEqCurveBuilder::manual)
    dynamics?.let { dp ->
      val curve = manual ?: SystemEqCurveBuilder.build(tuning.tone, null, limiter = tuning.peakGuard)
      val eq = DynamicsProcessing.Eq(true, true, curve.bands.size)
      curve.bands.forEachIndexed { i, b ->
        eq.setBand(i, DynamicsProcessing.EqBand(true, b.cutoffHz, b.gainDb))
      }
      dp.setPreEqAllChannelsTo(eq)
      dp.setInputGainAllChannelsTo(curve.inputGainDb)
      dp.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, tuning.peakGuard, 0,
        1f, 60f, 10f, tuning.ceilingDb.coerceIn(-12f, -0.1f), 0f))
      return
    }
    equalizer?.let { eq ->
      val range = eq.bandLevelRange
      val centres = List(eq.numberOfBands.toInt()) { eq.getCenterFreq(it.toShort()) / 1000f }
      check(centres.isNotEmpty() && range[0] <= 0 && range[1] >= 0)
      SystemEqCurveBuilder.oemLevels(tuning.tone, centres, range[0].toInt()..range[1].toInt(), manual)
        .forEachIndexed { i, level -> eq.setBandLevel(i.toShort(), level) }
    }
  }

  fun enable(): Boolean = runCatching {
    effect.enabled || (effect.setEnabled(true) == AudioEffect.SUCCESS && effect.enabled)
  }.getOrDefault(false)

  override fun close() {
    if (hasControl) runCatching { effect.setEnabled(false) }
    runCatching { effect.release() }
  }
}
