package com.n2k02.seq.adb

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** VRec-style OS-managed safety net; no permanent shell or busy-loop. */
class SeqAdbRecoveryWorker(context:Context,params:WorkerParameters):
  CoroutineWorker(context,params) {
  override suspend fun doWork():Result {
    val app=applicationContext
    val p=SeqAdbPreferences(app)
    val bootRecovery=inputData.getBoolean("boot_recovery",false)
    if(p.manuallyStopped || !p.automatic || !p.startOnBoot ||
      !p.previouslyAuthorized || (!bootRecovery && !p.watchdog)) return Result.success()
    try {
      if(p.restoreAfterRestart) restoreIfEligible(app,p)
      if(LocalAdbDiagnostics.active?.isPageActive()==true) return Result.success()
      // WorkManager cannot replace the foreground Accessibility pairing owner.
      // Every worker gets a short-lived controller and releases its callbacks.
      val client=LocalAdbDiagnostics(app,registerAsActive=false)
      try { client.recoverOnceForWorker() }
      finally { client.dispose() }
      return Result.success()
    } catch(cancelled:CancellationException) { throw cancelled }
    catch(e:Exception) { return Result.success() }
  }
  companion object {
    private const val WATCHDOG="seq-adb-watchdog"
    private const val BOOT="seq-adb-boot"
    fun reconcile(context:Context) {
      val app=context.applicationContext
      val p=SeqAdbPreferences(app)
      val wm=WorkManager.getInstance(app)
      if(!p.manuallyStopped && p.startOnBoot && p.watchdog && p.automatic &&
        p.previouslyAuthorized) {
        val req=PeriodicWorkRequestBuilder<SeqAdbRecoveryWorker>(15,TimeUnit.MINUTES).build()
        wm.enqueueUniquePeriodicWork(WATCHDOG,ExistingPeriodicWorkPolicy.KEEP,req)
      } else wm.cancelUniqueWork(WATCHDOG)
    }
    fun cancelAll(context:Context) {
      val wm=WorkManager.getInstance(context.applicationContext)
      wm.cancelUniqueWork(WATCHDOG)
      wm.cancelUniqueWork(BOOT)
    }
    fun requestBootRecovery(context:Context) {
      val app=context.applicationContext
      val p=SeqAdbPreferences(app)
      if(p.manuallyStopped || !p.startOnBoot || !p.automatic ||
        !p.previouslyAuthorized) return
      WorkManager.getInstance(app).enqueueUniqueWork(BOOT,ExistingWorkPolicy.REPLACE,
        OneTimeWorkRequestBuilder<SeqAdbRecoveryWorker>()
          .setInputData(workDataOf("boot_recovery" to true)).build())
    }
    /** Only after reboot, never overriding Wireless debugging manually disabled in same boot. */
    private fun restoreIfEligible(context:Context,p:SeqAdbPreferences) {
      val cm=context.getSystemService(ConnectivityManager::class.java) ?: return
      val wifi=cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(
        NetworkCapabilities.TRANSPORT_WIFI)==true
      val granted=context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")==
        PackageManager.PERMISSION_GRANTED
      if(!SeqAdbRestorePolicy.shouldRestore(p.restoreAfterRestart,p.wasPaired,
        Build.VERSION.SDK_INT,p.currentBoot,p.verifiedBoot,p.lastRestoreAttemptBoot,
        granted,wifi)) return
      val enabled=Settings.Global.getInt(context.contentResolver,"adb_wifi_enabled",0)
      if(enabled==1) return
      p.markRestoreAttempted(p.currentBoot)
      Settings.Global.putInt(context.contentResolver,"adb_wifi_enabled",1)
      check(Settings.Global.getInt(context.contentResolver,"adb_wifi_enabled",0)==1) {
        "Android rejected Wireless debugging reboot restoration"
      }
    }
  }
}
