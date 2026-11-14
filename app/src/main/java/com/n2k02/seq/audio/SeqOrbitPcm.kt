package com.n2k02.seq.audio

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.min
import kotlin.math.max
import kotlin.math.sqrt

/** Stereo-preserving, bounded 8D orbit for the owned PCM path. No audio I/O or frame allocations. */
class SeqOrbitPcm(sampleRate: Int) {
  private val rate = sampleRate.also { require(it in 8000..192000) }
  private val history = Array(2) { FloatArray(256) }
  private var index = 0
  private var phase = 0.0
  private var controlRemaining = 0
  private var leftGain = sqrt(.5f)
  private var rightGain = sqrt(.5f)
  private var leftDelay = 0f
  private var rightDelay = 0f
  private var leftGainStep = 0f
  private var rightGainStep = 0f
  private var leftDelayStep = 0f
  private var rightDelayStep = 0f

  var revolutionsPerMinute = 9f
    set(value) { field = if (value.isFinite()) value.coerceIn(1f, 30f) else 9f }
  var strength = 0.6f
    set(value) { field = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f }

  fun reset() {
    history.forEach { it.fill(0f) }
    index = 0; phase = 0.0; controlRemaining = 0
    leftGain = sqrt(.5f); rightGain = sqrt(.5f)
    leftDelay = 0f; rightDelay = 0f
  }

  private fun read(channel: Int, delay: Float): Float {
    val d = delay.coerceIn(0f, 90f)
    val whole = d.toInt()
    val fraction = d - whole
    val a = history[channel][(index - whole) and 255]
    val b = history[channel][(index - whole - 1) and 255]
    return a + (b - a) * fraction
  }

  fun process(stereo: FloatArray, offset: Int = 0, frames: Int = (stereo.size - offset) / 2) {
    require(offset >= 0 && offset % 2 == 0 && frames >= 0 && offset.toLong() + frames.toLong() * 2 <= stereo.size)
    if (frames == 0 || strength == 0f) return
    val mix = strength
    val maxDelay = min(90f, rate * 0.0006f * mix)
    val phaseStep = 2.0 * PI * revolutionsPerMinute / (60.0 * rate)
    for (frame in 0 until frames) {
      if (controlRemaining == 0) {
        // Interpolate control values over 16 frames: fewer transcendental calls,
        // with no stepped gain or discontinuous delay at centre crossings.
        phase = (phase + phaseStep * 16) % (2 * PI)
        val pan = sin(phase).toFloat()
        leftGainStep = (sqrt((1f - pan) * .5f) - leftGain) / 16
        rightGainStep = (sqrt((1f + pan) * .5f) - rightGain) / 16
        leftDelayStep = (max(0f, pan) * maxDelay - leftDelay) / 16
        rightDelayStep = (max(0f, -pan) * maxDelay - rightDelay) / 16
        controlRemaining = 16
      }
      leftGain += leftGainStep; rightGain += rightGainStep
      leftDelay += leftDelayStep; rightDelay += rightDelayStep
      controlRemaining--
      val pos = offset + frame * 2
      val inputL = stereo[pos]
      val inputR = stereo[pos + 1]
      history[0][index] = inputL
      history[1][index] = inputR
      // Keep each channel's content, including anti-phase stereo. Convex mixing
      // and gains <= 1 preserve the no-boost bound used by the DSP planner.
      stereo[pos] = inputL * (1f - mix) + read(0, leftDelay) * leftGain.coerceIn(0f, 1f) * mix
      stereo[pos + 1] = inputR * (1f - mix) + read(1, rightDelay) * rightGain.coerceIn(0f, 1f) * mix
      index = (index + 1) and 255
    }
  }
}
