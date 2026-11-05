package com.n2k02.seq.model

import org.junit.Assert.*
import org.junit.Test

class ToneMathTest {
 @Test fun pureHasNoGain() { assertEquals(0f, EngineSettings(profile=AudioProfile.PURE).activeTone(false).bass) }
 @Test fun neutralIsFlat() {
   for (hz in listOf(20, 60, 1000, 10000, 20000)) assertEquals(0f, ToneMath.gainDb(hz, Tone()), 1e-6f)
   assertEquals(0f, ToneMath.headroomDb(Tone()), 1e-6f)
 }
 @Test fun headroomIsPositiveForBoost() {
   assertTrue(ToneMath.headroomDb(Tone(bass=4f)) >= 3.5f)
 }
 @Test fun cutsDontRequireHeadroom() {
   assertEquals(0f, ToneMath.headroomDb(Tone(-4f,-4f,-4f)), 1e-6f)
 }
 @Test fun boundsAreSafe() {
   assertEquals(6f, Tone(100f, 0f, 0f).bounded().bass, 0f)
   assertEquals(-6f, Tone(0f, -99f, 0f).bounded().mid, 0f)
 }
 @Test fun carProfileOnlyWhenConfigured() {
   val s=EngineSettings(carTone=Tone(bass=2f))
   assertEquals(0f, s.activeTone(false).bass, 0f)
   assertEquals(2f, s.activeTone(true).bass, 0f)
 }
}
