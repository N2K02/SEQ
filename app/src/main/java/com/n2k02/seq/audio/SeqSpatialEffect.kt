package com.n2k02.seq.audio

import android.media.audiofx.Virtualizer
import com.n2k02.seq.model.SpatialMode
import kotlin.math.roundToInt

/**
 * Compatibility spatialization for an Android output-mix effect. This is NOT
 * mRadio's PCM crossfeed/reverb/orbit. OEMs may refuse session-0 virtualization,
 * especially on Bluetooth hardware-offloaded output. Never report it as audible
 * without a listener test; log only attachment/control state.
 */
internal class SeqSpatialEffect private constructor(private val fx: Virtualizer) {
  private var applied: Pair<SpatialMode, Int>? = null
  private var closed = false
  val hasControl: Boolean get() = runCatching { fx.hasControl() }.getOrDefault(false)

  fun update(mode: SpatialMode, intensity: Float): Boolean {
    if (closed || !mode.platformSupported || mode == SpatialMode.OFF || !hasControl) return false
    val strength = ((when (mode) {
      SpatialMode.WIDE -> 0.5f
      SpatialMode.HEADPHONE_3D -> 0.8f
      SpatialMode.CINEMA -> 1f
      else -> 0f
    }) * intensity.coerceIn(0f, 1f) * 1000f).roundToInt().coerceIn(0, 1000)
    val target = mode to strength
    if (target == applied) return runCatching { fx.enabled }.getOrDefault(false)
    val result = runCatching {
      if (fx.strengthSupported) fx.setStrength(strength.toShort())
      fx.enabled = true
      fx.hasControl() && fx.enabled
    }.getOrDefault(false)
    if (result) applied = target
    return result
  }

  fun close() {
    if (closed) return
    closed = true
    runCatching { fx.enabled = false }
    runCatching { fx.release() }
  }

  companion object {
    fun open(): SeqSpatialEffect? = runCatching {
      Virtualizer(0, 0).let { virtualizer ->
        if (!virtualizer.hasControl()) {
          virtualizer.release()
          null
        } else SeqSpatialEffect(virtualizer)
      }
    }.getOrNull()
  }
}
