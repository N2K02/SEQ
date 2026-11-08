package com.n2k02.seq.adb

import android.content.Context
import android.content.pm.PackageManager
import dev.n2k02.adb.core.AdbCore
import dev.n2k02.adb.core.AdbCoreRegistry
import dev.n2k02.adb.core.AdbHostHooks
import dev.n2k02.adb.core.AdbIdentity as CoreIdentity
import dev.n2k02.adb.core.AdbIdentitySource
import dev.n2k02.adb.core.AdbOption
import dev.n2k02.adb.core.AdbPreferenceStore
import dev.n2k02.adb.core.LibAdbWire
import dev.n2k02.adb.core.LocalAdbDiscovery

/** SEQ supplies its legacy identity/preferences, never a second ADB transport. */
internal object SharedAdbHostAdapter {
    @Volatile private var active: AdbCore? = null

    @Synchronized fun core(context: Context): AdbCore =
        active ?: create(context.applicationContext).also {
            active = it
            AdbCoreRegistry.install(it)
        }

    fun current(): AdbCore? = active

    suspend fun shutdown() {
        val old = synchronized(this) { active.also { active = null } }
        old?.let {
            AdbCoreRegistry.uninstall(it)
            it.quit()
        }
    }

    private fun create(context: Context): AdbCore {
        val app = context.applicationContext
        lateinit var adb: AdbCore
        val hooks = object : AdbHostHooks {
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
        adb = AdbCore(
            LibAdbWire(app, AdbIdentitySource { ctx ->
                val old = AdbIdentity.getOrCreate(ctx)
                CoreIdentity(old.privateKey, old.certificate)
            }, "SEQ"),
            Preferences(app), LocalAdbDiscovery(app), hooks
        )
        return adb
    }

    private class Preferences(private val context: Context) : AdbPreferenceStore {
        private val prefs = SeqAdbPreferences(context)
        override fun enabled(option: AdbOption) = when (option) {
            AdbOption.AUTO_PAIR -> prefs.autoPairCode
            AdbOption.WATCHDOG -> prefs.watchdog
            AdbOption.START_ON_BOOT -> prefs.startOnBoot
            AdbOption.RESTORE_AFTER_RESTART -> prefs.restoreAfterRestart
            AdbOption.TCP_5555 -> prefs.tcpMode
        }
        override fun setEnabled(option: AdbOption, enabled: Boolean) {
            when (option) {
                AdbOption.AUTO_PAIR -> prefs.autoPairCode = enabled
                AdbOption.WATCHDOG -> prefs.watchdog = enabled
                AdbOption.START_ON_BOOT -> prefs.startOnBoot = enabled
                AdbOption.RESTORE_AFTER_RESTART -> prefs.restoreAfterRestart = enabled
                AdbOption.TCP_5555 -> prefs.tcpMode = enabled
            }
            if (option == AdbOption.WATCHDOG || option == AdbOption.START_ON_BOOT)
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
