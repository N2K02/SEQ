package com.n2k02.seq

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Home tuning must be immediate, persistent, and simple enough to operate during playback. */
class SimpleSoundExperienceContractTest {
  private fun source(path: String): String =
    generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
      .first { it.resolve(path).isFile }.resolve(path).readText()

  @Test fun homeContainsEqualizerAndNoManualApplyOrPresets() {
    val home = source("app/src/main/java/com/n2k02/seq/MainActivity.kt")
    val homeBody = home.substringAfter("Page.HOME -> {").substringBefore("Page.SETTINGS -> {")
    assertTrue(homeBody.contains("SimpleEqualizerScreen("))
    assertTrue(homeBody.contains("Reset all sound settings"))
    assertTrue(homeBody.contains("store.resetSound()"))
    assertFalse(homeBody.contains("Open equalizer"))
    assertFalse(homeBody.contains("Save preset"))
    assertFalse(homeBody.contains("Signal path"))
    assertFalse(homeBody.contains("Advanced audio"))
    assertTrue(homeBody.contains("allMedia = true"))
  }

  @Test fun slidersApplyChangesAfterDebounceWithoutExplicitSave() {
    val ui = source("app/src/main/java/com/n2k02/seq/SeqAudioScreens.kt")
    val simple = ui.substringAfter("internal fun SimpleEqualizerScreen(")
    assertTrue(simple.contains("LaunchedEffect(gains, edited)"))
    assertTrue(simple.contains("latestOnBands(gains.toList())"))
    assertTrue(simple.contains("LaunchedEffect(bass)"))
    assertTrue(simple.contains("latestBass(bass)"))
    assertFalse(simple.contains("Apply manual tuning"))
  }

  @Test fun resettingSoundPreservesEnabledButClearsRouteOverrides() {
    val store = source("app/src/main/java/com/n2k02/seq/model/SettingsStore.kt")
    val reset = store.substringAfter("fun resetSound() {").substringBefore("private fun readLevel")
    assertTrue(reset.contains("getBoolean(\"enabled\", false)"))
    assertTrue(reset.contains("prefs.edit().clear().putBoolean(\"enabled\", enabled).apply()"))
    val main = source("app/src/main/java/com/n2k02/seq/MainActivity.kt")
    assertTrue(main.contains("update(store.read().copy(enabled = settings.enabled"))
  }

  @Test fun complexAdvancedScreensAreNotUserFacing() {
    val main = source("app/src/main/java/com/n2k02/seq/MainActivity.kt")
    assertFalse(main.contains("Page.ADVANCED_AUDIO"))
    assertFalse(main.contains("Page.SIGNAL_PATH"))
    assertFalse(main.contains("Page.DEVICE_PROFILES"))
    assertTrue(main.contains("Page.ADB_CONNECTION"))
    assertTrue(main.contains("Page.UPDATES"))
  }
}
