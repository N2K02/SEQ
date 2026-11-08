package com.n2k02.seq.adb

import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec

/**
 * Fresh private key object per libadb manager. The saved RSA identity remains
 * untouched even if libadb destroys the key during socket close.
 */
internal object AdbSessionKey {
    fun copyOf(persistent: PrivateKey): PrivateKey {
        val bytes = requireNotNull(persistent.encoded) {
            "ADB identity must be exportable for session reuse"
        }
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(bytes))
    }
}
