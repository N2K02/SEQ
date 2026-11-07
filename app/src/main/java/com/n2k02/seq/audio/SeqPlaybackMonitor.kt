package com.n2k02.seq.audio

import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.n2k02.seq.model.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow

/** Pure decision: which active players count as "media" SEQ should process. */
object PlaybackPolicy {
  private val mediaUsages = setOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME,
    AudioAttributes.USAGE_UNKNOWN)
  fun isMedia(usage: Int) = usage in mediaUsages
  fun mediaActive(usages: List<Int>) = usages.any(::isMedia)
}

/**
 * Process-wide detector for *any* playing media, without needing notification access or a
 * media session: Android's playback callback reports every active player (all apps, incl.
 * audio projected to Android Auto). Also follows car mode and output-device changes.
 * Each change re-syncs the all-media effect and, when allowed, starts the engine service.
 */
object SeqPlaybackMonitor {
  /** True while any app is playing media/game audio. */
  val mediaActive = MutableStateFlow(false)
  val carMode = MutableStateFlow(false)
  private var app: Context? = null
  private val main = Handler(Looper.getMainLooper())
  private const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
  private const val EXTRA_STREAM = "android.media.EXTRA_VOLUME_STREAM_TYPE"
  private val volumeSync = Runnable {
    resync()
  }

  private val playback = object : AudioManager.AudioPlaybackCallback() {
    override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) =
      update(configs)
  }
  private val devices = object : AudioDeviceCallback() {
    override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = resync()
    override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = resync()
  }
  private val carReceiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      if (intent.action == VOLUME_CHANGED) {
        // Loudness compensation follows media volume; ignore other streams.
        if (intent.getIntExtra(EXTRA_STREAM, -1) != AudioManager.STREAM_MUSIC) return
        if (!SettingsStore(context).read().loudness) return
        main.removeCallbacks(volumeSync)
        main.postDelayed(volumeSync, 150)
        return
      }
      val car = SeqRoute.isCarMode(context)
      if (carMode.value != car) {
        carMode.value = car
        SeqRuntime.log(if (car) "Android Auto / car mode entered" else "Car mode exited")
      }
      resync()
      if (car) startEngine(context)
    }
  }

  /** Idempotent; safe to call from activity, engine service and media listener. */
  @Synchronized fun start(context: Context) {
    if (app != null) return
    val ctx = context.applicationContext
    app = ctx
    val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    runCatching { audio.registerAudioPlaybackCallback(playback, main) }
      .onFailure { SeqRuntime.log("Playback monitor: " + it.javaClass.simpleName) }
    runCatching { audio.registerAudioDeviceCallback(devices, main) }
    val filter = IntentFilter().apply {
      addAction(UiModeManager.ACTION_ENTER_CAR_MODE)
      addAction(UiModeManager.ACTION_EXIT_CAR_MODE)
      addAction(VOLUME_CHANGED)
    }
    runCatching { registerCarReceiver(ctx, filter) }
    carMode.value = SeqRoute.isCarMode(ctx)
    update(runCatching { audio.activePlaybackConfigurations }.getOrDefault(emptyList()))
  }

  @Suppress("UnspecifiedRegisterReceiverFlag")
  private fun registerCarReceiver(ctx: Context, filter: IntentFilter) {
    if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(carReceiver, filter, Context.RECEIVER_EXPORTED)
    else ctx.registerReceiver(carReceiver, filter)
  }

  private fun update(configs: List<AudioPlaybackConfiguration>) {
    val active = PlaybackPolicy.mediaActive(configs.map { it.audioAttributes.usage })
    if (mediaActive.value == active) return
    mediaActive.value = active
    SeqRuntime.log(if (active) "Media playback detected" else "Media playback stopped")
    resync()
    app?.let { SeqAdaptiveAnalyzer.sync(it, active) }
    if (active) app?.let(::startEngine)
  }

  private fun resync() { app?.let(SeqGlobalEffect::sync) }

  /** Best effort: Android can refuse background FGS starts; the effect still runs in-process. */
  private fun startEngine(context: Context) {
    val s = SettingsStore(context).read()
    if (!s.enabled || SeqRuntime.status.value.running) return
    runCatching {
      context.startForegroundService(SeqEngineService.intent(context, SeqEngineService.START))
    }.onFailure { SeqRuntime.log("Engine auto-start deferred: " + it.javaClass.simpleName) }
  }
}
