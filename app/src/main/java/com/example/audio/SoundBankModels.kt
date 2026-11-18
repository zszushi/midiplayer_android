package com.example.audio

import java.nio.ShortBuffer

enum class SoundBankType {
  SINE,
  SF2,
  SFZ,
}

class SampleRegion(
  val bufferIndex: Int,
  val startFrame: Int,
  val endFrame: Int,
  val loopStartFrame: Int,
  val loopEndFrame: Int,
  val loopMode: Int, // 0 = no loop, 1 = continuous loop, 3 = loop until release
  val sampleRate: Int,
  val rootKey: Int,
  val tuningCents: Float,
  val scaleTuning: Float, // 1.0f = normal 100 cents/semitone, 0.0f = unpitched drum
  val loKey: Int,
  val hiKey: Int,
  val loVel: Int,
  val hiVel: Int,
  val gainLinear: Float,
  val pan: Float, // -1.0f (left) .. +1.0f (right)
  val attackSec: Float,
  val decaySec: Float,
  val sustainLevel: Float, // 0.0f .. 1.0f
  val releaseSec: Float,
)

/**
 * Pre-resolved flat lookup structure inspired by BlackMIDIDevs/xsynth.
 * Resolves all SoundFont / SFZ zones up front at load time so the real-time
 * audio thread never allocates or traverses trees on Note-On.
 */
class CompiledSoundBank(
  val name: String,
  val type: SoundBankType,
  val sampleBuffers: Array<ShortBuffer>,
  val regions: Array<SampleRegion>,
  private val presetKeyMap: Map<Int, Array<IntArray>>,
  private val fallbackMelodicKeyMap: Array<IntArray>,
  private val fallbackDrumKeyMap: Array<IntArray>?,
  val sfzAvailableFiles: List<Pair<String, String>> = emptyList(), // (displayName, docIdOrUri)
  val activeSfzName: String? = null,
) {
  val regionCount: Int
    get() = regions.size

  fun getMatchingRegionIndices(bank: Int, program: Int, isDrumChannel: Boolean, key: Int): IntArray {
    if (key !in 0..127) return EMPTY_INT_ARRAY
    if (type == SoundBankType.SINE) return EMPTY_INT_ARRAY

    if (isDrumChannel) {
      val drumExact = presetKeyMap[packBankProgram(128, program)]
        ?: presetKeyMap[packBankProgram(128, 0)]
        ?: fallbackDrumKeyMap
      if (drumExact != null) {
        val arr = drumExact[key]
        if (arr.isNotEmpty()) return arr
      }
    }

    val exact = presetKeyMap[packBankProgram(bank, program)]
      ?: presetKeyMap[packBankProgram(0, program)]
      ?: fallbackMelodicKeyMap
    return exact[key]
  }

  companion object {
    val EMPTY_INT_ARRAY = IntArray(0)

    fun packBankProgram(bank: Int, program: Int): Int = ((bank and 0xFF) shl 8) or (program and 0xFF)

    fun createBuiltInSine(): CompiledSoundBank {
      val emptyKeyMap = Array(128) { EMPTY_INT_ARRAY }
      return CompiledSoundBank(
        name = "内蔵サイン波 (Pure Sine LUT)",
        type = SoundBankType.SINE,
        sampleBuffers = emptyArray(),
        regions = emptyArray(),
        presetKeyMap = emptyMap(),
        fallbackMelodicKeyMap = emptyKeyMap,
        fallbackDrumKeyMap = null,
      )
    }

    fun buildKeyLookup(regions: List<SampleRegion>, indices: List<Int>): Array<IntArray> {
      val perKeyLists = Array(128) { ArrayList<Int>(2) }
      for (idx in indices) {
        val r = regions[idx]
        val lo = r.loKey.coerceIn(0, 127)
        val hi = r.hiKey.coerceIn(0, 127)
        for (k in lo..hi) {
          perKeyLists[k].add(idx)
        }
      }
      return Array(128) { k ->
        val list = perKeyLists[k]
        if (list.isEmpty()) EMPTY_INT_ARRAY else list.toIntArray()
      }
    }
  }
}
