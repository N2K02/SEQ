package com.n2k02.seq.adb

import org.junit.Assert.*
import org.junit.Test

class SeqAdbUiPolicyTest {
 @Test fun verifiedTcpDoesNotClaimWirelessPairingFailure() {
   assertEquals("Not required • authorized via TCP",
     SeqAdbUiPolicy.pairingLabel(false,true,"Local TCP/5555"))
   assertEquals("Wireless pairing saved",
     SeqAdbUiPolicy.pairingLabel(true,true,"Local TCP/5555"))
 }
 @Test fun lastKnownVerificationIsNotPresentedAsLiveSession() {
   assertEquals("UID 2000 previously verified • idle",
     SeqAdbUiPolicy.headline(false,true,"UID 2000 verified"))
   assertEquals("Checking local ADB…",
     SeqAdbUiPolicy.headline(true,true,"Connecting"))
 }
 @Test fun failedRechecksAreNotSilentlyClaimedAsSuccess() {
   assertEquals("ADB check failed • previous status retained",
     SeqAdbUiPolicy.headline(false,true,"ADB unavailable • ConnectionReset"))
   assertEquals("ADB setup required",
     SeqAdbUiPolicy.headline(false,false,"ADB disconnected"))
 }
}
