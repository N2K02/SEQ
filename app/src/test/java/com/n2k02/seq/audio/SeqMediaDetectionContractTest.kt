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
    assertTrue(engine.contains("SeqOwnedAudioSessions.contains"))
    assertFalse(engine.contains("Equalizer(0, 0)"))
  }
  @Test fun activationRequiresExplicitNotificationAccessAndShowsActualEffectState() {
    val manifest=source("AndroidManifest.xml")
    val activity=source("java/com/n2k02/seq/MainActivity.kt")
    assertTrue(manifest.contains("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"))
    assertTrue(manifest.contains("SeqMediaSessionListener"))
    assertTrue(activity.contains("ACTION_NOTIFICATION_LISTENER_SETTINGS"))
    assertTrue(activity.contains("SeqMediaSessionListener.detectedPlayer.collectAsState()"))
    assertTrue(activity.contains("!status.effectAttached"))
  }
}
