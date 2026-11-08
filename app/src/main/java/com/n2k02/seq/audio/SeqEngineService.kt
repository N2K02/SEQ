package com.n2k02.seq.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.app.UiModeManager
import android.media.audiofx.AudioEffect
import android.os.Build
import android.os.IBinder
import com.n2k02.seq.MainActivity
import com.n2k02.seq.model.AudioProfile
import com.n2k02.seq.model.EngineSettings
import com.n2k02.seq.model.SettingsStore
import com.n2k02.seq.model.SystemEqCurveBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * User-started foreground engine service.
 * All-media mode (default): drives the output-mix effect ([SeqGlobalEffect]) and
 * updates its tuning on route, car-mode and settings changes.
 * Per-app mode: attaches to positive session IDs announced by Android's opt-in
 * OPEN/CLOSE control broadcasts only.
 */
class SeqEngineService : Service() {
  companion object {
    const val START = "com.n2k02.seq.START"
    const val STOP = "com.n2k02.seq.STOP"
    const val REFRESH = "com.n2k02.seq.REFRESH"
    const val CHANNEL = "seq_engine"
    fun intent(context: Context, action: String) =
      Intent(context, SeqEngineService::class.java).setAction(action)
  }
  private val routeScope = CoroutineScope(Dispatchers.Main.immediate)
  private val effects = LinkedHashMap<Int, SeqSystemEffect>()
  private lateinit var audio: AudioManager
  private lateinit var settings: SettingsStore
  private var receiverRegistered = false
  private var callbackRegistered = false
  private val receiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      when (intent.action) {
        UiModeManager.ACTION_ENTER_CAR_MODE, UiModeManager.ACTION_EXIT_CAR_MODE -> {
          refresh()
          return
        }
      }
      val id = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
      val source = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME)
      if (!SessionPolicy.isExternalSession(id,source,packageName)) return
      when (intent.action) {
        AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> attach(id)
        AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> detach(id)
      }
    }
  }
  private val callback = object : AudioDeviceCallback() {
    override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
    override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
  }
  @Suppress("UnspecifiedRegisterReceiverFlag")
  override fun onCreate() {
    super.onCreate()
    audio = getSystemService(AUDIO_SERVICE) as AudioManager
    settings = SettingsStore(this)
    val filter = IntentFilter().apply {
      addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
      addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
      addAction(UiModeManager.ACTION_ENTER_CAR_MODE)
      addAction(UiModeManager.ACTION_EXIT_CAR_MODE)
    }
    if (Build.VERSION.SDK_INT >= 33) {
      registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
    } else {
      @Suppress("DEPRECATION")
      registerReceiver(receiver, filter)
    }
    receiverRegistered = true
    audio.registerAudioDeviceCallback(callback, null)
    callbackRegistered = true
    SeqPlaybackMonitor.start(this)
    routeScope.launch {
      combine(SeqPlaybackMonitor.carMode, SeqPlaybackMonitor.mediaVolume) { car, volume -> car to volume }
        .collect { if (!settings.read().allMedia) refresh() }
    }
  }
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == STOP || !settings.read().enabled) {
      SeqGlobalEffect.sync(this)
      stopSelf()
      return START_NOT_STICKY
    }
    try {
      val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
      manager.createNotificationChannel(NotificationChannel(CHANNEL, "SEQ audio engine", NotificationManager.IMPORTANCE_LOW))
      startForeground(1001, notification())
      SeqRuntime.status.value = SeqRuntime.status.value.copy(running = true)
      refresh()
    } catch (e: Exception) {
      SeqRuntime.log("Engine start failed: " + e.javaClass.simpleName)
      stopSelf()
    }
    // Restart after low-memory kills so all-media processing resumes without opening the app.
    return START_STICKY
  }
  private fun notification(): Notification {
    val open = PendingIntent.getActivity(
      this, 0, Intent(this, MainActivity::class.java),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
    val text = if (settings.read().allMedia) "All media • Android output mix"
      else "Processing compatible apps"
    return Notification.Builder(this, CHANNEL)
      .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("SEQ")
      .setContentText(text)
      .setOngoing(true).setContentIntent(open).build()
  }
  private fun attach(id: Int) {
    val s = settings.read()
    if (!SessionPolicy.canAttach(s, id, effects.containsKey(id))) return
    val car = SeqRoute.isCar()
    val route = SeqRoute.kind(this, car)
    val bassDb = SystemEqCurveBuilder.bassGain(s, route, SeqRoute.volumeFraction(this))
    val effect = SeqSystemEffect.open(0, id, SeqRoute.tone(this, s, car), s.peakGuard, s.peakCeilingDb,
      settings.routeBands(route) ?: s.manualBands, bassDb, s.preampDb,
      settings.routeCorrection(route), s.compressorEnabled, s.compressorRatio,
      s.compressorThresholdDb)
    if (effect == null) {
      SeqRuntime.log("Session effect unavailable")
      report("Audio effect unavailable")
      return
    }
    effects[id] = effect
    SeqRuntime.log("Effect attached to compatible session (" + effect.engine + ")")
    report("Audio session attached")
  }
  private fun apply(effect: SeqSystemEffect, s: EngineSettings) {
    val car = SeqRoute.isCar()
    val route = SeqRoute.kind(this, car)
    val bassDb = SystemEqCurveBuilder.bassGain(s, route, SeqRoute.volumeFraction(this))
    effect.apply(SeqRoute.tone(this, s, car), s.peakGuard, s.peakCeilingDb,
      settings.routeBands(route) ?: s.manualBands, bassDb, s.preampDb,
      settings.routeCorrection(route), s.compressorEnabled, s.compressorRatio,
      s.compressorThresholdDb)
  }
  private fun detach(id: Int) {
    effects.remove(id)?.close()
    report("Audio session closed")
  }
  private fun refresh() {
    val s = settings.read()
    SeqGlobalEffect.sync(this)
    if (s.profile == AudioProfile.PURE || !SessionPolicy.acceptsSessionBroadcasts(s)) {
      releaseAll()
      report(if (s.profile == AudioProfile.PURE) "Pure bypass" else SeqRuntime.status.value.lastEvent)
      return
    }
    effects.entries.toList().forEach { (id, effect) ->
      try { apply(effect, s) } catch (e: Exception) { detach(id) }
    }
    report(if (effects.isEmpty()) "Waiting for compatible audio session" else "Settings updated")
  }
  private fun releaseAll() {
    effects.values.forEach { it.close() }
    effects.clear()
  }
  private fun report(message: String) {
    val car = SeqRoute.isCar()
    SeqRuntime.status.value = SeqRuntime.status.value.copy(
      running = true, sessions = effects.size, lastEvent = message,
      carProfileActive = car, route = SeqRoute.describe(this, car),
      effectAttached = effects.isNotEmpty(),
      eqResponse = if (SeqRuntime.status.value.globalActive) SeqRuntime.status.value.eqResponse
        else effects.values.singleOrNull()?.response
    )
  }
  override fun onDestroy() {
    routeScope.cancel()
    releaseAll()
    if (receiverRegistered) unregisterReceiver(receiver)
    if (callbackRegistered) audio.unregisterAudioDeviceCallback(callback)
    SeqRuntime.reset()
    super.onDestroy()
  }
  override fun onBind(intent: Intent?): IBinder? = null
}
