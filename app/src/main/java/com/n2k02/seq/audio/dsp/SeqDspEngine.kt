package com.n2k02.seq.audio.dsp

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioPlaybackConfiguration
import android.media.AudioRecord
import android.media.AudioRouting
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import com.n2k02.seq.audio.SeqPcmRingBuffer
import com.n2k02.seq.audio.SeqRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import kotlin.math.abs

/** What the Home screen and notification show about the PCM engine. */
data class SeqDspStatus(
  val running: Boolean = false,
  val processing: Boolean = false,
  val sampleRate: Int = 0,
  val apps: Int = 0,
  val fallbackSessions: Int = 0,
  val latencyMs: Int = 0,
  val detail: String = "SEQ DSP off"
)

/** Pure per-session routing decision, unit tested. */
object SeqDspSessionPolicy {
  data class Decision(
    /** Sessions whose direct output is muted and replayed through SEQ DSP. */
    val mute: Set<Int>,
    /** Sessions SEQ DSP cannot capture: tuned with Android's per-session effect instead. */
    val fallback: Set<Int>,
    /** Distinct apps processed by SEQ DSP. */
    val apps: Set<Int>,
    /** At least one processed session is actively playing. */
    val playing: Boolean
  )

  const val FIRST_APP_UID = 10000

  fun decide(players: List<SeqPlayback>, ownUid: Int, excludedUids: Set<Int>,
    uidCapturable: (Int) -> Boolean): Decision {
    val mute = HashSet<Int>()
    val fallback = HashSet<Int>()
    val apps = HashSet<Int>()
    var playing = false
    players.forEach { p ->
      if (!p.mediaUsage || p.uid == ownUid || p.uid < FIRST_APP_UID) return@forEach
      if (p.captureBlockedByPlayer || p.uid in excludedUids || !uidCapturable(p.uid)) {
        fallback += p.sessionId
      } else {
        mute += p.sessionId
        apps += p.uid
        if (p.started) playing = true
      }
    }
    return Decision(mute, fallback - mute, apps, playing)
  }

  /** No captured signal at all since the mute, while the player reports "started". */
  fun captureSilent(mutedAtMs: Long, nowMs: Long, lastSignalMs: Long, started: Boolean): Boolean =
    started && nowMs - mutedAtMs in WATCHDOG_MIN_MS..WATCHDOG_MAX_MS && lastSignalMs < mutedAtMs

  const val WATCHDOG_MIN_MS = 3_500L
  const val WATCHDOG_MAX_MS = 15_000L
}

/**
 * SEQ's default sound engine, modelled on RootlessBrennanDSP: Android playback capture
 * (MediaProjection) → bounded SPSC ring → [SeqDspChain] → AudioTrack, with each captured
 * app's direct output muted per session. Real-time threads, bounded latency, idle suspend.
 *
 * Owned by [com.n2k02.seq.audio.SeqEngineService]; stop() restores every app's direct sound.
 */
