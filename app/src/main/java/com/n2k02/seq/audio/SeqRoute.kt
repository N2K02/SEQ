package com.n2k02.seq.audio

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.n2k02.seq.model.AudioProfile
import com.n2k02.seq.model.AutoSound
import com.n2k02.seq.model.EngineSettings
import com.n2k02.seq.model.RouteKind
import com.n2k02.seq.model.Tone
import com.n2k02.seq.model.SoundModeTuning

/** Pure output classification so it can be unit tested. */
object RoutePolicy {
  fun carProfile(projecting: Boolean, sdk: Int, legacyCarMode: Boolean): Boolean =
    projecting || (sdk < Build.VERSION_CODES.S && legacyCarMode)

  fun <T> mediaOutputs(routed: List<T>, connected: () -> List<T>): List<T> =
    routed.ifEmpty(connected)

  fun kind(carMode: Boolean, outputTypes: List<Int>): RouteKind = when {
    carMode -> RouteKind.CAR
    outputTypes.any { it in WIRED } -> RouteKind.WIRED
    outputTypes.any { it in BLUETOOTH } -> RouteKind.BLUETOOTH
    else -> RouteKind.SPEAKER
  }
  private val WIRED = setOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE)
  private val BLUETOOTH = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_HEARING_AID)
}

/** One route + tone decision for every SEQ path (native DSP, session and all-media effects). */
object SeqRoute {
  /** Fallback for older versions where projection can change the phone UI mode. */
  fun legacyCarMode(context: Context): Boolean = runCatching {
    (context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType ==
      Configuration.UI_MODE_TYPE_CAR
  }.getOrDefault(false)

  fun isCar(): Boolean = SeqPlaybackMonitor.carMode.value

  /** A new device ID can require new tuning even when the output category is unchanged. */
  fun outputKey(context: Context, car: Boolean): Pair<Boolean, List<Int>> =
    car to outputs(context).map { it.id }.sorted()

  fun kind(context: Context, car: Boolean): RouteKind =
    RoutePolicy.kind(car, outputs(context).map { it.type })

  /** Effective SEQ Auto tone; flat in Pure. */
  fun tone(context: Context, s: EngineSettings, car: Boolean): Tone {
    if (s.profile == AudioProfile.PURE) return Tone()
    val volume = if (s.loudness) volumeFraction(context) else 1f
    val adaptive = if (s.adaptive) SeqAdaptiveAnalyzer.correction else Tone()
    val output = kind(context, car)
    val tuned = SoundModeTuning.apply(AutoSound.compose(output, volume, adaptive), s.soundMode)
    return if (output == RouteKind.SPEAKER) tuned.copy(bass = tuned.bass.coerceAtMost(0f)) else tuned
  }

  fun volumeFraction(context: Context): Float = runCatching {
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val min = audio.getStreamMinVolume(AudioManager.STREAM_MUSIC)
    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    if (max <= min) 1f else (audio.getStreamVolume(AudioManager.STREAM_MUSIC) - min).toFloat() / (max - min)
  }.getOrDefault(1f)

  fun describe(context: Context, car: Boolean): String {
    if (car) return "Android Auto"
    val kind = kind(context, false)
    val name = outputs(context).firstOrNull {
      RoutePolicy.kind(false, listOf(it.type)) == kind && kind != RouteKind.SPEAKER
    }?.productName?.toString()?.take(60)
    return name?.let { "$it • ${kind.title}" } ?: kind.title
  }

  private val mediaAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
    .build()

  private fun outputs(context: Context): List<AudioDeviceInfo> = runCatching {
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val routed = if (Build.VERSION.SDK_INT >= 33) {
      runCatching { audio.getAudioDevicesForAttributes(mediaAttributes) }.getOrDefault(emptyList())
    } else emptyList()
    RoutePolicy.mediaOutputs(routed) { audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() }
  }.getOrDefault(emptyList())
}
