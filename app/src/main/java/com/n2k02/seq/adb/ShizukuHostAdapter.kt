package com.n2k02.seq.adb

import android.content.Context
import android.content.pm.PackageManager
import com.n2k02.seq.shizuku.ShizukuSession
import com.n2k02.seq.shizuku.ShizukuSessionRegistry
import com.n2k02.seq.shizuku.ShizukuHostHooks
import com.n2k02.seq.shizuku.ShizukuOption
import com.n2k02.seq.shizuku.ShizukuPreferences

/** SEQ supplies its legacy identity/preferences, never a second ADB transport. */
internal object ShizukuHostAdapter {
    @Volatile private var active: ShizukuSession? = null

    @Synchronized fun core(context: Context): ShizukuSession =
        active ?: create(context.applicationContext).also {
            active = it
            ShizukuSessionRegistry.install(it)
        }

    fun current(): ShizukuSession? = active

    suspend fun shutdown() {
        val old = synchronized(this) { active.also { active = null } }
        old?.let {
            ShizukuSessionRegistry.uninstall(it)
            it.quit()
        }
    }

    private fun create(context: Context): ShizukuSession {
        val app = context.applicationContext
        lateinit var adb: ShizukuSession
        val hooks = object : ShizukuHostHooks {
            override suspend fun onRestoreAfterRestartChanged(enabled: Boolean) {
                val permission = "android.permission.WRITE_SECURE_SETTINGS"
                val granted = app.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
                if (granted == enabled) return
                val pkg = app.packageName.takeIf { it.matches(
                    Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")
                ) } ?: return
                val command = if (enabled) "grant" else "revoke"
                runCatching {
                    adb.execVerified("pm " + command + " " + pkg + " " + permission, 10_000)
                }
            }
        }
        adb = ShizukuSession(app, Preferences(app), hooks)
        return adb
    }

    private class Preferences(private val context: Context) : ShizukuPreferences {
        private val prefs = SeqAdbPreferences(context)
        override fun enabled(option: ShizukuOption) = when (option) {
            ShizukuOption.AUTO_PAIR -> prefs.autoPairCode
            ShizukuOption.WATCHDOG -> prefs.watchdog
            ShizukuOption.START_ON_BOOT -> prefs.startOnBoot
            ShizukuOption.RESTORE_AFTER_RESTART -> prefs.restoreAfterRestart
            ShizukuOption.TCP_5555 -> prefs.tcpMode
        }
        override fun setEnabled(option: ShizukuOption, enabled: Boolean) {
            when (option) {
                ShizukuOption.AUTO_PAIR -> prefs.autoPairCode = enabled
                ShizukuOption.WATCHDOG -> prefs.watchdog = enabled
                ShizukuOption.START_ON_BOOT -> prefs.startOnBoot = enabled
                ShizukuOption.RESTORE_AFTER_RESTART -> prefs.restoreAfterRestart = enabled
                ShizukuOption.TCP_5555 -> prefs.tcpMode = enabled
            }
            if (option == ShizukuOption.WATCHDOG || option == ShizukuOption.START_ON_BOOT)
                SeqAdbRecoveryWorker.reconcile(context)
        }
        override fun manuallyStopped() = prefs.manuallyStopped
        override fun setManuallyStopped(stopped: Boolean) { prefs.manuallyStopped = stopped }
        override fun lastTlsPort() = prefs.lastVerifiedPort
        override fun setLastTlsPort(port: Int) { prefs.markVerified(port) }
        override fun lastVerifiedAt() = prefs.lastVerifiedAt
        override fun setLastVerifiedAt(timeMs: Long) { prefs.setVerifiedAt(timeMs) }
        override fun wasAuthorized() = prefs.previouslyAuthorized
        override fun setAuthorized() { prefs.markAuthorized() }
    }
}
