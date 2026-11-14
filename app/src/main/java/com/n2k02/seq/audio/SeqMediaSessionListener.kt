package com.n2k02.seq.audio

import com.n2k02.seq.adb.LocalAdbDiagnostics
import android.app.Notification
import android.content.ComponentName
import android.graphics.Bitmap
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.flow.MutableStateFlow

data class DetectedPlayer(val packageName: String, val name: String, val icon: Bitmap?)

/**
 * PBar-style media-session discovery. Media tokens never expose an audio-effect session ID,
 * so playback changes drive the output-mix effect ([SeqGlobalEffect]) instead.
 */
class SeqMediaSessionListener : NotificationListenerService() {
  companion object {
    val detectedPlayer = MutableStateFlow<DetectedPlayer?>(null)
    val connected = MutableStateFlow(false)
  }
  private val handler = Handler(Looper.getMainLooper())
  private lateinit var manager:MediaSessionManager
  private val controllers = linkedMapOf<MediaSession.Token,Pair<MediaController,MediaController.Callback>>()
  private val fromNotifications = linkedMapOf<String,MediaController>()
  private var refreshing = false
  private val refreshTask = Runnable { refreshing=false; refresh() }
  private val changed = MediaSessionManager.OnActiveSessionsChangedListener { scheduleRefresh() }

  override fun onListenerConnected() {
    super.onListenerConnected()
    connected.value=true
    manager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
    runCatching {
      manager.addOnActiveSessionsChangedListener(changed,
        ComponentName(this,SeqMediaSessionListener::class.java),handler)
    }.onFailure { SeqRuntime.log("Media listener registration: "+it.javaClass.simpleName) }
    runCatching { activeNotifications.orEmpty().forEach(::capture) }
    // System-bound listener: re-establish all-media processing after boot / process restart.
    SeqPlaybackMonitor.start(this)
    SeqGlobalEffect.sync(this)
    refresh()
  }

  @Suppress("DEPRECATION")
  private fun capture(sbn:StatusBarNotification) {
    val token=sbn.notification.extras?.getParcelable<MediaSession.Token>(
      Notification.EXTRA_MEDIA_SESSION)
    if(token!=null) runCatching { MediaController(this,token) }.onSuccess {
      fromNotifications[sbn.key]=it
    }
  }

  override fun onNotificationPosted(sbn:StatusBarNotification) {
    capture(sbn)
    scheduleRefresh()
  }
  override fun onNotificationRemoved(sbn:StatusBarNotification) {
    fromNotifications.remove(sbn.key)
    scheduleRefresh()
  }

  private fun scheduleRefresh() {
    if(refreshing || !connected.value) return
    refreshing=true
    handler.postDelayed(refreshTask,100)
  }

  private fun refresh() {
    if(!connected.value) return
    val available=runCatching {
      manager.getActiveSessions(ComponentName(this,SeqMediaSessionListener::class.java))
    }.onFailure { SeqRuntime.log("Media session query: "+it.javaClass.simpleName) }
      .getOrDefault(emptyList())
    val list=(available+fromNotifications.values).distinctBy { it.sessionToken }
    val tokens=list.map { it.sessionToken }.toSet()
    controllers.keys.filter { it !in tokens }.toList().forEach { token ->
      controllers.remove(token)?.let { (controller,callback) ->
        runCatching { controller.unregisterCallback(callback) }
      }
    }
    list.forEach { candidate ->
      if(candidate.sessionToken !in controllers) {
        val callback=object:MediaController.Callback() {
          override fun onPlaybackStateChanged(state:PlaybackState?) { scheduleRefresh() }
          // New track: let SEQ Auto re-measure its balance (it pauses once settled).
          override fun onMetadataChanged(metadata:android.media.MediaMetadata?) {
            SeqAdaptiveAnalyzer.onTrackChanged(this@SeqMediaSessionListener)
          }
          override fun onSessionDestroyed() {
            fromNotifications.entries.removeAll { it.value.sessionToken==candidate.sessionToken }
            scheduleRefresh()
          }
        }
        runCatching { candidate.registerCallback(callback,handler) }
          .onSuccess { controllers[candidate.sessionToken]=candidate to callback }
      }
    }
    val playing=controllers.values.map { it.first }.firstOrNull {
      it.packageName!=packageName &&
        it.playbackState?.state in listOf(PlaybackState.STATE_PLAYING,
          PlaybackState.STATE_BUFFERING,PlaybackState.STATE_FAST_FORWARDING,
          PlaybackState.STATE_REWINDING)
    }
    val next=playing?.packageName
    if (detectedPlayer.value?.packageName != next) {
      detectedPlayer.value = next?.let(::playerIdentity)
      SeqRuntime.log(if(next==null) "No external media session playing"
        else "External media player detected: "+next.take(100))
      onPlaybackChanged()
    }
  }

  private fun playerIdentity(packageId: String): DetectedPlayer {
    val manager = packageManager
    val info = runCatching { manager.getApplicationInfo(packageId, 0) }.getOrNull()
    val name = info?.let { runCatching { manager.getApplicationLabel(it).toString() }.getOrNull() }
      ?.takeIf { it.isNotBlank() } ?: packageId
    val icon = info?.let {
      runCatching { manager.getApplicationIcon(it).toBitmap(width = 96, height = 96) }.getOrNull()
    }
    return DetectedPlayer(packageId, name, icon)
  }

  /** Session-level change (names the app); the playback monitor covers start/stop + engine. */
  private fun onPlaybackChanged() {
    LocalAdbDiagnostics.active?.inspectPlayerChange()
    SeqGlobalEffect.sync(this)
  }

  private fun cleanup() {
    connected.value=false
    handler.removeCallbacksAndMessages(null)
    refreshing=false
    if(::manager.isInitialized) runCatching {
      manager.removeOnActiveSessionsChangedListener(changed)
    }
    controllers.values.forEach { (controller,callback) ->
      runCatching { controller.unregisterCallback(callback) }
    }
    controllers.clear()
    fromNotifications.clear()
    detectedPlayer.value=null
  }
  override fun onListenerDisconnected() {
    cleanup()
    super.onListenerDisconnected()
  }
  override fun onDestroy() {
    cleanup()
    super.onDestroy()
  }
}
