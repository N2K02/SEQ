package com.n2k02.seq.model

import org.junit.Assert.*
import org.junit.Test

class SystemEqCurveTest {
  @Test fun neutralToneIsFlat() {
    val c = SystemEqCurveBuilder.build(Tone(), null)
    assertEquals(SystemEqCurveBuilder.BAND_COUNT, c.bands.size)
    assertTrue(c.isFlat)
  }
  @Test fun cutoffsAreIncreasingAndCoverAudibleRange() {
    val c = SystemEqCurveBuilder.build(Tone(), null)
    c.bands.zipWithNext().forEach { (a, b) -> assertTrue(b.cutoffHz > a.cutoffHz) }
    assertEquals(20000f, c.bands.last().cutoffHz, 1f)
    assertTrue(c.bands.first().cutoffHz < 30f)
  }
  @Test fun boostIsCompensatedByInputGainSoNoBandExceedsZero() {
    val c = SystemEqCurveBuilder.build(Tone(bass = 6f, treble = 4f), null)
    assertTrue(c.inputGainDb <= -5.5f)
    c.bands.forEach { assertTrue(it.gainDb + c.inputGainDb <= 0.05f) }
    assertTrue(c.levelAt(40f) > c.levelAt(1000f))
  }
  @Test fun cutOnlyNeedsNoAttenuation() {
    val c = SystemEqCurveBuilder.build(Tone(-4f, -4f, -4f), null)
    assertEquals(0f, c.inputGainDb, 0f)
    assertTrue(c.bands.all { it.gainDb <= 0f })
  }
  @Test fun matchesNativeBiquadResponse() {
    val tone = Tone(mid = 5f)
    val c = SystemEqCurveBuilder.build(tone, null)
    val band = c.bands.first { it.cutoffHz >= 1100f }
    assertEquals(EqResponse.totalDb(1100.0, tone, null), band.gainDb.toDouble(), 0.6)
  }
  @Test fun oemFallbackUsesFullHeadroomWithoutLimiter() {
    val levels = SystemEqCurveBuilder.oemLevels(Tone(bass = 6f), listOf(60f, 1000f, 8000f),
      -1500..1500)
    assertEquals(0, levels.first().toInt())
    assertTrue(levels.drop(1).all { it < 0 })
  }

  @Test fun oemFallbackHonoursHardwareRangeAndPreservesNeutral() {
    val centres = listOf(60f, 1000f, 8000f)
    val range = -100..100
    assertEquals(listOf<Short>(0, 0, 0), SystemEqCurveBuilder.oemLevels(Tone(), centres, range))
    assertEquals(listOf<Short>(0, -100, -100),
      SystemEqCurveBuilder.oemLevels(Tone(bass = 9f), centres, range))
  }

  @Test fun presetPreampIsHonoured() {
    val p = HeadphoneProfile("x", -7.0, listOf(EqFilter(FilterKind.PEAK, 1000.0, 3.0, 1.0)))
    assertEquals(-7f, SystemEqCurveBuilder.build(Tone(), p).inputGainDb, 0.01f)
  }
}
