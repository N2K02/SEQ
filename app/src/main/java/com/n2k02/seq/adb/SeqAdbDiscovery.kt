package com.n2k02.seq.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

/** Bounded VRec-style mDNS discovery; only ports belonging to this phone are accepted. */
class SeqAdbDiscovery(
  context: Context,
  private val scope:CoroutineScope,
  private val onPort:(pairing:Boolean,port:Int)->Unit,
  private val onMessage:(String)->Unit
) {
  private val app=context.applicationContext
  private val manager=app.getSystemService(Context.NSD_SERVICE) as? NsdManager
  private val listeners=mutableListOf<NsdManager.DiscoveryListener>()
  private var multicast:WifiManager.MulticastLock?=null
  private var expiry:Job?=null
  @Volatile private var active=false
  @Volatile private var generation=0

  val isActive:Boolean get()=active

  @Synchronized fun start(durationMs:Long=30_000L) {
    stop()
    if(manager==null) { onMessage("Wireless discovery is unavailable");return }
    active=true
    val token=++generation
    multicast=runCatching {
      (app.getSystemService(Context.WIFI_SERVICE) as WifiManager)
        .createMulticastLock("seq-adb-discovery").apply {
          setReferenceCounted(false);acquire()
        }
    }.getOrNull()
    discover("_adb-tls-connect._tcp",false,token)
    discover("_adb-tls-pairing._tcp",true,token)
    expiry=scope.launch {
      delay(durationMs.coerceIn(5_000,120_000))
      if(token==generation && active) {
        stop()
        onMessage("Discovery completed. Reopen Wireless debugging or rescan.")
      }
    }
  }
  @Synchronized private fun discover(type:String,pairing:Boolean,token:Int) {
    val nsd=manager ?: return
    val listener=object:NsdManager.DiscoveryListener {
      override fun onDiscoveryStarted(type:String)=Unit
      override fun onDiscoveryStopped(type:String)=Unit
      override fun onStartDiscoveryFailed(type:String,code:Int) {
        if(active && token==generation) onMessage("Wireless discovery unavailable ($code)")
      }
      override fun onStopDiscoveryFailed(type:String,code:Int)=Unit
      override fun onServiceLost(info:NsdServiceInfo)=Unit
      @Suppress("DEPRECATION")
      override fun onServiceFound(info:NsdServiceInfo) {
        if(!active || token!=generation) return
        runCatching {
          nsd.resolveService(info,object:NsdManager.ResolveListener {
            override fun onResolveFailed(service:NsdServiceInfo,code:Int)=Unit
            override fun onServiceResolved(service:NsdServiceInfo) {
              if(!active || token!=generation) return
              val address=service.host ?: return
              if(service.port !in 1..65535 || !isLocalDevice(address)) return
              onPort(pairing,service.port)
            }
          })
        }
      }
    }
    listeners+=listener
    runCatching { nsd.discoverServices(type,NsdManager.PROTOCOL_DNS_SD,listener) }
      .onFailure { onMessage("Could not start local Wireless debugging discovery") }
  }
  @Synchronized fun stop() {
    active=false
    ++generation // Ignore callbacks from old searches.
    expiry?.cancel();expiry=null
    val nsd=manager
    listeners.forEach { listener ->
      if(nsd!=null) runCatching { nsd.stopServiceDiscovery(listener) }
    }
    listeners.clear()
    runCatching { multicast?.let { if(it.isHeld) it.release() } }
    multicast=null
  }
  private fun isLocalDevice(address:InetAddress):Boolean=runCatching {
    Collections.list(NetworkInterface.getNetworkInterfaces())
      .flatMap { Collections.list(it.inetAddresses) }
      .any { it==address }
  }.getOrDefault(false)
}
