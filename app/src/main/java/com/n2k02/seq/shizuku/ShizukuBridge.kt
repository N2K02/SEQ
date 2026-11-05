package com.n2k02.seq.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow
import rikka.shizuku.Shizuku

/** On-demand Shizuku only. No elevated audio effects or continuous shell polling. */
class ShizukuBridge(private val context: Context) {
  val state = MutableStateFlow("Not connected")
  private var remote: ISeqShell? = null
  private var bound = false
  private val args by lazy {
    Shizuku.UserServiceArgs(ComponentName(context, SeqShellService::class.java))
      .daemon(false).processNameSuffix("seq_diagnostics").debuggable(false).version(1)
  }
  private val connection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
      remote = ISeqShell.Stub.asInterface(binder)
      state.value = try {
        val uid = remote?.uid() ?: -1
        if (uid == 2000) "Shell UID 2000 verified" else "Connected, UID " + uid + " (diagnostics disabled)"
      } catch (e: Exception) { "Shell handshake failed" }
    }
    override fun onServiceDisconnected(name: ComponentName?) {
      remote = null
      state.value = "Shizuku disconnected"
    }
  }
  fun connect() {
    try {
      if (!Shizuku.pingBinder()) { state.value = "Start Shizuku first"; return }
      if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
        Shizuku.requestPermission(1002)
        state.value = "Shizuku permission requested — tap Connect again when granted"
        return
      }
      if (!bound) {
        Shizuku.bindUserService(args, connection)
        bound = true
        state.value = "Connecting to Shizuku…"
      }
    } catch (e: Exception) { state.value = "Shizuku unavailable: " + e.javaClass.simpleName }
  }
  /** Caller must run off the main thread. */
  fun snapshot(): String = try {
    val shell = remote ?: return "Connect Shizuku first"
    if (shell.uid() != 2000) "UID 2000 not verified"
    else shell.audioSnapshot()
  } catch (e: Exception) { "Diagnostics failed: " + e.javaClass.simpleName }
  fun close() {
    if (bound) runCatching { Shizuku.unbindUserService(args, connection, true) }
    bound = false
    remote = null
    state.value = "Disconnected"
  }
}
