package com.n2k02.seq.model

import kotlin.math.abs

/** Where the sound is going; each output gets its own automatic starting balance. */
enum class RouteKind(val title: String) {
  SPEAKER("Phone speaker"), WIRED("Wired headphones"), BLUETOOTH("Bluetooth audio"), CAR("Car")
}

/**
 * SEQ Auto: the automatic sound engine. No sliders.
 * 1. Output-aware base balance (speaker clarity, headphone/Harman-style bass, car road-noise).
 * 2. Adaptive music correction from a slow analysis of what is playing (optional).
 * 3. Volume-adaptive equal-loudness (not in the car, where the head unit sets the level).
 * Keep the phone speaker out of bass boost even after adaptive and loudness corrections.
 */
object AutoSound {
  fun shouldAnalyze(s: EngineSettings, mediaActive: Boolean, hasOutputPreset: Boolean,
    permitted: Boolean): Boolean = mediaActive && permitted && s.enabled && s.adaptive &&
    s.profile == AudioProfile.AUTO && s.manualBands == null && !hasOutputPreset

  /** Octave-band centres analysed by the adaptive stage. */
  val OCTAVES = doubleArrayOf(63.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0)
  /** Typical long-term music spectrum relative to 1 kHz (dB) — the balance we steer towards. */
  private val TARGET = doubleArrayOf(4.0, 4.0, 2.0, 1.0, 0.0, -3.0, -6.0, -10.0)
  private const val MAX_ADAPT = 3f

  fun base(route: RouteKind): Tone = when (route) {
    RouteKind.SPEAKER -> Tone(bass = -1f, mid = 0.85f, treble = 0.65f)
    RouteKind.WIRED -> Tone(bass = 2f, treble = -0.75f)
    RouteKind.BLUETOOTH -> Tone(bass = 1f, treble = -0.5f)
    RouteKind.CAR -> Tone(bass = 1.5f, mid = -0.5f, treble = 0.5f)
  }

  fun compose(route: RouteKind, volumeFraction: Float, adaptive: Tone): Tone {
    val base = base(route)
    val tone = Tone(base.bass + adaptive.bass * 0.5f, base.mid + adaptive.mid * 0.5f,
      base.treble + adaptive.treble * 0.5f)
    if (route == RouteKind.SPEAKER) {
      return Tone(tone.bass.coerceIn(-3f, 0f), tone.mid.coerceIn(-1f, 1f),
        tone.treble.coerceIn(-2f, 1f))
    }
    if (route == RouteKind.CAR) return tone
    val volume = 0.5f + 0.5f * volumeFraction.coerceIn(0f, 1f)
    return LoudnessCompensation.apply(tone, volume)
  }

  /**
   * Correction from a measured octave spectrum (dB, same order as [OCTAVES]).
   * Returns null when the signal is too quiet or incomplete to judge.
   * Gentle on purpose: corrects half the deviation, capped at ±3 dB.
   */
  fun adapt(octaveDb: DoubleArray): Tone? {
    if (octaveDb.size != OCTAVES.size || octaveDb.any { !it.isFinite() }) return null
    val ref = octaveDb[4]
    val rel = DoubleArray(octaveDb.size) { octaveDb[it] - ref - TARGET[it] }
    val bass = -(rel[0] + rel[1]) / 2.0 * 0.5
    val treble = -(rel[6] + rel[7]) / 2.0 * 0.5
    val mid = -(rel[3] + rel[5]) / 2.0 * 0.25
    return Tone(bass.toFloat().coerceIn(-MAX_ADAPT, MAX_ADAPT),
      mid.toFloat().coerceIn(-1.5f, 1.5f), treble.toFloat().coerceIn(-MAX_ADAPT, MAX_ADAPT))
  }

  /** Slow exponential smoothing so the sound never audibly "moves". */
  fun smooth(previous: Tone, next: Tone, alpha: Float = 0.2f) = Tone(
    previous.bass + (next.bass - previous.bass) * alpha,
    previous.mid + (next.mid - previous.mid) * alpha,
    previous.treble + (next.treble - previous.treble) * alpha)

  /** Only push an update when it is large enough to matter (avoids constant re-tuning). */
  /** Analysis can pause once this many decisions in a row changed nothing audible. */
  fun settled(stableDecisions: Int, needed: Int): Boolean = stableDecisions >= needed

  fun significant(a: Tone, b: Tone, thresholdDb: Float = 0.4f) =
    abs(a.bass - b.bass) >= thresholdDb || abs(a.mid - b.mid) >= thresholdDb ||
      abs(a.treble - b.treble) >= thresholdDb
}
