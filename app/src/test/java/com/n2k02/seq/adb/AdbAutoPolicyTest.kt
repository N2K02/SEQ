package com.n2k02.seq.adb

import org.junit.Assert.*
import org.junit.Test

class AdbAutoPolicyTest {
  @Test fun reuseOnlyVerifiedSameBootTlsPort() {
    assertEquals(38813,AdbAutoPolicy.cachedTlsPort(38813,10,10))
    assertNull(AdbAutoPolicy.cachedTlsPort(38813,10,11))
    assertNull(AdbAutoPolicy.cachedTlsPort(38813,-1,-1))
    assertNull(AdbAutoPolicy.cachedTlsPort(0,1,1))
  }
  @Test fun tcpPortIsOnlyProbedWhenAllowed() {
    assertEquals(listOf(5555),AdbAutoPolicy.candidates(true,false,null,null))
    assertTrue(AdbAutoPolicy.candidates(false,false,null,null).isEmpty())
  }
  @Test fun discoveryIsNotTrustedAsAuthorization() {
    assertEquals(listOf(5555),AdbAutoPolicy.candidates(true,false,null,38711))
    assertEquals(listOf(5555,38711),AdbAutoPolicy.candidates(true,true,null,38711))
  }
  @Test fun autoRetryDeduplicatesAndHonorsSecurityPreference() {
    assertEquals(listOf(38711),AdbAutoPolicy.candidates(false,true,38711,38711))
    assertEquals(listOf(5555,38711,38001),AdbAutoPolicy.candidates(true,true,38711,38001))
  }
}
