package com.n2k02.seq.adb

/** Labels are based on the *last verified* session, never a live socket assumption. */
object SeqAdbUiPolicy {
  fun pairingLabel(paired:Boolean,authorized:Boolean,transport:String):String=when {
    paired -> "Wireless pairing saved"
    authorized && transport=="Local TCP/5555" -> "Not required • authorized via TCP"
    authorized -> "ADB key authorized"
    else -> "Pairing required"
  }
  fun headline(busy:Boolean,verified:Boolean,message:String):String=when {
    busy -> "Checking local ADB…"
    message.startsWith("ADB unavailable") -> "ADB check failed • previous status retained"
    message.startsWith("Pairing failed") -> "Automatic pairing unsuccessful"
    verified -> "UID 2000 previously verified • idle"
    else -> "ADB setup required"
  }
}
