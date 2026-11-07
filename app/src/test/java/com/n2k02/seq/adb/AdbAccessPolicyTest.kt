package com.n2k02.seq.adb

import org.junit.Assert.*
import org.junit.Test

class AdbAccessPolicyTest {
  @Test fun acceptsLoopbackPortsOnly() {
    assertEquals(5555, AdbAccessPolicy.port("5555"))
    assertEquals(39717, AdbAccessPolicy.port("39717"))
    assertNull(AdbAccessPolicy.port("0"))
    assertNull(AdbAccessPolicy.port("65536"))
    assertNull(AdbAccessPolicy.port("192.168.1.7:5555"))
    assertNull(AdbAccessPolicy.port("5555;reboot"))
  }
  @Test fun exactSixDigits() {
    assertTrue(AdbAccessPolicy.pairingCode("012345"))
    assertFalse(AdbAccessPolicy.pairingCode("12345"))
    assertFalse(AdbAccessPolicy.pairingCode("123456;"))
  }
  @Test fun exactShellIdentity() {
    assertTrue(AdbAccessPolicy.verifiedUid("2000\n"))
    assertFalse(AdbAccessPolicy.verifiedUid("0"))
    assertFalse(AdbAccessPolicy.verifiedUid("2000\nroot"))
  }
  @Test fun diagnosticIsFixedAndReadOnly() {
    assertTrue(AdbAccessPolicy.AUDIO_DIAGNOSTIC.startsWith("dumpsys audio"))
    assertFalse(AdbAccessPolicy.AUDIO_DIAGNOSTIC.contains("setprop"))
    assertFalse(AdbAccessPolicy.AUDIO_DIAGNOSTIC.contains("pm grant"))
  }
}
