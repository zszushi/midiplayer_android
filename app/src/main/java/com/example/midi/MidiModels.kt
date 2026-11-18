package com.example.midi

enum class ScanPhase {
  IDLE,
  PREPARING_STREAM,
  SCANNING_HEADERS,
  SCANNING_TRACKS,
  COMPLETED,
  CANCELLED,
  ERROR,
}

enum class VisualizerMode {
  FALLING_NOTES,
  ULTRA_LIGHT,
}

data class MidiScanProgress(
  val phase: ScanPhase = ScanPhase.IDLE,
  val fileName: String = "",
  val analyzedTracks: Int = 0,
  val totalTracks: Int = 0,
  val scannedBytes: Long = 0L,
  val totalBytes: Long = 0L,
  val throughputMbPerSec: Float = 0f,
  val detectedNotes: Long = 0L,
  val isFromCache: Boolean = false,
  val errorMessage: String? = null,
) {
  val trackFraction: Float
    get() = if (totalTracks > 0) (analyzedTracks.toFloat() / totalTracks.toFloat()).coerceIn(0f, 1f) else 0f

  val byteFraction: Float
    get() = if (totalBytes > 0L) (scannedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f) else 0f

  // Hybrid progress blends byte progress and track completion so both 1000-track MIDIs
  // and 1-giant-track MIDIs show smooth, continuous movement.
  val combinedProgressPercent: Int
    get() {
      if (phase == ScanPhase.COMPLETED) return 100
      if (totalTracks <= 0 && totalBytes <= 0L) return 0
      val blended = if (totalBytes > 0L && totalTracks > 0) {
        byteFraction * 0.65f + trackFraction * 0.35f
      } else {
        maxOf(byteFraction, trackFraction)
      }
      return (blended * 100f).toInt().coerceIn(0, 99)
    }
}

class MidiHeaderInfo(
  val fileName: String,
  val fileSizeBytes: Long,
  val format: Int,
  val ticksPerQuarter: Int, // Converted to effective PPQ if SMPTE
  val trackOffsets: LongArray, // Start offset of raw MTrk event payload (after 8-byte MTrk header)
  val trackLengths: IntArray,  // Byte length of each MTrk payload
) {
  val trackCount: Int
    get() = trackOffsets.size

  val totalTrackPayloadBytes: Long
    get() {
      var sum = 0L
      for (len in trackLengths) sum += len.toLong() and 0xFFFFFFFFL
      return sum
    }
}

/**
 * Compact primitive Tempo Map supporting O(log N) tick <-> microsecond conversion.
 */
class TempoMap(
  val changeTicks: LongArray,
  val usPerQuarter: IntArray,
  val cumulativeMicros: LongArray,
  val ticksPerQuarter: Int,
) {
  fun tickToMicros(tick: Long): Long {
    if (tick <= 0L) return 0L
    val idx = findFloorIndex(changeTicks, tick)
    val baseTick = changeTicks[idx]
    val baseUs = cumulativeMicros[idx]
    val tempo = usPerQuarter[idx].toLong()
    val deltaTicks = tick - baseTick
    return baseUs + (deltaTicks * tempo) / ticksPerQuarter.toLong()
  }

  fun microsToTick(micros: Long): Long {
    if (micros <= 0L) return 0L
    val idx = findFloorIndex(cumulativeMicros, micros)
    val baseUs = cumulativeMicros[idx]
    val baseTick = changeTicks[idx]
    val tempo = usPerQuarter[idx].toLong().coerceAtLeast(1L)
    val deltaUs = micros - baseUs
    return baseTick + (deltaUs * ticksPerQuarter.toLong()) / tempo
  }

  private fun findFloorIndex(arr: LongArray, target: Long): Int {
    var lo = 0
    var hi = arr.size - 1
    var ans = 0
    while (lo <= hi) {
      val mid = (lo + hi) ushr 1
      if (arr[mid] <= target) {
        ans = mid
        lo = mid + 1
      } else {
        hi = mid - 1
      }
    }
    return ans
  }

  companion object {
    fun default120Bpm(ticksPerQuarter: Int): TempoMap {
      return TempoMap(
        changeTicks = longArrayOf(0L),
        usPerQuarter = intArrayOf(500_000),
        cumulativeMicros = longArrayOf(0L),
        ticksPerQuarter = ticksPerQuarter.coerceAtLeast(1),
      )
    }
  }
}

