package com.n2k02.seq.adb

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Consumer regression for the release-only libadb getDeviceName recursion.
 * adb-core 0.1.7 includes the qualified outer-field fix, committed as 44e75f8.
 * Pairing/connect/reconnect still require an on-device release APK smoke test.
 */
class AdbDeviceNameRegressionTest {
    private fun root(): File =
        generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
            .first { it.resolve("app/build.gradle.kts").isFile && it.resolve("app/src/main/java/com/n2k02/seq/adb/SharedAdbHostAdapter.kt").isFile }

    @Test fun allAdbDependenciesPinFixedLibrary() {
        val root = root()
        val app = root.resolve("app/build.gradle.kts").readText()
        assertTrue(app.contains("dev.n2k02:adb-core:0.1.7"))
        assertFalse(app.contains("dev.n2k02:adb-core:0.1.4"))
        
        // Resolving the class ensures the unit-test runtime includes the shared transport.
        assertTrue(Class.forName("dev.n2k02.adb.core.LibAdbWire").name.endsWith("LibAdbWire"))
    }

    @Test fun appUsesSharedTransportWithExplicitIdentityInsteadOfAnAnonymousRecursiveGetter() {
        val adapter = root().resolve("app/src/main/java/com/n2k02/seq/adb/SharedAdbHostAdapter.kt").readText()
        assertTrue(adapter.contains("LibAdbWire"))
        assertFalse(adapter.contains("override fun getDeviceName()"))
        assertFalse(adapter.contains("AbsAdbConnectionManager()"))
        
    }
}
