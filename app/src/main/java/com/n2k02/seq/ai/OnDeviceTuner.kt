package com.n2k02.seq.ai

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.n2k02.seq.model.Tone

data class TuningSuggestion(val tone: Tone, val explanation: String)

/**
 * An optional foreground-only, short-lived AICore client.
 * LLM output is never trusted as DSP commands: parser enforces ±2 dB, and UI requires confirmation.
 */
object OnDeviceTuner {
  fun parse(text: String, current: Tone): TuningSuggestion? {
    fun find(key: String): Float? {
      val pattern = Regex("\"" + key + "\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)")
      return pattern.find(text)?.groupValues?.get(1)?.toFloatOrNull()
    }
    val bass = find("bass") ?: return null
    val mid = find("mid") ?: return null
    val treble = find("treble") ?: return null
    if (!listOf(bass, mid, treble).all { it.isFinite() && it in -2f..2f }) return null
    return TuningSuggestion(
      Tone(current.bass + bass, current.mid + mid, current.treble + treble).bounded(),
      "Suggested offsets: bass " + bass + " dB, mids " + mid + " dB, treble " + treble + " dB"
    )
  }
  suspend fun suggest(request: String, current: Tone): Result<TuningSuggestion> {
    if (request.isBlank() || request.length > 300)
      return Result.failure(IllegalArgumentException("Enter a request under 300 characters"))
    val model = Generation.getClient()
    return try {
      val status = model.checkStatus()
      if (status != FeatureStatus.AVAILABLE)
        return Result.failure(IllegalStateException("On-device Gemini Nano unavailable or not downloaded (status " + status + ")"))
      val prompt = """Audio EQ suggestion. Propose ONLY small optional adjustments, never claim to repair recordings.
Return exactly a JSON object with numeric fields bass, mid, treble representing DELTAS in dB
in the inclusive range -2.0 to 2.0. No prose.
User request: """ + request.take(300)
      val answer = model.generateContent(prompt)
      val text = answer.candidates.firstOrNull()?.text.orEmpty()
      val suggestion = parse(text, current) ?: throw IllegalArgumentException("Model returned invalid EQ data")
      Result.success(suggestion)
    } catch (e: Exception) {
      Result.failure(e)
    } finally {
      model.close()
    }
  }
}
