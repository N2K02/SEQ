package com.n2k02.seq.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.min

/**
 * Experimental 8D orbit for PCM that SEQ already owns.
 *
 * This processor is intentionally NOT wired to external-app audio, MediaProjection
 * or AudioTrack. A capture/replay loop without exclusive routing causes duplicate
 * playback. It is safe to use only when a future owned, exclusive PCM source exists.
 *
 * Input: interleaved stereo floating-point PCM [-1, 1], modified in place.
 * No allocations per frame, no playback thread blocking, bounded moving ITD.
 */
class SeqOrbitPcm(sampleRate: Int) {
  private val rate: Int = sampleRate.also { require(it in 8000..192000) }
  private val history = Array(2) { FloatArray(256) }
  private var index = 0
  private var phase = 0.0

  /** Slow 360° orbit; positive phase pans toward the right side. */
  var revolutionsPerMinute = 9f
    set(value) { field = value.coerceIn(1f, 30f) }
  var strength = 0.6f
    set(value) { field = value.coerceIn(0f, 1f) }

  fun reset() {
    history.forEach { it.fill(0f) }
    index = 0
    phase = 0.0
  }

  fun process(stereo: FloatArray, offset: Int = 0, frames: Int = (stereo.size - offset) / 2) {
    require(offset >= 0 && offset % 2 == 0 && frames >= 0 && offset + frames * 2 <= stereo.size)
    if (frames == 0) return
    val step = 2.0 * PI * revolutionsPerMinute / (60.0 * rate)
    for (frame in 0 until frames) {
      val pos = offset + frame * 2
      val inputL = stereo[pos].coerceIn(-1f, 1f)
      val inputR = stereo[pos + 1].coerceIn(-1f, 1f)
      history[0][index] = inputL
      history[1][index] = inputR
      val pan = sin(phase).toFloat()
      // Constant-energy movement blended with the original, never boosts above 0dB.
      val leftScale = kotlin.math.sqrt((1f - pan) * 0.5f)
      val rightScale = kotlin.math.sqrt((1f + pan) * 0.5f)
      val delay = min(90f, rate * 0.0006f * strength).toInt()
      val delayedL = history[0][(index - if (pan > 0) delay else 0 + 256) and 255]
      val delayedR = history[1][(index - if (pan < 0) delay else 0 + 256) and 255]
      val mono = (delayedL + delayedR) * 0.5f
      val wetL = mono * leftScale
      val wetR = mono * rightScale
      val mix = strength.coerceIn(0f, 1f)
      stereo[pos] = (inputL * (1f - mix) + wetL * mix).coerceIn(-1f, 1f)
      stereo[pos + 1] = (inputR * (1f - mix) + wetR * mix).coerceIn(-1f, 1f)
      index = (index + 1) and 255
      phase += step
      if (phase >= 2.0 * PI) phase -= 2.0 * PI
    }
  }
}
