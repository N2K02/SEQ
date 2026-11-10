package com.n2k02.seq.audio

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Consent-based, read-only PCM compatibility probe based on RootlessBrennanDSP's
 * official AudioPlaybackCapture path. It NEVER creates an AudioTrack, never
 * replays, stores or uploads PCM and never changes another app's capture policy.
 *
 * Android 14+ MediaProjection tokens are single-use; every test requires fresh
 * user consent. Stop at explicit request, projection revocation or onDestroy.
 */
class SeqPcmCaptureProbeService : Service() {
  companion object {
    const val START = "com.n2k02.seq.PCM_CAPTURE_PROBE_START"
    const val STOP = "com.n2k02.seq.PCM_CAPTURE_PROBE_STOP"
    const val EXTRA_CAPTURE_DATA = "capture_result_data"
    private const val CHANNEL = "seq_pcm_probe"
    private const val NOTIFICATION_ID = 1042
    val status = MutableStateFlow("Capture probe idle · no audio is recorded")
  }

  private var projection: MediaProjection? = null
  private var recorder: AudioRecord? = null
  private var thread: Thread? = null
  private val running = AtomicBoolean(false)
  private var callback: MediaProjection.Callback? = null

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == STOP) {
      stopSelf()
      return START_NOT_STICKY
    }
    if (intent?.action != START || running.get()) return START_NOT_STICKY
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      status.value = "Audio recording permission required"
      stopSelf()
      return START_NOT_STICKY
    }
    val result: Intent? = if (Build.VERSION.SDK_INT >= 33)
      intent.getParcelableExtra(EXTRA_CAPTURE_DATA, Intent::class.java)
    else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_CAPTURE_DATA)
    if (result == null) {
      status.value = "MediaProjection consent required"
      stopSelf()
      return START_NOT_STICKY
    }
    try {
      val nm = getSystemService(NotificationManager::class.java)
      nm.createNotificationChannel(NotificationChannel(CHANNEL,
        "SEQ PCM compatibility probe", NotificationManager.IMPORTANCE_LOW))
      val notification = Notification.Builder(this, CHANNEL)
        .setContentTitle("SEQ · PCM capture test")
        .setContentText("Read-only media capture test · no sound replay")
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setOngoing(true)
        .build()
      startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
      val manager = getSystemService(MediaProjectionManager::class.java)
      val ownedProjection = manager.getMediaProjection(Activity.RESULT_OK, result)
      projection = ownedProjection
      callback = object : MediaProjection.Callback() {
        override fun onStop() {
          status.value = "MediaProjection revoked · capture stopped"
          stopSelf()
        }
      }.also { ownedProjection.registerCallback(it, Handler(Looper.getMainLooper())) }
      val capture = AudioPlaybackCaptureConfiguration.Builder(ownedProjection)
        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
        .addMatchingUsage(AudioAttributes.USAGE_GAME)
        .build()
      val format = AudioFormat.Builder()
        .setSampleRate(48000)
        .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
        .build()
      val minimum = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_STEREO,
        AudioFormat.ENCODING_PCM_FLOAT)
      if (minimum <= 0) error("Unsupported PCM capture format")
      val bufferSize = maxOf(minimum, 4096 * 4)
      val audioRecord = AudioRecord.Builder()
        .setAudioFormat(format)
        .setBufferSizeInBytes(bufferSize)
        .setAudioPlaybackCaptureConfig(capture)
        .build()
      if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
        audioRecord.release()
        error("PCM capture initialization failed")
      }
      recorder = audioRecord
      running.set(true)
      status.value = "Capture authorized · waiting for compatible media"
      thread = Thread({ sampleLoop(audioRecord) }, "SEQ-PCM-Capture-Probe").apply {
        isDaemon = true
        start()
      }
    } catch (error: Exception) {
      status.value = "Capture unavailable · " + error.javaClass.simpleName
      SeqRuntime.log("PCM capture probe failed: " + error.javaClass.simpleName)
      stopSelf()
    }
    return START_NOT_STICKY
  }

  private fun sampleLoop(record: AudioRecord) {
    Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
    val samples = FloatArray(2048)
    var blocks = 0
    var samplesSeen = 0L
    try {
      record.startRecording()
      while (running.get() && !Thread.currentThread().isInterrupted) {
        val count = record.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
        if (count <= 0) {
          if (count < 0) { status.value = "PCM capture read error=$count"; break }
          continue
        }
        var sum = 0.0
        var peak = 0f
        for (index in 0 until count) {
          val amplitude = abs(samples[index])
          sum += amplitude.toDouble() * amplitude
          peak = maxOf(peak, amplitude)
        }
        samplesSeen += count / 2
        blocks++
        // Low frequency, bounded telemetry; no samples leave this service.
        if (blocks % 24 == 0) {
          val rms = sqrt(sum / count)
          val message = "PCM detected · rate=48 kHz · peak=${"%.3f".format(peak)}" +
            " · RMS=${"%.3f".format(rms)} · frames=$samplesSeen" +
            " · original playback NOT suppressed"
          status.value = message
          SeqRuntime.log(message)
        }
      }
    } catch (error: Exception) {
      status.value = "PCM capture stopped · " + error.javaClass.simpleName
    } finally {
      running.set(false)
    }
  }

  override fun onDestroy() {
    running.set(false)
    runCatching { recorder?.stop() }
    runCatching { thread?.interrupt() }
    runCatching { thread?.join(150) }
    runCatching { recorder?.release() }
    recorder = null
    thread = null
    val active = projection
    projection = null
    callback?.let { c -> runCatching { active?.unregisterCallback(c) } }
    callback = null
    runCatching { active?.stop() }
    status.value = "PCM capture stopped · normal audio unchanged"
    super.onDestroy()
  }
}
