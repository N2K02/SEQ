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
  /** Octave-band centres analysed by the adaptive stage. */
  val OCTAVES = doubleArrayOf(63.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0)
  /** Typical long-term music spectrum relative to 1 kHz (dB) — the balance we steer towards. */
  private val TARGET = doubleArrayOf(4.0, 4.0, 2.0, 1.0, 0.0, -3.0, -6.0, -10.0)
  private const val MAX_ADAPT = 3f

  fun base(route: RouteKind): Tone = when (route) {
    // Small speakers: protect from bass overload, lift presence for clarity.
    RouteKind.SPEAKER -> Tone(bass = -1f, mid = 1f, treble = 0.5f)
    // Headphones: Harman-style low-shelf lift, slightly smoothed highs.
    RouteKind.WIRED -> Tone(bass = 3f, mid = 0f, treble = -0.5f)
    // Bluetooth earbuds/speakers are usually pre-tuned: gentle lift only.
    RouteKind.BLUETOOTH -> Tone(bass = 2f, mid = 0f, treble = 0.5f)
    // Cars: road noise masks lows and highs; mild mid scoop reduces boominess.
    RouteKind.CAR -> Tone(bass = 2f, mid = -0.5f, treble = 1.5f)
  }

  fun compose(route: RouteKind, volumeFraction: Float, adaptive: Tone): Tone {
    val b = base(route)
    val tone = Tone(b.bass + adaptive.bass, b.mid + adaptive.mid, b.treble + adaptive.treble)
    val shaped = if (route == RouteKind.CAR) tone else LoudnessCompensation.apply(tone, volumeFraction)
    val bassLimit = if (route == RouteKind.SPEAKER) 0f else 9f
    return Tone(shaped.bass.coerceIn(-6f, bassLimit), shaped.mid.coerceIn(-4f, 4f),
      shaped.treble.coerceIn(-6f, 6f))
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
  fun significant(a: Tone, b: Tone, thresholdDb: Float = 0.4f) =
    abs(a.bass - b.bass) >= thresholdDb || abs(a.mid - b.mid) >= thresholdDb ||
      abs(a.treble - b.treble) >= thresholdDb
}
