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
  @Test fun limiterHalvesPlatformHeadroomButPreampStillWins() {
    val tone = Tone(bass = 6f)
    val full = SystemEqCurveBuilder.build(tone, null)
    val half = SystemEqCurveBuilder.build(tone, null, limiter = true)
    assertEquals(full.inputGainDb / 2f, half.inputGainDb, 0.05f)
    val p = HeadphoneProfile("x", -7.0, listOf(EqFilter(FilterKind.PEAK, 1000.0, 3.0, 1.0)))
    assertEquals(-7f, SystemEqCurveBuilder.build(Tone(), p, limiter = true).inputGainDb, 0.01f)
  }
  @Test fun limiterAndLoudnessAreDefaults() {
    assertTrue(EngineSettings().peakGuard)
    assertTrue(EngineSettings().loudness)
  }
}
