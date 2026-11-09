package com.n2k02.seq.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.n2k02.seq.model.AutoSound
import com.n2k02.seq.model.SettingsStore
import com.n2k02.seq.model.Tone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.hypot
import kotlin.math.log10

/** Pure spectrum maths, unit-tested: FFT bytes → octave levels (dB). */
object SpectrumMath {
  /** Android Visualizer FFT layout: [Rf0, Rf(n/2), Rf1, If1, Rf2, If2, ...]. */
  fun octaveDb(fft: ByteArray, samplingRateHz: Double, centres: DoubleArray): DoubleArray? {
    val n = fft.size
    if (n < 64 || samplingRateHz <= 0) return null
    val power = DoubleArray(centres.size)
    val count = IntArray(centres.size)
    for (k in 1 until n / 2) {
      val hz = k * samplingRateHz / n
      val band = centres.indexOfFirst { hz >= it / 1.4142 && hz < it * 1.4142 }
      if (band < 0) continue
      val m = hypot(fft[2 * k].toDouble(), fft[2 * k + 1].toDouble())
      power[band] += m * m
      count[band]++
    }
    if (count.any { it == 0 }) return null
    // 8-bit capture: bands averaging below ~half an LSB are not measurable (silence/quiet
    // passage) — skip rather than mistake quantization for missing bass or treble.
    if ((power.indices).any { power[it] / count[it] < 0.25 }) return null
    return DoubleArray(centres.size) { 10.0 * log10(power[it] / count[it] + 1e-3) }
  }
}

/**
 * SEQ Auto's adaptive stage: listens to the mixed output spectrum (never the microphone,
 * never recorded or stored) at a low rate while media plays, and slowly steers the balance
 * toward a natural music spectrum. Off when nothing plays, in Pure, or without permission.
 */
object SeqAdaptiveAnalyzer {
  @Volatile var correction: Tone = Tone(); private set
  val state = MutableStateFlow(Tone())
  private const val SAMPLE_MS = 500L
  private const val RETRY_MS = 30_000L
  private const val DECIDE_EVERY = 8 // ~4 s of averaged spectrum per decision
  private val main = Handler(Looper.getMainLooper())
  private var visualizer: Visualizer? = null
  private var app: Context? = null
  private var sum: DoubleArray? = null
  private var frames = 0
  private var smoothed = Tone()
  private var comparisonGeneration = 0
  private var outputKey: Pair<Boolean, List<Int>>? = null
  private var fft = ByteArray(0)
  private var retryAfterMs = 0L
  private var consecutiveErrors = 0

  fun permitted(context: Context) =
    context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

  /** Idempotent: runs only while media plays in SEQ Auto with adaptive allowed. */
  @Synchronized fun sync(context: Context, mediaActive: Boolean) {
    val ctx = context.applicationContext
    app = ctx
    val store = SettingsStore(ctx)
    val s = store.read()
    val car = SeqRoute.isCar()
    val hasOutputPreset = store.routeBands(SeqRoute.kind(ctx, car)) != null
    val want = AutoSound.shouldAnalyze(s, mediaActive, hasOutputPreset, permitted(ctx))
    if (!want) { stop(); return }
    val key = SeqRoute.outputKey(ctx, car)
    if (key != outputKey) {
      resetCorrection()
      outputKey = key
    }
    if (visualizer != null || SystemClock.elapsedRealtime() < retryAfterMs) return
    var candidate: Visualizer? = null
    var phase = "create"
    visualizer = runCatching {
      val analyzer = Visualizer(0)
      candidate = analyzer
      phase = "capture-size"
      val sizes = Visualizer.getCaptureSizeRange()
      analyzer.captureSize = sizes.last().coerceAtMost(4096).coerceAtLeast(sizes.first())
      phase = "scaling"
      analyzer.scalingMode = Visualizer.SCALING_MODE_NORMALIZED
      phase = "enable"
      check(analyzer.setEnabled(true) == Visualizer.SUCCESS) { "Visualizer rejected enable" }
      fft = ByteArray(analyzer.captureSize)
      analyzer
    }.onFailure {
      runCatching { candidate?.release() }
      retryAfterMs = SystemClock.elapsedRealtime() + RETRY_MS
      SeqRuntime.log("Adaptive unavailable phase=$phase error=${it.javaClass.simpleName} retry=30s")
    }.getOrNull()
    if (visualizer != null) main.postDelayed(tick, SAMPLE_MS)
  }

  @Synchronized fun stop() {
    main.removeCallbacks(tick)
    visualizer?.let {
      runCatching { it.setEnabled(false) }
      runCatching { it.release() }
    }
    visualizer = null
    fft = ByteArray(0)
    consecutiveErrors = 0
    outputKey = null
    resetCorrection()
  }

  private fun resetCorrection() {
    sum = null
    frames = 0
    smoothed = Tone()
    correction = Tone()
    state.value = Tone()
  }

  private val tick = Runnable { sample() }

  @Synchronized private fun sample() {
    val v = visualizer ?: return
    val generation = SeqGlobalEffect.comparisonGeneration
    if (generation != comparisonGeneration) {
      comparisonGeneration = generation
      sum = null
      frames = 0
    }
    if (generation % 2 != 0) {
      main.postDelayed(tick, SAMPLE_MS)
      return
    }
    val result = runCatching {
      if (v.getFft(fft) == Visualizer.SUCCESS) {
        consecutiveErrors = 0
        SpectrumMath.octaveDb(fft, v.samplingRate / 1000.0, AutoSound.OCTAVES)?.let { db ->
          val acc = sum ?: DoubleArray(db.size).also { sum = it }
          for (i in db.indices) acc[i] += db[i]
          frames++
        }
      } else {
        consecutiveErrors++
      }
    }
    if (result.isFailure) consecutiveErrors++
    if (consecutiveErrors >= 3) {
      SeqRuntime.log("Adaptive capture interrupted error=${result.exceptionOrNull()?.javaClass?.simpleName ?: "capture-status"} retry=30s")
      retryAfterMs = SystemClock.elapsedRealtime() + RETRY_MS
      stop()
      return
    }
    if (frames >= DECIDE_EVERY) decide()
    main.postDelayed(tick, SAMPLE_MS)
  }

  private fun decide() {
    val acc = sum ?: return
    val avg = DoubleArray(acc.size) { acc[it] / frames }
    sum = null; frames = 0
    val target = AutoSound.adapt(avg) ?: return
    smoothed = AutoSound.smooth(smoothed, target)
    if (AutoSound.significant(correction, smoothed)) {
      correction = smoothed
      state.value = smoothed
      app?.let(SeqGlobalEffect::sync)
    }
  }
}
