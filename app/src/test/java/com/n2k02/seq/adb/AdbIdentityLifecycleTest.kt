package com.n2k02.seq.adb

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyPairGenerator

class AdbIdentityLifecycleTest {
  @Test fun eachSessionGetsAnIndependentKeyObject() {
    val persistent = KeyPairGenerator.getInstance("RSA")
      .apply { initialize(1024) }.generateKeyPair().private
    val first = AdbSessionKey.copyOf(persistent)
    val second = AdbSessionKey.copyOf(persistent)
    assertNotSame(persistent, first)
    assertNotSame(persistent, second)
    assertNotSame(first, second)
    assertArrayEquals(persistent.encoded, first.encoded)
    assertArrayEquals(first.encoded, second.encoded)
  }

  @Test fun managerDisconnectDoesNotDestroyPersistedKey() {
    val path = "app/src/main/java/com/n2k02/seq/adb/AdbTransport.kt"
    val source = listOf(File(path), File("../" + path)).first { it.isFile }.readText()
    assertTrue(source.contains("AdbSessionKey.copyOf(identity.privateKey)"))
    assertTrue(source.contains("getPrivateKey(): PrivateKey = sessionKey"))
    assertTrue(source.contains("oldManager?.disconnect()"))
    assertFalse(source.contains("mgr.close()"))
    assertFalse(source.contains("manager?.close()"))
    assertFalse(source.contains("getPrivateKey(): PrivateKey = identity.privateKey"))
  }

  @Test fun timeoutRetiresDesynchronizedTransport() {
    val path = "app/src/main/java/com/n2k02/seq/adb/AdbTransport.kt"
    val source = listOf(File(path), File("../" + path)).first { it.isFile }.readText()
    assertTrue(source.contains("val watchdog = launch"))
    assertTrue(source.contains("mgr.disconnect()"))
    assertTrue(source.contains("lastKnownConnected = false"))
    assertTrue(source.contains("runCatching { reader.close() }"))
    assertTrue(source.contains("runCatching { out.close() }"))
  }
}
