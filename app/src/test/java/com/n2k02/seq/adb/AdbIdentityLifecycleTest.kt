package com.n2k02.seq.adb
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyPairGenerator

class AdbIdentityLifecycleTest {
  private fun source(file:String):String {
    val name="app/src/main/java/com/n2k02/seq/adb/"+file
    return listOf(File(name),File("../"+name),File("src/main/java/com/n2k02/seq/adb/"+file))
      .first { it.isFile }.readText()
  }
  @Test fun eachSessionGetsAnIndependentKeyObject() {
    val persistent=KeyPairGenerator.getInstance("RSA")
      .apply { initialize(1024) }.generateKeyPair().private
    val first=AdbSessionKey.copyOf(persistent)
    val second=AdbSessionKey.copyOf(persistent)
    assertNotSame(persistent,first)
    assertNotSame(first,second)
    assertArrayEquals(persistent.encoded,first.encoded)
    assertArrayEquals(first.encoded,second.encoded)
  }
  @Test fun sharedWireReusesExistingPersistedIdentity() {
    val factory=source("SharedAdbHostAdapter.kt")
    assertTrue(factory.contains("AdbIdentity.getOrCreate(ctx)"))
    assertTrue(factory.contains("CoreIdentity(old.privateKey, old.certificate)"))
    assertTrue(factory.contains("LibAdbWire(app, AdbIdentitySource"))
    assertFalse(source("AdbTransport.kt").contains("AbsAdbConnectionManager"))
  }
  @Test fun onlySharedCoreRetiresSessions() {
    val transport=source("AdbTransport.kt")
    assertTrue(transport.contains("SharedAdbHostAdapter.current()?.disconnectIdle()"))
    assertTrue(transport.contains("execVerified(command, timeoutMs)"))
    assertFalse(transport.contains("mgr.disconnect()"))
  }
}
