package com.n2k02.seq.audio

import com.n2k02.seq.model.*
import org.junit.Assert.*
import org.junit.Test

class SessionPolicyTest {
  private val active=EngineSettings(enabled=true)
  @Test fun disabledCannotCreateAnEffect() {
    assertFalse(SessionPolicy.canAttach(active.copy(enabled=false), 9, false))
  }
  @Test fun pureNeverAttaches() {
    assertFalse(SessionPolicy.canAttach(active.copy(profile=AudioProfile.PURE), 9, false))
  }
  @Test fun neverAttachToGlobalSession() {
    assertFalse(SessionPolicy.canAttach(active, 0, false))
    assertFalse(SessionPolicy.canAttach(active, -1, false))
  }
  @Test fun repeatedOpenCannotCreateDuplicate() {
    assertTrue(SessionPolicy.canAttach(active, 9, false))
    assertFalse(SessionPolicy.canAttach(active, 9, true))
  }
  @Test fun activeEffectCanBeReopenedAfterClose() {
    assertTrue(SessionPolicy.canAttach(active, 9, false))
  }
}
