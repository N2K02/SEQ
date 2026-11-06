package com.n2k02.seq.adb

import org.junit.Assert.*
import org.junit.Test

class SeqAdbRecoveryPolicyTest {
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
