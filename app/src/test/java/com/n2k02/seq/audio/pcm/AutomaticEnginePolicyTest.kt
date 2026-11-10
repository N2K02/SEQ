package com.n2k02.seq.audio.pcm

import org.junit.Assert.assertEquals
import org.junit.Test

class AutomaticEnginePolicyTest {
  private val platform = AutomaticEnginePolicy.Backend.PLATFORM_EFFECT
  private val native = AutomaticEnginePolicy.Backend.NATIVE_PCM
  private val bypass = AutomaticEnginePolicy.Backend.BYPASS

  @Test fun disabledAlwaysBypasses() {
    assertEquals(bypass, AutomaticEnginePolicy.choose(
      AutomaticEnginePolicy.Evidence(enabled = false, ownPcmStream = true, platformEffectSupported = true)))
  }

  @Test fun seqOwnedPcmPrefersNative() {
    assertEquals(native, AutomaticEnginePolicy.choose(
      AutomaticEnginePolicy.Evidence(enabled = true, ownPcmStream = true, platformEffectSupported = true)))
  }

  @Test fun captureCopyDoesNotEstablishExclusiveRouting() {
    assertEquals(platform, AutomaticEnginePolicy.choose(
      AutomaticEnginePolicy.Evidence(enabled = true, platformEffectSupported = true)))
  }

  @Test fun verifiedExclusiveRoutePrefersNative() {
    assertEquals(native, AutomaticEnginePolicy.choose(
      AutomaticEnginePolicy.Evidence(true, exclusivePolicyVerified = true,
        originalOutputSuppressed = true, outputRouteVerified = true,
        rollbackReady = true, platformEffectSupported = true)))
  }

  @Test fun everyExclusiveSafetyConditionIsMandatory() {
    for (missing in 0..3) {
      val flags = BooleanArray(4) { it != missing }
      assertEquals(platform, AutomaticEnginePolicy.choose(
        AutomaticEnginePolicy.Evidence(true, exclusivePolicyVerified = flags[0],
          originalOutputSuppressed = flags[1], outputRouteVerified = flags[2],
          rollbackReady = flags[3], platformEffectSupported = true)))
    }
  }

  @Test fun unsupportedRouteFallsBackToBypass() {
    assertEquals(bypass, AutomaticEnginePolicy.choose(
      AutomaticEnginePolicy.Evidence(enabled = true)))
  }
}
