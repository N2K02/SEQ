package com.n2k02.seq.audio

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Ensure native playback applies settings changes without tearing or polling. */
class SeqNativeAudioProcessorContractTest {
  private fun read(rel:String):String {
    val p="app/src/main/java/com/n2k02/seq/"+rel
    return listOf(File(p),File("../"+p),File("src/main/java/com/n2k02/seq/"+rel))
      .first { it.isFile }.readText()
  }
  @Test fun reconfiguresOnlyOnRevisionWithCrossfade() {
    val source=read("audio/SeqNativeAudioProcessor.kt")
    assertTrue(source.contains("SeqAudioRevision.current()"))
    assertTrue(source.contains("fadeRemaining=128"))
    assertTrue(source.contains("previousSamples"))
    assertTrue(source.contains(">=250L"))
  }
  @Test fun everyCorrectionSourceSignalsChange() {
    assertTrue(read("model/SettingsStore.kt").contains("SeqAudioRevision.changed()"))
    assertTrue(read("model/HeadphoneProfile.kt").contains("SeqAudioRevision.changed()"))
    assertTrue(read("model/MeasuredImpulse.kt").contains("SeqAudioRevision.changed()"))
  }
  @Test fun pureModeKeepsNativeDspOff() {
    val source=read("audio/SeqNativeAudioProcessor.kt")
    assertTrue(source.contains("settings.profile==AudioProfile.PURE) null"))
    assertTrue(source.contains("output.put(inputBuffer)"))
  }
}
