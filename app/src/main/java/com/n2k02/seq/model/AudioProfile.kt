package com.n2k02.seq.model

enum class AudioProfile(val title: String, val subtitle: String) {
  PURE("Pure", "No SEQ processing"),
  REFERENCE("Reference Hi-Fi", "Natural reference tonality"),
  REFINED("Refined Hi-Fi", "Gentle optional tuning")
}
data class Tone(val bass: Float = 0f, val mid: Float = 0f, val treble: Float = 0f) {
  fun bounded() = Tone(bass.coerceIn(-6f, 6f), mid.coerceIn(-6f, 6f), treble.coerceIn(-6f, 6f))
}
data class EngineSettings(
  val enabled: Boolean = false,
  val profile: AudioProfile = AudioProfile.REFERENCE,
  val customTone: Tone = Tone(),
  val carTone: Tone = Tone(),
  val automaticCarProfile: Boolean = true,
  val carManual: Boolean = false,
  val carDeviceName: String = "",
  val aiEnabled: Boolean = false,
  val headphoneCorrection: Boolean = false,
  val convolution: Boolean = false,
  /** Lookahead limiter; also halves EQ headroom so boosts keep their loudness. */
  val peakGuard: Boolean = true,
  val peakCeilingDb: Float = -1.0f,
  val usbDirect: Boolean = false,
  /** Process every app's media (incl. Android Auto) through the output-mix effect. */
  val allMedia: Boolean = true,
  /** Equal-loudness compensation that follows the media volume (off in the car). */
  val loudness: Boolean = true
) {
  fun activeTone(car: Boolean): Tone = when (profile) {
    AudioProfile.PURE -> Tone()
    AudioProfile.REFERENCE -> if (car) carTone.bounded() else customTone.bounded()
    AudioProfile.REFINED -> {
      val t = if (car) carTone else customTone
      t.copy(bass = t.bass + 0.6f, treble = t.treble + 0.35f).bounded()
    }
  }
}
object ToneMath {
  fun gainDb(freqHz: Int, tone: Tone): Float {
    if (freqHz <= 0) return 0f
    val f = freqHz.toDouble()
    val bass = tone.bass.toDouble() / (1.0 + Math.pow(f / 220.0, 4.0))
    val midDistance = kotlin.math.abs(kotlin.math.log2(f / 1100.0))
    val mid = tone.mid.toDouble() * kotlin.math.max(0.0, 1.0 - midDistance / 2.2)
    val high = tone.treble.toDouble() / (1.0 + Math.pow(2500.0 / f, 4.0))
    return (bass + mid + high).toFloat()
  }
  /** Conservative headroom estimate, evaluated logarithmically at 241 points. */
  fun headroomDb(tone: Tone): Float = (0..240).maxOf {
    val hz = (20.0 * Math.pow(1000.0, it / 240.0)).toInt()
    gainDb(hz, tone)
  }.coerceAtLeast(0f)
}

/**
 * Volume-adaptive equal-loudness compensation (ISO 226 trend): the ear loses bass, and to a
 * lesser degree treble, as playback level drops. Adds gentle shelf boosts that grow as the
 * media volume is lowered and vanish at full volume, so the tonal balance stays constant.
 */
object LoudnessCompensation {
  /** Approximate attenuation of Android's media volume curve, 0 dB at max. */
  fun attenuationDb(volumeFraction: Float): Float =
    ((1f - volumeFraction.coerceIn(0f, 1f)) * 40f)
  fun apply(tone: Tone, volumeFraction: Float): Tone {
    val att = attenuationDb(volumeFraction)
    val bass = (att * 0.15f).coerceAtMost(5f)
    val treble = (att * 0.05f).coerceAtMost(2f)
    return tone.copy(bass = (tone.bass + bass).coerceIn(-6f, 9f),
      treble = (tone.treble + treble).coerceIn(-6f, 8f))
  }
}
