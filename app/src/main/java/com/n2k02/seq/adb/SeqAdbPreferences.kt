package com.n2k02.seq.adb

import android.content.Context
import android.provider.Settings

/**
 * SEQ retains authorized endpoints, never pairing codes. Dynamic TLS ports are
 * reusable only in the same Android boot; a remembered port is NOT proof of a
 * currently authenticated ADB connection.
 */
class SeqAdbPreferences(context: Context) {
  private val app=context.applicationContext
  private val prefs=app.getSharedPreferences("seq_adb_connection",Context.MODE_PRIVATE)
  var automatic: Boolean
    get()=prefs.getBoolean("automatic",true)
    set(value){ prefs.edit().putBoolean("automatic",value).apply() }
  var discoverWireless: Boolean
    get()=prefs.getBoolean("discover_wireless",true)
    set(value){ prefs.edit().putBoolean("discover_wireless",value).apply() }
  var watchdog: Boolean
    get()=prefs.getBoolean("watchdog",true)
    set(value){ prefs.edit().putBoolean("watchdog",value).apply() }
  var preferExistingTcp: Boolean
    get()=prefs.getBoolean("prefer_existing_tcp",true)
    set(value){ prefs.edit().putBoolean("prefer_existing_tcp",value).apply() }
  var autoPairCode:Boolean
    get()=prefs.getBoolean("auto_pair_code",true)
    set(v) {prefs.edit().putBoolean("auto_pair_code",v).apply()}
  var tcpMode:Boolean
    get()=prefs.getBoolean("tcp_mode",true)
    set(v) {prefs.edit().putBoolean("tcp_mode",v).apply()}
  var startOnBoot:Boolean
    get()=prefs.getBoolean("start_on_boot",true)
    set(v) {prefs.edit().putBoolean("start_on_boot",v).apply()}
  var restoreAfterRestart:Boolean
    get()=prefs.getBoolean("restore_after_restart",false)
    set(v) {prefs.edit().putBoolean("restore_after_restart",v).apply()}
  var manuallyStopped:Boolean
    get()=prefs.getBoolean("manual_quit",false)
    set(v) {prefs.edit().putBoolean("manual_quit",v).commit()}
  val verifiedBoot:Int get()=prefs.getInt("verified_boot",-1)
  val currentBoot:Int get()=boot()
  val lastRestoreAttemptBoot:Int get()=prefs.getInt("restore_attempt_boot",-1)
  fun markRestoreAttempted(boot:Int) {
    if(boot>=0) prefs.edit().putInt("restore_attempt_boot",boot).apply()
  }
  fun markUserOpened() { manuallyStopped=false }
  val previouslyAuthorized:Boolean get()=prefs.getBoolean("previously_authorized",false)
  val lastVerifiedAt:Long get()=prefs.getLong("verified_at",0L)
  val lastVerifiedTransport:String get()=prefs.getString("verified_transport","None") ?: "None"
  private fun boot():Int=runCatching {
    Settings.Global.getInt(app.contentResolver,Settings.Global.BOOT_COUNT,-1)
  }.getOrDefault(-1)
  val lastVerifiedPort:Int?
    get()=AdbAutoPolicy.cachedTlsPort(
      prefs.getInt("tls_port",-1),
      prefs.getInt("tls_boot",-1),boot()
    )
  fun markPaired(){ prefs.edit().putBoolean("paired",true).apply() }
  val wasPaired:Boolean get()=prefs.getBoolean("paired",false)
  fun markVerified(port:Int, verifiedPort:Int=port) {
    if(port !in 1..65535 || verifiedPort !in 1..65535) return
    val editor=prefs.edit().putBoolean("previously_authorized",true)
      .putLong("verified_at",System.currentTimeMillis()).putInt("verified_boot",boot())
      .putString("verified_transport",if(verifiedPort==5555) "Local TCP/5555" else "Wireless TLS")
    if(port!=5555 && boot()>=0) {
      editor.putInt("tls_port",port).putInt("tls_boot",boot())
    }
    editor.apply()
  }
  fun clearStalePort(port:Int) {
    if(lastVerifiedPort==port) prefs.edit().remove("tls_port").remove("tls_boot").apply()
  }
}
/** Safe-to-test connection candidate policy; never probes foreign IP addresses. */
object AdbAutoPolicy {
  fun cachedTlsPort(port:Int,savedBoot:Int,currentBoot:Int):Int? =
    port.takeIf { it in 1..65535 && currentBoot>=0 && savedBoot==currentBoot && it!=5555 }
  fun shouldRestartAdbdForTcp(port:Int,enabled:Boolean,listenerPresent:Boolean):Boolean =
    enabled && port!=5555 && !listenerPresent
  fun candidates(preferTcp:Boolean,previouslyAuthorized:Boolean,cachedPort:Int?,discoveredPort:Int?):List<Int> {
    val endpoints=mutableListOf<Int>()
    if(preferTcp) endpoints+=5555
    if(previouslyAuthorized) {
      if(cachedPort != null && cachedPort in 1..65535) endpoints+=cachedPort
      if(discoveredPort != null && discoveredPort in 1..65535) endpoints+=discoveredPort
    }
    return endpoints.distinct()
  }
}
