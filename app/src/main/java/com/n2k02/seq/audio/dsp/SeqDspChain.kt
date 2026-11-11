package com.n2k02.seq.audio.dsp

import com.n2k02.seq.audio.SeqHallPcm
import com.n2k02.seq.audio.SeqOrbitPcm
import com.n2k02.seq.model.EqFilter
import com.n2k02.seq.model.FilterKind
import com.n2k02.seq.model.SpatialMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * RBJ biquad coefficients, identical maths to [com.n2k02.seq.model.EqResponse] so the
 * graph, the platform fallback and the PCM engine describe the same curve.
 * Returns normalised b0, b1, b2, a1, a2.
 */
object SeqBiquad {
  fun coefficients(filter: EqFilter, sampleRate: Int): DoubleArray {
    if (!filter.enabled || filter.gain == 0.0) return doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0)
    val f = filter.frequency.coerceIn(10.0, sampleRate * 0.475)
    val w = 2.0 * PI * f / sampleRate
    val c = cos(w)
    val s = sin(w)
    val a = 10.0.pow(filter.gain / 40.0)
    val alpha = s / (2.0 * filter.q)
    val root = sqrt(a)
    val al = s * sqrt(2.0) / 2.0
    val b0: Double; val b1: Double; val b2: Double
    val a0: Double; val a1: Double; val a2: Double
    when (filter.kind) {
      FilterKind.PEAK -> {
        b0 = 1 + alpha * a; b1 = -2 * c; b2 = 1 - alpha * a
        a0 = 1 + alpha / a; a1 = -2 * c; a2 = 1 - alpha / a
      }
      FilterKind.LOW_SHELF -> {
        b0 = a * ((a + 1) - (a - 1) * c + 2 * root * al)
        b1 = 2 * a * ((a - 1) - (a + 1) * c)
        b2 = a * ((a + 1) - (a - 1) * c - 2 * root * al)
        a0 = (a + 1) + (a - 1) * c + 2 * root * al
        a1 = -2 * ((a - 1) + (a + 1) * c)
        a2 = (a + 1) + (a - 1) * c - 2 * root * al
      }
      FilterKind.HIGH_SHELF -> {
        b0 = a * ((a + 1) + (a - 1) * c + 2 * root * al)
        b1 = -2 * a * ((a - 1) + (a + 1) * c)
        b2 = a * ((a + 1) + (a - 1) * c - 2 * root * al)
        a0 = (a + 1) - (a - 1) * c + 2 * root * al
        a1 = 2 * ((a - 1) - (a + 1) * c)
        a2 = (a + 1) - (a - 1) * c - 2 * root * al
      }
    }
    return doubleArrayOf(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
  }
}

/**
 * Immutable processing plan, prepared off the audio thread. The audio thread only swaps
 * the reference, so a settings change never allocates or locks inside the render loop.
 */
class SeqDspPlan(
  val sampleRate: Int,
  /** Packed [b0,b1,b2,a1,a2] × filters. */
  val coefficients: DoubleArray,
  val preGain: Float,
  val spatial: SpatialMode,
  val spatialStrength: Float,
  val compressor: Boolean,
  val compressorRatio: Float,
  val compressorThresholdDb: Float,
  val limiter: Boolean,
  val ceiling: Float,
  /** Volume leveller: slow gain riding that keeps quiet and loud apps closer together. */
  val leveler: Boolean = false,
  val description: String = ""
) {
  init {
    require(sampleRate in 8000..192000)
    require(coefficients.size % 5 == 0 && coefficients.size / 5 <= SeqDspChain.MAX_FILTERS)
    require(coefficients.all { it.isFinite() } && preGain.isFinite() && preGain in 0f..4f)
    require(ceiling.isFinite() && ceiling in 0.1f..1f)
  }
  val filterCount: Int get() = coefficients.size / 5

  companion object {
    fun passthrough(sampleRate: Int) = SeqDspPlan(sampleRate, DoubleArray(0), 1f,
      SpatialMode.OFF, 0f, false, 1f, -18f, limiter = true, ceiling = 0.98f, description = "Flat")
  }
}

