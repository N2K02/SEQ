package com.n2k02.seq

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Source contracts for the redesigned equalizer: true-response curve, ±12 dB, no Apply step. */
class EqualizerRedesignContractTest {
  private val ui: String by lazy {
    listOf("app/src/main/java/com/n2k02/seq/SeqAudioScreens.kt",
      "src/main/java/com/n2k02/seq/SeqAudioScreens.kt").map(::File).first { it.exists() }.readText()
  }

  @Test fun curveShowsTheSolvedRealResponseNotARawPolyline() {
    val curve = ui.substringAfter("private fun EqualizerCurve(").substringBefore("internal fun SimpleEqualizerScreen(")
    assertTrue(curve.contains("GraphicEq.filters(gains)"))
    assertTrue(curve.contains("EqResponse.db("))
    assertTrue(curve.contains("detectDragGestures"))
    assertTrue(curve.contains("onDoubleTap"))
  }

  @Test fun equalizerOffersPreciseAccessibleControlsAndFlatReset() {
    val screen = ui.substringAfter("internal fun SimpleEqualizerScreen(")
    assertTrue(screen.contains("Lower \${bandLabel(hz)} by 0.5 dB"))
    assertTrue(screen.contains("Raise \${bandLabel(hz)} by 0.5 dB"))
    assertTrue(screen.contains("Text(\"Flat\")"))
    assertTrue(screen.contains("SystemEqCurveBuilder.MANUAL_LIMIT_DB"))
    assertFalse(screen.contains("Apply manual tuning"))
    assertFalse(screen.contains("Save preset"))
  }

  @Test fun everyEditStillAutoAppliesAfterAShortDebounce() {
    val screen = ui.substringAfter("internal fun SimpleEqualizerScreen(")
    assertTrue(screen.contains("LaunchedEffect(gains, edited)"))
    assertTrue(screen.contains("latestOnBands(gains.toList())"))
  }
}
