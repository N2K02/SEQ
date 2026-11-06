package com.n2k02.seq.adb

import android.content.Context
import android.os.SystemClock
import com.n2k02.seq.audio.SeqRuntime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AdbDiagnosticState(
  val busy:Boolean = false,
  val message:String = "ADB disconnected",
  val details:String = "",
  val scanning:Boolean = false,
  val pairingPort:Int? = null,
  val connectPort:Int? = null,
  val lastVerifiedPort:Int? = null,
  val transport:String = "None",
  val verifiedAt:Long = 0L,
  val uidVerified:Boolean = false,
  val connected:Boolean = false
)

object AdbAccessPolicy {
  fun port(input:String):Int?=input.toIntOrNull()?.takeIf { it in 1..65535 }
  fun pairingCode(code:String)=code.matches(Regex("[0-9]{6}"))
  fun verifiedUid(output:String):Boolean=output.trim()=="2000"
  const val AUDIO_DIAGNOSTIC =
    "dumpsys audio | grep -E -i 'audio route|output device|speaker|bluetooth|a2dp' | head -n 25"
}

/**
 * Mirrors VRec's discovery/reconnect UX without a persistent shell or a second
 * background daemon. All automatic checks are scoped to the Diagnostics page.
 * A verified UID refers to the LAST check; the socket is always closed after
 * that check. NSD callbacks never cause a connection to any foreign IP.
 */
class LocalAdbDiagnostics(context:Context) {
  companion object {
    @Volatile var active:LocalAdbDiagnostics?=null
      private set
  }
  init { active=this }
  private val app=context.applicationContext
  val preferences=SeqAdbPreferences(app)
  private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
  private val operation=Mutex()
  private val tried=java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
  @Volatile private var visible=false
  @Volatile private var shutDown=false
  @Volatile private var lastAutoMs=0L
  private var watchdog:Job?=null
  private var job:Job?=null
  val state=MutableStateFlow(AdbDiagnosticState(
    message=if(preferences.previouslyAuthorized)
      "Previously authorized • disconnected to save battery" else "ADB setup required",
    uidVerified=preferences.previouslyAuthorized,
    verifiedAt=preferences.lastVerifiedAt,
    lastVerifiedPort=preferences.lastVerifiedPort
  ))
  private val discovery=SeqAdbDiscovery(app,scope,
    onPort={ pairing,port ->
      if(visible && !shutDown) {
        state.update { old ->
          if(pairing) old.copy(pairingPort=port,message="Pairing port automatically detected")
          else old.copy(connectPort=port,message="Wireless debugging connection port detected")
        }
        if(pairing) SeqPairingAccessibilityService.onPairingPortDiscovered()
        if(!pairing && preferences.automatic && preferences.previouslyAuthorized) {
          scope.launch { if(!state.value.busy) autoConnect() }
        }
      }
    },
    onMessage={ msg ->
      if(visible && !shutDown) state.update { it.copy(scanning=discoveryRunning(),message=msg) }
    }
  )
  // This avoids accessing the discovery property from its own initializer.
  private fun discoveryRunning():Boolean=runCatching { discovery.isActive }.getOrDefault(false)

