package com.n2k02.seq.audio

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Playback detection must never be confused with permission to process a player's PCM. */
class SeqMediaDetectionContractTest {
  private fun source(path:String):String {
    val options=listOf(File("src/main/$path"),File("app/src/main/$path"))
    return options.first { it.isFile }.readText()
  }
  @Test fun usesPbarMediaSessionsWithNotificationTokenFallback() {
    val src=source("java/com/n2k02/seq/audio/SeqMediaSessionListener.kt")
    assertTrue(src.contains("getActiveSessions("))
    assertTrue(src.contains("Notification.EXTRA_MEDIA_SESSION"))
    assertTrue(src.contains("distinctBy { it.sessionToken }"))
    assertTrue(src.contains("onPlaybackStateChanged"))
    assertTrue(src.contains("onNotificationRemoved"))
    assertTrue(src.contains("unregisterCallback"))
    assertTrue(src.contains("removeOnActiveSessionsChangedListener"))
    assertFalse(src.contains("Equalizer("))
    assertFalse(src.contains("getSessionId()"))
  }
  @Test fun onlyAndroidEffectBroadcastProvidesExternalEffectId() {
    val engine=source("java/com/n2k02/seq/audio/SeqEngineService.kt")
    assertTrue(engine.contains("AudioEffect.EXTRA_AUDIO_SESSION"))
    assertTrue(engine.contains("SessionPolicy.isExternalSession"))
    assertFalse(engine.contains("Equalizer(0, 0)"))
  }
  @Test fun activationRequiresExplicitNotificationAccessAndShowsActualEffectState() {
    val manifest=source("AndroidManifest.xml")
    val activity=source("java/com/n2k02/seq/MainActivity.kt")
    assertTrue(manifest.contains("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"))
    assertTrue(manifest.contains("SeqMediaSessionListener"))
    // Playback detection and output-mix tuning work without forcing notification settings.
    assertFalse(activity.contains("ACTION_NOTIFICATION_LISTENER_SETTINGS"))
    assertTrue(activity.contains("SeqMediaSessionListener.detectedPlayer.collectAsState()"))
    assertTrue(activity.contains("SeqPlaybackMonitor.mediaActive.collectAsState()"))
  }
  @Test fun playbackChangesDriveAllMediaEffectForPhoneAndAndroidAuto() {
    val listener=source("java/com/n2k02/seq/audio/SeqMediaSessionListener.kt")
    val engine=source("java/com/n2k02/seq/audio/SeqEngineService.kt")
    val global=source("java/com/n2k02/seq/audio/SeqGlobalEffect.kt")
    assertTrue(listener.contains("SeqGlobalEffect.sync(this)"))
    assertTrue(engine.contains("UiModeManager.ACTION_ENTER_CAR_MODE"))
    assertTrue(engine.contains("SeqGlobalEffect.sync(this)"))
    assertTrue(global.contains("SessionPolicy.shouldProcessAllMedia"))
    assertTrue(global.contains("OUTPUT_MIX = 0"))
    val monitor=source("java/com/n2k02/seq/audio/SeqPlaybackMonitor.kt")
    assertTrue(monitor.contains("registerAudioPlaybackCallback"))
    assertTrue(monitor.contains("ACTION_ENTER_CAR_MODE"))
    assertTrue(listener.contains("SeqPlaybackMonitor.start(this)"))
    assertTrue(engine.contains("SeqPlaybackMonitor.start(this)"))
  }
  @Test fun liveDynamicsFailureTriesOemWithoutRecreatingDynamics() {
    val global = source("java/com/n2k02/seq/audio/SeqGlobalEffect.kt")
    val backend = source("java/com/n2k02/seq/audio/SeqSystemEffect.kt")
    assertTrue(global.contains("if (active.limiterAvailable) attach(allowDynamics = false)"))
    assertTrue(backend.contains("if (allowDynamics)"))
    assertTrue(backend.contains("preferred ?: configure(runCatching { equalizer"))
  }

  @Test fun smoothEqChangesKeepHeadroomAndCancelOnShutdown() {
    val backend = source("java/com/n2k02/seq/audio/SeqSystemEffect.kt")
    assertTrue(backend.contains("main.postDelayed(this, 40L)"))
    assertTrue(backend.contains("SystemEqCurveBuilder.transitionGain(programmedCurve, curve)"))
    assertTrue(backend.indexOf("setInputGainAllChannelsTo(interim)") <
      backend.indexOf("dp.setPreEqAllChannelsTo(eq)"))
    assertTrue(backend.contains("closed = true"))
    assertTrue(backend.contains("cancelTransition()"))
    assertTrue(backend.contains("pending?.let { main.removeCallbacks(it); canceledTransitions++ }"))
  }

  @Test fun bluetoothChangesAreDebouncedAndRoutingIsExplicitlyUnverified() {
    val monitor = source("java/com/n2k02/seq/audio/SeqPlaybackMonitor.kt")
    val global = source("java/com/n2k02/seq/audio/SeqGlobalEffect.kt")
    assertTrue(monitor.contains("scheduleRouteSync()"))
    assertTrue(monitor.contains("configs.filter { it.isActive }"))
    assertTrue(monitor.contains("ROUTE_SETTLE_MS = 250L"))
    assertTrue(monitor.contains("main.removeCallbacks(routeSync)"))
    assertTrue(monitor.contains("main.removeCallbacks(playbackSync)"))
    assertTrue(global.contains("Bluetooth offload path unverified"))
    assertTrue(global.contains("lastRouteKey = routeKey"))
  }

  @Test fun adaptiveFailuresHaveBoundedRetriesAndUsefulDiagnostics() {
    val src = source("java/com/n2k02/seq/audio/SeqAdaptiveAnalyzer.kt")
    assertTrue(src.contains("RETRY_MS = 30_000L"))
    assertTrue(src.contains("retryAfterMs = SystemClock.elapsedRealtime() + RETRY_MS"))
    assertTrue(src.contains("Adaptive unavailable phase="))
    assertTrue(src.contains("consecutiveErrors >= 3"))
  }

  @Test fun newEffectDoesNotGetReprogrammedOnFirstSync() {
    val src = source("java/com/n2k02/seq/audio/SeqGlobalEffect.kt")
    assertTrue(src.contains("if (alreadyAttached) active.apply("))
    assertTrue(src.contains("released reason="))
  }

  @Test fun comparisonRequiresAControlledEffectAndExcludesAdaptiveSamples() {
    val global = source("java/com/n2k02/seq/audio/SeqGlobalEffect.kt")
    val analyzer = source("java/com/n2k02/seq/audio/SeqAdaptiveAnalyzer.kt")
    assertTrue(global.contains("!current.hasControl || !current.bypass()"))
    assertTrue(analyzer.contains("generation != comparisonGeneration"))
    assertTrue(analyzer.indexOf("if (generation % 2 != 0)") < analyzer.indexOf("v.getFft(fft)"))
  }
  @Test fun detectedMediaUsesAppIdentityAndKeepsAnUnknownPlayerFallback() {
    val listener = source("java/com/n2k02/seq/audio/SeqMediaSessionListener.kt")
    val activity = source("java/com/n2k02/seq/MainActivity.kt")
    assertTrue(listener.contains("next?.let(::playerIdentity)"))
    assertTrue(listener.contains("getApplicationLabel(it)"))
    assertTrue(listener.contains("getApplicationIcon(it)"))
    assertTrue(activity.contains("externalPlayer?.name ?: if (mediaActive)"))
    assertTrue(activity.contains("Play music to start tuning"))
  }
}