/**
 * SEQ DSP: the default PCM engine, modelled after RootlessBrennanDSP's capture → DSP → render
 * design (independently written; no GPL code). Signal path, stereo interleaved float:
 * fade/pre-gain → parametric EQ → 3D/spatial → compressor → leveller → lookahead limiter → hard
 * clamp (anti-clipping output protection). No allocation, locks or logging per sample.
 */
class SeqDspChain(val sampleRate: Int) {
  companion object {
    const val MAX_FILTERS = 24
    private const val FADE_SECONDS = 0.05
  }
  init { require(sampleRate in 8000..192000) }

  @Volatile private var pending: SeqDspPlan = SeqDspPlan.passthrough(sampleRate)
  private var plan: SeqDspPlan = pending
  fun update(next: SeqDspPlan) {
    require(next.sampleRate == sampleRate) { "Plan sample rate mismatch" }
    pending = next
  }
  val current: SeqDspPlan get() = pending

  // Biquad state (direct form I in double precision for low-frequency accuracy).
  private val x1 = DoubleArray(MAX_FILTERS * 2)
  private val x2 = DoubleArray(MAX_FILTERS * 2)
  private val y1 = DoubleArray(MAX_FILTERS * 2)
  private val y2 = DoubleArray(MAX_FILTERS * 2)

  // Smoothed pre-gain and start fade.
  private var gain = 0f
  private val gainCoef = (1.0 - exp(-1.0 / (0.01 * sampleRate))).toFloat()
  private var fade = 0f
  private val fadeStep = (1.0 / (FADE_SECONDS * sampleRate)).toFloat()

  // Headphone 3D crossfeed (Bauer-style): delayed, low-passed opposite channel.
  private val crossDelay = FloatArray(128)
  private var crossIndex = 0
  private val crossDelayFrames = (sampleRate * 0.0003).toInt().coerceIn(1, 63)
  private val crossLpA = (1.0 - exp(-2.0 * PI * 700.0 / sampleRate)).toFloat()
  private var crossLpL = 0f
  private var crossLpR = 0f

  private val hall = SeqHallPcm(sampleRate)
  private val orbit = SeqOrbitPcm(sampleRate)

  // Compressor and leveller envelopes.
  private var compEnv = 0f
  private val compAttack = exp(-1.0 / (0.010 * sampleRate)).toFloat()
  private val compRelease = exp(-1.0 / (0.150 * sampleRate)).toFloat()
  private var levelEnv = 0.01f
  private var levelGain = 1f
  private val levelCoef = exp(-1.0 / (3.0 * sampleRate)).toFloat()
  private val levelGainCoef = exp(-1.0 / (1.5 * sampleRate)).toFloat()

  // Lookahead peak limiter (~1.5 ms) with hold and smooth release.
  private val lookahead = (sampleRate * 0.0015).toInt().coerceIn(16, 512)
  private val delayL = FloatArray(lookahead)
  private val delayR = FloatArray(lookahead)
  private var delayIndex = 0
  private var peakEnv = 0f
  private var peakHold = 0
  private var limGain = 1f
  private val limAttack = (1.0 - exp(-4.6 / lookahead)).toFloat()
  private val limRelease = (1.0 - exp(-1.0 / (0.08 * sampleRate))).toFloat()
  private val peakRelease = exp(-1.0 / (0.05 * sampleRate)).toFloat()

  /** Highest absolute output sample since the last call; for tests and status. */
  var lastPeak = 0f
    private set
  var clampedSamples = 0L
    private set

  /** Reset all state and restart the fade-in (start, route change, recovery). */
  fun reset() {
    x1.fill(0.0); x2.fill(0.0); y1.fill(0.0); y2.fill(0.0)
    crossDelay.fill(0f); crossIndex = 0; crossLpL = 0f; crossLpR = 0f
    hall.reset(); orbit.reset()
    compEnv = 0f; levelEnv = 0.01f; levelGain = 1f
    delayL.fill(0f); delayR.fill(0f); delayIndex = 0
    peakEnv = 0f; peakHold = 0; limGain = 1f
    fade = 0f
    gain = pending.preGain
  }

  init { reset() }

