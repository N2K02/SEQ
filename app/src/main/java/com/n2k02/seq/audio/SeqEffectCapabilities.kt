package com.n2k02.seq.audio

import android.media.audiofx.AudioEffect
import android.media.audiofx.PresetReverb
import android.media.audiofx.EnvironmentalReverb
import android.media.audiofx.Virtualizer
import kotlinx.coroutines.flow.MutableStateFlow

/** Best-effort, read-only inventory of effects implemented by the OEM.
 * Presence never proves that another application's audio is routed through it.
 */
object SeqEffectCapabilities {
  val summary = MutableStateFlow("Not checked")
  fun inspect(): String {
    val descriptors = runCatching { AudioEffect.queryEffects()?.toList().orEmpty() }.getOrDefault(emptyList())
    val lines = descriptors.map { descriptor ->
      val name = descriptor.name.orEmpty().take(48)
      val impl = descriptor.implementor.orEmpty().take(36)
      "$name [$impl]"
    }.distinct().sorted().take(30)
    val reverb = descriptors.any { it.type == AudioEffect.EFFECT_TYPE_PRESET_REVERB ||
      it.type == AudioEffect.EFFECT_TYPE_ENV_REVERB }
    val virtualizer = descriptors.any { it.type == AudioEffect.EFFECT_TYPE_VIRTUALIZER }
    val result = "Android effect descriptors=${descriptors.size} · reverb=$reverb · virtualizer=$virtualizer\n" +
      lines.joinToString("\n") +
      "\nPresence is not proof of a routed or audible output-mix effect"
    summary.value = result
    SeqRuntime.log("Effect inventory descriptors=${descriptors.size} reverb=$reverb virtualizer=$virtualizer")
    return result
  }

  /**
   * Explicit, non-persistent reverb capability probe. PresetReverb is an AUX
   * effect; playback applications must call attachAuxEffect/setAuxEffectSendLevel.
   * ADB grants do not supply those calls for external player instances.
   */
  fun probeAuxReverb(): String {
    val result = runCatching {
      val reverb = PresetReverb(0, 0)
      try {
        val control = reverb.hasControl()
        "Auxiliary reverb created=$control · external-player send NOT configured"
      } finally {
        reverb.release()
      }
    }.getOrElse { "Auxiliary reverb unavailable: ${it.javaClass.simpleName}" }
    SeqRuntime.log("Reverb probe: $result")
    return result
  }
}
