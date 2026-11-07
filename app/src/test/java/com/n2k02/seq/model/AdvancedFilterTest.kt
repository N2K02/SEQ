package com.n2k02.seq.model

import org.junit.Assert.*
import org.junit.Test

class AdvancedFilterTest {
 @Test fun validPresetSupports12Filters() {
   val p=HeadphoneProfile("Corrected",-8.0,(1..12).map {
     EqFilter(FilterKind.PEAK,100.0*it, -2.0, 1.2)
   })
   assertTrue(p.valid())
 }
 @Test fun frequencyRangeIsChecked() {
   assertFalse(EqFilter(FilterKind.PEAK,25000.0,0.0,1.0).valid())
   assertFalse(EqFilter(FilterKind.PEAK,1000.0,0.0,0.0).valid())
   assertFalse(EqFilter(FilterKind.PEAK,1000.0,Double.NaN,1.0).valid())
 }
 @Test fun preampCannotBoost() {
   assertFalse(HeadphoneProfile("Gain",2.0,listOf(EqFilter(FilterKind.PEAK,900.0,1.0,0.7))).valid())
 }
}
