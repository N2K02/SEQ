package com.n2k02.seq.audio

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.n2k02.seq.model.RouteKind
import com.n2k02.seq.model.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Experimental *device-side* EQ. FreeBuds 5 only: verified HFreebuds SPP packet format.
 * This sends one supported manufacturer preset, NOT arbitrary SEQ 10-band gains.
 * Never bypass Android DSP until the preset is read back from the actual earbuds.
 */
internal object HuaweiEqWire {
  const val READ = 0x2B4A
  const val WRITE = 0x2B49
  val presets = setOf(1, 2, 3, 9)
  fun crc(data: ByteArray): Int {
    var crc = 0
    for (byte in data) {
      crc = crc xor ((byte.toInt() and 255) shl 8)
      repeat(8) {
        crc = if ((crc and 0x8000) != 0) (crc shl 1) xor 0x1021 else crc shl 1
        crc = crc and 0xFFFF
      }
    }
    return crc
  }
  fun packet(command: Int, params: ByteArray): ByteArray {
    val len = params.size + 3
    val payload = byteArrayOf(0x5A, (len ushr 8).toByte(), len.toByte(), 0,
      (command ushr 8).toByte(), command.toByte()) + params
    val c = crc(payload)
    return payload + byteArrayOf((c ushr 8).toByte(), c.toByte())
  }
  fun write(preset: Int): ByteArray {
    require(preset in presets)
    return packet(WRITE, byteArrayOf(1, 1, preset.toByte()))
  }
  // Exact FreeBuds 5 read packet observed by HFreebuds from Huawei AI Life.
  fun read(): ByteArray = packet(READ, byteArrayOf(2, 0))
  /** Returns the readback preset, or null for corrupted / unrelated frames. */
  fun readPreset(frame: ByteArray): Int? {
    if (frame.size < 8 || frame[0] != 0x5A.toByte()) return null
    val len = ((frame[1].toInt() and 255) shl 8) or (frame[2].toInt() and 255)
    if (len + 5 != frame.size || len > 4096) return null
    val c = ((frame[frame.size - 2].toInt() and 255) shl 8) or
      (frame.last().toInt() and 255)
    if (crc(frame.copyOfRange(0, frame.size - 2)) != c) return null
    val command = ((frame[4].toInt() and 255) shl 8) or (frame[5].toInt() and 255)
    if (command != READ) return null
    var i = 6
    while (i + 2 <= frame.size - 2) {
      val t = frame[i].toInt() and 255
      val count = frame[i + 1].toInt() and 255
      if (i + 2 + count > frame.size - 2) return null
      if (t == 2 && count == 1) return frame[i + 2].toInt() and 255
      i += count + 2
    }
    return null
  }
}

object HuaweiNativeEq {
  private const val PREFS = "seq_native_bluetooth"
  private const val SERVICE_UUID = "00001101-0000-1000-8000-00805F9B34FB"
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var job: Job? = null
  @Volatile private var socketInUse: BluetoothSocket? = null
  val status = MutableStateFlow("Not configured")
  @Volatile private var confirmedRoute: String? = null
  @Volatile private var confirmedPreset: Int? = null
  @Volatile var lastResult: String = "Not configured"
    private set
  private fun report(message: String) {
    lastResult = message
    status.value = message
  }

