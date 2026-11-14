package com.n2k02.seq.audio.dsp

import com.n2k02.seq.model.EqFilter
import com.n2k02.seq.model.FilterKind
import com.n2k02.seq.model.HeadphoneProfile
import com.n2k02.seq.model.SpatialMode
import com.n2k02.seq.model.Tone
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.log10

class SeqDspPlannerTest {
  private fun inputs(manual: List<Float>? = null, tone: Tone = Tone(), peakGuard: Boolean = true,
    correction: HeadphoneProfile? = null, bass: Float = 0f, bypass: Boolean = false) = SeqDspInputs(
    tone, manual, bass, 0f, correction, peakGuard, -1f, SpatialMode.OFF, 0.6f,
    false, 1.5f, -18f, eqBypass = bypass)

  private fun db(linear: Float) = 20 * log10(linear.toDouble())

  @Test fun flatSoundHasNoFiltersAndUnityGain() {
    val plan = SeqDspPlanner.plan(48000, inputs())
    assertEquals(0, plan.filterCount)
    assertEquals(1f, plan.preGain, 1e-6f)
  }

  @Test fun graphicBandsBecomeTrueParametricFilters() {
    val bands = List(10) { if (it == 5) 6f else 0f }
    val i = inputs(manual = bands, peakGuard = false)
    assertEquals(1, SeqDspPlanner.filters(i).size)
    assertEquals(1000.0, SeqDspPlanner.filters(i).single().frequency, 1.0)
    val plan = SeqDspPlanner.plan(48000, i)
    assertEquals(-6.5, db(plan.preGain), 0.1) // full headroom without limiter
    val limited = SeqDspPlanner.plan(48000, i.copy(peakGuard = true))
    assertEquals(-6.5, db(limited.preGain), 0.1) // full headroom even with limiter
    assertTrue(limited.limiter)
  }

  @Test fun correctionPreampAndBassShelfAreIncluded() {
    val profile = HeadphoneProfile("Test", -4.0, listOf(EqFilter(FilterKind.PEAK, 3000.0, -3.0, 2.0)))
    val i = inputs(tone = Tone(bass = 2f), correction = profile, bass = 3f)
    val filters = SeqDspPlanner.filters(i)
    assertEquals(3, filters.size) // tone bass shelf (0 dB mid/treble dropped) + correction + bass boost
    assertTrue(db(SeqDspPlanner.plan(48000, i).preGain) <= -4.0 + 1e-3)
  }

  @Test fun deviceEqBypassKeepsOnlyLimiterAndSpatial() {
    val plan = SeqDspPlanner.plan(48000, inputs(manual = List(10) { 4f }, bypass = true))
    assertEquals(0, plan.filterCount)
    assertEquals(1f, plan.preGain, 1e-6f)
  }

  @Test fun responseHasTenBandsAndStaysBelowClipping() {
    val response = SeqDspPlanner.response(inputs(manual = List(10) { 6f }, peakGuard = false))
    assertEquals(10, response.size)
    assertTrue(response.all { it <= 0.5f })
  }
  @Test fun narrowCorrectionPeaksAndShelfEndpointsReserveTheirFullGain() {
    for (rate in listOf(8000, 44100, 48000, 96000)) {
      val filters = listOf(
        EqFilter(FilterKind.PEAK, minOf(18237.0, rate * .43), 12.0, 12.0),
        EqFilter(FilterKind.HIGH_SHELF, minOf(15000.0, rate * .3), 5.0, .7071))
      val scanned = SeqDspPlanner.maxBoostDb(filters, rate)
      val dense = (0..10000).maxOf { step ->
        val hz = rate * .5 * step / 10000
        filters.sumOf { com.n2k02.seq.model.EqResponse.db(hz, it, rate.toDouble()) }
      }
      assertTrue("$rate boost=$dense reserved=$scanned", scanned >= dense + .4)
    }
  }

}
