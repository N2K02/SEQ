package com.n2k02.seq

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Regression guard: compact UI without a music library. */
class SeqLightweightUiContractTest {
  private val source: String by lazy {
    val relative = "app/src/main/java/com/n2k02/seq/MainActivity.kt"
    listOf(File(relative),File("../"+relative),File("src/main/java/com/n2k02/seq/MainActivity.kt"))
      .first { it.isFile }.readText()
  }
  @Test fun musicLibraryIsRemoved() {
    assertFalse(source.contains("Music Library"))
    assertFalse(source.contains("LocalMusicLibrary"))
    assertFalse(source.contains("Page.PLAYER"))
  }
  @Test fun navigationKeepsStateOnRotation() {
    assertTrue(source.contains("var page by rememberSaveable"))
    assertTrue(source.contains("topBar = { Header(page.title, pageIcon(page)) }"))
    assertFalse(source.contains("Page.LOGS"))
    assertFalse(source.contains("Page.ABOUT"))
  }
}
