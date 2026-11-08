package com.n2k02.seq.adb

import org.junit.Assert.*
import org.junit.Test

class SeqAdbRecoveryPolicyTest {
 @Test fun disablingBootStartupDoesNotDisableSameBootForegroundRecovery() {
   assertFalse(SeqAdbStartupPolicy.mayRecover(false, 30, -1))
   assertFalse(SeqAdbStartupPolicy.mayRecover(false, 31, 30))
   assertFalse(SeqAdbStartupPolicy.mayRecover(false, -1, -1))
   assertTrue(SeqAdbStartupPolicy.mayRecover(false, 31, 31))
   assertTrue(SeqAdbStartupPolicy.mayRecover(true, 31, -1))
 }
 @Test fun noForeignEndpointIsIntroduced() {
   assertTrue(AdbAutoPolicy.candidates(true,false,null,null).all { it==5555 })
 }
 @Test fun newBootRejectsCachedPort() {
   assertNull(AdbAutoPolicy.cachedTlsPort(39001,4,5))
 }
 @Test fun pairedButUnverifiedDoesNotTrustTlsPort() {
   assertTrue(AdbAutoPolicy.candidates(false,false,null,39001).isEmpty())
 }
}
