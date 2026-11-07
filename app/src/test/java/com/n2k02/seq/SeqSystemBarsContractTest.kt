package com.n2k02.seq

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Same single-header, top inset and opaque bottom bar layout as mRadio. */
class SeqSystemBarsContractTest {
  private fun source(path:String):String {
    val relative="app/src/main/java/com/n2k02/seq/"+path
    return listOf(File(relative),File("../"+relative),
      File("src/main/java/com/n2k02/seq/"+path)).first { it.isFile }.readText()
  }
  @Test fun topHeaderRespectsStatusBarOnEveryPage() {
    val activity=source("MainActivity.kt")
    assertTrue(activity.contains("enableEdgeToEdge()"))
    assertTrue(activity.contains("topBar = { Header(page.title, pageIcon(page)) }"))
    val header=activity.substringAfter("@Composable private fun Header(")
      .substringBefore("@Composable private fun SurfaceCard(")
    assertTrue(header.contains(".statusBarsPadding()"))
    assertTrue(header.indexOf(".statusBarsPadding()") < header.indexOf(".padding(horizontal"))
    assertEquals(1,Regex("""@Composable private fun Header\(""").findAll(activity).count())
  }
  @Test fun bottomBarKeepsGesturesBehindOpaqueSurface() {
    val activity=source("MainActivity.kt")
    val bar=activity.substringAfter("bottomBar = {").substringBefore(") { padding ->")
    assertTrue(bar.contains("Surface("))
    assertTrue(bar.contains("Column(Modifier.navigationBarsPadding())"))
    assertTrue(bar.contains("windowInsets = WindowInsets(0, 0, 0, 0)"))
  }
  @Test fun settingsAndDiagnosticsDoNotAddExtraHostHeader() {
    val activity=source("MainActivity.kt")
    assertFalse(activity.contains("Page.LOGS ->"))
    assertFalse(activity.contains("Page.ABOUT ->"))
    assertEquals(1,Regex("""topBar = \{ Header\(""").findAll(activity).count())
  }
}
