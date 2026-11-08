package com.n2k02.seq.audio.pcm

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.PI
import kotlin.math.sin

/**
 * Sample-level DSP kernel for AUDIO OWNED BY SEQ or a proven exclusive output route.
 * This class does not capture another app or change Android's audio policy.
 *
 * Interleaved stereo PCM32 [-1,+1]; prepare and replace processors OFF the playback thread.
 * Supports a bounded short FIR (for short correction, not long room reverb responses).
 * The 4x windowed-sinc peak estimator adds 16 frames of lookahead; this is an\n * experimental peak guard, not an independently certified true-peak limiter.
 */
class AdvancedPcmProcessor(
  leftIr: FloatArray = floatArrayOf(1f),
  rightIr: FloatArray = leftIr,
  private val ceiling: Float = 0.85f,
  private val release: Float = 0.00015f
) {
  init {
    require(ceiling in 0.1f..0.95f && release in 0f..0.01f)
    require(leftIr.size in 1..256 && rightIr.size in 1..256)
    require(leftIr.all { it.isFinite() } && rightIr.all { it.isFinite() })
  }

  private val filters = arrayOf(leftIr.clone(), rightIr.clone())
  private val buffers = arrayOf(FloatArray(leftIr.size), FloatArray(rightIr.size))
  private val positions = IntArray(2)
  /** 4x interpolation around the delayed centre sample; calculated once, never in callback. */
  private val tapCount = 32
  val lookaheadFrames: Int = 16
  private val history = Array(2) { FloatArray(tapCount) }
  private var cursor = 0
  private val kernels = Array(4) { phase ->
    val fractions = DoubleArray(tapCount) { tap ->
      val x = 15.0 + phase / 4.0 - tap
      if (x == 0.0) 1.0
      else if (kotlin.math.abs(x) >= 16.0) 0.0
      else {
        val z = PI * x
        (sin(z) / z) * (sin(z / 16.0) / (z / 16.0))
      }
    }
    val normalizer = fractions.sum()
    FloatArray(tapCount) { (fractions[it] / normalizer).toFloat() }
  }
  private var gain = 1f

  /** Processes exactly [frames] stereo frames in-place without allocating. */
  fun process(samples: FloatArray, frames: Int = samples.size / 2) {
    require(frames >= 0 && frames * 2 <= samples.size)
    for (frame in 0 until frames) {
      val index = frame * 2
      val a = filter(0, samples[index])
      val b = filter(1, samples[index + 1])
      history[0][cursor] = a
      history[1][cursor] = b
      cursor = (cursor + 1) % tapCount
      val outputIndex = (cursor + 15) % tapCount
      val estimated = max(oversampledPeak(0), oversampledPeak(1))
      val desired = if (estimated > ceiling) ceiling / estimated else 1f
      gain = if (desired < gain) desired else min(1f, gain + release)
      samples[index] = (history[0][outputIndex] * gain).coerceIn(-ceiling, ceiling)
      samples[index + 1] = (history[1][outputIndex] * gain).coerceIn(-ceiling, ceiling)
    }
  }

  private fun filter(channel: Int, input: Float): Float {
    val ring = buffers[channel]
    var pos = positions[channel]
    ring[pos] = input
    var result = 0f
    val coefficients = filters[channel]
    for (tap in coefficients.indices) {
      result += coefficients[tap] * ring[pos]
      pos = if (pos == 0) ring.lastIndex else pos - 1
    }
    positions[channel] = (positions[channel] + 1) % ring.size
    return result
  }

  private fun oversampledPeak(channel: Int): Float {
    var peak = 0f
    val ring = history[channel]
    for (phase in 0..3) {
      val coeff = kernels[phase]
      var sample = 0f
      for (tap in 0 until tapCount) {
        sample += coeff[tap] * ring[(cursor + tap) % tapCount]
      }
      peak = max(peak, abs(sample))
    }
    return peak
  }

  fun reset() {
    buffers.forEach { it.fill(0f) }
    positions.fill(0)
    history.forEach { it.fill(0f) }
    cursor = 0
    gain = 1f
  }
}

/** Don't replace another app's stream unless we have an exclusive, verifiable route. */
object PcmRoutingPolicy {
  enum class Source { OWN_PLAYER, PROVEN_EXCLUSIVE_AUDIO_POLICY, PLAYBACK_CAPTURE_COPY }
  fun canReplaceOriginal(source: Source, exclusiveReroutingConfirmed: Boolean): Boolean =
    source == Source.OWN_PLAYER ||
      (source == Source.PROVEN_EXCLUSIVE_AUDIO_POLICY && exclusiveReroutingConfirmed)
}
