package com.n2k02.seq.model

import java.io.InputStream
import kotlin.math.*

enum class FilterKind(val nativeCode: Int) { PEAK(0), LOW_SHELF(1), HIGH_SHELF(2) }

data class EqFilter(
  val kind: FilterKind, val frequency: Double, val gain: Double,
  val q: Double, val enabled: Boolean = true
) {
  /** [maxGainDb] is 12 for imported correction files; the graphic-EQ solver may need up to 24. */
  fun valid(maxGainDb: Double = 24.0): Boolean = frequency.isFinite() && frequency in 20.0..20000.0 &&
    gain.isFinite() && gain in -maxGainDb..maxGainDb && q.isFinite() && q in 0.15..12.0
}
data class HeadphoneProfile(
  val name: String, val preampDb: Double = 0.0,
  val filters: List<EqFilter> = emptyList()
) {
  fun valid() = name.isNotBlank() && name.length<=100 &&
    preampDb.isFinite() && preampDb in -24.0..0.0 &&
    filters.size in 1..12 && filters.all { it.valid(12.0) }
}

/** Import the commonly exported AutoEq / Equalizer APO parametric text format. */
object AutoEqImport {
  private val preamp = Regex("""^\s*Preamp\s*:\s*([+-]?\d+(?:\.\d+)?)\s*dB\s*$""", RegexOption.IGNORE_CASE)
  private val filter = Regex("""^\s*Filter\s+\d+\s*:\s*(ON|OFF)\s+(PK|LS|LSC|HS|HSC)\s+Fc\s+(\d+(?:\.\d+)?)\s*Hz\s+Gain\s+(-?\d+(?:\.\d+)?)\s*dB\s+Q\s+(\d+(?:\.\d+)?).*$""", RegexOption.IGNORE_CASE)
  fun parse(name: String, content: String): HeadphoneProfile {
    require(content.length <= 50000) { "Correction file is too large" }
    val filters=mutableListOf<EqFilter>()
    var gain=0.0
    content.lineSequence().forEach { line ->
      preamp.matchEntire(line)?.let {
        gain=it.groupValues[1].toDouble()
      }
      filter.matchEntire(line)?.let { row ->
        val kind=when(row.groupValues[2].uppercase()) {
          "PK" -> FilterKind.PEAK
          "LS", "LSC" -> FilterKind.LOW_SHELF
          else -> FilterKind.HIGH_SHELF
        }
        filters += EqFilter(
          kind, row.groupValues[3].toDouble(), row.groupValues[4].toDouble(),
          row.groupValues[5].toDouble(), row.groupValues[1].equals("ON",true)
        )
      }
    }
    val candidate=HeadphoneProfile(name.take(100),gain,filters)
    require(candidate.valid()) { "Expected 1–12 valid filters and a non-positive preamp" }
    return candidate
  }
}

/** Mathematical response matching SEQ's RBJ biquad implementation. */
object EqResponse {
  fun db(hz: Double, filter: EqFilter, sampleRate: Double = 48000.0): Double {
    if (!filter.enabled || filter.gain == 0.0) return 0.0
    val c = com.n2k02.seq.audio.dsp.SeqBiquad.coefficients(filter, sampleRate)
    val x = 2.0 * PI * hz / sampleRate
    fun magnitude(u: Double, v: Double, z: Double) =
      hypot(u + v * cos(x) + z * cos(2 * x), -v * sin(x) - z * sin(2 * x))
    return 20.0 * log10(magnitude(c[0], c[1], c[2]) / magnitude(1.0, c[3], c[4]))
  }
  /** The three tone filters used by the native DSP; single source of truth for every path. */
  fun toneFilters(tone:Tone):List<EqFilter> = listOf(
    EqFilter(FilterKind.LOW_SHELF,110.0,tone.bass.toDouble(),0.7071),
    EqFilter(FilterKind.PEAK,1100.0,tone.mid.toDouble(),0.80),
    EqFilter(FilterKind.HIGH_SHELF,6000.0,tone.treble.toDouble(),0.7071)
  )

  /** Exact biquad response of tone + optional correction (same filters as native DSP). */
  fun totalDb(hz:Double,tone:Tone,profile:HeadphoneProfile?):Double =
    toneFilters(tone).sumOf { db(hz,it) } +
      (profile?.filters?.sumOf { db(hz,it) } ?: 0.0)
  fun totalDb(hz:Int,tone:Tone,profile:HeadphoneProfile?):Double = totalDb(hz.toDouble(),tone,profile)

  /** Attenuation needed so the largest boost (or a stricter preset preamp) cannot clip. */
  fun headroomDb(tone:Tone, profile:HeadphoneProfile?):Double {
    val maxBoost=(0..400).maxOf { i -> totalDb(20.0*1000.0.pow(i/400.0),tone,profile) }
    return max(0.0,max(-(profile?.preampDb ?: 0.0),maxBoost))
  }
}

/** No network downloads. User supplies their own correctly measured AutoEq preset. */
