package com.n2k02.seq.adb

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.content.Context

/** Retained only for pre-migration manifest compatibility; Shizuku needs no pairing reader. */
class SeqPairingAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    companion object {
        fun isEnabled(context: Context): Boolean = false
        fun onPairingPortDiscovered() {}
    }
}
