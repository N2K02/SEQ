package com.n2k02.seq.adb

import org.junit.Assert.*
import org.junit.Test

class SeqPairingCodeUiPolicyTest {
 @Test fun acceptsOnlyAndroidSettings() {
   assertTrue(SeqPairingCodeUiPolicy.isSettingsPackage("com.android.settings"))
   assertFalse(SeqPairingCodeUiPolicy.isSettingsPackage("com.whatsapp"))
   assertFalse(SeqPairingCodeUiPolicy.isSettingsPackage("com.example.fake.settings"))
 }
 @Test fun requiresRealPairingDialog() {
   assertNull(SeqPairingCodeUiPolicy.extractCode(listOf("Phone number","012345")))
   assertEquals("012345",SeqPairingCodeUiPolicy.extractCode(listOf("Pair device with pairing code","012345")))
   assertEquals("123456",SeqPairingCodeUiPolicy.extractCode(listOf("Wi-Fi pairing code: 123456")))
 }
 @Test fun rejectsPartialOrSevenDigits() {
   assertNull(SeqPairingCodeUiPolicy.extractCode(listOf("Pairing code","12345")))
   assertNull(SeqPairingCodeUiPolicy.extractCode(listOf("Pairing code: 1234567")))
 }
}
