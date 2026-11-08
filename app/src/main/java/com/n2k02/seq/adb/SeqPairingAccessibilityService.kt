package com.n2k02.seq.adb

import android.content.Context
import dev.n2k02.adb.core.AdbCore
import dev.n2k02.adb.core.SharedAdbPairingAccessibilityService

/** Manifest identity only. Strict Settings-only code extraction lives in ADB core. */
class SeqPairingAccessibilityService : SharedAdbPairingAccessibilityService() {
    companion object {
        fun isEnabled(context: Context): Boolean =
            SharedAdbPairingAccessibilityService.isEnabled(
                context, SeqPairingAccessibilityService::class.java
            )

        fun onPairingPortDiscovered() =
            SharedAdbPairingAccessibilityService.notifyPairingPortDiscovered()
    }

    override fun acquireCore(): AdbCore = SharedAdbHostAdapter.core(applicationContext)
}
