package com.n2k02.seq.model

import org.junit.Assert.*
import org.junit.Test

class HeadphoneProfileTest {
 private val example="""
Preamp: -5.2 dB
Filter 1: ON PK Fc 105 Hz Gain -4.5 dB Q 0.80
Filter 2: ON LS Fc 60 Hz Gain 2.0 dB Q 0.71
Filter 3: OFF HS Fc 7000 Hz Gain 1.2 dB Q 0.80
 """.trimIndent()
 @Test fun importsCorrectly() {
   val p=AutoEqImport.parse("Demo headphones",example)
   assertEquals("Demo headphones",p.name)
   assertEquals(-5.2,p.preampDb,1e-7)
   assertEquals(3,p.filters.size)
   assertEquals(FilterKind.LOW_SHELF,p.filters[1].kind)
   assertFalse(p.filters[2].enabled)
 }
 @Test fun rejectUnsafeGain() {
   val bad=example.replace("-4.5 dB", "33.0 dB")
   assertThrows(IllegalArgumentException::class.java) { AutoEqImport.parse("Demo",bad) }
 }
 @Test fun rejectPositivePreamp() {
   assertThrows(IllegalArgumentException::class.java) {
     AutoEqImport.parse("Demo",example.replace("-5.2 dB","+3 dB"))
   }
 }
 @Test fun rejectMoreThanTwelveFilters() {
   val lines=(1..13).joinToString("\n") { "Filter $it: ON PK Fc 1000 Hz Gain 1 dB Q 1" }
   assertThrows(IllegalArgumentException::class.java) { AutoEqImport.parse("Demo",lines) }
 }
 @Test fun noFilterNotAccepted() {
   assertThrows(IllegalArgumentException::class.java) { AutoEqImport.parse("Demo","Preamp: -3 dB") }
 }
 @Test fun approximateResponses() {
   val peak=EqFilter(FilterKind.PEAK,1000.0,4.0,1.0)
   assertEquals(4.0,EqResponse.db(1000.0,peak),0.06)
   assertEquals(0.0,EqResponse.db(40.0,peak),0.2)
   val off=peak.copy(enabled=false)
   assertEquals(0.0,EqResponse.db(1000.0,off),0.00001)
 }
 @Test fun headroomIncludesImportedPreset() {
   val boosted=HeadphoneProfile("Demo",-3.0,listOf(EqFilter(FilterKind.PEAK,1000.0,6.0,1.0)))
   assertTrue(EqResponse.headroomDb(Tone(),boosted)>5.8)
 }
}
