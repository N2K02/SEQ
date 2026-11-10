package com.n2k02.seq.model

enum class AudioProfile(val title: String, val subtitle: String) {
  AUTO("SEQ Auto", "Tunes itself to your output, volume and music"),
  PURE("Pure", "No SEQ processing")
}
enum class SoundMode(val title: String) {
  NATURAL("Natural"), SMOOTH("Smooth"), DEEP_BASS("Deep Bass"), IMMERSIVE("Immersive")
}

/** Deliberately bounded tonal characters, without phase-based stereo widening. */
object SoundModeTuning {
  fun apply(tone: Tone, mode: SoundMode): Tone = when (mode) {
    SoundMode.NATURAL -> tone
    SoundMode.SMOOTH -> tone.copy(bass = tone.bass + 0.5f, mid = tone.mid + 0.25f, treble = tone.treble - 1.25f)
    SoundMode.DEEP_BASS -> tone.copy(bass = tone.bass + 2f, mid = tone.mid - 0.25f, treble = tone.treble - 0.5f)
    SoundMode.IMMERSIVE -> tone.copy(bass = tone.bass + 0.75f, mid = tone.mid - 0.75f, treble = tone.treble + 0.75f)
  }
}

data class Tone(val bass: Float = 0f, val mid: Float = 0f, val treble: Float = 0f)

data class EngineSettings(
  val enabled: Boolean = false,
  val profile: AudioProfile = AudioProfile.AUTO,
  /** Adaptive music analysis (needs Android's audio-analysis permission). */
  val adaptive: Boolean = true,
  val peakGuard: Boolean = true,
  /** Limiter ceiling (dBFS). */
  val peakCeilingDb: Float = -1.0f,
  val allMedia: Boolean = true,
  val loudness: Boolean = true,
  val manualBands: List<Float>? = null,
  val bassBoost: Float = 0f,
  val spatialMode: SpatialMode = SpatialMode.OFF,
  val spatialIntensity: Float = 0.6f,
  val preampDb: Float = 0f,
  val soundMode: SoundMode = SoundMode.NATURAL,
  val compressorEnabled: Boolean = false,
  val compressorRatio: Float = 1.5f,
  val compressorThresholdDb: Float = -18f,
  val dynamicBass: Boolean = false,
  val bassIntensity: Float = 0.35f
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
