package com.n2k02.seq.adb

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

/**
 * Same VRec pairing-code UX: a user-enabled Accessibility service reads only
 * Android Settings' visible Wireless debugging pairing dialog. It never scans
 * other apps, clicks UI, persists pairing codes or logs them.
 */
class SeqPairingAccessibilityService:AccessibilityService() {
  companion object {
    @Volatile private var instance:SeqPairingAccessibilityService?=null
    fun isEnabled(context:Context):Boolean {
      val raw=Settings.Secure.getString(context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
      val component=ComponentName(context,SeqPairingAccessibilityService::class.java).flattenToString()
      return raw.split(':').any { it.equals(component,ignoreCase=true) }
    }
    fun onPairingPortDiscovered() {
      val service=instance ?: return
      service.handler.post { if(instance===service) service.scheduleRead() }
    }
  }
  private val handler=Handler(Looper.getMainLooper())
  private var remaining=0
  private var lastSignature:Int?=null
  private var lastDiscoveryAt=0L
  private val reader=object:Runnable {
    override fun run() {
      val controller=LocalAdbDiagnostics.active ?: return
      if(!controller.preferences.autoPairCode) return
      if(readFromSettings()) return
      if(remaining-->0) handler.postDelayed(this,400L)
    }
  }
  override fun onServiceConnected() {
    super.onServiceConnected()
    instance=this
    if(LocalAdbDiagnostics.active?.state?.value?.pairingPort!=null) scheduleRead()
  }
  private fun scheduleRead() {
    handler.removeCallbacks(reader)
    remaining=15
    reader.run()
  }
  private fun readFromSettings():Boolean {
    val controller=LocalAdbDiagnostics.active ?: return false
    if(!controller.preferences.autoPairCode) return false
    val root=rootInActiveWindow ?: return false
    if(!SeqPairingCodeUiPolicy.isSettingsPackage(root.packageName?.toString())) return false
    val nodes=ArrayDeque<AccessibilityNodeInfo>()
    val labels=mutableListOf<String>()
    nodes.add(root)
    var scanned=0
    while(nodes.isNotEmpty() && scanned++<100) {
      val node=nodes.removeFirst()
      node.text?.toString()?.let(labels::add)
      node.contentDescription?.toString()?.let(labels::add)
      for(i in 0 until node.childCount.coerceAtMost(20))
        node.getChild(i)?.let(nodes::add)
    }
    val code=SeqPairingCodeUiPolicy.extractCode(labels) ?: return false
    if(controller.state.value.pairingPort==null) {
      val now=SystemClock.elapsedRealtime()
      if(now-lastDiscoveryAt>10_000L) {
        lastDiscoveryAt=now
        controller.onAndroidPairingDialogVisible()
      }
      return false
    }
    val signature=(controller.state.value.pairingPort.toString()+":"+root.windowId+":"+code).hashCode()
    if(signature==lastSignature) return true
    lastSignature=signature
    controller.pairDetectedCode(code)
    return true
  }
  override fun onAccessibilityEvent(event:AccessibilityEvent?) {
    if(SeqPairingCodeUiPolicy.isSettingsPackage(event?.packageName?.toString())) scheduleRead()
  }
  override fun onInterrupt()=Unit
  override fun onDestroy() {
    handler.removeCallbacks(reader)
    if(instance===this)instance=null
    super.onDestroy()
  }
}
