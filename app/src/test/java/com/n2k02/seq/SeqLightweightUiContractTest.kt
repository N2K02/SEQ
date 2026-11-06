package com.n2k02.seq

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Regression guard: avoid eager composition of hundreds of music rows. */
class SeqLightweightUiContractTest {
  private val source: String by lazy {
    val relative = "app/src/main/java/com/n2k02/seq/MainActivity.kt"
    listOf(File(relative),File("../"+relative),File("src/main/java/com/n2k02/seq/MainActivity.kt"))
      .first { it.isFile }.readText()
  }
  @Test fun libraryIsPagedWithinExistingScreen() {
    assertTrue(source.contains("music.take(visibleTrackCount).forEach"))
    assertTrue(source.contains("mutableIntStateOf(20)"))
    assertTrue(source.contains("Show more tracks"))
    assertFalse(source.contains("music.forEach { track ->"))
  }
  @Test fun navigationKeepsStateOnRotation() {
    assertTrue(source.contains("var page by rememberSaveable"))
    assertTrue(source.contains("topBar = { Header(page.title, pageIcon(page)) }"))
    assertFalse(source.contains("Page.LOGS"))
    assertFalse(source.contains("Page.ABOUT"))
  }
}
