package com.n2k02.seq.audio

import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import com.n2k02.seq.model.SystemEqCurve
import com.n2k02.seq.model.SystemEqCurveBuilder
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
  val engine: String get() = if (dynamics != null) "31-band precision" else "OEM equalizer"
  val hasControl: Boolean get() = runCatching { effect.hasControl() }.getOrDefault(false)

  companion object {
    fun create(priority: Int, session: Int, onControl: ((Boolean) -> Unit)? = null): SeqSystemEffect? {
      val created = runCatching { dynamics(priority, session) }.getOrNull()
        ?: runCatching { equalizer(priority, session) }.getOrNull()
      if (created != null && onControl != null) runCatching {
        created.effect.setControlStatusListener { _, granted -> onControl(granted) }
      }
      return created
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

  fun apply(curve: SystemEqCurve, peakGuard: Boolean, ceilingDb: Float) {
    dynamics?.let { dp ->
      val eq = DynamicsProcessing.Eq(true, true, curve.bands.size)
      curve.bands.forEachIndexed { i, b ->
        eq.setBand(i, DynamicsProcessing.EqBand(true, b.cutoffHz, b.gainDb))
      }
      dp.setPreEqAllChannelsTo(eq)
      dp.setInputGainAllChannelsTo(curve.inputGainDb)
      dp.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, peakGuard, 0,
        1f, 60f, 10f, ceilingDb.coerceIn(-12f, -0.1f), 0f))
      return
    }
    equalizer?.let { eq ->
      val range = eq.bandLevelRange
      for (i in 0 until eq.numberOfBands.toInt()) {
        val hz = eq.getCenterFreq(i.toShort()) / 1000f
        val mb = (curve.levelAt(hz) * 100f).toInt()
          .coerceIn(range[0].toInt(), range[1].toInt()).toShort()
        eq.setBandLevel(i.toShort(), mb)
      }
    }
  }

  fun setEnabled(on: Boolean): Boolean = runCatching {
    effect.setEnabled(on) == AudioEffect.SUCCESS && effect.enabled == on
  }.getOrDefault(false)

  override fun close() {
    runCatching { effect.setEnabled(false) }
    runCatching { effect.release() }
  }
}
