package com.n2k02.seq.adb

import org.junit.Assert.*
import org.junit.Test

class SeqAdbRestorePolicyTest {
 private fun allowed(boot:Int=5,verified:Int=4,optIn:Boolean=true,
     permission:Boolean=true,wifi:Boolean=true,attempted:Int=-1,paired:Boolean=true)=
   SeqAdbRestorePolicy.shouldRestore(optIn,paired,37,boot,verified,attempted,permission,wifi)
 @Test fun neverRestoresWithoutExplicitOptIn() { assertFalse(allowed(optIn=false)) }
 @Test fun neverOverridesManualOffInSameBoot() { assertFalse(allowed(verified=5)) }
 @Test fun retryIsBoundedToOneBootAttempt() { assertFalse(allowed(attempted=5)) }
 @Test fun rejectsUnknownBootAndMissingPrivilege() {
   assertFalse(allowed(boot=-1))
   assertFalse(allowed(permission=false))
   assertFalse(allowed(wifi=false))
   assertFalse(allowed(paired=false))
 }
 @Test fun permitsOptedInNewBootRecovery() { assertTrue(allowed()) }
}
