package com.n2k02.seq.adb
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Verified shell and stream lifetime belong to shared ADB core, never to SEQ. */
class AdbBrokenStreamRecoveryTest {
    private fun source(file: String): String = listOf(
        File("app/src/main/java/com/n2k02/seq/adb/" + file),
        File("../app/src/main/java/com/n2k02/seq/adb/" + file),
        File("src/main/java/com/n2k02/seq/adb/" + file),
    ).first { it.isFile }.readText()

    @Test fun allCommandsUseVerifiedSharedShell() {
        val transport = source("AdbTransport.kt")
        assertTrue(transport.contains("core().execVerified(command, timeoutMs)"))
        assertFalse(transport.contains("AbsAdbConnectionManager"))
        assertFalse(transport.contains("openStream("))
        assertFalse(transport.contains("readLine()"))
    }

    @Test fun closeReleasesSharedSessionWithoutDiscardingAuthorization() {
        val transport = source("AdbTransport.kt")
        assertTrue(transport.contains("disconnectIdle()"))
        assertTrue(transport.contains("suspend fun close() = disconnect()"))
        assertFalse(transport.contains("manager = null"))
    }

    @Test fun transportFailuresDoNotSwallowCancellation() {
        val transport = source("AdbTransport.kt")
        assertTrue(transport.contains("catch (cancelled: CancellationException)"))
        assertTrue(transport.contains("throw cancelled"))
        assertTrue(transport.contains("Result.failure(e)"))
    }
}
