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
 @Test fun workManagerAwaitsTransportReleaseOnCancellation() {
   val client=source("LocalAdbDiagnostics.kt")
   val worker=source("SeqAdbRecoveryWorker.kt")
   assertTrue(client.contains("suspend fun recoverOnceForWorker()"))
   assertTrue(client.contains("withTimeoutOrNull(25_000L)"))
   assertTrue(client.contains("leavePageAndAwait()"))
   assertTrue(client.contains("currentJob?.join()"))
   assertTrue(client.contains("withTimeoutOrNull(5_000L)"))
   assertTrue(worker.contains("finally { client.disposeAndAwait() }"))
   assertTrue(worker.contains("catch(cancelled:CancellationException) { throw cancelled }"))
 }
 @Test fun backgroundAccessibilitySessionExpiresAndQuitCancelsWork() {
   val client=source("LocalAdbDiagnostics.kt")
   assertTrue(client.contains("delay(90_000L)"))
   assertTrue(client.contains("if(!foregroundPage) leavePageAndAwait()"))
   assertTrue(client.contains("SeqAdbRecoveryWorker.cancelAll(app)"))
 }
}
