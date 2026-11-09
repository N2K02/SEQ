package com.n2k02.seq.model

import org.junit.Assert.*
import org.junit.Test

class SoundTransitionPolicyTest {
  @Test fun transitionReservesHeadroomForEitherCurve() {
    val old = SystemEqCurveBuilder.manual(List(10) { if (it < 3) 6f else -6f })
    val next = SystemEqCurveBuilder.manual(List(10) { if (it < 3) -6f else 6f })
    val gain = SystemEqCurveBuilder.transitionGain(old, next)
    assertTrue(gain <= old.inputGainDb)
    assertTrue(gain <= next.inputGainDb)
    assertTrue(gain + old.bands.maxOf { it.gainDb } <= 0f)
    assertTrue(gain + next.bands.maxOf { it.gainDb } <= 0f)
  }

  @Test fun positivePreampHasSafetyHeadroomEvenWithLimiter() {
    val curve = SystemEqCurveBuilder.configured(Tone(bass = 3f), null, 4f, 6f, true)
    assertTrue(curve.bands.all { it.gainDb + curve.inputGainDb <= 0f })
  }
}
