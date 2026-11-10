package com.n2k02.seq.audio.pcm

/**
 * Chooses exactly one output backend. Native processing is preferred only when it
 * can replace the original playback stream; playback capture alone is never proof.
 *
 * This is a pure policy component. It does not grant permissions, start capture,
 * or claim that an Android audio route is exclusive.
 */
internal object AutomaticEnginePolicy {
  enum class Backend { NATIVE_PCM, PLATFORM_EFFECT, BYPASS }
  data class Evidence(
    val enabled: Boolean,
    val ownPcmStream: Boolean = false,
    val exclusivePolicyVerified: Boolean = false,
    val originalOutputSuppressed: Boolean = false,
    val outputRouteVerified: Boolean = false,
    val rollbackReady: Boolean = false,
    val platformEffectSupported: Boolean = false
  )

  fun choose(e: Evidence): Backend = when {
    !e.enabled -> Backend.BYPASS
    e.ownPcmStream -> Backend.NATIVE_PCM
    e.exclusivePolicyVerified && e.originalOutputSuppressed &&
      e.outputRouteVerified && e.rollbackReady -> Backend.NATIVE_PCM
    e.platformEffectSupported -> Backend.PLATFORM_EFFECT
    else -> Backend.BYPASS
  }
}
