package com.n2k02.seq.model

import kotlin.math.ln

/** mRadio sound-mode labels. Only the compatible platform spatial modes can
 * affect arbitrary external audio: mRadio's hall reverb and moving 8D orbit
 * require ownership of decoded stereo PCM.
 */
enum class SpatialMode(val label: String, val blurb: String, val platformSupported: Boolean) {
  OFF("Off", "Original stereo, without spatial processing.", true),
  WIDE("Wide", "Wider stereo where Android supports virtualization.", true),
  HEADPHONE_3D("Headphone 3D", "Headphone spatial effect where supported.", true),
  HALL("Concert hall", "Requires PCM reverb; unavailable for other apps.", false),
  CINEMA("Cinema", "Expanded stereo where Android supports virtualization.", true),
  ORBIT_8D("8D orbit", "Requires PCM movement effects; unavailable for other apps.", false)
}

/** 5-band source presets ported from N2K02/MRadio SoundFx.PRESETS.
 * Interpolate on the log-frequency axis to the single 10-band SEQ graphic EQ.
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
  fun matching(bands: List<Float>?): String? =
    if (bands == null) null else names.firstOrNull { name ->
      val preset = bands(name) ?: return@firstOrNull false
      preset.size == bands.size && preset.indices.all { i -> kotlin.math.abs(preset[i] - bands[i]) < 0.01f }
    }

  private fun resample(source: List<Float>): List<Float> {
    val target = SystemEqCurveBuilder.centres(SystemEqCurveBuilder.BAND_COUNT)
    return target.map { hz ->
      val high = sourceHz.indexOfFirst { it >= hz }
      val gain = when (high) {
        -1 -> source.last()
        0 -> source.first()
        else -> {
          val low = high - 1
          val fraction = ln(hz / sourceHz[low]) / ln(sourceHz[high] / sourceHz[low])
          (source[low] + (source[high] - source[low]) * fraction).toFloat()
        }
      }
      (kotlin.math.round(gain * 2f) / 2f).coerceIn(-SystemEqCurveBuilder.MANUAL_LIMIT_DB,
        SystemEqCurveBuilder.MANUAL_LIMIT_DB)
    }
  }
}