  fun isPageActive():Boolean=visible
  /** WorkManager boot/watchdog recovery, bounded and disposed without a daemon. */
  suspend fun recoverOnceForWorker() {
    if(preferences.manuallyStopped || !preferences.previouslyAuthorized) return
    val baseline=state.value.verifiedAt
    enterPage(fromUser=false)
    try {
      var elapsed=0
      while(elapsed<25_000 && !preferences.manuallyStopped &&
        state.value.verifiedAt<=baseline && currentCoroutineContext().isActive) {
        delay(500)
        elapsed+=500
      }
    } finally { leavePage() }
  }
  fun enterPage(fromUser:Boolean=true) {
    if(shutDown || visible || (!fromUser && preferences.manuallyStopped)) return
    if(fromUser) preferences.markUserOpened()
    visible=true
    tried.clear()
    if(preferences.discoverWireless) discover(false)
    if(preferences.automatic) autoConnect()
    configureWatchdog()
  }
  fun leavePage() {
    visible=false
    discovery.stop()
    watchdog?.cancel();watchdog=null
    job?.cancel();job=null
    tried.clear()
    scope.launch {
      operation.withLock { AdbTransport.close() }
      state.update { it.copy(busy=false,scanning=false,connected=false) }
    }
  }
  fun resumed() {
    if(!visible || shutDown) return
    val now=SystemClock.elapsedRealtime()
    if(now-lastAutoMs<30_000) return
    if(preferences.discoverWireless) discover(false)
    if(preferences.automatic) autoConnect()
  }
  fun setAutoPairCode(enabled:Boolean) {
    preferences.autoPairCode=enabled
  }
  fun setTcpMode(enabled:Boolean) {
    val previous=preferences.tcpMode
    preferences.tcpMode=enabled
    if(enabled && visible && preferences.automatic) autoConnect()
    if(!enabled && previous && preferences.previouslyAuthorized) disconnectTcp()
  }
  fun setStartOnBoot(enabled:Boolean) {
    preferences.startOnBoot=enabled
    SeqAdbRecoveryWorker.reconcile(app)
  }
  fun setRestoreAfterRestart(enabled:Boolean) {
    val old=preferences.restoreAfterRestart
    preferences.restoreAfterRestart=enabled
    if(enabled && visible && preferences.automatic) {
      tried.clear()
      connect()
    } else if(!enabled && old &&
      app.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") ==
        android.content.pm.PackageManager.PERMISSION_GRANTED) {
      // VRec parity: return the optional permission when restore is disabled.
      scope.launch {
        operation.withLock {
          val ports=AdbAutoPolicy.candidates(true,preferences.previouslyAuthorized,
            preferences.lastVerifiedPort,state.value.connectPort)
          for(port in ports.take(3)) {
            try {
              withTimeout(12_000L) {
                (if(port==5555) AdbTransport.connect(app,AdbTransport.LOOPBACK,port)
                  else AdbTransport.connectLocalTls(app,port)).getOrThrow()
                check(AdbAccessPolicy.verifiedUid(AdbTransport.exec("id -u",6000).getOrThrow()))
                AdbTransport.exec("pm revoke "+app.packageName+
                  " android.permission.WRITE_SECURE_SETTINGS",6000).getOrThrow()
              }
              break
            } catch(_:Exception) {
              // Revocation is best effort if the authorized shell is unavailable.
            } finally {
              withContext(NonCancellable) { AdbTransport.close() }
            }
          }
        }
      }
    }
  }
  fun setAutomatic(enabled:Boolean) {
    preferences.automatic=enabled
    SeqAdbRecoveryWorker.reconcile(app)
    tried.clear()
    if(enabled && visible) autoConnect()
  }
  fun setDiscovery(enabled:Boolean) {
    preferences.discoverWireless=enabled
    if(!enabled) { discovery.stop();state.update { it.copy(scanning=false) } }
    else if(visible) discover(false)
  }
  fun setWatchdog(enabled:Boolean) {
    preferences.watchdog=enabled
    SeqAdbRecoveryWorker.reconcile(app)
    configureWatchdog()
  }
  fun inspectAutomatic() { inspect() }
  fun disconnectTcp() {
    if(shutDown || state.value.busy) return
    scope.launch {
      operation.withLock {
        state.update { it.copy(busy=true,message="Disabling TCP/5555…") }
        try {
          AdbTransport.close()
          AdbTransport.connect(app,AdbTransport.LOOPBACK,5555).getOrThrow()
          check(AdbAccessPolicy.verifiedUid(AdbTransport.exec("id -u",8000).getOrThrow()))
          AdbTransport.switchToUsb().getOrThrow()
          preferences.tcpMode=false
          state.update { it.copy(message="TCP/5555 disabled; Wireless debugging remains available",transport="None") }
        } catch(e:Exception) {
          state.update { it.copy(message="Could not disable TCP/5555: "+e.javaClass.simpleName) }
        } finally {
          withContext(NonCancellable) { AdbTransport.close() }
          state.update { it.copy(busy=false,connected=false) }
        }
      }
    }
  }
  fun setUseExistingTcp(enabled:Boolean) {
    preferences.preferExistingTcp=enabled
    tried.clear()
    if(enabled && visible && preferences.automatic) autoConnect()
  }
  private fun configureWatchdog() {
    watchdog?.cancel();watchdog=null
    if(!visible || shutDown || !preferences.watchdog) return
    watchdog=scope.launch {
      while(isActive && visible && preferences.watchdog) {
        delay(120_000)
        if(!visible || !preferences.watchdog) break
        tried.clear()
        if(preferences.automatic) autoConnect()
      }
    }
  }
  fun discover(userInitiated:Boolean) {
    if(shutDown || !visible) return
    state.update { it.copy(scanning=true,message="Discovering this phone's Wireless debugging ports…") }
    discovery.start(if(userInitiated)90_000L else 25_000L)
  }
  private fun autoConnect() {
    if(!visible || shutDown || !preferences.automatic || state.value.busy) return
    val now=SystemClock.elapsedRealtime()
    if(now-lastAutoMs<4_000L) return
    lastAutoMs=now
    val ports=AdbAutoPolicy.candidates(
      preferences.preferExistingTcp,preferences.previouslyAuthorized,
      preferences.lastVerifiedPort,state.value.connectPort
    ).filter { tried.add(it) }
    if(ports.isNotEmpty()) runCheck(ports,false)
  }
  fun connect() = connectDiscovered(null)
  private fun connectDiscovered(port:Int?) {
    if(shutDown || state.value.busy) return
    val ports=(listOfNotNull(port)+AdbAutoPolicy.candidates(
      preferences.preferExistingTcp,preferences.previouslyAuthorized,
      preferences.lastVerifiedPort,state.value.connectPort)).distinct()
    if(ports.isEmpty()) {
      state.update { it.copy(message="No ADB endpoint. Open Wireless debugging and retry automatic discovery.") }
      return
    }
    tried.clear()
    runCheck(ports,false)
  }
  fun inspect() {
    if(shutDown || state.value.busy) return
    val ports=AdbAutoPolicy.candidates(preferences.preferExistingTcp,
      preferences.previouslyAuthorized,preferences.lastVerifiedPort,state.value.connectPort)
    if(ports.isEmpty()) {
      state.update { it.copy(message="No ADB endpoint. Open Wireless debugging and retry automatic discovery.") }
      return
    }
    runCheck(ports,true)
  }
  private fun runCheck(ports:List<Int>,readAudio:Boolean) {
    if(shutDown || state.value.busy) return
    job=scope.launch {
      operation.withLock {
        if(shutDown || (!visible && !readAudio)) return@withLock
        state.update { it.copy(busy=true,connected=false,
          message=if(readAudio) "Checking local audio via ADB…" else "Connecting automatically to ADB…") }
        var error="No authorized local ADB endpoint"
        var success=false
        try {
          for(port in ports.distinct().take(3)) {
            if(shutDown || !isActive) break
            try {
              state.update { it.copy(message="Authenticating ADB at 127.0.0.1:$port…") }
              withTimeout(16_000L) {
                (if(port==5555) AdbTransport.connect(app,AdbTransport.LOOPBACK,port)
                  else AdbTransport.connectLocalTls(app,port)).getOrThrow()
                val uid=AdbTransport.exec("id -u",8_000).getOrThrow()
                check(AdbAccessPolicy.verifiedUid(uid)) { "Shell UID 2000 could not be verified" }
                val detail=if(readAudio)
                  AdbTransport.exec(AdbAccessPolicy.AUDIO_DIAGNOSTIC,8_000).getOrThrow().take(2500)
                  else state.value.details
                if(preferences.restoreAfterRestart &&
                  app.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED) {
                  AdbTransport.exec(
                    "pm grant "+app.packageName+" android.permission.WRITE_SECURE_SETTINGS",
                    8000
                  ).getOrNull()
                  if(app.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED)
                    SeqRuntime.log("ADB restore permission denied by Android")
                }
                // Like VRec: migrate only an authorized TLS session to TCP/5555
                // when the user opted into that mode. Reverify UID after restart.
                var verifiedPort=port
                if(port!=5555 && preferences.tcpMode) {
                  AdbTransport.switchToTcpip().getOrThrow()
                  withContext(NonCancellable) { AdbTransport.close() }
                  var tcpVerified=false
                  repeat(12) {
                    if(!tcpVerified) {
                      delay(650)
                      val ready=runCatching {
                        AdbTransport.connect(app,AdbTransport.LOOPBACK,5555).getOrThrow()
                        AdbAccessPolicy.verifiedUid(AdbTransport.exec("id -u",8000).getOrThrow())
                      }.getOrDefault(false)
                      if(ready) tcpVerified=true
                      else AdbTransport.close()
                    }
                  }
                  check(tcpVerified) { "TCP/5555 did not reverify shell UID 2000" }
                  verifiedPort=5555
                }
                preferences.markVerified(port)
                SeqAdbRecoveryWorker.reconcile(app)
                state.update { it.copy(
                  message="UID 2000 verified • disconnected to save battery",
                  verifiedAt=preferences.lastVerifiedAt,uidVerified=true,
                  lastVerifiedPort=verifiedPort,transport=if(verifiedPort==5555)"Local TCP/5555" else "Wireless TLS",
                  details=detail.ifBlank { "No matching audio routing lines" },connected=false
                ) }
                SeqRuntime.log("ADB UID 2000 verified via "+if(verifiedPort==5555)"TCP" else "TLS")
                success=true
              }
              if(success) break
            } catch(e:TimeoutCancellationException) {
              error="Timed out connecting to localhost:$port"
              preferences.clearStalePort(port)
            } catch(e:CancellationException) {
              throw e
            } catch(e:Exception) {
              error="Port $port: "+e.javaClass.simpleName
              preferences.clearStalePort(port)
            } finally {
              withContext(NonCancellable) { AdbTransport.close() }
            }
          }
          if(!success && visible) {
            state.update { it.copy(message="ADB unavailable • $error",connected=false) }
          }
        } catch(e:CancellationException) {
          state.update { it.copy(message="ADB connection stopped",connected=false) }
          throw e
        } finally {
          state.update { it.copy(busy=false,connected=false) }
          // Any new discovered port that arrived during the first probe gets
          // one additional attempt, never an unbounded retry loop.
          if(visible && preferences.automatic && !success && state.value.connectPort!=null) {
            val port=state.value.connectPort!!
            if(tried.add(port)) scope.launch { delay(400);autoConnect() }
          }
        }
      }
    }
  }
  fun onAndroidPairingDialogVisible() {
    if(!shutDown && preferences.autoPairCode) {
      if(!visible) enterPage()
      if(!discovery.isActive) discover(true)
    }
  }
  fun pairDetectedCode(code:String) {
    if(!preferences.autoPairCode || !visible || shutDown) return
    if(state.value.pairingPort==null) return
    // A stale unauthorized 5555 probe must not block the pairing dialog.
    if(state.value.busy) job?.cancel()
    pair(code)
  }
  private fun pair(code:String) {
    val port=state.value.pairingPort ?: return
    if(!AdbAccessPolicy.pairingCode(code)) {
      state.update { it.copy(message="Android Settings did not provide a valid six-digit pairing code") }
      return
    }
    if(shutDown) return
    job=scope.launch {
      operation.withLock {
        state.update { it.copy(busy=true,message="Pairing with Android…") }
        var paired=false
        try {
          withTimeout(20_000L) {
            AdbTransport.close()
            AdbTransport.pairLocal(app,port,code).getOrThrow()
          }
          preferences.markPaired()
          paired=true
          state.update { it.copy(message="Paired • ready to verify the connection port") }
          SeqRuntime.log("Wireless debugging pairing completed")
        } catch(e:CancellationException) {
          state.update { it.copy(message="Pairing cancelled") }
          throw e
        } catch(e:Exception) {
          state.update { it.copy(message="Pairing failed • reopen Android pairing dialog") }
        } finally {
          withContext(NonCancellable) { AdbTransport.close() }
          state.update { it.copy(busy=false,connected=false) }
        }
        if(paired && visible) {
          val portNow=state.value.connectPort
          if(portNow!=null) scope.launch { delay(350); connectDiscovered(portNow) }
          else discover(true)
        }
      }
    }
  }
  fun disconnect() {
    preferences.automatic=false
    preferences.manuallyStopped=true
    SeqAdbRecoveryWorker.reconcile(app)
    job?.cancel()
    discovery.stop()
    watchdog?.cancel();watchdog=null
    state.update { it.copy(connected=false,busy=false,scanning=false,
      message="Manually disconnected • automatic connection disabled") }
    scope.launch { operation.withLock { AdbTransport.close() } }
  }
  fun shutdown() {
    if(active===this) active=null
    preferences.manuallyStopped=true
    SeqAdbRecoveryWorker.cancelAll(app)
    shutDown=true
    visible=false
    discovery.stop()
    watchdog?.cancel();watchdog=null
    job?.cancel();job=null
    scope.coroutineContext.cancelChildren()
    scope.launch { operation.withLock { AdbTransport.close() } }
    state.update { it.copy(busy=false,connected=false,scanning=false,
      message="SEQ ADB stopped") }
  }
}
