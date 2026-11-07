package com.n2k02.seq.adb

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SharedAdbHostAdapterContractTest {
    @Test fun importedSharedCoreIsPinnedAndPreservesIdentityAndChoiceStore() {
        val app = listOf(File("app"), File("../app")).first { it.isDirectory }
        val build = app.resolve("build.gradle.kts").readText()
        val source = app.resolve("src/main/java/com/n2k02/seq/adb/SharedAdbHostAdapter.kt").readText()
        assertTrue(build.contains("dev.n2k02:adb-core:0.1.3"))
        assertTrue(source.contains("AdbIdentity.getOrCreate(ctx)"))
        assertTrue(source.contains("SeqAdbPreferences(context)"))
        assertTrue(source.contains("manuallyStopped()"))
        assertTrue(source.contains("LibAdbWire"))
    }
}
