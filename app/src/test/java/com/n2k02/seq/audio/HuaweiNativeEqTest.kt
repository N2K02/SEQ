package com.n2k02.seq.audio

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class HuaweiNativeEqTest {
  private fun source(path: String): String =
    listOf(File("src/main/$path"), File("app/src/main/$path")).first { it.isFile }.readText()

  @Test fun writeMatchesCapturedFreeBuds5Frames() {
    assertEquals("5A0006002B490101021F79", HuaweiEqWire.write(2).joinToString("") { "%02X".format(it) })
    assertEquals("5A0006002B49010109AE12", HuaweiEqWire.write(9).joinToString("") { "%02X".format(it) })
    assertEquals("5A0005002B4A02008C46", HuaweiEqWire.read().joinToString("") { "%02X".format(it) })
  }

  @Test fun readbackRejectsCorruptFramesAndAcceptsMatchingPreset() {
    val response = HuaweiEqWire.packet(HuaweiEqWire.READ, byteArrayOf(2, 1, 3))
    assertEquals(3, HuaweiEqWire.readPreset(response))
    val wrong = response.copyOf().also { it[it.size - 1] = (it.last().toInt() xor 1).toByte() }
    assertNull(HuaweiEqWire.readPreset(wrong))
    assertNull(HuaweiEqWire.readPreset(HuaweiEqWire.write(3)))
  }

  @Test fun unknownPresetsCannotBeSent() {
    try { HuaweiEqWire.write(7); fail("Unsupported preset accepted") }
    catch (_: IllegalArgumentException) { }
  }

  @Test fun nativeMustBeVerifiedBeforeAndroidMixIsBypassed() {
    val native = source("java/com/n2k02/seq/audio/HuaweiNativeEq.kt")
    val global = source("java/com/n2k02/seq/audio/SeqGlobalEffect.kt")
    assertTrue(native.contains("confirmedPreset == preset(context)"))
    assertTrue(native.contains("HuaweiEqWire.readPreset(frame) == preset"))
    assertTrue(native.contains("SettingsStore(context).read().enabled"))
    assertTrue(global.contains("HuaweiNativeEq.activeFor(ctx)"))
    assertTrue(global.contains("Huawei native EQ verified; Android effect bypassed"))
  }
}
