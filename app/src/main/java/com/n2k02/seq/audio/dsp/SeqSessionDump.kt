package com.n2k02.seq.audio.dsp

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.util.concurrent.TimeUnit

/** One app player as reported by Android's audio service. */
data class SeqPlayback(
  val sessionId: Int,
  val uid: Int,
  val usage: String,
  val started: Boolean,
  /** The player opted out of playback capture (AudioAttributes FLAG_NO_MEDIA_PROJECTION). */
  val captureBlockedByPlayer: Boolean
) {
  /** Same usages SEQ DSP captures; anything else (calls, alarms, notifications) is untouched. */
  val mediaUsage: Boolean get() = usage in MEDIA_USAGES
  companion object {
    val MEDIA_USAGES = setOf("USAGE_MEDIA", "USAGE_GAME", "USAGE_UNKNOWN")
    const val FLAG_NO_MEDIA_PROJECTION = 0x400
  }
}

/**
 * Pure parsers for `dumpsys audio` and `dumpsys media.audio_policy`, tolerant of the field
 * order differences between Android 10 and 17. Based on the session discovery approach of
 * RootlessJamesDSP/RootlessBrennanDSP (DUMP permission); independently written.
 */
object SeqSessionDumpParser {
  private val uidPid = Regex("""u/pid:\s*(\d+)\s*/\s*(\d+)""")
  private val usage = Regex("""usage=(\w+)""")
  private val session = Regex("""session(?:Id)?:\s*(\d+)""", RegexOption.IGNORE_CASE)
  private val state = Regex("""state:\s*(\w+)""")
  private val flags = Regex("""flags=0x([0-9a-fA-F]+)""")
  private val capture = Regex("""allowPlaybackCapture=(\w+)\s*,.*?packageName=([\w.:]+)""")

  fun parseAudioService(dump: String): List<SeqPlayback> {
    val bySession = LinkedHashMap<Int, SeqPlayback>()
    dump.lineSequence().forEach { raw ->
      val line = raw.trim()
      // Only the live player table; event-log history lines also mention u/pid.
      val live = line.startsWith("ID:") || line.startsWith("AudioPlaybackConfiguration")
      if (!live || !line.contains("u/pid")) return@forEach
      val ids = uidPid.find(line) ?: return@forEach
      val uid = ids.groupValues[1].toIntOrNull() ?: return@forEach
      val use = usage.find(line)?.groupValues?.get(1) ?: return@forEach
      val sid = session.findAll(line).mapNotNull { it.groupValues[1].toIntOrNull() }
        .firstOrNull { it > 0 } ?: return@forEach
      val started = state.find(line)?.groupValues?.get(1)?.equals("started", true) == true
      val flag = flags.find(line)?.groupValues?.get(1)?.toLongOrNull(16)?.toInt() ?: 0
      val entry = SeqPlayback(sid, uid, use, started,
        (flag and SeqPlayback.FLAG_NO_MEDIA_PROJECTION) != 0)
      val previous = bySession[sid]
      bySession[sid] = if (previous == null) entry else previous.copy(
        started = previous.started || entry.started,
        captureBlockedByPlayer = previous.captureBlockedByPlayer || entry.captureBlockedByPlayer)
    }
    return bySession.values.toList()
  }

  /** Package-level manifest/runtime capture policy (package name → allowed). */
  fun parseCapturePolicy(dump: String): Map<String, Boolean> {
    val result = HashMap<String, Boolean>()
    capture.findAll(dump).forEach {
      val pkg = it.groupValues[2].removePrefix("shared:")
      val allowed = it.groupValues[1].equals("true", true)
      // Shared-UID packages may be listed more than once; be conservative.
      result[pkg] = (result[pkg] ?: true) && allowed
    }
    return result
  }
}

/** Reads system service dumps; requires android.permission.DUMP (granted once over ADB). */
object SeqServiceDumper {
  fun granted(context: Context) =
    context.checkSelfPermission(android.Manifest.permission.DUMP) ==
      android.content.pm.PackageManager.PERMISSION_GRANTED

  fun dump(context: Context, service: String, timeoutMs: Long = 1500): String? {
    if (!granted(context)) return null
    return binderDump(service) ?: shellDump(service, timeoutMs)
  }

  private fun binderDump(service: String): String? = runCatching {
    val binder = Class.forName("android.os.ServiceManager")
      .getMethod("getService", String::class.java)
      .invoke(null, service) as? android.os.IBinder ?: return null
    val pipe = ParcelFileDescriptor.createPipe()
    pipe[1].use { write -> binder.dumpAsync(write.fileDescriptor, emptyArray<String>()) }
    FileInputStream(pipe[0].fileDescriptor).use { input ->
      val text = input.readBytes().toString(Charsets.UTF_8)
      pipe[0].close()
      text
    }
  }.getOrNull()?.takeIf { it.isNotBlank() }

  private fun shellDump(service: String, timeoutMs: Long): String? = runCatching {
    val process = ProcessBuilder("dumpsys", service).redirectErrorStream(true).start()
    val output = StringBuilder()
    val reader = Thread {
      runCatching { output.append(process.inputStream.bufferedReader().readText()) }
    }.apply { isDaemon = true; start() }
    if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) process.destroy()
    reader.join(200)
    output.toString()
  }.getOrNull()?.takeIf { it.isNotBlank() && !it.contains("Permission Denial") }
}
