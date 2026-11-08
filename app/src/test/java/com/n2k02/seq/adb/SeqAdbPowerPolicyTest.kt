package com.n2k02.seq.adb
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class SeqAdbPowerPolicyTest {
 private fun source(file: String): String {
   val p="app/src/main/java/com/n2k02/seq/adb/"+file
   return listOf(File(p), File("../"+p), File("src/main/java/com/n2k02/seq/adb/"+file))
     .first { it.isFile }.readText()
 }
 @Test fun automaticRecoveryDefaultsAndRestoreOptInRemain() {
   val prefs=source("SeqAdbPreferences.kt")
   for(key in listOf("watchdog","start_on_boot","automatic","tcp_mode"))
     assertTrue(prefs.contains("getBoolean(\""+key+"\",true)"))
   assertTrue(prefs.contains("getBoolean(\"restore_after_restart\",false)"))
 }
 @Test fun tcpPromotionAndUidVerificationAreInSharedCore() {
   val transport=source("AdbTransport.kt")
   assertTrue(transport.contains("AdbCore.localListenerAvailable()"))
   assertTrue(transport.contains("adb.state.value.transport == AdbTransportMode.LOCAL_TCP_5555"))
   assertTrue(transport.contains("core().execVerified(command, timeoutMs)"))
   assertFalse(source("LocalAdbDiagnostics.kt").contains("switchToTcpip()"))
   assertFalse(source("LocalAdbDiagnostics.kt").contains("openStream("))
 }
}
