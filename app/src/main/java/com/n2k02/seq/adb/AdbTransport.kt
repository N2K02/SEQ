package com.n2k02.seq.adb

import android.content.Context
import dev.n2k02.adb.core.AdbCore
import dev.n2k02.adb.core.AdbOption
import dev.n2k02.adb.core.AdbTransportMode
import kotlinx.coroutines.CancellationException

/** Legacy SEQ call-site API; ALL ADB I/O is shared and verifies UID 2000. */
object AdbTransport {
    const val LOOPBACK = "127.0.0.1"
    const val LEGACY_TCP_PORT = 5555
    val isConnected: Boolean get() = SharedAdbHostAdapter.current()?.state?.value?.connected == true
    val lastKnownConnected: Boolean get() = isConnected
    fun localTcpListenerPresent(): Boolean = AdbCore.localListenerAvailable()
    private fun core(): AdbCore = checkNotNull(SharedAdbHostAdapter.current()) {
        "SEQ ADB shared core not initialized; legacy transport fallback forbidden"
    }

    suspend fun exec(command: String, timeoutMs: Long = 15_000): Result<String> = try {
        Result.success(core().execVerified(command, timeoutMs))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) { Result.failure(e) }

    suspend fun pairLocal(context: Context, port: Int, code: String): Result<Unit> = try {
        check(core().pair(port, code)) { "ADB pairing failed" }
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) { Result.failure(e) }

    suspend fun connectLocalTls(context: Context, port: Int): Result<Unit> = try {
        check(core().connectTls(port)) { "ADB TLS failed" }
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) { Result.failure(e) }

    suspend fun connect(context: Context, host: String, port: Int): Result<Unit> = try {
        require(host == LOOPBACK && port == LEGACY_TCP_PORT)
        val adb = core()
        check(adb.retry(auto = true) &&
            adb.state.value.transport == AdbTransportMode.LOCAL_TCP_5555)
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) { Result.failure(e) }

    suspend fun switchToTcpip(): Result<Unit> = try {
        val adb = core()
        adb.setOption(AdbOption.TCP_5555, true)
        check(adb.retry() &&
            adb.state.value.transport == AdbTransportMode.LOCAL_TCP_5555)
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) { Result.failure(e) }

    suspend fun switchToUsb(): Result<Unit> = try {
        check(core().disableTcp())
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (e: Exception) { Result.failure(e) }

    /** Diagnostic/worker close is not a user-requested Stop. */
    suspend fun disconnect() { SharedAdbHostAdapter.current()?.disconnectIdle() }
    suspend fun close() = disconnect()
}
