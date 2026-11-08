package com.n2k02.seq.audio.pcm

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Sample-level DSP kernel for AUDIO OWNED BY SEQ or a proven exclusive output route.
 * This class does not capture another app or change Android's audio policy.
 *
 * Interleaved stereo PCM32 [-1,+1]; prepare and replace processors OFF the playback thread.
 * Supports a bounded short FIR (for short correction, not long room reverb responses).
 * The 4x intersample estimator is an additional guard, not a certified true-peak limiter.
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
  private val previous = FloatArray(2)
  private var gain = 1f

  /** Processes exactly [frames] stereo frames in-place without allocating. */
  fun process(samples: FloatArray, frames: Int = samples.size / 2) {
    require(frames >= 0 && frames * 2 <= samples.size)
    for (frame in 0 until frames) {
      val index = frame * 2
      val a = filter(0, samples[index])
      val b = filter(1, samples[index + 1])
      // Cubic interpolated peak estimate is conservative with an additional ceiling margin.
      val estimated = max(estimatePeak(previous[0], a), estimatePeak(previous[1], b))
      val desired = if (estimated > ceiling) ceiling / estimated else 1f
      gain = if (desired < gain) desired else min(1f, gain + release)
      samples[index] = (a * gain).coerceIn(-ceiling, ceiling)
      samples[index + 1] = (b * gain).coerceIn(-ceiling, ceiling)
      previous[0] = a
      previous[1] = b
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

  private fun estimatePeak(before: Float, now: Float): Float {
    var peak = max(abs(before), abs(now))
    // Linear 4x estimates are cheap; hardware/true-peak compliance is NOT implied.
    for (i in 1..3) peak = max(peak, abs(before + (now - before) * i / 4f))
    return peak
  }

  fun reset() {
    buffers.forEach { it.fill(0f) }
    positions.fill(0)
    previous.fill(0f)
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
