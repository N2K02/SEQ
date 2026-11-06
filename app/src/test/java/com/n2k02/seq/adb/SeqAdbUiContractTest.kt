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
   val diagnostics=source("SeqAdbDiagnosticsScreen.kt")
   assertTrue(ui.contains("SeqAdbDiagnosticsScreen("))
   assertTrue(diagnostics.contains("SEQ ADB Details"))
   assertTrue(diagnostics.contains("Auto-detect pairing code"))
   assertTrue(diagnostics.contains("Restore after restart"))
   assertTrue(diagnostics.contains("DiagnosticSection(\"Permissions\""))
   assertTrue(diagnostics.indexOf("DiagnosticSection(\"Permissions\"") <
     diagnostics.indexOf("DiagnosticSection(\"Automatic connection\""))
   assertTrue(diagnostics.contains("SeqPairingAccessibilityService.isEnabled(context)"))
   assertTrue(ui.contains("permissionRevision++"))
   assertFalse(ui.contains("Page.LOGS"))
   assertFalse(ui.contains("Page.ABOUT"))
   assertFalse(ui.contains("Pair with Android"))
   assertFalse(ui.contains("Local ADB connection port"))
   assertFalse(ui.contains("Six-digit pairing code"))
   assertFalse(diagnostics.contains("OutlinedTextField"))
   assertFalse(diagnostics.contains("Enter pairing code"))
 }
 @Test fun permissionsAreSeparateFromPairingSettings() {
   val diagnostics=source("SeqAdbDiagnosticsScreen.kt")
   val permissionBegin=diagnostics.indexOf("DiagnosticSection(\"Permissions\"")
   val pairingBegin=diagnostics.indexOf("DiagnosticSection(\"Pairing & recovery\"")
   assertTrue(permissionBegin>=0 && pairingBegin>permissionBegin)
   val permissions=diagnostics.substring(permissionBegin,pairingBegin)
   assertTrue(permissions.contains("ADB authorization"))
   assertTrue(permissions.contains("Accessibility"))
   assertTrue(permissions.contains("Secure settings"))
   assertTrue(permissions.contains("Notifications"))
   assertTrue(diagnostics.contains("permissionRevision"))
   assertTrue(diagnostics.contains("verifiedThisBoot="))
   assertTrue(diagnostics.contains("Optional"))
   assertTrue(diagnostics.contains("confirmDisableTcp"))
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
