package com.n2k02.seq.adb

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/**
 * Background recovery defaults follow VRec: active only after a valid, previously
 * authorized identity; diagnostics alone may not restart adbd on an unknown device.
 * Reboot-time Wireless debugging restoration remains explicitly opt-in.
 */
class SeqAdbPowerPolicyTest {
    private val source by lazy {
        val f = "app/src/main/java/com/n2k02/seq/adb/SeqAdbPreferences.kt"
        listOf(File(f), File("../" + f),
            File("src/main/java/com/n2k02/seq/adb/SeqAdbPreferences.kt"))
            .first { it.isFile }.readText()
    }
    private val manager by lazy {
        val f = "app/src/main/java/com/n2k02/seq/adb/LocalAdbDiagnostics.kt"
        listOf(File(f), File("../" + f),
            File("src/main/java/com/n2k02/seq/adb/LocalAdbDiagnostics.kt"))
            .first { it.isFile }.readText()
    }

    @Test fun automaticRecoveryDefaultsMatchVrecAndRestoreRequiresOptIn() {
        assertTrue(source.contains("getBoolean(\"watchdog\",true)"))
        assertTrue(source.contains("getBoolean(\"start_on_boot\",true)"))
        assertTrue(source.contains("getBoolean(\"automatic\",true)"))
        assertTrue(source.contains("getBoolean(\"restore_after_restart\",false)"))
    }

    @Test fun tcpPromotionNeedsVerifiedShellAndDoesNotRestartExistingListener() {
        assertTrue(source.contains("getBoolean(\"tcp_mode\",true)"))
        assertTrue(source.contains("getBoolean(\"prefer_existing_tcp\",true)"))
        assertTrue(source.contains("!listenerPresent"))
        assertTrue(manager.contains("AdbAccessPolicy.verifiedUid(uid)"))
        assertTrue(manager.contains("AdbAutoPolicy.shouldRestartAdbdForTcp("))
        assertTrue(manager.contains("AdbTransport.localTcpListenerPresent()"))
        assertTrue(manager.contains("check(tcpVerified)"))
    }
}
