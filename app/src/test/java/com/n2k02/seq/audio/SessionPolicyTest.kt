package com.n2k02.seq.audio

import com.n2k02.seq.model.*
import org.junit.Assert.*
import org.junit.Test

class SessionPolicyTest {
  private val active=EngineSettings(enabled=true, allMedia=false)
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
  @Test fun allMediaIsDefaultAndDisablesPerSessionEffects() {
    assertTrue(EngineSettings().allMedia)
    val global=EngineSettings(enabled=true)
    assertFalse(SessionPolicy.canAttach(global, 9, false))
    assertFalse(SessionPolicy.acceptsSessionBroadcasts(global))
    assertTrue(SessionPolicy.acceptsSessionBroadcasts(active))
  }
  @Test fun allMediaRunsOnlyWhenEnabledAndProcessed() {
    val global=EngineSettings(enabled=true)
    assertTrue(SessionPolicy.shouldProcessAllMedia(global))
    assertFalse(SessionPolicy.shouldProcessAllMedia(global.copy(enabled=false)))
    assertFalse(SessionPolicy.shouldProcessAllMedia(global.copy(profile=AudioProfile.PURE)))
    assertFalse(SessionPolicy.shouldProcessAllMedia(active))
  }
  @Test fun routeKindIsAutomatic() {
    val bt=android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
    val wired=android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES
    val speaker=android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
    assertEquals(RouteKind.CAR, RoutePolicy.kind(true, listOf(bt)))
    assertEquals(RouteKind.WIRED, RoutePolicy.kind(false, listOf(speaker, bt, wired)))
    assertEquals(RouteKind.BLUETOOTH, RoutePolicy.kind(false, listOf(speaker, bt)))
    assertEquals(RouteKind.SPEAKER, RoutePolicy.kind(false, listOf(speaker)))
  }
}
