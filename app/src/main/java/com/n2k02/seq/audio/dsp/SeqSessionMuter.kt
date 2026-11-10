package com.n2k02.seq.audio.dsp

import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import com.n2k02.seq.audio.SeqRuntime

/**
 * Silences an app's *direct* output while SEQ DSP replays its captured, processed copy —
 * the RootlessBrennanDSP "muted session" technique: a highest-priority DynamicsProcessing
 * with −96 dB input gain on that app's audio session. Playback capture taps the track before
 * session effects, so capture still receives the original signal.
 *
 * Releasing the effect immediately restores the app's normal sound (safe rollback).
 */
class SeqSessionMuter(private val onLost: (sessionId: Int) -> Unit) {
  private val effects = HashMap<Int, DynamicsProcessing>()

  val sessions: Set<Int> @Synchronized get() = effects.keys.toSet()

  @Synchronized fun mute(sessionId: Int): Boolean {
    if (sessionId <= 0) return false
    if (effects.containsKey(sessionId)) return true
    val effect = runCatching { create(sessionId) }.getOrElse {
      SeqRuntime.log("DSP mute unavailable session=$sessionId error=${it.javaClass.simpleName}")
      null
    } ?: return false
    effects[sessionId] = effect
    return true
  }

  @Synchronized fun unmute(sessionId: Int) {
    effects.remove(sessionId)?.let(::release)
  }

  /** Keep only [keep]; restore every other session's direct sound. */
  @Synchronized fun retain(keep: Set<Int>) {
    effects.keys.filter { it !in keep }.forEach(::unmute)
  }

  @Synchronized fun releaseAll() {
    effects.values.forEach(::release)
    effects.clear()
  }

  private fun release(effect: AudioEffect) {
    runCatching { effect.enabled = false }
    runCatching { effect.release() }
  }

  private fun create(sessionId: Int): DynamicsProcessing {
    val config = DynamicsProcessing.Config.Builder(
      DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
      false, 0, false, 0, false, 0, true
    ).build()
    val effect = DynamicsProcessing(Int.MAX_VALUE, sessionId, config)
    try {
      effect.setInputGainAllChannelsTo(MUTE_DB)
      runCatching {
        val limiter = effect.getLimiterByChannelIndex(0)
        limiter.isEnabled = true
        limiter.postGain = MUTE_DB
        limiter.threshold = MUTE_DB
        effect.setLimiterByChannelIndex(0, limiter)
        effect.setLimiterByChannelIndex(1, limiter)
      }
      check(effect.setEnabled(true) == AudioEffect.SUCCESS) { "mute rejected" }
      check(effect.hasControl()) { "mute control unavailable" }
    } catch (failure: Exception) {
      release(effect)
      throw failure
    }
    effect.setEnableStatusListener { fx, enabled ->
      if (!enabled) runCatching {
        (fx as DynamicsProcessing).setInputGainAllChannelsTo(MUTE_DB)
        fx.enabled = true
      }.onFailure { lost(sessionId) }
    }
    effect.setControlStatusListener { fx, granted ->
      if (!granted) lost(sessionId)
      else runCatching {
        (fx as DynamicsProcessing).setInputGainAllChannelsTo(MUTE_DB)
        fx.enabled = true
      }.onFailure { lost(sessionId) }
    }
    return effect
  }

  /** Another effect app took the session: unmute and let the engine fall back for that app. */
  private fun lost(sessionId: Int) {
    synchronized(this) { effects.remove(sessionId)?.let(::release) }
    SeqRuntime.log("DSP mute control lost session=$sessionId")
    onLost(sessionId)
  }

  private companion object { const val MUTE_DB = -96f }
}
