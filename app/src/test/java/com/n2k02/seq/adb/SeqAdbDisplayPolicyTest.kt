package com.n2k02.seq.adb

import org.junit.Assert.*
import org.junit.Test

class SeqAdbDisplayPolicyTest {
  @Test fun lastVerificationIsNotActiveSession() {
    val s=SeqAdbDisplayPolicy.resolve(true,false,false,"Local TCP/5555")
    assertEquals("Disconnected (idle)",s.connection)
    assertEquals("UID 2000 (last)",s.shell)
    assertEquals("Authorized via ADB",s.authorization)
    assertEquals("Local TCP/5555 (last)",s.transport)
  }
  @Test fun firstTimeMeansNotAuthorized() {
    val s=SeqAdbDisplayPolicy.resolve(false,false,false,"None")
    assertEquals("Unverified",s.shell)
    assertEquals("Not authorized",s.authorization)
  }
  @Test fun checkingDoesNotMeanVerified() {
    val s=SeqAdbDisplayPolicy.resolve(false,true,false,"Wireless TLS")
    assertEquals("Checking…",s.connection)
    assertEquals("Unverified",s.shell)
    assertEquals("None",s.transport)
  }
  @Test fun rememberedUidFromPreviousBootNeedsNewVerification() {
    val stale=SeqAdbDisplayPolicy.resolve(true,false,false,"Local TCP/5555",
      verifiedThisBoot=false)
    assertFalse(stale.previouslyVerified)
    assertEquals("Unverified",stale.shell)
    assertEquals("Disconnected (idle)",stale.connection)
    assertEquals("Reverify this boot",stale.authorization)
    assertEquals("None",stale.transport)
  }
  @Test fun pairingIsSeparateFromAuthorization() {
    assertEquals("Previously paired",
      SeqAdbDisplayPolicy.resolve(true,false,true,"Wireless TLS").authorization)
  }
}
