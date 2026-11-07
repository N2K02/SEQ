package com.n2k02.seq.adb

import android.content.Context
import dev.n2k02.adb.core.AdbCore
import dev.n2k02.adb.core.AdbIdentity as CoreIdentity
import dev.n2k02.adb.core.AdbIdentitySource
import dev.n2k02.adb.core.AdbOption
import dev.n2k02.adb.core.AdbPreferenceStore
import dev.n2k02.adb.core.LibAdbWire
import dev.n2k02.adb.core.LocalAdbDiscovery

/** Reuses SEQ's original RSA/X509 pair and preference store for future cutover.
 * Call only after retiring the legacy SEQ recovery engine. */
internal object SharedAdbHostAdapter {
    fun create(context: Context): AdbCore {
        val app = context.applicationContext
        return AdbCore(
            LibAdbWire(app, AdbIdentitySource { ctx ->
                val old = AdbIdentity.getOrCreate(ctx)
                CoreIdentity(old.privateKey, old.certificate)
            }, "SEQ"),
            Preferences(app), LocalAdbDiscovery(app),
        )
    }
    private class Preferences(context: Context) : AdbPreferenceStore {
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
        }
        override fun manuallyStopped() = prefs.manuallyStopped
        override fun setManuallyStopped(stopped: Boolean) { prefs.manuallyStopped = stopped }
        override fun lastTlsPort() = prefs.lastVerifiedPort
        override fun setLastTlsPort(port: Int) { prefs.markVerified(port) }
        override fun lastVerifiedAt() = prefs.lastVerifiedAt
        override fun setLastVerifiedAt(timeMs: Long) {
            // Existing SEQ markVerified() owns verified_at; do not rewrite it here.
        }
        override fun wasAuthorized() = prefs.previouslyAuthorized
        override fun setAuthorized() { prefs.markPaired() }
    }
}
