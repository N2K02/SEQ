package com.n2k02.seq.adb

import android.content.Context
import com.n2k02.seq.audio.SeqRuntime
import dev.n2k02.adb.core.AdbCore
import dev.n2k02.adb.core.AdbOption
import dev.n2k02.adb.core.AdbState
import dev.n2k02.adb.core.AdbTransportMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class AdbDiagnosticState(
    val busy: Boolean = false,
    val message: String = "ADB disconnected",
    val details: String = "",
    val scanning: Boolean = false,
    val pairingPort: Int? = null,
    val connectPort: Int? = null,
    val lastVerifiedPort: Int? = null,
    val transport: String = "None",
    val verifiedAt: Long = 0L,
    val uidVerified: Boolean = false,
    val connected: Boolean = false,
)

object AdbAccessPolicy {
    fun port(input: String): Int? = input.toIntOrNull()?.takeIf { it in 1..65535 }
    fun pairingCode(code: String): Boolean = code.matches(Regex("[0-9]{6}"))
    fun verifiedUid(output: String): Boolean = output.trim() == "2000"
    const val AUDIO_DIAGNOSTIC =
        "dumpsys audio | grep -E -i 'audio route|output device|speaker|bluetooth|a2dp' | head -n 25"
}

/** SEQ-specific audio diagnostics only; ADB connection, discovery, pairing,
 * watchdog and UI are exclusively delegated to n2k02AdbCoreLibrary.
 */
class LocalAdbDiagnostics(context: Context, registerAsActive: Boolean = true) {
    companion object {
        @Volatile var active: LocalAdbDiagnostics? = null
            private set
    }

    private val app = context.applicationContext
    val preferences = SeqAdbPreferences(app)
    val sharedCore: AdbCore = SharedAdbHostAdapter.core(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observer: Job? = null
    @Volatile private var visible = false
    @Volatile private var shutDown = false

    private fun snapshot(s: AdbState): AdbDiagnosticState {
        val mode = when (s.transport) {
            AdbTransportMode.WIRELESS_TLS -> "Wireless TLS"
            AdbTransportMode.LOCAL_TCP_5555 -> "Local TCP/5555"
            AdbTransportMode.NONE -> "None"
        }
        return state.value.copy(
            busy = s.phase == dev.n2k02.adb.core.AdbPhase.CONNECTING ||
                s.phase == dev.n2k02.adb.core.AdbPhase.PAIRING,
            message = s.message,
            scanning = s.scanning,
            pairingPort = s.pairingPort,
            connectPort = s.connectionPort,
            lastVerifiedPort = preferences.lastVerifiedPort,
            transport = mode,
            verifiedAt = s.lastVerifiedAt,
            uidVerified = s.connected,
            connected = s.connected,
        )
    }

    val state = MutableStateFlow(AdbDiagnosticState(
        message = if (preferences.previouslyAuthorized) "Previously authorized • idle" else "ADB setup required",
        verifiedAt = preferences.lastVerifiedAt,
        lastVerifiedPort = preferences.lastVerifiedPort,
    ))

    init {
        if (registerAsActive) active = this
        observer = scope.launch { sharedCore.state.collect { state.value = snapshot(it) } }
    }

    fun isPageActive(): Boolean = visible

    @Synchronized fun enterPage(fromUser: Boolean = true) {
        if (shutDown || (!fromUser && preferences.manuallyStopped) || visible) return
        if (fromUser) preferences.markUserOpened()
        visible = true
        sharedCore.enterPage()
    }

    @Synchronized fun leavePage() {
        if (!visible) return
        visible = false
        sharedCore.leavePage()
    }

    fun resumed() {
        if (visible && !shutDown)
            scope.launch { sharedCore.retry(auto = true) }
    }

    fun setAutoPairCode(enabled: Boolean) { scope.launch { sharedCore.setOption(AdbOption.AUTO_PAIR, enabled) } }
    fun setTcpMode(enabled: Boolean) { scope.launch { sharedCore.setOption(AdbOption.TCP_5555, enabled) } }
    fun setStartOnBoot(enabled: Boolean) {
        scope.launch { sharedCore.setOption(AdbOption.START_ON_BOOT, enabled) }
    }
    fun setRestoreAfterRestart(enabled: Boolean) {
        scope.launch { sharedCore.setOption(AdbOption.RESTORE_AFTER_RESTART, enabled) }
    }
    fun setAutomatic(enabled: Boolean) { preferences.automatic = enabled }
    fun setDiscovery(enabled: Boolean) { preferences.discoverWireless = enabled }
    fun setWatchdog(enabled: Boolean) { scope.launch { sharedCore.setOption(AdbOption.WATCHDOG, enabled) } }
    fun setUseExistingTcp(enabled: Boolean) { preferences.preferExistingTcp = enabled }
    fun discover(userInitiated: Boolean) {
        scope.launch { sharedCore.retry(auto = !userInitiated) }
    }
    fun connect() { scope.launch { sharedCore.retry(auto = false) } }
    fun inspectAutomatic() = inspect()

    /** Audio dumpsys is an app-specific command executed only via verified UID 2000 shell. */
    fun inspect() {
        if (shutDown) return
        scope.launch {
            try {
                if (!sharedCore.retry(auto = true)) return@launch
                val output = sharedCore.execVerified(AdbAccessPolicy.AUDIO_DIAGNOSTIC, 8_000)
                state.value = state.value.copy(details = output.take(2_500))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                SeqRuntime.log("SEQ audio diagnostics unavailable: " + failure.javaClass.simpleName)
            }
        }
    }

    fun disconnectTcp() { scope.launch { sharedCore.disableTcp() } }
    fun onAndroidPairingDialogVisible() { sharedCore.beginAutomaticPairingDiscovery() }
    fun pairDetectedCode(code: String) {
        val port = sharedCore.state.value.pairingPort ?: return
        scope.launch { sharedCore.pair(port, code) }
    }
    fun disconnect() { scope.launch { sharedCore.stop() } }

    /** OS backstop runs against the same instance, not another libadb socket. */
    suspend fun recoverOnceForWorker() {
        if (preferences.manuallyStopped || !preferences.previouslyAuthorized || shutDown) return
        sharedCore.acquireService()
        try {
            withTimeoutOrNull(25_000L) { sharedCore.retry(auto = true) }
        } finally {
            withContext(NonCancellable) {
                sharedCore.releaseService()
                if (!visible) sharedCore.disconnectIdle()
            }
        }
    }

    suspend fun disposeAndAwait() {
        leavePage()
        observer?.cancel()
        observer = null
        scope.cancel()
    }

    fun dispose() { scope.launch { disposeAndAwait() } }

    suspend fun shutdownAndAwait() {
        if (shutDown) return
        shutDown = true
        SeqAdbRecoveryWorker.cancelAll(app)
        leavePage()
        withContext(NonCancellable) {
            SharedAdbHostAdapter.shutdown()
            observer?.cancel()
            observer = null
            scope.cancel()
        }
    }

    fun shutdown() { scope.launch { shutdownAndAwait() } }
}
