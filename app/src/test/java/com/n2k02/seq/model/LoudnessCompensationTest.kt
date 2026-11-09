package com.n2k02.seq.model

import org.junit.Assert.*
import org.junit.Test

class LoudnessCompensationTest {
  @Test fun fullVolumeIsUnchanged() {
    val t = Tone(1f, 2f, 3f)
    assertEquals(t, LoudnessCompensation.apply(t, 1f))
  }
  @Test fun lowerVolumeAddsMoreBassThanTreble() {
    val half = LoudnessCompensation.apply(Tone(), 0.5f)
    val low = LoudnessCompensation.apply(Tone(), 0.2f)
    assertTrue(half.bass > 0f && half.treble > 0f && half.bass > half.treble)
    assertTrue(low.bass > half.bass && low.treble >= half.treble)
    assertEquals(0f, low.mid, 0f)
  }
  @Test fun boostIsBounded() {
    val t = LoudnessCompensation.apply(Tone(6f, 0f, 6f), 0f)
    assertTrue(t.bass <= 9f && t.treble <= 8f)
    assertEquals(5f, LoudnessCompensation.apply(Tone(), 0f).bass, 0.001f)
  }
  @Test fun tinyPreampAdjustmentKeepsAutomaticHeadroomContinuous() {
    val tone = Tone(bass = 6f)
    val base = SystemEqCurveBuilder.configured(tone, null, 0f, 0f, allowBoost = true)
    val adjusted = SystemEqCurveBuilder.configured(tone, null, 0f, 0.01f, allowBoost = true)
    assertTrue(adjusted.inputGainDb <= base.inputGainDb)
    assertTrue(adjusted.bands.all { it.gainDb + adjusted.inputGainDb <= 0f })
    val p = HeadphoneProfile("x", -7.0, listOf(EqFilter(FilterKind.PEAK, 1000.0, 3.0, 1.0)))
    assertEquals(-7f, SystemEqCurveBuilder.build(Tone(), p).inputGainDb, 0.01f)
  }

  @Test fun transitionProtectsBothCurvesBeforeFinalPreamp() {
    val boosted = SystemEqCurveBuilder.configured(Tone(), List(10) { 6f }, 0f, 4f, allowBoost = true)
    val flat = SystemEqCurveBuilder.manual(List(10) { 0f })
    assertEquals(-6.5f, SystemEqCurveBuilder.transitionGain(flat, boosted), 0f)
    assertEquals(-6.5f, SystemEqCurveBuilder.transitionGain(boosted, flat), 0f)
    assertEquals(-6.5f, SystemEqCurveBuilder.transitionGain(null, boosted), 0f)
  }

  @Test fun manualOutputPresetSuppressesAdaptiveAnalysis() {
    val s = EngineSettings(enabled = true)
    assertTrue(AutoSound.shouldAnalyze(s, true, false, true))
    assertFalse(AutoSound.shouldAnalyze(s, true, true, true))
    assertFalse(AutoSound.shouldAnalyze(s.copy(manualBands = List(10) { 0f }), true, false, true))
    assertFalse(AutoSound.shouldAnalyze(s.copy(profile = AudioProfile.PURE), true, false, true))
    assertFalse(AutoSound.shouldAnalyze(s, false, false, true))
  }

  @Test fun limiterAndLoudnessAreDefaults() {
    assertTrue(EngineSettings().peakGuard)
    assertTrue(EngineSettings().loudness)
  }
}
