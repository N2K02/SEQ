package com.n2k02.seq.audio

import com.n2k02.seq.model.AutoSound
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeqAdaptiveSettleTest {
  @Test fun pausesOnlyAfterSeveralSteadyDecisions() {
    assertFalse(AutoSound.settled(0, 3))
    assertFalse(AutoSound.settled(2, 3))
    assertTrue(AutoSound.settled(3, 3))
  }

  private fun src(path: String) =
    listOf(File(path), File("../$path")).first { it.isFile }.readText()

  @Test fun settledAnalyzerKeepsCorrectionAndResumesOnTrackOrRouteChange() {
    val analyzer = src("app/src/main/java/com/n2k02/seq/audio/SeqAdaptiveAnalyzer.kt")
    val listener = src("app/src/main/java/com/n2k02/seq/audio/SeqMediaSessionListener.kt")
    assertTrue(analyzer.contains("if (settled) { release(); return }"))
    // release() frees the Visualizer but never resets the correction.
    val release = analyzer.substringAfter("private fun release() {").substringBefore("\n  }")
    assertFalse(release.contains("resetCorrection"))
    assertTrue(analyzer.contains("settled = false") && analyzer.contains("outputKey = key"))
    assertTrue(listener.contains("SeqAdaptiveAnalyzer.onTrackChanged(this@SeqMediaSessionListener)"))
  }
}
