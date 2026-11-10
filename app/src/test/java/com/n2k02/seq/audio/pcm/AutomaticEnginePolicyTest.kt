package com.n2k02.seq.audio.pcm

import org.junit.Assert.assertEquals
import org.junit.Test

class AutomaticEnginePolicyTest {
  private val platform = AutomaticEnginePolicy.Backend.PLATFORM_EFFECT
  private val native = AutomaticEnginePolicy.Backend.NATIVE_PCM
  private val bypass = AutomaticEnginePolicy.Backend.BYPASS
  private val ready = AutomaticEnginePolicy.Evidence(enabled = true, recordAudio = true,
    dumpGranted = true, captureConsent = true, sessionMuteAvailable = true,
    platformEffectSupported = true)

  @Test fun disabledAlwaysBypasses() {
    assertEquals(bypass, AutomaticEnginePolicy.choose(ready.copy(enabled = false)))
  }

  @Test fun seqDspIsTheDefaultWhenEveryPrerequisiteHolds() {
    assertEquals(native, AutomaticEnginePolicy.choose(ready))
  }

  @Test fun everyDspPrerequisiteIsMandatoryAndFallsBackToPlatform() {
    listOf(ready.copy(dspWanted = false), ready.copy(recordAudio = false),
      ready.copy(dumpGranted = false), ready.copy(captureConsent = false),
      ready.copy(sessionMuteAvailable = false)).forEach {
      assertEquals(platform, AutomaticEnginePolicy.choose(it))
    }
  }

  @Test fun unsupportedRouteFallsBackToBypass() {
    assertEquals(bypass, AutomaticEnginePolicy.choose(
      AutomaticEnginePolicy.Evidence(enabled = true)))
  }
}