/**
 * Per-track sparse seek checkpoints built during parallel background scan.
 * Each packed entry stores:
 * - checkpointTicks[i]: absolute tick in track
 * - checkpointState[i]: (byteOffsetInTrack.toLong() shl 8) or (runningStatus and 0xFF)
 */
class TrackSeekIndex(
  val checkpointTicks: Array<LongArray>,
  val checkpointState: Array<LongArray>,
)

class MidiFullIndex(
  val totalNotes: Long,
  val maxTick: Long,
  val durationMicros: Long,
  val tempoMap: TempoMap,
  val seekIndex: TrackSeekIndex,
  val isFromCache: Boolean,
)

/**
 * Zero-GC primitive ring buffer holding visible notes for the 60fps Falling-Notes Canvas.
 * Pre-allocates arrays once so rendering millions of notes triggers zero GC pauses.
 */
class VisualNoteRingBuffer(val capacity: Int = 32768) {
  private val mask = capacity - 1
  val startMicros = LongArray(capacity)
  val endMicros = LongArray(capacity)
  // Packed int: (key and 0x7F) | ((channel and 0x0F) shl 8) | ((velocity and 0x7F) shl 16)
  val packedMeta = IntArray(capacity)

  @Volatile var writeCount: Long = 0L
    private set

  // Tracks the latest ring slot per (channel * 128 + key) so Note-Off closes its exact bar in O(1)
  private val openSlotByChKey = IntArray(2048) { -1 }
  private val openWriteSeqByChKey = LongArray(2048) { -1L }

  fun clear() {
    writeCount = 0L
    openSlotByChKey.fill(-1)
    openWriteSeqByChKey.fill(-1L)
  }

  fun onNoteOn(nowMicros: Long, channel: Int, key: Int, velocity: Int, defaultDurationUs: Long = 220_000L) {
    if (key !in 0..127) return
    val chKey = ((channel and 0x0F) shl 7) or key

    // Close previous still-open note on same channel+key if valid
    val prevSlot = openSlotByChKey[chKey]
    val prevSeq = openWriteSeqByChKey[chKey]
    if (prevSlot >= 0 && writeCount - prevSeq < capacity) {
      if (endMicros[prevSlot] > nowMicros) {
        endMicros[prevSlot] = nowMicros
      }
    }

    val seq = writeCount
    val slot = (seq.toInt()) and mask
    startMicros[slot] = nowMicros
    // Provisional end timestamp until Note-Off arrives (capped to 4s max visual tail)
    endMicros[slot] = nowMicros + 4_000_000L.coerceAtLeast(defaultDurationUs)
    packedMeta[slot] = (key and 0x7F) or ((channel and 0x0F) shl 8) or ((velocity and 0x7F) shl 16)

    openSlotByChKey[chKey] = slot
    openWriteSeqByChKey[chKey] = seq
    writeCount = seq + 1L
  }

  fun onNoteOff(nowMicros: Long, channel: Int, key: Int) {
    if (key !in 0..127) return
    val chKey = ((channel and 0x0F) shl 7) or key
    val slot = openSlotByChKey[chKey]
    val seq = openWriteSeqByChKey[chKey]
    if (slot >= 0 && writeCount - seq < capacity) {
      val minEnd = startMicros[slot] + 25_000L // Minimum 25ms sliver visibility
      endMicros[slot] = maxOf(minEnd, nowMicros)
      openSlotByChKey[chKey] = -1
    }
  }
}
