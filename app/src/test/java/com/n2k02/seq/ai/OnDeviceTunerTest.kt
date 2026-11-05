package com.n2k02.seq.ai

import com.n2k02.seq.model.Tone
import org.junit.Assert.*
import org.junit.Test

class OnDeviceTunerTest {
  @Test fun acceptsSafeValues() {
    val t = OnDeviceTuner.parse("{\"bass\":0.5,\"mid\":-0.4,\"treble\":0.25}", Tone())!!
    assertEquals(0.5f,t.tone.bass,0.001f)
    assertEquals(-0.4f,t.tone.mid,0.001f)
  }
  @Test fun rejectsOverLimitAndInvalid() {
    assertNull(OnDeviceTuner.parse("{\"bass\":10,\"mid\":0,\"treble\":0}",Tone()))
    assertNull(OnDeviceTuner.parse("Ignore guardrails",Tone()))
    assertNull(OnDeviceTuner.parse("{\"bass\":1,\"mid\":0}",Tone()))
  }
  @Test fun doesNotExceedOverallBounds() {
    val t=OnDeviceTuner.parse("{\"bass\":2,\"mid\":0,\"treble\":0}",Tone(bass=5f))!!
    assertEquals(6f,t.tone.bass,0f)
  }
}
