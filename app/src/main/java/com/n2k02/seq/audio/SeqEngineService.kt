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
import android.media.audiofx.AudioEffect
import android.media.audiofx.Equalizer
import android.os.Build
import android.os.IBinder
import com.n2k02.seq.MainActivity
import com.n2k02.seq.model.AudioProfile
import com.n2k02.seq.model.EngineSettings
import com.n2k02.seq.model.SettingsStore
import com.n2k02.seq.model.ToneMath
import com.n2k02.seq.model.EqResponse
import com.n2k02.seq.model.HeadphoneProfileStore
import com.n2k02.seq.audio.SeqOwnedAudioSessions

/**
 * User-started foreground session-effects service.
 * No implicit background starts. Sessions are accepted only through Android's opt-in
 * playback session OPEN/CLOSE control broadcasts. Never attaches to global session 0.
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
  private val effects = LinkedHashMap<Int, Equalizer>()
  private lateinit var audio: AudioManager
  private lateinit var settings: SettingsStore
  private var receiverRegistered = false
  private var callbackRegistered = false
  private val receiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
      val id = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
      val source = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME)
      if (!SessionPolicy.isExternalSession(id,source,packageName) ||
          SeqOwnedAudioSessions.contains(id)) return
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
  override fun onCreate() {
    super.onCreate()
    audio = getSystemService(AUDIO_SERVICE) as AudioManager
    settings = SettingsStore(this)
    val filter = IntentFilter().apply {
      addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
      addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
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
  }
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == STOP || !settings.read().enabled) {
      stopSelf()
      return START_NOT_STICKY
    }
    try {
      val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
      manager.createNotificationChannel(NotificationChannel(CHANNEL, "SEQ audio engine", NotificationManager.IMPORTANCE_LOW))
      startForeground(1001, notification())
      SeqRuntime.status.value = SeqRuntime.status.value.copy(running = true, lastEvent = "Waiting for compatible audio session")
      refresh()
    } catch (e: Exception) {
      SeqRuntime.log("Engine start failed: " + e.javaClass.simpleName)
      stopSelf()
    }
    return START_NOT_STICKY
  }
  private fun notification(): Notification {
    val open = PendingIntent.getActivity(
      this, 0, Intent(this, MainActivity::class.java),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
    return Notification.Builder(this, CHANNEL)
      .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("SEQ")
      .setContentText("Audio session processing • compatible apps only")
      .setOngoing(true).setContentIntent(open).build()
  }
  private fun routeInfo(): Pair<Boolean, String> {
    val s = settings.read()
    val outputs = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    val carByName = s.carDeviceName.isNotBlank() && outputs.any {
      it.productName?.toString()?.equals(s.carDeviceName, ignoreCase = true) == true
    }
    val car = s.carManual || (s.automaticCarProfile && carByName)
    val title = if (car) "Car profile (selected)" else outputs.firstOrNull {
      it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_USB_DEVICE
    }?.productName?.toString() ?: "Phone / other output"
    return car to title.take(80)
  }
  private fun attach(id: Int) {
    val s = settings.read()
    if (!SessionPolicy.canAttach(s, id, effects.containsKey(id))) return
    var created: Equalizer? = null
    try {
      val eq = Equalizer(0, id)
      created = eq
      if (!eq.hasControl()) {
        eq.release()
        SeqRuntime.log("Session effect unavailable (control denied)")
        return
      }
      apply(eq, s)
      val rc = eq.setEnabled(true)
      if (rc != AudioEffect.SUCCESS || !eq.enabled) {
        eq.release()
        SeqRuntime.log("Session equalizer could not be enabled")
        return
      }
      effects[id] = eq
      created = null
      SeqRuntime.log("Effect attached to compatible session")
      report("Audio session attached")
    } catch (e: Exception) {
      runCatching { created?.release() }
      SeqRuntime.log("Attach failed: " + e.javaClass.simpleName)
      report("Audio effect unavailable")
    }
  }
  private fun apply(eq: Equalizer, s: EngineSettings) {
    val (car, _) = routeInfo()
    val tone = s.activeTone(car)
    val preset = if (s.headphoneCorrection && !car)
      HeadphoneProfileStore(this).read() else null
    val headroom = EqResponse.headroomDb(tone, preset)
    val range = eq.bandLevelRange
    for (i in 0 until eq.numberOfBands.toInt()) {
      val freq = eq.getCenterFreq(i.toShort()) / 1000
      // Approximation at OEM fixed band centers; native owned playback uses exact filters.
      val millibel = ((EqResponse.totalDb(freq, tone, preset) - headroom) * 100.0)
        .toInt().coerceIn(range[0].toInt(), range[1].toInt()).toShort()
      eq.setBandLevel(i.toShort(), millibel)
    }
  }
  private fun detach(id: Int) {
    effects.remove(id)?.let { runCatching { it.setEnabled(false); it.release() } }
    report("Audio session closed")
  }
  private fun refresh() {
    val s = settings.read()
    if (s.profile == AudioProfile.PURE) {
      releaseAll()
      report("Pure bypass")
      return
    }
    effects.entries.toList().forEach { (id, eq) ->
      try { apply(eq, s) } catch (e: Exception) { detach(id) }
    }
    report("Settings updated")
  }
  private fun releaseAll() {
    effects.values.forEach { runCatching { it.setEnabled(false); it.release() } }
    effects.clear()
  }
  private fun report(message: String) {
    val (car, route) = routeInfo()
    SeqRuntime.status.value = EngineStatus(
      running = true, sessions = effects.size, lastEvent = message,
      carProfileActive = car, route = route, effectAttached = effects.isNotEmpty()
    )
  }
  override fun onDestroy() {
    releaseAll()
    if (receiverRegistered) unregisterReceiver(receiver)
    if (callbackRegistered) audio.unregisterAudioDeviceCallback(callback)
    SeqRuntime.reset()
    super.onDestroy()
  }
  override fun onBind(intent: Intent?): IBinder? = null
}