class SeqDspEngine(
  context: Context,
  private val projection: MediaProjection,
  private val listener: Listener
) {
  interface Listener {
    /** Called on the engine's session thread. */
    fun onFallbackSessions(sessions: Set<Int>)
    fun onStopped(reason: String)
  }

  companion object {
    val status = MutableStateFlow(SeqDspStatus())
    private const val SCAN_DEBOUNCE_MS = 120L
  }

  private val app = context.applicationContext
  private val audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
  private val ownUid = Process.myUid()
  val sampleRate: Int = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
    .takeIf { it in 8000..192000 } ?: 48000
  private val burstFrames = (audio.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
    ?.toIntOrNull() ?: 256).coerceIn(128, 512)
  private val chain = SeqDspChain(sampleRate)
  private val ring = SeqPcmRingBuffer(8192)

  private val running = AtomicBoolean(false)
  @Volatile private var idle = true
  @Volatile private var recreateRecorder = false
  @Volatile private var flushRequested = false
  @Volatile private var lastSignalMs = 0L
  private var captureThread: Thread? = null
  private var renderThread: Thread? = null

  private val sessionThread = HandlerThread("SEQ-DSP-Sessions").apply { start() }
  private val sessions = Handler(sessionThread.looper)
  private val main = Handler(Looper.getMainLooper())
  private val muter = SeqSessionMuter { sid -> sessions.post { onMuteLost(sid) } }
  /** Apps that could not be captured during this run (mute lost, silent capture). */
  private val excludedUids = HashSet<Int>()
  @Volatile private var captureExcludes: Set<Int> = emptySet()
  /** When a muted session was first seen playing; the silent-capture watchdog uses this. */
  private val mutedAt = HashMap<Int, Long>()
  private val sessionUid = HashMap<Int, Int>()
  private var lastPlayers: List<SeqPlayback> = emptyList()
  private var lastFallback: Set<Int> = emptySet()
  private val capturableCache = HashMap<Int, Boolean>()
  private var packagePolicy: Map<String, Boolean>? = null

  private val projectionCallback = object : MediaProjection.Callback() {
    override fun onStop() { stop("Android stopped audio capture") }
  }
  private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
    override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) =
      requestScan()
  }
  private val scanRunnable = Runnable { scan() }
  private val watchdogRunnable = Runnable { watchdog() }

  fun update(inputs: SeqDspInputs) {
    chain.update(SeqDspPlanner.plan(sampleRate, inputs))
  }

  fun start(): Boolean {
    if (!running.compareAndSet(false, true)) return true
    projection.registerCallback(projectionCallback, main)
    if (!SeqServiceDumper.granted(app)) {
      stop("DUMP permission missing")
      return false
    }
    runCatching { audio.registerAudioPlaybackCallback(playbackCallback, sessions) }
    captureExcludes = setOf(ownUid)
    val recorder = runCatching { buildRecorder(captureExcludes) }.getOrElse {
      SeqRuntime.log("SEQ DSP recorder failed: ${it.javaClass.simpleName}")
      stop("Playback capture unavailable")
      return false
    }
    val track = runCatching { buildTrack() }.getOrElse {
      recorder.release()
      SeqRuntime.log("SEQ DSP output failed: ${it.javaClass.simpleName}")
      stop("Audio output unavailable")
      return false
    }
    startThreads(recorder, track)
    publish("Starting")
    sessions.post(scanRunnable)
    SeqRuntime.log("SEQ DSP started rate=$sampleRate burst=$burstFrames")
    return true
  }

  fun requestScan() {
    if (!running.get()) return
    sessions.removeCallbacks(scanRunnable)
    sessions.postDelayed(scanRunnable, SCAN_DEBOUNCE_MS)
  }

  fun stop(reason: String) {
    if (!running.getAndSet(false)) return
    runCatching { audio.unregisterAudioPlaybackCallback(playbackCallback) }
    sessions.removeCallbacksAndMessages(null)
    // Restore direct sound before tearing down replay to avoid a gap of silence.
    muter.releaseAll()
    captureThread?.interrupt()
    renderThread?.interrupt()
    runCatching { captureThread?.join(300) }
    runCatching { renderThread?.join(300) }
    captureThread = null
    renderThread = null
    ring.stop()
    runCatching { projection.unregisterCallback(projectionCallback) }
    runCatching { projection.stop() }
    sessionThread.quitSafely()
    status.value = SeqDspStatus(detail = "SEQ DSP off • $reason")
    SeqRuntime.log("SEQ DSP stopped reason=$reason")
    main.post { listener.onStopped(reason) }
  }

  val isRunning: Boolean get() = running.get()

  // ---- Session discovery (session thread) ----

  private fun scan() {
    if (!running.get()) return
    val audioDump = SeqServiceDumper.dump(app, "audio")
    if (audioDump == null) {
      SeqRuntime.log("SEQ DSP session dump unavailable")
      return
    }
    if (packagePolicy == null) {
      packagePolicy = SeqServiceDumper.dump(app, "media.audio_policy")
        ?.let(SeqSessionDumpParser::parseCapturePolicy).orEmpty()
    }
    val players = SeqSessionDumpParser.parseAudioService(audioDump)
    lastPlayers = players
    val decision = SeqDspSessionPolicy.decide(players, ownUid, excludedUids, ::uidCapturable)
    val now = SystemClock.elapsedRealtime()
    val failed = HashSet<Int>()
    decision.mute.forEach { sid ->
      val uid = players.first { it.sessionId == sid }.uid
      sessionUid[sid] = uid
      if (sid !in muter.sessions) {
        if (!muter.mute(sid)) failed += uid
      }
      // Arm the watchdog when playback starts, not when a paused session was muted.
      val started = players.any { it.sessionId == sid && it.started }
      if (started && sid in muter.sessions && sid !in mutedAt) {
        mutedAt[sid] = now
        sessions.removeCallbacks(watchdogRunnable)
        sessions.postDelayed(watchdogRunnable, SeqDspSessionPolicy.WATCHDOG_MIN_MS + 200)
      } else if (!started) mutedAt.remove(sid)
    }
    muter.retain(decision.mute)
    mutedAt.keys.retainAll(muter.sessions)
    sessionUid.keys.retainAll(decision.mute)
    if (!running.get()) {
      // stop() raced with this scan: never leave an app muted.
      muter.releaseAll()
      return
    }
    if (failed.isNotEmpty()) {
      // Could not silence the direct output: exclude from capture so nothing doubles.
      exclude(failed, "mute unavailable")
      return
    }
    idle = !decision.playing
    val fallback = decision.fallback
    if (fallback != lastFallback) {
      lastFallback = fallback
      listener.onFallbackSessions(fallback)
    }
    publish(if (idle) "Waiting for music" else "Processing")
  }

  private fun watchdog() {
    if (!running.get()) return
    val now = SystemClock.elapsedRealtime()
    val silentUids = mutedAt.filter { (sid, at) ->
      val started = lastPlayers.any { it.sessionId == sid && it.started }
      SeqDspSessionPolicy.captureSilent(at, now, lastSignalMs, started)
    }.keys.mapNotNull(sessionUid::get).toSet()
    if (silentUids.isNotEmpty()) exclude(silentUids, "no capturable signal")
    else if (mutedAt.values.any { now - it < SeqDspSessionPolicy.WATCHDOG_MAX_MS })
      sessions.postDelayed(watchdogRunnable, 2_000)
  }

  private fun onMuteLost(sessionId: Int) {
    val uid = sessionUid[sessionId] ?: return
    exclude(setOf(uid), "another effect app took control")
  }

  /** Unmute, stop capturing and hand these apps to the Android effect fallback. */
  private fun exclude(uids: Set<Int>, reason: String) {
    if (!running.get()) return
    excludedUids += uids
    muter.sessions.filter { sessionUid[it] in uids }.forEach {
      muter.unmute(it)
      mutedAt.remove(it)
    }
    captureExcludes = excludedUids + ownUid
    recreateRecorder = true
    SeqRuntime.log("SEQ DSP fallback apps=${uids.size} reason=$reason")
    sessions.post(scanRunnable)
  }

  private fun uidCapturable(uid: Int): Boolean = capturableCache.getOrPut(uid) {
    val pm = app.packageManager
    val packages = pm.getPackagesForUid(uid).orEmpty()
    if (packages.isEmpty()) return@getOrPut true
    packages.all { pkg ->
      packagePolicy?.get(pkg) ?: runCatching {
        // Apps targeting Android 9 or lower are not capturable unless they opt in.
        pm.getApplicationInfo(pkg, 0).targetSdkVersion >= Build.VERSION_CODES.Q
      }.getOrDefault(true)
    }
  }

  private fun publish(state: String) {
    val muted = muter.sessions
    val apps = muted.mapNotNull(sessionUid::get).toSet().size
    val latency = (ring.availableFrames() + burstFrames * 2) * 1000 / sampleRate
    status.value = SeqDspStatus(
      running = running.get(), processing = running.get() && !idle && muted.isNotEmpty(),
      sampleRate = sampleRate, apps = apps, fallbackSessions = lastFallback.size,
      latencyMs = latency,
      detail = "SEQ DSP • ${sampleRate / 1000.0} kHz • $state")
  }

  // ---- Audio threads ----

  private fun startThreads(initialRecorder: AudioRecord, track: AudioTrack) {
    val chunk = burstFrames
    val routeChanged = AtomicBoolean(false)
    var routedDevice: Int? = null
    val routing = AudioRouting.OnRoutingChangedListener { router ->
      // Start/resume also reports the initial route; only a different device needs a flush.
      val id = router.routedDevice?.id
      if (routedDevice != null && id != null && id != routedDevice) routeChanged.set(true)
      if (id != null) routedDevice = id
    }
    track.addOnRoutingChangedListener(routing, main)

    captureThread = Thread({
      Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
      var recorder = initialRecorder
      val buffer = FloatArray(chunk * 2)
      try {
        while (running.get()) {
          if (recreateRecorder) {
            recreateRecorder = false
            runCatching { recorder.stop() }
            recorder.release()
            recorder = buildRecorder(captureExcludes)
            flushRequested = true
          }
          if (idle) {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            Thread.sleep(40)
            continue
          }
          if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            recorder.startRecording()
            flushRequested = true
          }
          val read = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
          if (read <= 0) {
            if (read == AudioRecord.ERROR_DEAD_OBJECT || read == AudioRecord.ERROR_INVALID_OPERATION) {
              // audioserver restarted or the recorder was invalidated: rebuild it.
              recreateRecorder = true
            } else if (read < 0) Thread.sleep(5)
            continue
          }
          val frames = read / 2
          var peak = 0f
          for (i in 0 until frames * 2) { val a = abs(buffer[i]); if (a > peak) peak = a }
          if (peak > 1e-5f) lastSignalMs = SystemClock.elapsedRealtime()
          ring.offer(buffer, 0, frames) // full ring drops newest: render trims backlog
        }
      } catch (interrupted: InterruptedException) {
      } catch (failure: Exception) {
        SeqRuntime.log("SEQ DSP capture stopped: ${failure.javaClass.simpleName}")
        main.post { stop("Capture error") }
      } finally {
        runCatching { recorder.stop() }
        runCatching { recorder.release() }
      }
    }, "SEQ-DSP-Capture").apply { start() }

    renderThread = Thread({
      Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
      val buffer = FloatArray(chunk * 2)
      var primed = false
      var blocks = 0L
      try {
        while (running.get()) {
          if (flushRequested || routeChanged.getAndSet(false)) {
            flushRequested = false
            ring.flush()
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) { track.pause(); track.flush() }
            chain.reset()
            primed = false
          }
          if (idle) {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) { track.pause(); track.flush() }
            if (primed) { chain.reset(); primed = false; ring.flush() }
            Thread.sleep(40)
            continue
          }
          if (!primed) {
            // Pre-roll two bursts to absorb scheduling jitter without adding much latency.
            if (ring.availableFrames() < chunk * 2) { LockSupport.parkNanos(1_000_000); continue }
            primed = true
            track.play()
          }
          if (ring.availableFrames() < chunk) { LockSupport.parkNanos(1_000_000); continue }
          ring.poll(buffer, 0, chunk)
          chain.process(buffer, 0, chunk)
          var offset = 0
          while (offset < buffer.size && running.get()) {
            val written = track.write(buffer, offset, buffer.size - offset, AudioTrack.WRITE_BLOCKING)
            if (written < 0) error("track write $written") // dead output → stop and fall back
            if (written == 0) break
            offset += written
          }
          blocks++
          // Clock-drift control: never let the backlog grow past ~6 bursts.
          if ((blocks and 63L) == 0L) {
            if (ring.availableFrames() > chunk * 6) ring.trimTo(chunk * 3)
            if ((blocks and 1023L) == 0L) sessions.post { if (running.get()) publish(if (idle) "Waiting for music" else "Processing") }
          }
        }
      } catch (interrupted: InterruptedException) {
      } catch (failure: Exception) {
        SeqRuntime.log("SEQ DSP render stopped: ${failure.javaClass.simpleName}")
        main.post { stop("Output error") }
      } finally {
        runCatching { track.removeOnRoutingChangedListener(routing) }
        runCatching { track.pause(); track.flush(); track.stop() }
        runCatching { track.release() }
      }
    }, "SEQ-DSP-Render").apply { start() }
  }

  @SuppressLint("MissingPermission")
  private fun buildRecorder(excludes: Set<Int>): AudioRecord {
    val config = AudioPlaybackCaptureConfiguration.Builder(projection)
      .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
      .addMatchingUsage(AudioAttributes.USAGE_GAME)
      .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
    excludes.forEach { config.excludeUid(it) }
    val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
      .setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
    val minimum = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_STEREO,
      AudioFormat.ENCODING_PCM_FLOAT)
    val size = maxOf(minimum * 2, burstFrames * 8 * 4)
    val record = AudioRecord.Builder().setAudioFormat(format).setBufferSizeInBytes(size)
      .setAudioPlaybackCaptureConfig(config.build()).build()
    if (record.state != AudioRecord.STATE_INITIALIZED) {
      record.release()
      error("recorder not initialized")
    }
    return record
  }

  private fun buildTrack(): AudioTrack {
    val attributes = AudioAttributes.Builder()
      .setUsage(AudioAttributes.USAGE_MEDIA)
      .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
      // Never re-capture SEQ's own output (no feedback loop).
      .setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE)
    if (Build.VERSION.SDK_INT >= 32) {
      // SEQ DSP already renders its own 3D; skip Android's spatializer stage.
      attributes.setSpatializationBehavior(AudioAttributes.SPATIALIZATION_BEHAVIOR_NEVER)
    }
    val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
      .setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build()
    val minimum = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO,
      AudioFormat.ENCODING_PCM_FLOAT)
    val size = maxOf(minimum * 2, burstFrames * 8 * 4)
    val track = AudioTrack.Builder().setAudioAttributes(attributes.build()).setAudioFormat(format)
      .setTransferMode(AudioTrack.MODE_STREAM)
      .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)
      .setBufferSizeInBytes(size).build()
    if (track.state != AudioTrack.STATE_INITIALIZED) {
      track.release()
      error("track not initialized")
    }
    return track
  }
}