  /** Process interleaved stereo float PCM in place. */
  fun process(buffer: FloatArray, offset: Int = 0, frames: Int = (buffer.size - offset) / 2) {
    require(offset >= 0 && offset % 2 == 0 && frames >= 0 &&
      offset.toLong() + frames.toLong() * 2 <= buffer.size.toLong())
    if (frames == 0) return
    val p = pending
    if (p !== plan) {
      // Different filter count: clear only now-unused state so a new filter starts clean.
      if (p.filterCount != plan.filterCount) {
        for (i in p.filterCount * 2 until MAX_FILTERS * 2) { x1[i] = 0.0; x2[i] = 0.0; y1[i] = 0.0; y2[i] = 0.0 }
      }
      if (p.spatial != plan.spatial) { hall.reset(); orbit.reset() }
      if (p.limiter != plan.limiter) {
        // Never replay stale lookahead audio after the limiter is switched back on.
        delayL.fill(0f); delayR.fill(0f); delayIndex = 0
        peakEnv = 0f; peakHold = 0; limGain = 1f
      }
      plan = p
    }
    gainAndFade(buffer, offset, frames, p.preGain)
    equalize(buffer, offset, frames, p)
    spatialize(buffer, offset, frames, p)
    if (p.compressor && p.compressorRatio > 1f) compress(buffer, offset, frames, p)
    if (p.leveler) level(buffer, offset, frames)
    limit(buffer, offset, frames, p)
  }

  private fun gainAndFade(b: FloatArray, offset: Int, frames: Int, target: Float) {
    var g = gain
    var f = fade
    for (i in 0 until frames) {
      g += (target - g) * gainCoef
      if (f < 1f) f = minOf(1f, f + fadeStep)
      val k = g * f * f // squared ramp: smooth, no click on start
      val j = offset + i * 2
      b[j] *= k
      b[j + 1] *= k
    }
    gain = g
    fade = f
  }

  private fun equalize(b: FloatArray, offset: Int, frames: Int, p: SeqDspPlan) {
    val c = p.coefficients
    for (filter in 0 until p.filterCount) {
      val k = filter * 5
      val b0 = c[k]; val b1 = c[k + 1]; val b2 = c[k + 2]; val a1 = c[k + 3]; val a2 = c[k + 4]
      for (ch in 0..1) {
        val s = filter * 2 + ch
        var xa = x1[s]; var xb = x2[s]; var ya = y1[s]; var yb = y2[s]
        var j = offset + ch
        for (i in 0 until frames) {
          val x = b[j].toDouble()
          var y = b0 * x + b1 * xa + b2 * xb - a1 * ya - a2 * yb
          if (abs(y) < 1e-25) y = 0.0 // flush denormals
          xb = xa; xa = x; yb = ya; ya = y
          b[j] = y.toFloat()
          j += 2
        }
        x1[s] = xa; x2[s] = xb; y1[s] = ya; y2[s] = yb
      }
    }
  }

  private fun spatialize(b: FloatArray, offset: Int, frames: Int, p: SeqDspPlan) {
    val strength = p.spatialStrength.coerceIn(0f, 1f)
    when (p.spatial) {
      SpatialMode.OFF -> return
      SpatialMode.WIDE -> widen(b, offset, frames, 1f + 0.6f * strength)
      SpatialMode.HEADPHONE_3D -> crossfeed(b, offset, frames, 0.12f + 0.33f * strength)
      SpatialMode.HALL -> {
        hall.wet = 0.08f + 0.22f * strength
        hall.decay = 0.55f + 0.2f * strength
        hall.process(b, offset, frames)
      }
      SpatialMode.CINEMA -> {
        widen(b, offset, frames, 1.2f + 0.3f * strength)
        hall.wet = 0.05f + 0.07f * strength
        hall.decay = 0.5f
        hall.process(b, offset, frames)
      }
      SpatialMode.ORBIT_8D -> {
        orbit.strength = 0.35f + 0.6f * strength
        orbit.process(b, offset, frames)
      }
    }
  }

  /** Mid/side widening; mono content is untouched. */
  private fun widen(b: FloatArray, offset: Int, frames: Int, width: Float) {
    for (i in 0 until frames) {
      val j = offset + i * 2
      val mid = (b[j] + b[j + 1]) * 0.5f
      val side = (b[j] - b[j + 1]) * 0.5f * width
      b[j] = mid + side
      b[j + 1] = mid - side
    }
  }

