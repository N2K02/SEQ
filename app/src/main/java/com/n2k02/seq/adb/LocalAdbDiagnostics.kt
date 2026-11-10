package com.n2k02.seq.adb

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
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
    // Bounded, read-only observations. ADB dumpsys does not expose assignable AudioEffect sessions.
    const val PLAYER_PROBE =
        "dumpsys media.audio_flinger | grep -E -i '(track|session|offload|a2dp|bluetooth)' | head -n 35"
    const val AUDIO_DIAGNOSTIC =
        "dumpsys audio | grep -E -i 'audio route|output device|speaker|bluetooth|a2dp' | head -n 25"
    // Read-only capability hint; never activates or registers routing.
    const val ROUTING_PROBE =
        "id -u; dumpsys media.audio_policy | grep -E -i 'r_submix|remote.?submix|mix route|policy mix|policy mixes' | head -n 24"
    /** Read-only vendor DSP/effect discovery. No policy mutations, PCM capture or media routing. */
    const val EFFECTS_PROBE =
        "dumpsys media.audio_flinger | grep -E -i '(effect chain|effect id|effect descriptor|effect name|reverb|spatial|virtualizer|offload|output thread|mixer thread)' | head -n 60"
    const val POLICY_PROBE =
        "dumpsys media.audio_policy | grep -E -i '(mix port|route|output|a2dp|offload|spatial|direct)' | head -n 60"
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
    @Volatile private var lastPlayerProbeAt = 0L
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

    val playerProbe = MutableStateFlow("Player diagnostics not checked")
    val dumpPermissionGranted: Boolean
        get() = app.checkSelfPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED
    val dumpGrant = MutableStateFlow("DUMP permission not checked")
    val routingProbe = MutableStateFlow("Not checked · exclusive routing unverified")
    val effectProbe = MutableStateFlow("Not checked · vendor effects unknown")
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
        if (shutDown || visible) return
        // MainActivity records a real foreground app launch. Merely navigating
        // between Settings pages must not clear an explicit ADB Stop.
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

    /** Explicit ADB permission request. DUMP is development/signature-restricted on
     * many builds; a successful command is not proof of usable audio-effect control.
     */
    fun grantDumpPermission() {
        if (shutDown) return
        scope.launch {
            dumpGrant.value = "Requesting diagnostic permission…"
            val result = try {
                if (!sharedCore.state.value.connected) {
                    "ADB not connected"
                } else {
                    val output = sharedCore.execVerified(
                        "pm grant com.n2k02.seq android.permission.DUMP", 8_000)
                    if (dumpPermissionGranted) {
                        "DUMP permission granted · diagnostics only"
                    } else if (output.contains("Exception", true) || output.contains("denied", true)) {
                        "DUMP permission unavailable on this Android build"
                    } else {
                        "DUMP permission not granted · Android restricted this permission"
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                SeqRuntime.log("DUMP permission grant unavailable: " + failure.javaClass.simpleName)
                "DUMP permission unavailable: " + failure.javaClass.simpleName
            }
            dumpGrant.value = result
        }
    }

    /** Poweramp-style diagnostic permission improves observations, not PCM access.
     * Never assume shell-visible session IDs can be used to attach to another app.
     */
    fun inspectPlayers() {
        if (shutDown) return
        scope.launch {
            playerProbe.value = "Inspecting player routing…"
            val result = try {
                if (!sharedCore.state.value.connected) {
                    "ADB disconnected · Android media callbacks remain active"
                } else {
                    val output = sharedCore.execVerified(AdbAccessPolicy.PLAYER_PROBE, 8_000)
                    val sample = output.lineSequence().map(String::trim)
                        .filter(String::isNotEmpty).take(35).toList()
                    val offload = sample.any { it.contains("offload", true) }
                    val bluetooth = sample.any { it.contains("bluetooth", true) || it.contains("a2dp", true) }
                    val matchingLines = sample.count { it.contains("track", true) || it.contains("session", true) }
                    val permission = if (dumpPermissionGranted) "DUMP granted" else "DUMP not granted"
                    SeqRuntime.log("ADB audio probe lines=$matchingLines offloadHint=$offload bluetoothHint=$bluetooth; PCM path unknown")
                    "ADB snapshot · $permission · track/session lines=$matchingLines · offload hint=$offload · Bluetooth hint=$bluetooth · effect path unverified"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                SeqRuntime.log("ADB player probe unavailable: " + failure.javaClass.simpleName)
                "ADB player probe unavailable"
            }
            playerProbe.value = result
        }
    }

    /** Automatic probe only when media-player identity changes; bounded to 30 seconds. */
    @Synchronized fun inspectPlayerChange() {
        if (shutDown || !sharedCore.state.value.connected) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPlayerProbeAt < 30_000L) return
        lastPlayerProbeAt = now
        inspectPlayers()
    }

    /** Explicit, read-only routing probe; never starts capture or redirects audio. */
    fun inspectRouting() {
        if (shutDown) return
        routingProbe.value = "Checking ADB audio policy…"
        scope.launch {
            val result = try {
                if (!sharedCore.retry(auto = false)) {
                    "ADB unavailable · audio routing unchanged"
                } else {
                    val output = sharedCore.execVerified(AdbAccessPolicy.ROUTING_PROBE, 8_000)
                    val lines = output.lineSequence().map { it.trim() }
                        .filter { it.isNotEmpty() }.take(25).toList()
                    val uid = lines.firstOrNull()
                    val hasSubmix = lines.any { line ->
                        line.contains("r_submix", ignoreCase = true) ||
                            line.contains("remote_submix", ignoreCase = true) ||
                            line.contains("remote submix", ignoreCase = true)
                    }
                    SeqRuntime.log("Routing probe shellUID=${if (uid == "2000") "2000" else "unverified"} submixHint=$hasSubmix")
                    if (uid != "2000") "Shell identity not verified · audio unchanged"
                    else if (hasSubmix) "Remote-submix hint found · exclusive route NOT verified"
                    else "Remote-submix not seen · exclusive route NOT verified"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                SeqRuntime.log("Routing probe failed: " + failure.javaClass.simpleName)
                "Routing probe failed: " + failure.javaClass.simpleName
            }
            routingProbe.value = result
        }
    }

    /** Capture only limited diagnostics from the already verified ADB shell.
     * Do not treat an effect name in a dump as proof of usable session-0 control.
     */
    fun inspectAudioEffects() {
        if (shutDown) return
        effectProbe.value = "Inspecting audio effects and policy…"
        scope.launch {
            val result = try {
                if (!sharedCore.state.value.connected) {
                    "ADB disconnected · no privileged effect discovery"
                } else {
                    val effects = sharedCore.execVerified(AdbAccessPolicy.EFFECTS_PROBE, 8_000)
                    val policy = sharedCore.execVerified(AdbAccessPolicy.POLICY_PROBE, 8_000)
                    val lines = effects.lineSequence().filter { it.isNotBlank() }.take(60).toList()
                    val routes = policy.lineSequence().filter { it.isNotBlank() }.take(60).toList()
                    val reverb = lines.any { it.contains("reverb", true) }
                    val spatial = lines.any { it.contains("spatial", true) || it.contains("virtualizer", true) }
                    val offload = (lines + routes).any { it.contains("offload", true) }
                    val bluetooth = routes.any { it.contains("a2dp", true) || it.contains("bluetooth", true) }
                    SeqRuntime.log("ADB effects lines=${lines.size} policyLines=${routes.size} reverbHint=$reverb spatialHint=$spatial offloadHint=$offload bluetoothHint=$bluetooth")
                    "AudioFlinger effects: ${lines.size} matching lines · reverb hint=$reverb · spatial hint=$spatial\n" +
                        "Audio policy: ${routes.size} matching lines · Bluetooth hint=$bluetooth · offload hint=$offload\n" +
                        "Discovery only: no PCM access or exclusive effect route confirmed"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                SeqRuntime.log("ADB effect probe failed: " + failure.javaClass.simpleName)
                "Effect discovery unavailable: " + failure.javaClass.simpleName
            }
            effectProbe.value = result
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
                // A foreground page may have opened while this worker was connecting.
                // Never retire the shared socket out from under its new UI owner.
                if (!visible && active?.isPageActive() != true)
                    sharedCore.disconnectIdle()
            }
        }
    }

    suspend fun disposeAndAwait() {
        leavePage()
        observer?.cancel()
        observer = null
        if (active === this@LocalAdbDiagnostics) active = null
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
            if (active === this@LocalAdbDiagnostics) active = null
            scope.cancel()
        }
    }

    fun shutdown() { scope.launch { shutdownAndAwait() } }
}
