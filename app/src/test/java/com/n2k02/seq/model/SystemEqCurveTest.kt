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

  @Test fun manualGainsRoundTripAndPreserveHeadroom() {
    val gains = List(SystemEqCurveBuilder.BAND_COUNT) { if (it == 0) 6f else -2f }
    val restored = SystemEqCurveBuilder.parseManual(gains.joinToString(","))
    assertEquals(gains, restored)
    val curve = SystemEqCurveBuilder.manual(checkNotNull(restored))
    assertEquals(-6f, curve.inputGainDb, 0f)
    assertEquals(0f, curve.levelAt(20f), 0f)
    assertEquals(-8f, curve.levelAt(1000f), 0f)
    assertEquals(listOf<Short>(0, -800),
      SystemEqCurveBuilder.oemLevels(Tone(), listOf(20f, 1000f), -1500..1500, curve))
  }

  @Test fun invalidStoredManualCurvesFallBackToAuto() {
    assertNull(SystemEqCurveBuilder.parseManual(null))
    assertNull(SystemEqCurveBuilder.parseManual("0,0"))
    for (bad in listOf("NaN", "Infinity", "6.5", "invalid", "")) {
      val bands = List(SystemEqCurveBuilder.BAND_COUNT) { if (it == 0) bad else "0" }
      assertNull(SystemEqCurveBuilder.parseManual(bands.joinToString(",")))
    }
  }

  @Test fun presetPreampIsHonoured() {
    val p = HeadphoneProfile("x", -7.0, listOf(EqFilter(FilterKind.PEAK, 1000.0, 3.0, 1.0)))
    assertEquals(-7f, SystemEqCurveBuilder.build(Tone(), p).inputGainDb, 0.01f)
  }
}