  private fun crossfeed(b: FloatArray, offset: Int, frames: Int, amount: Float) {
    val norm = 1f / (1f + amount)
    val mask = crossDelay.size / 2 - 1 // 64 stereo frames
    for (i in 0 until frames) {
      val j = offset + i * 2
      val l = b[j]
      val r = b[j + 1]
      crossLpL += crossLpA * (l - crossLpL)
      crossLpR += crossLpA * (r - crossLpR)
      val w = (crossIndex and mask) * 2
      crossDelay[w] = crossLpL
      crossDelay[w + 1] = crossLpR
      val rd = ((crossIndex - crossDelayFrames) and mask) * 2
      val fromL = crossDelay[rd]
      val fromR = crossDelay[rd + 1]
      b[j] = (l + fromR * amount) * norm
      b[j + 1] = (r + fromL * amount) * norm
      crossIndex = (crossIndex + 1) and mask
    }
  }

  private fun compress(b: FloatArray, offset: Int, frames: Int, p: SeqDspPlan) {
    val threshold = 10.0.pow(p.compressorThresholdDb / 20.0).toFloat()
    val exponent = 1f - 1f / p.compressorRatio
    var env = compEnv
    for (i in 0 until frames) {
      val j = offset + i * 2
      val level = maxOf(abs(b[j]), abs(b[j + 1]))
      env = if (level > env) compAttack * env + (1f - compAttack) * level
        else compRelease * env + (1f - compRelease) * level
      if (env > threshold) {
        val g = (threshold / env).pow(exponent)
        b[j] *= g
        b[j + 1] *= g
      }
    }
    compEnv = env
  }

  /** Slow RMS leveller toward −20 dBFS (power 0.01), limited to ±6 dB, frozen on silence. */
  private fun level(b: FloatArray, offset: Int, frames: Int) {
    val target = 0.1f
    var env = levelEnv
    var g = levelGain
    for (i in 0 until frames) {
      val j = offset + i * 2
      val power = (b[j] * b[j] + b[j + 1] * b[j + 1]) * 0.5f
      if (power > 1e-6f) env = levelCoef * env + (1f - levelCoef) * power
      val wanted = (target / sqrt(env.coerceAtLeast(1e-6f))).coerceIn(0.5f, 2f)
      g = levelGainCoef * g + (1f - levelGainCoef) * wanted
      b[j] *= g
      b[j + 1] *= g
    }
    levelEnv = env
    levelGain = g
  }

  private fun limit(b: FloatArray, offset: Int, frames: Int, p: SeqDspPlan) {
    val ceiling = p.ceiling
    var peak = 0f
    var clamped = 0L
    for (i in 0 until frames) {
      val j = offset + i * 2
      val inL = b[j]
      val inR = b[j + 1]
      var outL: Float
      var outR: Float
      if (p.limiter) {
        val level = maxOf(abs(inL), abs(inR))
        if (level >= peakEnv) { peakEnv = level; peakHold = lookahead }
        else if (peakHold > 0) peakHold--
        else peakEnv = maxOf(level, peakEnv * peakRelease)
        val target = if (peakEnv > ceiling) ceiling / peakEnv else 1f
        limGain += (target - limGain) * if (target < limGain) limAttack else limRelease
        outL = delayL[delayIndex] * limGain
        outR = delayR[delayIndex] * limGain
        delayL[delayIndex] = inL
        delayR[delayIndex] = inR
        delayIndex++
        if (delayIndex == lookahead) delayIndex = 0
      } else {
        outL = inL
        outR = inR
      }
      // Anti-clipping output protection: never emit outside full scale or NaN.
      if (outL.isNaN()) outL = 0f
      if (outR.isNaN()) outR = 0f
      if (outL > 1f || outL < -1f) { outL = outL.coerceIn(-1f, 1f); clamped++ }
      if (outR > 1f || outR < -1f) { outR = outR.coerceIn(-1f, 1f); clamped++ }
      b[j] = outL
      b[j + 1] = outR
      val a = maxOf(abs(outL), abs(outR))
      if (a > peak) peak = a
    }
    lastPeak = peak
    clampedSamples += clamped
  }
}
