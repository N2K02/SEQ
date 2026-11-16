package com.n2k02.seq.adb

import android.content.Context
import com.n2k02.seq.shizuku.ShizukuSession
import kotlinx.coroutines.CancellationException

/** SEQ command facade through the per-app authorized Shizuku user service.
 * The historical method names are retained for callers, never for actual ADB sockets.
 */
object AdbTransport {
    const val LOOPBACK = "127.0.0.1"
    const val LEGACY_TCP_PORT = 5555

    val isConnected: Boolean get() = ShizukuHostAdapter.current()?.state?.value?.connected == true
    val lastKnownConnected: Boolean get() = isConnected
    fun localTcpListenerPresent(): Boolean = false

    private fun core(): ShizukuSession = checkNotNull(ShizukuHostAdapter.current()) {
        "Shizuku shell session has not been initialized"
    }

    suspend fun exec(command: String, timeoutMs: Long = 15_000): Result<String> = try {
        Result.success(core().execVerified(command, timeoutMs))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    suspend fun pairLocal(context: Context, port: Int, code: String): Result<Unit> =
        Result.failure(UnsupportedOperationException("Shizuku uses per-app authorization, not pairing"))

    suspend fun connectLocalTls(context: Context, port: Int): Result<Unit> = try {
        check(ShizukuHostAdapter.core(context).retry(auto = true)) {
            "Authorize SEQ using the running LocalADB Shizuku service"
        }
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    suspend fun connect(context: Context, host: String, port: Int): Result<Unit> = try {
        require(host == LOOPBACK && port == LEGACY_TCP_PORT)
        check(ShizukuHostAdapter.core(context).retry(auto = true)) {
            "Shizuku shell permission is required"
        }
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    suspend fun switchToTcpip(): Result<Unit> =
        Result.failure(UnsupportedOperationException("Shizuku never opens TCP/5555"))

    suspend fun switchToUsb(): Result<Unit> =
        Result.failure(UnsupportedOperationException("Shizuku cannot toggle USB debugging"))

    /** A diagnostic cleanup must not revoke another component's Shizuku authorization. */
    suspend fun disconnect() { ShizukuHostAdapter.current()?.disconnectIdle() }
    suspend fun close() = disconnect()
}
