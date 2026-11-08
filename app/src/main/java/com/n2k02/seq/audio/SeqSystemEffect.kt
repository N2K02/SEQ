package com.n2k02.seq.audio

import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.os.Handler
import android.os.Looper
import com.n2k02.seq.model.SystemEqCurve
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
      manualBands: List<Float>?, bassDb: Float, preampDb: Float,
      allowDynamics: Boolean = true, onControl: ((SeqSystemEffect, Boolean) -> Unit)? = null): SeqSystemEffect? {
      val preferred = if (allowDynamics) {
        configure(runCatching { dynamics(priority, session) }.getOrNull(),
          tone, peakGuard, ceilingDb, manualBands, bassDb, preampDb)
      } else null
      val created = preferred ?: configure(runCatching { equalizer(priority, session) }.getOrNull(),
          tone, peakGuard, ceilingDb, manualBands, bassDb, preampDb)
      if (created != null && onControl != null) runCatching {
        created.effect.setControlStatusListener { _, granted -> onControl(created, granted) }
      }
      return created
    }

    private fun configure(candidate: SeqSystemEffect?, tone: Tone, peakGuard: Boolean,
      ceilingDb: Float, manualBands: List<Float>?, bassDb: Float, preampDb: Float): SeqSystemEffect? {
      candidate ?: return null
      val ready = runCatching {
        candidate.apply(tone, peakGuard, ceilingDb, manualBands, bassDb, preampDb)
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
    val manualBands: List<Float>?, val bassDb: Float, val preampDb: Float)
  private var tuning: Tuning? = null
  private var programmedCurve: SystemEqCurve? = null
  private val main = Handler(Looper.getMainLooper())
  private var pending: Runnable? = null
  private var lastOemLevels: List<Short>? = null
  private var closed = false
  private fun cancelTransition() { pending?.let(main::removeCallbacks); pending = null }
  var response: List<Float>? = null
    private set

  fun apply(tone: Tone, peakGuard: Boolean, ceilingDb: Float, manualBands: List<Float>?,
    bassDb: Float, preampDb: Float) {
    val next = Tuning(if (manualBands == null) tone else Tone(), peakGuard, ceilingDb, manualBands, bassDb, preampDb)
    if (next == tuning) return
    program(next)
    tuning = next
  }

  fun invalidateTuning() {
    cancelTransition()
    tuning = null
    programmedCurve = null
  }

  /** Animate filter changes over 200 ms without blocking the playback thread. */
  private fun program(tuning: Tuning) {
    cancelTransition()
    dynamics?.let { dp ->
      val target = SystemEqCurveBuilder.configured(tuning.tone, tuning.manualBands, tuning.bassDb,
        tuning.preampDb, allowBoost = tuning.peakGuard)
      val old = programmedCurve
      val prior = this.tuning
      if (prior?.peakGuard != tuning.peakGuard || prior.ceilingDb != tuning.ceilingDb) {
        dp.setLimiterAllChannelsTo(DynamicsProcessing.Limiter(true, tuning.peakGuard, 0,
          1f, 60f, 10f, tuning.ceilingDb.coerceIn(-12f, -0.1f), 0f))
      }
      if (old == null || old.bands.size != target.bands.size) {
        programCurve(dp, target)
        programmedCurve = target
        response = target.bands.map { it.gainDb + target.inputGainDb }
        return
      }
      if (old == target) return
      var step = 0
      val tick = object : Runnable {
        override fun run() {
          if (closed || !hasControl) { pending = null; return }
          step++
          val fraction = step / 10f
          val curve = SystemEqCurve(
            old.inputGainDb + (target.inputGainDb - old.inputGainDb) * fraction,
            old.bands.zip(target.bands) { a, b ->
              a.copy(gainDb = a.gainDb + (b.gainDb - a.gainDb) * fraction)
            }
          )
          val success = runCatching {
            programCurve(dp, curve)
            programmedCurve = curve
            response = curve.bands.map { it.gainDb + curve.inputGainDb }
          }.isSuccess
          if (!success) { invalidateTuning(); return }
          if (step < 10) { pending = this; main.postDelayed(this, 20L) }
          else { pending = null; programmedCurve = target }
        }
      }
      pending = tick
      main.post(tick)
      return
    }
    equalizer?.let { eq ->
      val manual = tuning.manualBands?.let(SystemEqCurveBuilder::manual)
      val range = eq.bandLevelRange
      val centres = List(eq.numberOfBands.toInt()) { eq.getCenterFreq(it.toShort()) / 1000f }
      check(centres.isNotEmpty() && range[0] <= 0 && range[1] >= 0)
      val adjusted = if (tuning.bassDb != 0f || tuning.preampDb < 0f) {
        val base = manual ?: SystemEqCurveBuilder.build(tuning.tone, null)
        SystemEqCurveBuilder.withOutputControls(base, tuning.bassDb, tuning.preampDb, allowBoost = false)
      } else manual
      val target = SystemEqCurveBuilder.oemLevels(tuning.tone, centres,
        range[0].toInt()..range[1].toInt(), adjusted)
      val before = lastOemLevels
      if (before == null || before.size != target.size) {
        target.forEachIndexed { i, level -> eq.setBandLevel(i.toShort(), level) }
        lastOemLevels = target
        response = target.map { it / 100f }
        return
      }
      if (target == before) return
      var step = 0
      val tick = object : Runnable {
        override fun run() {
          if (closed || !hasControl) { pending = null; return }
          step++
          val fraction = step / 10f
          val current = before.zip(target) { a, b ->
            (a.toFloat() + (b - a) * fraction).toInt().toShort()
          }
          val success = runCatching {
            current.forEachIndexed { i, level -> eq.setBandLevel(i.toShort(), level) }
            lastOemLevels = current
            response = current.map { it / 100f }
          }.isSuccess
          if (!success) { invalidateTuning(); return }
          if (step < 10) { pending = this; main.postDelayed(this, 20L) }
          else { pending = null; lastOemLevels = target }
        }
      }
      pending = tick
      main.post(tick)
    }
  }

  private fun programCurve(dp: DynamicsProcessing, curve: SystemEqCurve) {
    // Lower gain before replacing the bands; restore only after the new curve is live.
    val interim = SystemEqCurveBuilder.transitionGain(programmedCurve, curve)
    if (programmedCurve?.inputGainDb != interim) dp.setInputGainAllChannelsTo(interim)
    val eq = DynamicsProcessing.Eq(true, true, curve.bands.size)
    curve.bands.forEachIndexed { i, band ->
      eq.setBand(i, DynamicsProcessing.EqBand(true, band.cutoffHz, band.gainDb))
    }
    dp.setPreEqAllChannelsTo(eq)
    if (interim != curve.inputGainDb) dp.setInputGainAllChannelsTo(curve.inputGainDb)
  }

  fun bypass(): Boolean = runCatching {
    !effect.enabled || (effect.setEnabled(false) == AudioEffect.SUCCESS && !effect.enabled)
  }.getOrDefault(false)

  fun enable(): Boolean = runCatching {
    effect.enabled || (effect.setEnabled(true) == AudioEffect.SUCCESS && effect.enabled)
  }.getOrDefault(false)

  override fun close() {
    closed = true
    cancelTransition()
    if (hasControl) runCatching { effect.setEnabled(false) }
    runCatching { effect.release() }
  }
}
