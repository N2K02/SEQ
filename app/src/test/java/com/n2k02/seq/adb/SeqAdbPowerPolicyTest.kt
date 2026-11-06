package com.n2k02.seq.adb

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class SeqAdbPowerPolicyTest {
 private val source by lazy {
  val f = "app/src/main/java/com/n2k02/seq/adb/SeqAdbPreferences.kt"
  listOf(File(f),File("../"+f),File("src/main/java/com/n2k02/seq/adb/SeqAdbPreferences.kt"))
    .first { it.isFile }.readText()
 }
 @Test fun backgroundJobsAreOffUntilExplicitOptIn() {
  assertTrue(source.contains("getBoolean(\"watchdog\",false)"))
  assertTrue(source.contains("getBoolean(\"start_on_boot\",false)"))
 }
 @Test fun openingDiagnosticsDoesNotStartNetworkReachableAdbd() {
  assertTrue(source.contains("getBoolean(\"tcp_mode\",false)"))
  assertTrue(source.contains("getBoolean(\"automatic\",true)"))
 }
}
