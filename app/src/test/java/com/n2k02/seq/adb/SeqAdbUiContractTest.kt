package com.n2k02.seq.adb

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Regression checks for VRec-style automatic setup with no manual fallback UI. */
class SeqAdbUiContractTest {
 private fun source(name:String):String {
   val paths=listOf(File("src/main/java/com/n2k02/seq/$name"),
     File("app/src/main/java/com/n2k02/seq/$name"))
   return paths.first { it.isFile }.readText()
 }
 @Test fun removesManualPairingAndAboutLogsScreens() {
   val ui=source("MainActivity.kt")
   assertTrue(ui.contains("SEQ ADB Details"))
   assertTrue(ui.contains("Auto-detect pairing code"))
   assertTrue(ui.contains("Restore after restart"))
   assertFalse(ui.contains("Page.LOGS"))
   assertFalse(ui.contains("Page.ABOUT"))
   assertFalse(ui.contains("Pair with Android"))
   assertFalse(ui.contains("Local ADB connection port"))
   assertFalse(ui.contains("Six-digit pairing code"))
 }
 @Test fun pairingReaderNeverExaminesOtherAppContents() {
   val a=source("adb/SeqPairingAccessibilityService.kt")
   assertTrue(a.contains("SeqPairingCodeUiPolicy.isSettingsPackage"))
   assertFalse(a.contains("performAction("))
   assertFalse(a.contains("Settings.Secure.putString"))
 }
 @Test fun quitCancelsBackgroundRecoveryAndClosesTransport() {
   val a=source("adb/LocalAdbDiagnostics.kt")
   assertTrue(a.contains("SeqAdbRecoveryWorker.cancelAll(app)"))
   assertTrue(a.contains("discovery.stop()"))
   assertTrue(a.contains("AdbTransport.close()"))
 }
}
