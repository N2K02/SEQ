package com.n2k02.seq.adb

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SharedAdbHostAdapterContractTest {
    @Test fun importedSharedCoreIsPinnedAndPreservesIdentityAndChoiceStore() {
        val app = listOf(File("app"), File("../app")).first { it.isDirectory }
        val build = app.resolve("build.gradle.kts").readText()
        val source = app.resolve("src/main/java/com/n2k02/seq/adb/ShizukuHostAdapter.kt").readText()
        assertTrue(build.contains("dev.rikka.shizuku:api:13.1.5"))
        assertTrue(!build.contains("dev.n2k02:adb-core:"))
        assertTrue(source.contains("ShizukuSession("))
        assertTrue(source.contains("SeqAdbPreferences(context)"))
        assertTrue(source.contains("manuallyStopped()"))
        assertTrue(!source.contains("LibAdbWire"))
    }
}
