package com.n2k02.seq.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/** Experimental owned-PCM DSP inspired by RootlessBrennanDSP's feature set.
 *
 * All operations are deterministic and avoid allocations in the sample loop.
 * This processor NEVER captures, routes or plays third-party media.
 * Advanced features must only run on verified owned/exclusive PCM sources.
 */
data class SeqAdvancedPcmConfig(
  val stereoWidth: Float = 1f,
  val tubeDrive: Float = 0f,
  val compressorThresholdDb: Float = -18f,
  val compressorRatio: Float = 1f,
  val bassGainDb: Float = 0f,
  val bassCutoffHz: Float = 120f,
  val convolutionWet: Float = 0f
) {
  private fun bounded(value: Float, min: Float, max: Float, fallback: Float) =
    if (value.isFinite()) value.coerceIn(min, max) else fallback
  fun safe() = copy(
    stereoWidth = bounded(stereoWidth, 0f, 1.8f, 1f),
    tubeDrive = bounded(tubeDrive, 0f, 1f, 0f),
    compressorThresholdDb = bounded(compressorThresholdDb, -36f, -3f, -18f),
    compressorRatio = bounded(compressorRatio, 1f, 8f, 1f),
    bassGainDb = bounded(bassGainDb, 0f, 6f, 0f),
    bassCutoffHz = bounded(bassCutoffHz, 40f, 200f, 120f),
    convolutionWet = bounded(convolutionWet, 0f, .7f, 0f)
  )
}

/** Bounded mono FIR impulse applied separately to stereo channels.
 * Caller may load a user-owned WAV/IRS impulse through a validated decoder.
 * No untrusted file parsing occurs on an audio thread.
 */
class SeqStereoConvolver(private val impulse: FloatArray) {
  init { require(impulse.isNotEmpty() && impulse.size <= 4096 && impulse.all(Float::isFinite)) }
  private val left = FloatArray(impulse.size)
  private val right = FloatArray(impulse.size)
  private var cursor = 0

  fun reset() { left.fill(0f); right.fill(0f); cursor = 0 }

  var outLeft = 0f
    private set
  var outRight = 0f
    private set

  /** Primitive outputs avoid allocating a Pair for every PCM frame. */
  fun process(leftIn: Float, rightIn: Float) {
    left[cursor] = leftIn
    right[cursor] = rightIn
    var sumL = 0f
    var sumR = 0f
    var at = cursor
    for (coefficient in impulse) {
      sumL += left[at] * coefficient
      sumR += right[at] * coefficient
      at--
      if (at < 0) at = impulse.lastIndex
    }
    cursor++
    if (cursor == impulse.size) cursor = 0
    outLeft = sumL
    outRight = sumR
  }
}

/** Stereo widening, smooth compression, low-shelf-like dynamic bass, and
 * optional convolution. Outputs stay inside floating PCM [-1, 1].
 */
class SeqAdvancedPcm(
  sampleRate: Int,
  config: SeqAdvancedPcmConfig = SeqAdvancedPcmConfig(),
  impulse: FloatArray? = null
) {
  private val rate = sampleRate.also { require(it in 8000..192000) }
  private var options = config.safe()
  private val convolver = impulse?.let(::SeqStereoConvolver)
  private var bassL = 0f
  private var bassR = 0f
  private var envelope = 0f
  private val attack = exp(-1.0 / (0.01 * rate)).toFloat()
  private val release = exp(-1.0 / (0.13 * rate)).toFloat()

  fun update(config: SeqAdvancedPcmConfig) { options = config.safe() }
  fun reset() {
    convolver?.reset()
    bassL = 0f
    bassR = 0f
    envelope = 0f
  }

  fun process(stereo: FloatArray, offset: Int = 0, frames: Int = (stereo.size - offset) / 2) {
    require(offset >= 0 && offset % 2 == 0 && frames >= 0 &&
      offset.toLong() + frames.toLong() * 2 <= stereo.size.toLong())
    val settings = options
    val bassA = (1.0 - exp(-2.0 * PI * settings.bassCutoffHz / rate)).toFloat()
    val gain = (10.0.pow(settings.bassGainDb / 20.0) - 1.0).toFloat()
    val drive = 1f + 3f * settings.tubeDrive
    val driveNorm = if (settings.tubeDrive > 0f) tanh(drive) else 1f
    val threshold = 10.0.pow(settings.compressorThresholdDb / 20.0).toFloat()
    for (i in 0 until frames) {
      val p = offset + i * 2
      var l = stereo[p].coerceIn(-1f, 1f)
      var r = stereo[p + 1].coerceIn(-1f, 1f)
      bassL += bassA * (l - bassL)
      bassR += bassA * (r - bassR)
      l += bassL * gain
      r += bassR * gain
      val mid = (l + r) * .5f
      val side = (l - r) * .5f * settings.stereoWidth
      l = mid + side
      r = mid - side
      if (settings.tubeDrive > 0f) {
        l = tanh(l * drive) / driveNorm
        r = tanh(r * drive) / driveNorm
      }
      val level = max(abs(l), abs(r))
      envelope = if (level > envelope)
        attack * envelope + (1f - attack) * level
      else release * envelope + (1f - release) * level
      if (settings.compressorRatio > 1f && envelope > threshold) {
        val attenuation = (threshold / envelope).pow(1f - 1f / settings.compressorRatio)
        l *= attenuation
        r *= attenuation
      }
      if (settings.convolutionWet > 0f && convolver != null) {
        convolver.process(l, r)
        l = l * (1f - settings.convolutionWet) + convolver.outLeft * settings.convolutionWet
        r = r * (1f - settings.convolutionWet) + convolver.outRight * settings.convolutionWet
      }
      stereo[p] = l.coerceIn(-1f, 1f)
      stereo[p + 1] = r.coerceIn(-1f, 1f)
    }
  }
}
