package com.n2k02.seq.model

enum class AudioProfile(val title: String, val subtitle: String) {
  AUTO("SEQ Auto", "Tunes itself to your output, volume and music"),
  PURE("Pure", "No SEQ processing")
}
data class Tone(val bass: Float = 0f, val mid: Float = 0f, val treble: Float = 0f)

/** Only what the automatic engine needs; there is no manual tuning to manage. */
data class EngineSettings(
  val enabled: Boolean = false,
  val profile: AudioProfile = AudioProfile.AUTO,
  /** Adaptive music analysis (needs Android's audio-analysis permission). */
  val adaptive: Boolean = true,
  // Retained backend capabilities without UI; always safe defaults.
  val headphoneCorrection: Boolean = false,
  val convolution: Boolean = false,
  val peakGuard: Boolean = true,
  val peakCeilingDb: Float = -1.0f,
  val usbDirect: Boolean = false,
  val allMedia: Boolean = true,
  val loudness: Boolean = true
)

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
