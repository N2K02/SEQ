package com.n2k02.seq.adb

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AdbDiagnosticState(
  val busy: Boolean = false,
  val message: String = "Idle • ADB is disconnected",
  val details: String = ""
)

/** Explicit, bounded localhost ports only. No shell commands can come from user input. */
object AdbAccessPolicy {
  fun port(input: String): Int? = input.toIntOrNull()?.takeIf { it in 1..65535 }
  fun pairingCode(code: String) = code.matches(Regex("[0-9]{6}"))
  fun verifiedUid(output: String): Boolean = output.trim() == "2000"
  const val AUDIO_DIAGNOSTIC = "dumpsys audio | grep -E -i 'audio route|output device|speaker|bluetooth|a2dp' | head -n 25"
}

/**
 * ADB is NEVER kept running for streaming DSP. One explicit action opens the localhost
 * transport, verifies shell UID 2000, executes a bounded read-only diagnostics command,
 * then disconnects. No WorkManager recovery or background polling.
 */
class LocalAdbDiagnostics(context: Context) {
  private val app = context.applicationContext
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val operation = Mutex()
  val state = MutableStateFlow(AdbDiagnosticState())

  fun inspect(portText: String) {
    val port = AdbAccessPolicy.port(portText)
    if (port == null) { state.value = AdbDiagnosticState(message = "Enter a port from 1 to 65535"); return }
    if (state.value.busy) return
    scope.launch {
      operation.withLock {
        state.value = AdbDiagnosticState(busy = true, message = "Connecting to local ADB…")
        try {
          AdbTransport.close()
          val connection = if (port == 5555)
            AdbTransport.connect(app, AdbTransport.LOOPBACK, port)
          else AdbTransport.connectLocalTls(app, port)
          connection.getOrThrow()
          val uid = withTimeout(12_000L) { AdbTransport.exec("id -u", 8_000).getOrThrow() }
          check(AdbAccessPolicy.verifiedUid(uid)) { "ADB did not verify shell UID 2000" }
          val result = withTimeout(12_000L) {
            AdbTransport.exec(AdbAccessPolicy.AUDIO_DIAGNOSTIC, 8_000).getOrThrow()
          }.take(2500)
          state.value = AdbDiagnosticState(
            message = "Shell UID 2000 verified • ADB disconnected after inspection",
            details = result.ifBlank { "No matching audio-routing data" }
          )
        } catch (e: CancellationException) {
          state.value = AdbDiagnosticState(message = "ADB operation cancelled")
          throw e
        } catch (e: Exception) {
          state.value = AdbDiagnosticState(message = "ADB diagnostics unavailable: " + e.javaClass.simpleName)
        } finally { AdbTransport.close() }
      }
    }
  }

  fun pair(portText: String, code: String) {
    val port = AdbAccessPolicy.port(portText)
    if (port == null || !AdbAccessPolicy.pairingCode(code)) {
      state.value = AdbDiagnosticState(message = "Enter the pairing port and six-digit code")
      return
    }
    if (state.value.busy) return
    scope.launch {
      operation.withLock {
        state.value = AdbDiagnosticState(busy = true, message = "Pairing with Android…")
        try {
          AdbTransport.close()
          withTimeout(20_000L) { AdbTransport.pairLocal(app, port, code).getOrThrow() }
          state.value = AdbDiagnosticState(message = "Paired. Enter the separate Wireless debugging connection port and Verify.")
        } catch (e: CancellationException) {
          state.value = AdbDiagnosticState(message = "Pairing cancelled")
          throw e
        } catch (e: Exception) {
          state.value = AdbDiagnosticState(message = "Pairing failed. Reopen the Android pairing dialog.")
        } finally { AdbTransport.close() }
      }
    }
  }

  fun shutdown() {
    scope.coroutineContext.cancelChildren()
    scope.launch { operation.withLock { AdbTransport.close() } }
    state.value = AdbDiagnosticState(message = "Stopped • ADB disconnected")
  }
}
