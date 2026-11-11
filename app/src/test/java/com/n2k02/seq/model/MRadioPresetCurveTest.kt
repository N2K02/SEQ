package com.n2k02.seq.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** SEQ presets must sound like mRadio's: same filter curve, sampled at SEQ's 10 bands. */
class MRadioPresetCurveTest {
  private fun bands(name: String) = MRadioPresets.bands(name)!!

  @Test fun bassBoostFollowsMRadioInsteadOfOverBoostingSubBass() {
    // 31, 62, 125, 250, 500, 1k, 2k, 4k, 8k, 16k
    assertEquals(listOf(2.5f, 6f, 3.5f, 4.5f, 1.5f, 0.5f, 0f, 0f, 0f, 0f), bands("Bass boost"))
  }

  @Test fun trebleBoostFollowsMRadioCurve() {
    assertEquals(listOf(0f, 0f, 0f, 0f, 0f, 0.5f, 1.5f, 3f, 2.5f, 5f), bands("Treble boost"))
  }

  @Test fun vocalLiftsThePresenceRange() {
    val v = bands("Vocal")
    assertEquals(listOf(-1f, -2f, -0.5f, 0f, 1.5f, 3f, 2f, 3f, 1f, 0f), v)
  }

  @Test fun responseMatchesMRadioFilterMaths() {
    // Single +6 dB peaking band at 60 Hz: exactly +6 at its centre, neutral far away.
    val src = listOf(6f, 0f, 0f, 0f, 0f)
    assertEquals(6.0, MRadioPresets.sourceResponseDb(src, 60.0), 0.01)
    assertEquals(0.0, MRadioPresets.sourceResponseDb(src, 8000.0), 0.05)
    assertEquals(0.0, MRadioPresets.sourceResponseDb(List(5) { 0f }, 1000.0), 0.0)
  }
}
