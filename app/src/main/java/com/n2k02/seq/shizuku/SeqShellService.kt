package com.n2k02.seq.shizuku

import android.os.Process
import java.util.concurrent.TimeUnit

/** Executes within Shizuku's isolated shell-privileged UserService process. */
class SeqShellService : ISeqShell.Stub() {
  override fun destroy() { kotlin.system.exitProcess(0) }
  override fun uid(): Int = Process.myUid()

  override fun audioSnapshot(): String {
    if (uid() != 2000) return "Shell UID 2000 not verified — diagnostics refused."
    // A fixed command, no user input. Return bounded, low-sensitivity audio route metadata.
    val process = ProcessBuilder("sh", "-c",
      "dumpsys audio | grep -E -i 'audio route|output device|audio focus|speaker|bluetooth|a2dp' | head -n 25")
      .redirectErrorStream(true).start()
    if (!process.waitFor(3, TimeUnit.SECONDS)) {
      process.destroyForcibly()
      return "Audio diagnostic timed out"
    }
    return process.inputStream.bufferedReader().use { it.readText().take(2500) }
      .ifBlank { "No matching audio diagnostic lines. Exit=" + process.exitValue() }
  }
}
