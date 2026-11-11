package com.n2k02.seq.audio.pcm

/**
 * Chooses exactly one output backend. SEQ DSP (playback capture → PCM DSP → replay, with each
 * captured app's direct output muted per session) is the default; Android's output-mix effect
 * is the automatic fallback whenever one prerequisite is missing.
 *
 * Pure policy: it grants nothing and starts nothing.
 */
internal object AutomaticEnginePolicy {
  enum class Backend { NATIVE_PCM, PLATFORM_EFFECT, BYPASS }
  data class Evidence(
    val enabled: Boolean,
    /** User setting; on by default. */
    val dspWanted: Boolean = true,
    /** RECORD_AUDIO: required by Android for playback capture. */
    val recordAudio: Boolean = false,
    /** DUMP: needed to find each app's audio session so its direct sound can be muted. */
    val dumpGranted: Boolean = false,
    /** A live MediaProjection token from Android's capture consent. */
    val captureConsent: Boolean = false,
    /** Session mute (DynamicsProcessing) available, so replay never doubles the sound. */
    val sessionMuteAvailable: Boolean = false,
    val platformEffectSupported: Boolean = false
  )

  fun choose(e: Evidence): Backend = when {
    !e.enabled -> Backend.BYPASS
    dspReady(e) -> Backend.NATIVE_PCM
    e.platformEffectSupported -> Backend.PLATFORM_EFFECT
    else -> Backend.BYPASS
  }

  fun dspReady(e: Evidence): Boolean = e.dspWanted && e.recordAudio && e.dumpGranted &&
    e.captureConsent && e.sessionMuteAvailable
}
