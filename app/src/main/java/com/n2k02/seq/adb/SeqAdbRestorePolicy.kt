package com.n2k02.seq.adb

/** Pure reboot gating from VRec. Never override Wireless debugging switched off in same boot. */
object SeqAdbRestorePolicy {
  fun shouldRestore(optedIn:Boolean,paired:Boolean,sdk:Int,boot:Int,
    verifiedBoot:Int,lastAttemptBoot:Int,permission:Boolean,onWifi:Boolean):Boolean =
    optedIn && paired && sdk>=30 && boot>=0 && verifiedBoot>=0 &&
      boot!=verifiedBoot && lastAttemptBoot!=boot && permission && onWifi
}
