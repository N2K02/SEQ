package com.n2k02.seq.adb
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class SeqAdbWorkerLifecycleContractTest {
 private fun source(file:String):String {
   val p="app/src/main/java/com/n2k02/seq/adb/"+file
   return listOf(File(p),File("../"+p),File("src/main/java/com/n2k02/seq/adb/"+file))
     .first { it.isFile }.readText()
 }
 @Test fun workerUsesSingleCoreAndAwaitsReleaseEvenOnCancellation() {
   val client=source("LocalAdbDiagnostics.kt")
   val worker=source("SeqAdbRecoveryWorker.kt")
   assertTrue(client.contains("suspend fun recoverOnceForWorker()"))
   assertTrue(client.contains("withTimeoutOrNull(25_000L)"))
   assertTrue(client.contains("sharedCore.acquireService()"))
   assertTrue(client.contains("withContext(NonCancellable)"))
   assertTrue(client.contains("sharedCore.releaseService()"))
   assertTrue(client.contains("sharedCore.disconnectIdle()"))
   assertTrue(worker.contains("finally { client.disposeAndAwait() }"))
   assertTrue(worker.contains("catch(cancelled:CancellationException) { throw cancelled }"))
   assertTrue(worker.contains("SeqRuntime.log("))
   assertTrue(worker.contains("recovery failed: "))
 }
 @Test fun workerCannotDisconnectForegroundOwnerOnCompletion() {
   val client=source("LocalAdbDiagnostics.kt")
   val cleanup=client.substringAfter("suspend fun recoverOnceForWorker()")
     .substringBefore("suspend fun disposeAndAwait()")
   assertTrue(cleanup.contains("sharedCore.releaseService()"))
   assertTrue(cleanup.contains("if (!visible && active?.isPageActive() != true)"))
   assertTrue(cleanup.contains("sharedCore.disconnectIdle()"))
 }
 @Test fun navigatingAdbSettingsDoesNotClearManualStop() {
   val client=source("LocalAdbDiagnostics.kt")
   val enter=client.substringAfter("@Synchronized fun enterPage(")
     .substringBefore("@Synchronized fun leavePage()")
   assertTrue(enter.contains("sharedCore.enterPage()"))
   assertFalse(enter.contains("preferences.markUserOpened()"))
   val activity=listOf(File("app/src/main/java/com/n2k02/seq/MainActivity.kt"),
     File("../app/src/main/java/com/n2k02/seq/MainActivity.kt")).first { it.isFile }.readText()
   assertTrue(activity.contains("adb.preferences.markUserOpened()"))
 }
 @Test fun noParallelDiscoveryOrWatchdogInSeqFacade() {
   val client=source("LocalAdbDiagnostics.kt")
   assertFalse(client.contains("SeqAdbDiscovery("))
   assertFalse(client.contains("NsdManager"))
   assertFalse(client.contains("private var watchdog"))
   assertTrue(client.contains("sharedCore.enterPage()"))
   assertTrue(client.contains("sharedCore.leavePage()"))
   assertTrue(client.contains("SeqAdbRecoveryWorker.cancelAll(app)"))
 }
}
