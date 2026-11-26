package com.n2k02.seq.model

import kotlin.math.abs

/**
 * 10-band graphic equaliser whose real response matches its sliders.
 *
 * Adjacent one-octave peaking filters overlap, so applying each slider value directly makes
 * neighbouring bands add up (all bands at +6 dB would measure close to +10 dB). The filter gains
 * are therefore solved so the *combined* response at every band centre equals the requested
 * value. Solved once per edit, off the audio thread.
 */
object GraphicEq {
  /** One-octave bands; Q 1.41 keeps the bands smooth and the overlap predictable. */
  const val Q = 1.41
  private const val MAX_FILTER_GAIN = 24.0
  private const val PROBE_GAIN = 6.0
  private const val ITERATIONS = 8

  val centres: List<Double> get() = SystemEqCurveBuilder.centres(SystemEqCurveBuilder.BAND_COUNT)

  /** Peaking filters (same centres/Q every time) whose summed response hits [bands] at the centres. */
  fun filters(bands: List<Float>, sampleRate: Double = 48000.0): List<EqFilter> {
    require(bands.size == SystemEqCurveBuilder.BAND_COUNT) { "Expected 10 bands" }
    val target = DoubleArray(bands.size) { bands[it].toDouble().takeIf(Double::isFinite) ?: 0.0 }
    if (target.all { abs(it) < 0.01 }) return emptyList()
    val n = target.size
    val hz = centres
    // Unit response matrix: response (dB) at centre i of a 1 dB peak at centre j.
    val a = Array(n) { i -> DoubleArray(n) { j ->
      EqResponse.db(hz[i], EqFilter(FilterKind.PEAK, hz[j], PROBE_GAIN, Q), sampleRate) / PROBE_GAIN
    } }
    val gains = target.copyOf()
    repeat(ITERATIONS) {
      val realized = DoubleArray(n) { i ->
        (0 until n).sumOf { j -> EqResponse.db(hz[i], EqFilter(FilterKind.PEAK, hz[j], gains[j], Q), sampleRate) }
      }
      val error = DoubleArray(n) { target[it] - realized[it] }
      if (error.all { abs(it) < 0.005 }) return@repeat
      val step = solve(a, error) ?: return@repeat
      for (i in 0 until n) gains[i] = (gains[i] + step[i]).coerceIn(-MAX_FILTER_GAIN, MAX_FILTER_GAIN)
    }
    return gains.mapIndexed { i, g -> EqFilter(FilterKind.PEAK, hz[i], g, Q) }
      .filter { abs(it.gain) >= 0.005 }
  }

  /** Combined response (dB) of [bands] at [frequencyHz] as the DSP will actually render it. */
  fun responseDb(bands: List<Float>, frequencyHz: Double, sampleRate: Double = 48000.0): Double =
    filters(bands, sampleRate).sumOf { EqResponse.db(frequencyHz, it, sampleRate) }

  /** Gaussian elimination with partial pivoting and a small ridge for conditioning. */
  private fun solve(matrix: Array<DoubleArray>, rhs: DoubleArray): DoubleArray? {
    val n = rhs.size
    val m = Array(n) { i -> DoubleArray(n + 1) { j ->
      when { j == n -> rhs[i]; i == j -> matrix[i][j] + 1e-4; else -> matrix[i][j] }
    } }
    for (col in 0 until n) {
      var pivot = col
      for (r in col + 1 until n) if (abs(m[r][col]) > abs(m[pivot][col])) pivot = r
      if (abs(m[pivot][col]) < 1e-9) return null
      val t = m[col]; m[col] = m[pivot]; m[pivot] = t
      for (r in col + 1 until n) {
        val f = m[r][col] / m[col][col]
        for (c in col..n) m[r][c] -= f * m[col][c]
      }
    }
    val x = DoubleArray(n)
    for (i in n - 1 downTo 0) {
      var sum = m[i][n]
      for (j in i + 1 until n) sum -= m[i][j] * x[j]
      x[i] = sum / m[i][i]
    }
    return x.takeIf { r -> r.all { it.isFinite() } }
  }
}
