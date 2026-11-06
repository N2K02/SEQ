package com.n2k02.seq.model

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import kotlin.math.*

enum class FilterKind(val nativeCode: Int) { PEAK(0), LOW_SHELF(1), HIGH_SHELF(2) }

data class EqFilter(
  val kind: FilterKind, val frequency: Double, val gain: Double,
  val q: Double, val enabled: Boolean = true
) {
  fun valid(): Boolean = frequency.isFinite() && frequency in 20.0..20000.0 &&
    gain.isFinite() && gain in -12.0..12.0 && q.isFinite() && q in 0.15..12.0
}
data class HeadphoneProfile(
  val name: String, val preampDb: Double = 0.0,
  val filters: List<EqFilter> = emptyList()
) {
  fun valid() = name.isNotBlank() && name.length<=100 &&
    preampDb.isFinite() && preampDb in -24.0..0.0 &&
    filters.size in 1..12 && filters.all { it.valid() }
}

/** Import the commonly exported AutoEq / Equalizer APO parametric text format. */
object AutoEqImport {
  private val preamp = Regex("""^\s*Preamp\s*:\s*([+-]?\d+(?:\.\d+)?)\s*dB\s*$""", RegexOption.IGNORE_CASE)
  private val filter = Regex("""^\s*Filter\s+\d+\s*:\s*(ON|OFF)\s+(PK|LS|LSC|HS|HSC)\s+Fc\s+(\d+(?:\.\d+)?)\s*Hz\s+Gain\s+(-?\d+(?:\.\d+)?)\s*dB\s+Q\s+(\d+(?:\.\d+)?).*$""", RegexOption.IGNORE_CASE)
  fun parse(name: String, content: String): HeadphoneProfile {
    require(content.length <= 50000) { "Correction file is too large" }
    val filters=mutableListOf<EqFilter>()
    var gain=0.0
    content.lineSequence().forEach { line ->
      preamp.matchEntire(line)?.let {
        gain=it.groupValues[1].toDouble()
      }
      filter.matchEntire(line)?.let { row ->
        val kind=when(row.groupValues[2].uppercase()) {
          "PK" -> FilterKind.PEAK
          "LS", "LSC" -> FilterKind.LOW_SHELF
          else -> FilterKind.HIGH_SHELF
        }
        filters += EqFilter(
          kind, row.groupValues[3].toDouble(), row.groupValues[4].toDouble(),
          row.groupValues[5].toDouble(), row.groupValues[1].equals("ON",true)
        )
      }
    }
    val candidate=HeadphoneProfile(name.take(100),gain,filters)
    require(candidate.valid()) { "Expected 1–12 valid filters and a non-positive preamp" }
    return candidate
  }
}

/** Mathematical response matching SEQ's RBJ biquad implementation. */
object EqResponse {
  fun db(hz: Double, filter: EqFilter, sampleRate: Double = 48000.0): Double {
    if (!filter.enabled || filter.gain == 0.0) return 0.0
    val f=filter.frequency.coerceIn(10.0,sampleRate*0.475)
    val w=2.0*PI*f/sampleRate
    val c=cos(w); val s=sin(w)
    val a=10.0.pow(filter.gain/40.0); val alpha=s/(2.0*filter.q)
    val root=sqrt(a)
    var b0=1.0; var b1=0.0; var b2=0.0
    var a0=1.0; var a1=0.0; var a2=0.0
    when(filter.kind) {
      FilterKind.PEAK -> {
        b0=1+alpha*a; b1=-2*c; b2=1-alpha*a
        a0=1+alpha/a; a1=-2*c; a2=1-alpha/a
      }
      FilterKind.LOW_SHELF -> {
        val al=s*sqrt(2.0)/2.0
        b0=a*((a+1)-(a-1)*c+2*root*al)
        b1=2*a*((a-1)-(a+1)*c)
        b2=a*((a+1)-(a-1)*c-2*root*al)
        a0=(a+1)+(a-1)*c+2*root*al
        a1=-2*((a-1)+(a+1)*c)
        a2=(a+1)+(a-1)*c-2*root*al
      }
      FilterKind.HIGH_SHELF -> {
        val al=s*sqrt(2.0)/2.0
        b0=a*((a+1)+(a-1)*c+2*root*al)
        b1=-2*a*((a-1)+(a+1)*c)
        b2=a*((a+1)+(a-1)*c-2*root*al)
        a0=(a+1)-(a-1)*c+2*root*al
        a1=2*((a-1)-(a+1)*c)
        a2=(a+1)-(a-1)*c-2*root*al
      }
    }
    val x=2.0*PI*hz/sampleRate
    fun magnitude(u:Double,v:Double,z:Double)=hypot(u+v*cos(x)+z*cos(2*x),
      -v*sin(x)-z*sin(2*x))
    return 20.0*log10(magnitude(b0,b1,b2)/magnitude(a0,a1,a2))
  }
  fun totalDb(hz:Int,tone:Tone,profile:HeadphoneProfile?):Double =
    ToneMath.gainDb(hz,tone).toDouble()+
      (profile?.filters?.sumOf { db(hz.toDouble(),it) } ?: 0.0)

  fun headroomDb(tone:Tone, profile:HeadphoneProfile?):Double {
    if(profile==null) return ToneMath.headroomDb(tone).toDouble()
    val maxBoost=(0..400).maxOf { i ->
      val f=(20.0*1000.0.pow(i/400.0)).toInt()
      totalDb(f,tone,profile)
    }
    return max(0.0,max(-profile.preampDb,maxBoost))
  }
}

/** No network downloads. User supplies their own correctly measured AutoEq preset. */
class HeadphoneProfileStore(context:Context) {
  private val prefs=context.applicationContext.getSharedPreferences("seq_headphones",Context.MODE_PRIVATE)
  fun save(profile:HeadphoneProfile) {
    require(profile.valid())
    val filters=JSONArray()
    profile.filters.forEach { f ->
      filters.put(JSONObject().put("type",f.kind.name).put("hz",f.frequency)
        .put("db",f.gain).put("q",f.q).put("enabled",f.enabled))
    }
    val obj=JSONObject().put("name",profile.name).put("preamp",profile.preampDb).put("filters",filters)
    prefs.edit().putString("profile",obj.toString()).apply()
    SeqAudioRevision.changed()
  }
  fun read():HeadphoneProfile? = runCatching {
    val raw=prefs.getString("profile",null) ?: return null
    val json=JSONObject(raw)
    val arr=json.getJSONArray("filters")
    val list=(0 until arr.length()).map { i ->
      val f=arr.getJSONObject(i)
      EqFilter(FilterKind.valueOf(f.getString("type")),f.getDouble("hz"),
        f.getDouble("db"),f.getDouble("q"),f.getBoolean("enabled"))
    }
    HeadphoneProfile(json.getString("name"),json.getDouble("preamp"),list).takeIf { it.valid() }
  }.getOrNull()
  fun clear() {
    prefs.edit().remove("profile").apply()
    SeqAudioRevision.changed()
  }
}
