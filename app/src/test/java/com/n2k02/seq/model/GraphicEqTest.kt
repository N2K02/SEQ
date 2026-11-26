package com.n2k02.seq.model

import com.n2k02.seq.audio.dsp.SeqBiquad
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class GraphicEqTest {
  private val centres = SystemEqCurveBuilder.centres(SystemEqCurveBuilder.BAND_COUNT)

  private fun realized(bands: List<Float>) = centres.map { GraphicEq.responseDb(bands, it) }

  @Test fun flatSlidersCreateNoFilters() {
    assertTrue(GraphicEq.filters(List(10) { 0f }).isEmpty())
  }

  @Test fun responseHitsEverySliderValueAtItsCentre() {
    val cases = listOf(
      List(10) { 6f }, List(10) { -9f }, List(10) { if (it % 2 == 0) 8f else -8f },
      listOf(12f, 9f, 5f, 2f, 0f, 0f, -3f, -6f, -9f, -12f),
      List(10) { if (it == 4) 12f else 0f })
    cases.forEach { bands ->
      realized(bands).forEachIndexed { i, db ->
        assertEquals("band $i of $bands", bands[i].toDouble(), db, 0.15)
      }
    }
  }

  @Test fun theOldDirectMappingOvershotNeighbouringBands() {
    // Regression for the logic bug fixed here: unsolved overlapping peaks added up.
    val flatBoost = List(10) { 6f }
    val naive = centres.map { hz ->
      flatBoost.indices.sumOf { j -> EqResponse.db(hz, EqFilter(FilterKind.PEAK, centres[j], 6.0, GraphicEq.Q)) }
    }
    assertTrue("direct mapping overshoots", naive.drop(2).dropLast(2).all { it > 8.0 })
    realized(flatBoost).drop(2).dropLast(2).forEach { assertEquals(6.0, it, 0.15) }
  }

  @Test fun solverStaysBoundedAndFinite() {
    val worst = List(10) { if (it % 2 == 0) 12f else -12f }
    GraphicEq.filters(worst).forEach { assertTrue(it.gain.isFinite() && abs(it.gain) <= 24.0 && it.valid()) }
  }

  @Test fun nonFiniteSliderValuesAreTreatedAsFlat() {
    val f = GraphicEq.filters(List(10) { if (it == 0) Float.NaN else 0f })
    assertTrue(f.isEmpty())
  }

  @Test fun shelfFiltersHonourTheirQ() {
    val soft = EqFilter(FilterKind.LOW_SHELF, 100.0, 6.0, 0.5)
    val sharp = EqFilter(FilterKind.LOW_SHELF, 100.0, 6.0, 1.2)
    assertNotEquals(SeqBiquad.coefficients(soft, 48000)[1], SeqBiquad.coefficients(sharp, 48000)[1], 1e-6)
    // Q = 1/sqrt(2) is the classic slope-1 shelf: far below the corner the gain is the full 6 dB.
    val classic = EqFilter(FilterKind.LOW_SHELF, 100.0, 6.0, 0.7071)
    assertEquals(6.0, EqResponse.db(20.0, classic), 0.1)
    assertEquals(3.0, EqResponse.db(100.0, classic), 0.15) // half gain at the corner
    // A lower Q gives a gentler transition: more boost than the classic shelf above the corner.
    assertTrue(EqResponse.db(300.0, soft) > EqResponse.db(300.0, classic))
    val high = EqFilter(FilterKind.HIGH_SHELF, 6000.0, -4.0, 0.5)
    assertEquals(-4.0, EqResponse.db(20000.0, high), 0.3)
  }

  @Test fun graphAndEngineUseOneBiquadImplementation() {
    val f = EqFilter(FilterKind.HIGH_SHELF, 5000.0, 5.0, 0.9)
    val c = SeqBiquad.coefficients(f, 48000)
    val w = 2 * Math.PI * 9000.0 / 48000
    fun mag(a: Double, b: Double, d: Double) = Math.hypot(a + b * Math.cos(w) + d * Math.cos(2 * w),
      -b * Math.sin(w) - d * Math.sin(2 * w))
    val direct = 20 * Math.log10(mag(c[0], c[1], c[2]) / mag(1.0, c[3], c[4]))
    assertEquals(direct, EqResponse.db(9000.0, f), 1e-9)
  }
}
