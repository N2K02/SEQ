package com.n2k02.seq.model

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MRadioSoundTest {
  @Test fun allTenOriginalMRadiopresetsConvertToTenBands() {
    assertEquals(listOf("Flat", "Bass boost", "Vocal", "Pop", "Rock",
      "Dance", "Hip-hop", "Jazz", "Classical", "Treble boost"), MRadioPresets.names)
    MRadioPresets.names.forEach { name ->
      val bands = MRadioPresets.bands(name)
      assertNotNull(name, bands)
      assertEquals(name, 10, bands!!.size)
      assertTrue(name, bands.all { it.isFinite() && it in -6f..6f })
      assertEquals(name, name, MRadioPresets.matching(bands))
      assertEquals(name, 10, SystemEqCurveBuilder.manual(bands).bands.size)
    }
    assertEquals(List(10) { 0f }, MRadioPresets.bands("Flat"))
    assertNull(MRadioPresets.bands("Unknown"))
    assertNull(MRadioPresets.matching(List(10) { 4.5f }))
  }

  @Test fun spatialModesDistinguishPlatformVirtualizationFromPcmOnly() {
    assertTrue(SpatialMode.WIDE.platformSupported)
    assertTrue(SpatialMode.HEADPHONE_3D.platformSupported)
    assertTrue(SpatialMode.CINEMA.platformSupported)
    assertFalse(SpatialMode.HALL.platformSupported)
    assertFalse(SpatialMode.ORBIT_8D.platformSupported)
    assertEquals(SpatialMode.OFF, EngineSettings().spatialMode)
    assertEquals(0.6f, EngineSettings().spatialIntensity)
  }

  private fun source(path: String): String = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
    .first { it.resolve(path).isFile }.resolve(path).readText()

  @Test fun spatialControlsArePersistedAndBypassReleasesExtraEffect() {
    val store = source("app/src/main/java/com/n2k02/seq/model/SettingsStore.kt")
    val ui = source("app/src/main/java/com/n2k02/seq/SeqAudioScreens.kt")
    val global = source("app/src/main/java/com/n2k02/seq/audio/SeqGlobalEffect.kt")
    assertTrue(store.contains("putString(\"spatial_mode\", s.spatialMode.name)"))
    assertTrue(store.contains("putFloat(\"spatial_intensity\", s.spatialIntensity)"))
    assertTrue(ui.contains("MRadioPresets.names.forEach"))
    assertTrue(ui.contains("SpatialMode.entries.forEach"))
    assertTrue(global.contains("syncSpatial(s.spatialMode, s.spatialIntensity)"))
    assertTrue(global.contains("spatialEffect?.close()"))
    assertTrue(global.contains("HuaweiNativeEq.activeFor(ctx)"))
  }
}
