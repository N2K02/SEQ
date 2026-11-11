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
import android.content.pm.ServiceInfo
import android.media.audiofx.AudioEffect
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import com.n2k02.seq.MainActivity
import com.n2k02.seq.audio.dsp.SeqDspEngine
import com.n2k02.seq.audio.dsp.SeqDspInputs
import com.n2k02.seq.audio.dsp.SeqDspPlanner
import com.n2k02.seq.audio.dsp.SeqDspRuntime
import com.n2k02.seq.model.RouteKind
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
 * Default: hosts SEQ DSP ([SeqDspEngine]) once Android's capture consent is available —
 * every capturable app is muted per session, processed in PCM and replayed. Apps that block
 * capture get a per-session Android effect. Without SEQ DSP (no consent / no DUMP grant),
 * the output-mix effect ([SeqGlobalEffect]) is the automatic fallback.
 * Per-app mode: attaches to positive session IDs announced by Android's opt-in
 * OPEN/CLOSE control broadcasts only.
 */
class SeqEngineService : Service() {
  companion object {
    const val START = "com.n2k02.seq.START"
    const val STOP = "com.n2k02.seq.STOP"
    const val REFRESH = "com.n2k02.seq.REFRESH"
    const val CHANNEL = "seq_engine"
    /** MediaProjection consent result (Intent) from [MainActivity]. */
    const val EXTRA_PROJECTION = "com.n2k02.seq.PROJECTION_DATA"
    const val EXTRA_RESUME_DSP = "com.n2k02.seq.RESUME_DSP"
    fun intent(context: Context, action: String) =
      Intent(context, SeqEngineService::class.java).setAction(action)
    fun dspIntent(context: Context, consent: Intent) =
      intent(context, START).putExtra(EXTRA_PROJECTION, consent)
  }
  private var dsp: SeqDspEngine? = null
  /** Sessions SEQ DSP cannot capture: Android per-session effects (no double processing). */
  private val fallbackEffects = LinkedHashMap<Int, SeqSystemEffect>()
  private val main = android.os.Handler(android.os.Looper.getMainLooper())
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
    SeqDspRuntime.retune = { main.post { updateDsp() } }
    routeScope.launch {
      combine(SeqPlaybackMonitor.carMode, SeqPlaybackMonitor.mediaVolume) { car, volume -> car to volume }
        .collect { if (dsp != null || !settings.read().allMedia) refresh() }
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
      val consent = projectionData(intent)
      val projectionForeground = consent != null && dsp == null && runCatching {
        // Android 14+: the mediaProjection FGS type must be active before getMediaProjection().
        startForeground(1001, notification(dspActive = true), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
          ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
      }.onFailure { SeqRuntime.log("SEQ DSP foreground refused: " + it.javaClass.simpleName) }.isSuccess
      if (projectionForeground) {
        startDsp(consent!!)
      } else if (dsp == null) {
        val resume = SeqDspRuntime.wanted(settings.read()) && SeqDspRuntime.readiness(this).ready
        startForeground(1001, notification(resume = resume), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
      }
      SeqRuntime.status.value = SeqRuntime.status.value.copy(running = true)
      refresh()
    } catch (e: Exception) {
      SeqRuntime.log("Engine start failed: " + e.javaClass.simpleName)
      stopSelf()
    }
    // Restart after low-memory kills so all-media processing resumes without opening the app.
    return START_STICKY
  }
  @Suppress("DEPRECATION")
  private fun projectionData(intent: Intent?): Intent? =
    if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(EXTRA_PROJECTION, Intent::class.java)
    else intent?.getParcelableExtra(EXTRA_PROJECTION)

  private fun startDsp(consent: Intent) {
    val projection = runCatching {
      (getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager)
        .getMediaProjection(android.app.Activity.RESULT_OK, consent)
    }.getOrNull()
    if (projection == null) {
      SeqRuntime.log("SEQ DSP consent unusable; Android effect fallback")
      downgradeForeground(resume = true)
      return
    }
    val engine = SeqDspEngine(this, projection, object : SeqDspEngine.Listener {
      override fun onFallbackSessions(sessions: Set<Int>) { main.post { syncFallback(sessions) } }
      override fun onStopped(reason: String) { onDspStopped(reason) }
    })
    engine.update(dspInputs(settings.read()))
    dsp = engine
    SeqDspRuntime.engine = engine
    if (!engine.start()) return
    // Release the output-mix effect: SEQ DSP now processes every capturable app.
    SeqGlobalEffect.sync(this)
    report("SEQ DSP engine active")
  }

  private fun onDspStopped(reason: String) {
    if (dsp == null) return
    dsp = null
    SeqDspRuntime.engine = null
    releaseFallback()
    // Restore the automatic Android effect immediately so sound stays tuned.
    SeqGlobalEffect.sync(this)
    val s = settings.read()
    if (s.enabled) downgradeForeground(resume = SeqDspRuntime.wanted(s))
    report("Android effect fallback • $reason")
  }

  private fun downgradeForeground(resume: Boolean) {
    runCatching {
      startForeground(1001, notification(resume = resume), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }
  }

  private fun dspInputs(s: EngineSettings): SeqDspInputs {
    val car = SeqRoute.isCar()
    val route = SeqRoute.kind(this, car)
    return SeqDspInputs(
      tone = SeqRoute.tone(this, s, car),
      manualBands = settings.routeBands(route) ?: s.manualBands,
      bassDb = SystemEqCurveBuilder.bassGain(s, route, SeqRoute.volumeFraction(this)),
      preampDb = s.preampDb,
      correction = settings.routeCorrection(route),
      peakGuard = s.peakGuard,
      ceilingDb = s.peakCeilingDb,
      spatial = s.spatialMode,
      spatialStrength = s.spatialIntensity,
      compressor = s.compressorEnabled,
      compressorRatio = s.compressorRatio,
      compressorThresholdDb = s.compressorThresholdDb,
      // Even volume is automatic in the car: road noise masks quiet passages.
      leveler = s.leveler || route == RouteKind.CAR,
      eqBypass = HuaweiNativeEq.activeFor(this)
    )
  }

  private fun updateDsp() {
    val engine = dsp ?: return
    val inputs = dspInputs(settings.read())
    runCatching { engine.update(inputs) }
      .onFailure { SeqRuntime.log("SEQ DSP plan rejected: " + it.javaClass.simpleName) }
    SeqRuntime.status.value = SeqRuntime.status.value.copy(eqResponse = SeqDspPlanner.response(inputs, engine.sampleRate))
    fallbackEffects.values.forEach { effect -> runCatching { apply(effect, settings.read()) } }
  }

  private fun syncFallback(sessions: Set<Int>) {
    if (dsp == null) return
    fallbackEffects.keys.filter { it !in sessions }.forEach { fallbackEffects.remove(it)?.close() }
    val s = settings.read()
    sessions.filter { it !in fallbackEffects }.forEach { id ->
      val car = SeqRoute.isCar()
      val route = SeqRoute.kind(this, car)
      SeqSystemEffect.open(0, id, SeqRoute.tone(this, s, car), s.peakGuard, s.peakCeilingDb,
        settings.routeBands(route) ?: s.manualBands,
        SystemEqCurveBuilder.bassGain(s, route, SeqRoute.volumeFraction(this)), s.preampDb,
        settings.routeCorrection(route), s.compressorEnabled, s.compressorRatio,
        s.compressorThresholdDb)?.let { fallbackEffects[id] = it }
    }
    if (sessions.isNotEmpty()) SeqRuntime.log("SEQ DSP fallback effects=${fallbackEffects.size}/${sessions.size}")
  }

  private fun releaseFallback() {
    fallbackEffects.values.forEach { it.close() }
    fallbackEffects.clear()
  }

  private fun notification(dspActive: Boolean = dsp != null, resume: Boolean = false): Notification {
    val open = PendingIntent.getActivity(
      this, 0, Intent(this, MainActivity::class.java).putExtra(EXTRA_RESUME_DSP, resume),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
    val text = when {
      dspActive -> "SEQ DSP engine • all media"
      resume -> "Android effect • tap to resume SEQ DSP"
      settings.read().allMedia -> "All media • Android output mix"
      else -> "Processing compatible apps"
    }
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
    if (dsp != null && !SeqDspRuntime.wanted(s)) dsp?.stop("SEQ DSP turned off")
    SeqGlobalEffect.sync(this)
    updateDsp()
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
    SeqDspRuntime.retune = null
    dsp?.let { engine ->
      dsp = null
      SeqDspRuntime.engine = null
      engine.stop("Engine stopped")
    }
    releaseFallback()
    SeqGlobalEffect.sync(this)
    releaseAll()
    if (receiverRegistered) unregisterReceiver(receiver)
    if (callbackRegistered) audio.unregisterAudioDeviceCallback(callback)
    SeqRuntime.reset()
    super.onDestroy()
  }
  override fun onBind(intent: Intent?): IBinder? = null
}
