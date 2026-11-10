package com.n2k02.seq.model

import org.junit.Assert.*
import org.junit.Test

class SystemEqCurveTest {
  @Test fun fallbackBandCountsPreserveSafetyAndCoverage() {
    val original = SystemEqCurveBuilder.build(Tone(bass = 5f, treble = 3f), null)
    for (count in listOf(31, 10, 5)) {
      val reduced = SystemEqCurveBuilder.forBandCount(original, count)
      assertEquals(count, reduced.bands.size)
      assertEquals(20000f, reduced.bands.last().cutoffHz, 1f)
      assertTrue(reduced.inputGainDb <= original.inputGainDb)
      assertTrue(reduced.bands.all { it.gainDb + reduced.inputGainDb <= 0.05f })
    }
  }

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

  @Test fun compactPresetUsesTheTenOctaveCentresAndRoundTrips() {
    val expected = listOf(31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)
    assertEquals(expected, SystemEqCurveBuilder.centres(10))
    val gains = List(10) { it - 4f }
    assertEquals(gains, SystemEqCurveBuilder.parseManual(gains.joinToString(",")))
    val curve = SystemEqCurveBuilder.manual(gains)
    assertEquals(31, curve.bands.size)
    assertEquals(-5f, curve.inputGainDb, 0f)
    assertEquals(-4f, curve.bands.first().gainDb, 0f)
    assertEquals(5f, curve.bands.last().gainDb, 0f)
    assertTrue(curve.bands.zipWithNext().all { (a, b) -> a.gainDb <= b.gainDb })
  }

  @Test fun convertingBetweenLayoutsPreservesAFlatPreset() {
    val gains = List(31) { 2.5f }
    val compact = SystemEqCurveBuilder.resample(gains, 10)
    assertEquals(List(10) { 2.5f }, compact)
    assertEquals(gains, SystemEqCurveBuilder.resample(compact, 31))
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
  @Test fun outputPreampRequiresLimiterForPositiveGain() {
    val curve = SystemEqCurveBuilder.manual(List(10) { 0f })
    assertEquals(0f, SystemEqCurveBuilder.withOutputControls(curve, 0f, 6f, false).inputGainDb, 0f)
    assertEquals(-0.5f, SystemEqCurveBuilder.withOutputControls(curve, 0f, 6f, true).inputGainDb, 0f)
    assertEquals(-5f, SystemEqCurveBuilder.withOutputControls(curve, 0f, -5f, false).inputGainDb, 0f)
  }

  @Test fun bassAdjustmentKeepsHeadroomAndTapersOnPhoneSpeaker() {
    val settings = EngineSettings(bassBoost = 1f, dynamicBass = true, bassIntensity = 1f)
    assertEquals(4f, SystemEqCurveBuilder.bassGain(settings, RouteKind.SPEAKER, 0f), 0f)
    assertEquals(0f, SystemEqCurveBuilder.bassGain(settings, RouteKind.SPEAKER, 1f), 0f)
    assertEquals(5f, SystemEqCurveBuilder.bassGain(settings, RouteKind.WIRED, 1f), 0f)
    val curve = SystemEqCurveBuilder.manual(List(10) { 2f })
    val boosted = SystemEqCurveBuilder.withOutputControls(curve, 5f, 0f, false)
    assertTrue(boosted.bands.first().gainDb > boosted.bands.last().gainDb)
    assertTrue(boosted.bands.all { it.gainDb + boosted.inputGainDb <= 0f })
  }

  @Test fun correctionDoesNotSubtractItsPeakTwice() {
    val profile = HeadphoneProfile("test", -1.0,
      listOf(EqFilter(FilterKind.PEAK, 1000.0, 2.0, 1.0)))
    val curve = SystemEqCurveBuilder.configured(Tone(), null, 0f, 0f, false, profile)
    val maximum = curve.bands.maxOf { it.gainDb }
    assertTrue(curve.inputGainDb >= -3f)
    assertTrue(curve.inputGainDb + maximum <= 0.05f)
  }

  @Test fun bassAtNonpeakFrequencyDoesNotAddFullIndependentHeadroom() {
    val base = SystemEqCurveBuilder.manual(List(10) { if (it == 9) 4f else 0f })
    val adjusted = SystemEqCurveBuilder.withOutputControls(base, 2f, 0f, false)
    assertTrue(adjusted.inputGainDb <= base.inputGainDb)
    // No more than the new shelf boost is needed as additional attenuation.
    assertTrue(adjusted.inputGainDb >= base.inputGainDb - 2.5f)
    assertTrue(adjusted.bands.all { it.gainDb + adjusted.inputGainDb <= 0.01f })
  }

  @Test fun soundModesAreDistinctAndNaturalIsNeutral() {
    val tone = Tone(bass = 1f, mid = 0.5f, treble = -0.25f)
    assertEquals(tone, SoundModeTuning.apply(tone, SoundMode.NATURAL))
    assertTrue(SoundModeTuning.apply(tone, SoundMode.SMOOTH).treble < tone.treble)
    assertTrue(SoundModeTuning.apply(tone, SoundMode.DEEP_BASS).bass > tone.bass)
    assertTrue(SoundModeTuning.apply(tone, SoundMode.IMMERSIVE).mid < tone.mid)
  }

  @Test fun positivePreampAndBassCannotExceedHeadroom() {
    val curve = SystemEqCurveBuilder.configured(Tone(), List(10) { 3f }, 5f, 6f, true)
    assertTrue(curve.bands.all { it.gainDb + curve.inputGainDb <= -0.49f })
  }
}
