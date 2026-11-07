package com.n2k02.seq.adb

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Prevent SEQ ADB settings drifting from VRec's two-card automatic recovery UI. */
class SeqAdbUiContractTest {
 private fun source(name:String):String {
   val paths=listOf(File("src/main/java/com/n2k02/seq/$name"),
     File("app/src/main/java/com/n2k02/seq/$name"))
   return paths.first { it.isFile }.readText()
 }
 @Test fun vrecSettingsParityWithoutExtraControls() {
   val ui=source("MainActivity.kt")
   val diagnostics=source("SeqAdbDiagnosticsScreen.kt")
   assertTrue(ui.contains("SeqAdbDiagnosticsScreen("))
   assertTrue(diagnostics.contains("AdbSettingCard(\"SEQ ADB Details\""))
   assertTrue(diagnostics.contains("status.uidVerified"))
   assertTrue(diagnostics.contains("status.verifiedAt"))
   assertTrue(diagnostics.contains("status.scanning"))
   assertTrue(diagnostics.contains("status.transport"))
   assertTrue(diagnostics.contains("AdbSettingCard(\"Pairing & recovery\""))
   assertTrue(diagnostics.contains("AdbSettingCard(\"Connection & security\""))
   listOf("Auto-detect pairing code","Connection watchdog","Start on boot",
     "Restore after restart","Keep ADB without Wi-Fi (TCP/5555)").forEach {
     assertTrue(diagnostics.contains(it))
   }
   assertEquals(5,Regex("AdbSettingToggle\\(\"").findAll(diagnostics).count())
   listOf("Permissions","Automatic connection","SEQ audio diagnostics",
     "Show discovered ports","Inspect audio","Retry","Open Wireless debugging",
     "Disable TCP/5555","OutlinedTextField","Enter pairing code").forEach {
     assertFalse("Unexpected manual or extra control: $it",diagnostics.contains(it))
   }
   assertTrue(diagnostics.contains("SeqPairingAccessibilityService.isEnabled(context)"))
   assertTrue(ui.contains("permissionRevision++"))
   assertFalse(ui.contains("Page.LOGS"))
   assertFalse(ui.contains("Page.ABOUT"))
 }
 @Test fun automaticAdbDefaultsMatchVrec() {
   val prefs=source("adb/SeqAdbPreferences.kt")
   assertTrue(prefs.contains("getBoolean(\"watchdog\",true)"))
   assertTrue(prefs.contains("getBoolean(\"start_on_boot\",true)"))
   assertTrue(prefs.contains("getBoolean(\"tcp_mode\",true)"))
   assertTrue(prefs.contains("getBoolean(\"auto_pair_code\",true)"))
   assertTrue(prefs.contains("getBoolean(\"restore_after_restart\",false)"))
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
