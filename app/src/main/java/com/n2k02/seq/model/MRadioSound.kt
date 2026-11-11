package com.n2k02.seq.model

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

/** mRadio sound-mode labels. Every mode runs in the SEQ DSP PCM engine.
 * [platformSupported] marks the modes the Android Virtualizer fallback can also approximate;
 * hall reverb and the moving 8D orbit need SEQ DSP.
 */
enum class SpatialMode(val label: String, val blurb: String, val platformSupported: Boolean) {
  OFF("Off", "Original stereo, without spatial processing.", true),
  WIDE("Wide", "Wider stereo image.", true),
  HEADPHONE_3D("Headphone 3D", "Natural speaker-like crossfeed for headphones.", true),
  HALL("Concert hall", "Concert-hall reverb • SEQ DSP engine.", false),
  CINEMA("Cinema", "Wide stereo with a subtle room.", true),
  ORBIT_8D("8D orbit", "Sound slowly circles your head • SEQ DSP engine.", false)
}

/** 5-band source presets ported from N2K02/MRadio SoundFx.PRESETS.
 * Converted to the single 10-band SEQ graphic EQ from mRadio's real filter response.
 * Respect SEQ's conservative +/-6 dB headroom limit and never install 5-band
 * arrays directly into SettingsStore.
 */
object MRadioPresets {
  private val sourceHz = listOf(60.0, 230.0, 910.0, 3600.0, 14000.0)
  private val sources = linkedMapOf(
    "Flat" to listOf(0f, 0f, 0f, 0f, 0f),
    "Bass boost" to listOf(6f, 4f, 0f, 0f, 0f),
    "Vocal" to listOf(-2f, 0f, 3f, 3f, 0f),
    "Pop" to listOf(-1f, 2f, 4f, 2f, -1f),
    "Rock" to listOf(4f, 2f, -1f, 3f, 4f),
    "Dance" to listOf(6f, 3f, 0f, 2f, 3f),
    "Hip-hop" to listOf(5f, 3f, 0f, 1f, 3f),
    "Jazz" to listOf(3f, 2f, -2f, 2f, 4f),
    "Classical" to listOf(4f, 3f, -2f, 3f, 4f),
    "Treble boost" to listOf(0f, 0f, 0f, 3f, 6f)
  )
  val names: List<String> get() = sources.keys.toList()
  fun bands(name: String): List<Float>? = sources[name]?.let(::resample)
  fun matching(selected: List<Float>?): String? =
    if (selected == null) null else names.firstOrNull { name ->
      val preset = bands(name) ?: return@firstOrNull false
      preset.size == selected.size && preset.indices.all { i -> kotlin.math.abs(preset[i] - selected[i]) < 0.01f }
    }

  /** mRadio's engine: RBJ peaking filters, Q 0.9, at 48 kHz. */
  private const val SOURCE_Q = 0.9
  private const val FS = 48000.0

  /**
   * SEQ's 10 band gains = mRadio's actual summed filter response at each SEQ centre.
   * (Interpolating the slider values instead over-boosted the extremes, e.g. Bass boost
   * gave +6 dB at 31 Hz where mRadio gives about +3 dB.)
   */
  private fun resample(source: List<Float>): List<Float> =
    SystemEqCurveBuilder.centres(SystemEqCurveBuilder.BAND_COUNT).map { hz ->
      val gain = sourceResponseDb(source, hz)
      (kotlin.math.round(gain * 2.0) / 2.0).toFloat().coerceIn(-SystemEqCurveBuilder.MANUAL_LIMIT_DB,
        SystemEqCurveBuilder.MANUAL_LIMIT_DB)
    }

  /** Magnitude in dB of mRadio's cascaded peaking filters at [hz]. */
  internal fun sourceResponseDb(source: List<Float>, hz: Double): Double {
    val w = 2 * PI * hz / FS
    // z^-1 and z^-2 on the unit circle.
    val c1 = cos(w); val s1 = -sin(w)
    val c2 = cos(2 * w); val s2 = -sin(2 * w)
    var db = 0.0
    source.forEachIndexed { i, g ->
      if (abs(g) < 0.1f) return@forEachIndexed
      val a = 10.0.pow(g / 40.0)
      val w0 = 2 * PI * sourceHz[i] / FS
      val alpha = sin(w0) / (2 * SOURCE_Q)
      val k = -2 * cos(w0)
      val nr = (1 + alpha * a) + k * c1 + (1 - alpha * a) * c2
      val ni = k * s1 + (1 - alpha * a) * s2
      val dr = (1 + alpha / a) + k * c1 + (1 - alpha / a) * c2
      val di = k * s1 + (1 - alpha / a) * s2
      db += 10 * log10((nr * nr + ni * ni) / (dr * dr + di * di))
    }
    return db
  }
}