  fun enabled(context: Context): Boolean =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false)
  fun preset(context: Context): Int =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("preset", 1)

  fun permitted(context: Context): Boolean =
    Build.VERSION.SDK_INT < 31 ||
      context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

  /** Never confuse a supported product name with proof that its SPP connection works. */
  fun targetRoute(context: Context): String? {
    if (SeqRoute.kind(context, SeqRoute.isCar()) != RouteKind.BLUETOOTH) return null
    val name = SeqRoute.describe(context, false)
    return name.takeIf { it.contains("FreeBuds 5", true) &&
      !it.contains("FreeBuds 5i", true) && !it.contains("FreeBuds 5 Pro", true) }
  }
  fun activeFor(context: Context): Boolean =
    SettingsStore(context).read().enabled && enabled(context) && targetRoute(context) == confirmedRoute &&
      confirmedPreset == preset(context)

  fun configure(context: Context, enabled: Boolean, selectedPreset: Int) {
    require(selectedPreset in HuaweiEqWire.presets)
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
      .putBoolean("enabled", enabled).putInt("preset", selectedPreset).apply()
    confirmedRoute = null
    confirmedPreset = null
    job?.cancel()
    runCatching { socketInUse?.close() }
    // Restores platform DSP until a device-side preset is actually verified.
    SeqGlobalEffect.sync(context)
    sync(context)
  }

  fun sync(context: Context) {
    val app = context.applicationContext
    val route = targetRoute(app)
    if (!SettingsStore(app).read().enabled || !enabled(app) || route == null || !permitted(app)) {
      if (confirmedRoute != null) {
        confirmedRoute = null
        confirmedPreset = null
        SeqGlobalEffect.sync(app)
      }
      if (enabled(app) && !permitted(app)) report("Bluetooth permission required")
      return
    }
    val target = preset(app)
    if (confirmedRoute == route && confirmedPreset == target) return
    if (job?.isActive == true) return
    job = scope.launch {
      val result = runCatching { writeAndVerify(app, route, target) }
      if (targetRoute(app) != route || !enabled(app) || preset(app) != target) return@launch
      if (result.getOrDefault(false)) {
        confirmedRoute = route
        confirmedPreset = target
        report("Native EQ verified preset=$target")
        SeqRuntime.log("Huawei native EQ verified preset=$target; Android mix can be bypassed")
      } else {
        confirmedRoute = null
        confirmedPreset = null
        report("Native EQ unavailable; Android fallback")
        SeqRuntime.log("Huawei native EQ readback unavailable; retaining Android DSP")
      }
      SeqGlobalEffect.sync(app)
    }
  }

  fun stop() {
    job?.cancel()
    runCatching { socketInUse?.close() }
    confirmedRoute = null
    confirmedPreset = null
  }

  @Suppress("MissingPermission")
  private suspend fun writeAndVerify(context: Context, route: String, preset: Int): Boolean {
    val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    val adapter = manager.adapter ?: return false
    if (!permitted(context)) return false
    val name = route.substringBefore(" • ")
    val device = adapter.bondedDevices.firstOrNull {
      (it.alias ?: it.name)?.equals(name, ignoreCase = true) == true
    } ?: return false
    val socket = device.createRfcommSocketToServiceRecord(UUID.fromString(SERVICE_UUID))
    socketInUse = socket
    val timeout = scope.launch {
      delay(4500)
      runCatching { socket.close() }
    }
    try {
      socket.connect()
      socket.outputStream.write(HuaweiEqWire.write(preset))
      socket.outputStream.flush()
      // Let the earbuds apply the setting before requesting the confirmed value.
      delay(350)
      socket.outputStream.write(HuaweiEqWire.read())
      socket.outputStream.flush()
      val input = socket.inputStream
      val bytes = ByteArray(512)
      val frames = ByteArrayOutputStream()
      while (frames.size() < 4096) {
        val n = input.read(bytes)
        if (n < 0) break
        frames.write(bytes, 0, n)
        val data = frames.toByteArray()
        for (start in data.indices) {
          if (data[start] != 0x5A.toByte() || start + 4 > data.size) continue
          val length = ((data[start + 1].toInt() and 255) shl 8) or
            (data[start + 2].toInt() and 255)
          if (length !in 3..4096 || start + length + 5 > data.size) continue
          val frame = data.copyOfRange(start, start + length + 5)
          if (HuaweiEqWire.readPreset(frame) == preset) return true
        }
      }
      return false
    } finally {
      timeout.cancel()
      if (socketInUse === socket) socketInUse = null
      runCatching { socket.close() }
    }
  }
}
