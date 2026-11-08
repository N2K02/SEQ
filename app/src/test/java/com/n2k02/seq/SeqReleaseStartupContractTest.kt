package com.n2k02.seq

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Prevent WorkManager/Room reflection from crashing AndroidX Startup in minified APKs. */
class SeqReleaseStartupContractTest {
  private fun source(path: String): String = listOf(
    File(path), File("../$path"), File(path.removePrefix("app/"))
  ).first { it.isFile }.readText()

  @Test fun releaseShrinkingRetainsWorkManagerRoomConstructors() {
    val gradle = source("app/build.gradle.kts")
    val rules = source("app/proguard-rules.pro")
    assertTrue(gradle.contains("isMinifyEnabled = true"))
    assertTrue(gradle.contains("\"proguard-rules.pro\""))
    assertTrue(rules.contains("-keep class androidx.work.impl.WorkDatabase_Impl"))
    assertTrue(rules.contains("public <init>();"))
    assertTrue(rules.contains("-keep class * extends androidx.room.RoomDatabase"))
    assertTrue(rules.contains("-keep class * extends androidx.work.InputMerger"))
  }
}
