package com.n2k02.seq.adb

/** Display semantics follow VRec: a remembered UID is never an active connection. */
data class SeqAdbViewState(
  val previouslyVerified:Boolean,val shell:String,val connection:String,
  val transport:String,val authorization:String
)
object SeqAdbDisplayPolicy {
  fun resolve(previouslyVerified:Boolean,working:Boolean,paired:Boolean,transport:String,
    verifiedThisBoot:Boolean=true):SeqAdbViewState {
    val last=previouslyVerified && verifiedThisBoot
    return SeqAdbViewState(
      previouslyVerified=last,
      shell=if(last)"UID 2000 (last)" else "Unverified",
      connection=if(working)"Checking…" else "Disconnected (idle)",
      transport=if(last && transport!="None" && transport.isNotBlank())
        "$transport (last)" else "None",
      authorization=when {
        paired -> "Previously paired"
        last -> "Authorized via ADB"
        previouslyVerified -> "Reverify this boot"
        else -> "Not authorized"
      }
    )
  }
}
