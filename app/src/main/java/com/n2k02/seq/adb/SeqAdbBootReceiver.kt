package com.n2k02.seq.adb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Honors manual Quit and Start on boot; never starts a foreground service directly. */
class SeqAdbBootReceiver:BroadcastReceiver() {
  override fun onReceive(context:Context,intent:Intent) {
    if(intent.action==Intent.ACTION_BOOT_COMPLETED ||
       intent.action==Intent.ACTION_MY_PACKAGE_REPLACED) {
      SeqAdbRecoveryWorker.requestBootRecovery(context.applicationContext)
      SeqAdbRecoveryWorker.reconcile(context.applicationContext)
    }
  }
}
