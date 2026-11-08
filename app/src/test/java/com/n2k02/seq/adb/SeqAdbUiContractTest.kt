package com.n2k02.seq.adb
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Shared core is the only author of connection Settings and Diagnostics. */
class SeqAdbUiContractTest {
 private fun source(name:String):String {
   val paths=listOf(File("src/main/java/com/n2k02/seq/"+name),
     File("app/src/main/java/com/n2k02/seq/"+name))
   return paths.first { it.isFile }.readText()
 }
 @Test fun coreRendersBothConnectionCardsWithNoDuplicateUi() {
   val screens=source("SeqAdbDiagnosticsScreen.kt")
   assertTrue(screens.contains("AdbConnectionSettingsPage("))
   assertTrue(screens.contains("AdbDiagnosticsPage("))
   assertTrue(screens.contains("adb.sharedCore"))
   assertTrue(screens.contains("SeqPairingAccessibilityService.isEnabled(context)"))
   assertFalse(screens.contains("AdbSettingToggle("))
   assertFalse(screens.contains("OutlinedTextField"))
   assertFalse(screens.contains("Pairing & recovery"))
   assertFalse(screens.contains("Connection & security"))
   assertFalse(screens.contains("Enter pairing code"))
 }
 @Test fun diagnosticsIsRemovedAndHomeShowsLiveAdbBadge() {
   val ui=source("MainActivity.kt")
   assertFalse(ui.contains("Page.DIAGNOSTICS"))
   assertFalse(ui.contains("MenuRow(\"Diagnostics\""))
   assertTrue(ui.contains("Page.ADB_CONNECTION -> SeqAdbConnectionScreen("))
   assertTrue(ui.contains("val adbState by adb.sharedCore.state.collectAsState()"))
   assertTrue(ui.contains("if (adbState.connected) \"ADB connected\" else \"ADB disconnected\""))
   assertTrue(ui.indexOf("AudioCard {", ui.indexOf("Page.HOME -> {")) <
     ui.indexOf("AdbHomeSetupPage(", ui.indexOf("Page.HOME -> {")))
   assertTrue(ui.contains("permissionRevision++"))
 }
 @Test fun automaticAdbDefaultsMatchVrec() {
   val prefs=source("adb/SeqAdbPreferences.kt")
   listOf("watchdog","start_on_boot","tcp_mode","auto_pair_code").forEach {
     assertTrue(prefs.contains("getBoolean(\""+it+"\",true)"))
   }
   assertTrue(prefs.contains("getBoolean(\"restore_after_restart\",false)"))
 }
 @Test fun pairingReaderDelegatesSettingsOnlyPolicyToCore() {
   val reader=source("adb/SeqPairingAccessibilityService.kt")
   assertTrue(reader.contains("SharedAdbPairingAccessibilityService()"))
   assertTrue(reader.contains("override fun acquireCore()"))
   assertFalse(reader.contains("rootInActiveWindow"))
   assertFalse(reader.contains("performAction("))
 }
 @Test fun quitAwaitedBeforeFinishingActivity() {
   val ui=source("MainActivity.kt")
   val facade=source("adb/LocalAdbDiagnostics.kt")
   assertTrue(ui.contains("adb.shutdownAndAwait()"))
   assertTrue(facade.contains("SeqAdbRecoveryWorker.cancelAll(app)"))
   assertTrue(facade.contains("SharedAdbHostAdapter.shutdown()"))
 }
}
