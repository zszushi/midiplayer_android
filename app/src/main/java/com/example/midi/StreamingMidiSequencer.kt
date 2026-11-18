package com.example.midi

import android.os.Process
import com.example.audio.ZeroGcSynthEngine
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.min

/**
 * Zero-GC K-Way Min-Heap Streaming MIDI Sequencer.
 *
 * Streams arbitrarily large Black MIDI files directly from [FileChannel] using small adaptive
 * per-track byte buffers (bounded total RAM even for 5,000+ track MIDIs!).
 *
 * Supports:
 * - Instant playback immediately after Stage 1 MTrk boundary scan (before Stage 2 completes)
 * - Lookahead visual streaming into [VisualNoteRingBuffer] when [VisualizerMode.FALLING_NOTES] is active
 * - Zero visual overhead bypass when [VisualizerMode.ULTRA_LIGHT] is active
 * - Instant seeking using [TrackSeekIndex] checkpoints
 */
class StreamingMidiSequencer(
  private val synth: ZeroGcSynthEngine,
  val visualNotes: VisualNoteRingBuffer = VisualNoteRingBuffer(65536),
) {
  companion object {
    const val MIN_NOTE_ZOOM = 0.25f
    const val MAX_NOTE_ZOOM = 4.0f
    const val BASE_VISIBLE_WINDOW_US = 1_400_000L // 1.40s waterfall window at 1.0x zoom

    private const val SCHED_RING_SIZE = 524288 // Power of 2 (512K scheduled events for zoomed-out 5.6s window)
    private const val SCHED_RING_MASK = SCHED_RING_SIZE - 1
    private const val ULTRA_LIGHT_LOOKAHEAD_US = 30_000L // 30ms tight audio lookahead
  }

  private class SessionSource(
    val channel: FileChannel,
    val header: MidiHeaderInfo,
  )

  private val sourceRef = AtomicReference<SessionSource?>(null)
  private val fullIndexRef = AtomicReference<MidiFullIndex?>(null)

  @Volatile var isPlaying: Boolean = false
    private set
  @Volatile var currentPositionMicros: Long = 0L
    private set
  @Volatile var durationMicros: Long = 0L
    private set
  @Volatile var playbackSpeed: Float = 1.0f
  @Volatile var noteZoom: Float = 1.0f
  @Volatile var visualizerMode: VisualizerMode = VisualizerMode.FALLING_NOTES

  fun currentVisibleWindowMicros(): Long {
    val z = noteZoom.coerceIn(MIN_NOTE_ZOOM, MAX_NOTE_ZOOM)
    return (BASE_VISIBLE_WINDOW_US.toDouble() / z.toDouble()).toLong().coerceIn(320_000L, 6_000_000L)
  }

  // Live Telemetry
  @Volatile var liveNps: Int = 0
    private set
  @Volatile var playedNotesTotal: Long = 0L
    private set

  // Active key state for 88-key highlight: (velocity and 0x7F) or ((channel and 0x0F) shl 8), 0 = inactive
  val activeKeyboardState = IntArray(128)
  private val activeKeyRefCount = IntArray(128)

  private val pendingSeekMicros = AtomicLong(-1L)

  @Volatile private var workerRunning = false
  private var sequencerThread: Thread? = null

  fun loadMidiSource(channel: FileChannel, header: MidiHeaderInfo) {
    stopSequencerThread()
    synth.resetAllControllersAndSilence()
    visualNotes.clear()
    activeKeyboardState.fill(0)
    activeKeyRefCount.fill(0)
    currentPositionMicros = 0L
    durationMicros = 0L
    liveNps = 0
    playedNotesTotal = 0L
    fullIndexRef.set(null)
    sourceRef.set(SessionSource(channel, header))
  }

  fun attachFullIndex(index: MidiFullIndex) {
    fullIndexRef.set(index)
    durationMicros = index.durationMicros
  }

  fun play() {
    val src = sourceRef.get() ?: return
    if (workerRunning) {
      isPlaying = true
      return
    }
    isPlaying = true
    workerRunning = true
    sequencerThread = Thread({ runSequencerLoop(src) }, "Obsidian-MIDI-Stream").apply {
      isDaemon = true
      start()
    }
  }

  fun pause() {
    isPlaying = false
    synth.panicAllNotesOff()
    activeKeyboardState.fill(0)
    activeKeyRefCount.fill(0)
    liveNps = 0
  }

  fun stopAndRewind() {
    isPlaying = false
    pendingSeekMicros.set(0L)
    currentPositionMicros = 0L
    synth.resetAllControllersAndSilence()
    visualNotes.clear()
    activeKeyboardState.fill(0)
    activeKeyRefCount.fill(0)
    liveNps = 0
  }

  fun seekToMicros(targetMicros: Long) {
    val clamped = if (durationMicros > 0L) targetMicros.coerceIn(0L, durationMicros) else max(0L, targetMicros)
    currentPositionMicros = clamped
    pendingSeekMicros.set(clamped)
  }

  fun release() {
    stopSequencerThread()
    sourceRef.set(null)
    fullIndexRef.set(null)
  }

  private fun stopSequencerThread() {
    isPlaying = false
    workerRunning = false
    sequencerThread?.join(400)
    sequencerThread = null
  }

  private fun runSequencerLoop(source: SessionSource) {
    try {
      Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
    } catch (_: Throwable) {}

    val header = source.header
    val trackCount = header.trackCount
    if (trackCount <= 0) {
      workerRunning = false
      isPlaying = false
      return
    }

    // Adaptive per-track buffer sizing: cap total buffer footprint to ~16MB even for 4,000 tracks!
    val perTrackBufSize = (16 * 1024 * 1024 / trackCount).coerceIn(512, 16384)
    val trackBuffers = Array(trackCount) { ByteArray(perTrackBufSize) }
    val trackNioWraps = Array(trackCount) { ByteBuffer.wrap(trackBuffers[it]) }

    // Per-track primitive state arrays (Zero-GC!)
    val trkRelConsumed = IntArray(trackCount)
    val trkBufPos = IntArray(trackCount)
    val trkBufLim = IntArray(trackCount)
    val trkRunningStatus = IntArray(trackCount)
    val trkNextTick = LongArray(trackCount)
    val trkEnded = BooleanArray(trackCount)

    // Binary Min-Heap of track indices ordered by trkNextTick[trackIdx]
    val heap = IntArray(trackCount)
    var heapSize = 0

    // Scheduled Audio Event Ring Buffer (bridges lookahead horizon -> exact audio dispatch time)
    val schedMicros = LongArray(SCHED_RING_SIZE)
    val schedPacked = LongArray(SCHED_RING_SIZE)
    var schedHead = 0
    var schedTail = 0

    // Dynamic fallback tempo state when playing before Stage 2 full scan finishes
    var liveFallbackTickRef = 0L
    var liveFallbackMicrosRef = 0L
    var liveUsPerQuarter = 500_000L
    val ppq = header.ticksPerQuarter.toLong().coerceAtLeast(1L)

    // Sliding 1-second NPS bucket ring (20 buckets x 50ms = 1.0s)
    val npsBuckets = IntArray(20)
    var npsBucketIdx = 0
    var npsRollingSum = 0
    var lastBucketStepNs = System.nanoTime()

    fun refillTrack(t: Int): Boolean {
      trkRelConsumed[t] += trkBufLim[t]
      trkBufPos[t] = 0
      trkBufLim[t] = 0
      val rem = header.trackLengths[t] - trkRelConsumed[t]
      if (rem <= 0) {
        trkEnded[t] = true
        return false
      }
      val toRead = min(rem, perTrackBufSize)
      val bb = trackNioWraps[t]
      bb.clear()
      bb.limit(toRead)
      val n = MidiFastScanner.readAt(source.channel, header.trackOffsets[t] + trkRelConsumed[t], bb)
      if (n <= 0) {
        trkEnded[t] = true
        return false
      }
      trkBufLim[t] = n
      return true
    }

    fun readTrackByte(t: Int): Int {
      if (trkBufPos[t] >= trkBufLim[t] && !refillTrack(t)) return -1
      return trackBuffers[t][trkBufPos[t]++].toInt() and 0xFF
    }

    fun peekTrackByte(t: Int): Int {
      if (trkBufPos[t] >= trkBufLim[t] && !refillTrack(t)) return -1
      return trackBuffers[t][trkBufPos[t]].toInt() and 0xFF
    }

    fun skipTrackBytes(t: Int, count: Int) {
      if (count <= 0) return
      val avail = trkBufLim[t] - trkBufPos[t]
      if (count <= avail) {
        trkBufPos[t] += count
        return
      }
      val remSkip = count - avail
      trkRelConsumed[t] = (trkRelConsumed[t] + trkBufLim[t] + remSkip).coerceAtMost(header.trackLengths[t])
      trkBufPos[t] = 0
      trkBufLim[t] = 0
      if (trkRelConsumed[t] >= header.trackLengths[t]) trkEnded[t] = true
    }

    fun readTrackVlq(t: Int): Long {
      var v = 0L
      var c = 0
      while (c < 5) {
        val b = readTrackByte(t)
        if (b < 0) {
          trkEnded[t] = true
          return -1L
        }
        v = (v shl 7) or (b.toLong() and 0x7FL)
        if ((b and 0x80) == 0) return v
        c++
      }
      return v
    }

    fun advanceTrackDelta(t: Int): Boolean {
      if (trkEnded[t]) return false
      val d = readTrackVlq(t)
      if (d < 0L) {
        trkEnded[t] = true
        return false
      }
      trkNextTick[t] += d
      return true
    }

    fun heapPush(trackIdx: Int) {
      var i = heapSize++
      heap[i] = trackIdx
      while (i > 0) {
        val p = (i - 1) ushr 1
        val pTrk = heap[p]
        if (trkNextTick[trackIdx] < trkNextTick[pTrk] ||
          (trkNextTick[trackIdx] == trkNextTick[pTrk] && trackIdx < pTrk)
        ) {
          heap[i] = pTrk
          heap[p] = trackIdx
          i = p
        } else {
          break
        }
      }
    }

    fun heapSiftDownRoot() {
      if (heapSize <= 1) return
      val rootTrk = heap[0]
      var i = 0
      val half = heapSize ushr 1
      while (i < half) {
        var child = (i shl 1) + 1
        var childTrk = heap[child]
        val right = child + 1
        if (right < heapSize) {
          val rightTrk = heap[right]
          if (trkNextTick[rightTrk] < trkNextTick[childTrk] ||
            (trkNextTick[rightTrk] == trkNextTick[childTrk] && rightTrk < childTrk)
          ) {
            child = right
            childTrk = rightTrk
          }
        }
        if (trkNextTick[childTrk] < trkNextTick[rootTrk] ||
          (trkNextTick[childTrk] == trkNextTick[rootTrk] && childTrk < rootTrk)
        ) {
          heap[i] = childTrk
          i = child
        } else {
          break
        }
      }
      heap[i] = rootTrk
    }

    fun heapPopRoot() {
      heapSize--
      if (heapSize > 0) {
        heap[0] = heap[heapSize]
        heapSiftDownRoot()
      }
    }

    fun tickToMicros(tick: Long): Long {
      val idx = fullIndexRef.get()
      if (idx != null) {
        return idx.tempoMap.tickToMicros(tick)
      }
      if (tick < liveFallbackTickRef) {
        return (tick * liveUsPerQuarter) / ppq
      }
      return liveFallbackMicrosRef + ((tick - liveFallbackTickRef) * liveUsPerQuarter) / ppq
    }

    fun resetTracksToTargetMicros(targetMicros: Long) {
      synth.resetAllControllersAndSilence()
      visualNotes.clear()
      activeKeyboardState.fill(0)
      activeKeyRefCount.fill(0)
      schedHead = 0
      schedTail = 0
      heapSize = 0
      liveFallbackTickRef = 0L
      liveFallbackMicrosRef = 0L
      liveUsPerQuarter = 500_000L

      val fullIdx = fullIndexRef.get()
      val targetTick = if (targetMicros <= 0L) {
        0L
      } else if (fullIdx != null) {
        fullIdx.tempoMap.microsToTick(targetMicros)
      } else {
        (targetMicros * ppq) / 500_000L
      }

      for (t in 0 until trackCount) {
        trkEnded[t] = false
        trkBufPos[t] = 0
        trkBufLim[t] = 0

        var startRelByte = 0
        var startTick = 0L
        var startStatus = 0

        if (targetTick > 0L && fullIdx != null) {
          val cpTicks = fullIdx.seekIndex.checkpointTicks[t]
          val cpStates = fullIdx.seekIndex.checkpointState[t]
          if (cpTicks.isNotEmpty()) {
            var lo = 0
            var hi = cpTicks.size - 1
            var best = -1
            while (lo <= hi) {
              val mid = (lo + hi) ushr 1
              if (cpTicks[mid] <= targetTick) {
                best = mid
                lo = mid + 1
              } else {
                hi = mid - 1
              }
            }
            if (best >= 0) {
              startTick = cpTicks[best]
              val st = cpStates[best]
              startRelByte = (st ushr 8).toInt()
              startStatus = (st and 0xFFL).toInt()
            }
          }
        }

        trkRelConsumed[t] = startRelByte
        trkNextTick[t] = startTick
        trkRunningStatus[t] = startStatus

        if (!advanceTrackDelta(t)) continue

        // Fast-forward track events prior to targetTick while preserving Program/CC controller changes!
        while (!trkEnded[t] && trkNextTick[t] < targetTick) {
          val peek = peekTrackByte(t)
          if (peek < 0) {
            trkEnded[t] = true
            break
          }
          val status = if ((peek and 0x80) != 0) {
            val s = readTrackByte(t)
            if (s < 0xF0) trkRunningStatus[t] = s
            else if (s in 0xF0..0xF7) trkRunningStatus[t] = 0
            s
          } else {
            trkRunningStatus[t]
          }

          val hiNibble = status and 0xF0
          val ch = status and 0x0F
          when (hiNibble) {
            0x80, 0x90, 0xA0 -> skipTrackBytes(t, 2)
            0xB0 -> {
              val cc = readTrackByte(t)
              val v = readTrackByte(t)
              if (cc in intArrayOf(0, 7, 10, 11)) {
                synth.enqueueEvent(ZeroGcSynthEngine.packEvent(ZeroGcSynthEngine.CMD_CONTROL_CHANGE, ch, cc, v))
              }
            }
            0xC0 -> {
              val prog = readTrackByte(t)
              synth.enqueueEvent(ZeroGcSynthEngine.packEvent(ZeroGcSynthEngine.CMD_PROGRAM_CHANGE, ch, prog, 0))
            }
            0xD0 -> skipTrackBytes(t, 1)
            0xE0 -> {
              val lsb = readTrackByte(t)
              val msb = readTrackByte(t)
              synth.enqueueEvent(ZeroGcSynthEngine.packEvent(ZeroGcSynthEngine.CMD_PITCH_BEND, ch, 0, ((msb and 0x7F) shl 7) or (lsb and 0x7F)))
            }
            0xF0 -> {
              if (status == 0xFF) {
                val metaType = readTrackByte(t)
                val len = readTrackVlq(t).toInt()
                if (len < 0) {
                  trkEnded[t] = true
                  break
                }
                if (metaType == 0x51 && len == 3) {
                  val b0 = readTrackByte(t)
                  val b1 = readTrackByte(t)
                  val b2 = readTrackByte(t)
                  liveFallbackMicrosRef = tickToMicros(trkNextTick[t])
                  liveFallbackTickRef = trkNextTick[t]
                  liveUsPerQuarter = ((b0 shl 16) or (b1 shl 8) or b2).toLong().coerceIn(10_000L, 10_000_000L)
                } else if (metaType == 0x2F) {
                  skipTrackBytes(t, len)
                  trkEnded[t] = true
                  break
                } else {
                  skipTrackBytes(t, len)
                }
              } else if (status == 0xF0 || status == 0xF7) {
                val len = readTrackVlq(t).toInt()
                if (len < 0) {
                  trkEnded[t] = true
                  break
                }
                skipTrackBytes(t, len)
              } else if (status == 0xF1 || status == 0xF3) {
                skipTrackBytes(t, 1)
              } else if (status == 0xF2) {
                skipTrackBytes(t, 2)
              }
            }
            else -> skipTrackBytes(t, 1)
          }

          if (!advanceTrackDelta(t)) break
        }

        if (!trkEnded[t]) {
          heapPush(t)
        }
      }
    }

    resetTracksToTargetMicros(currentPositionMicros)
    var lastWallNs = System.nanoTime()

    while (workerRunning) {
      val seekTarget = pendingSeekMicros.getAndSet(-1L)
      if (seekTarget >= 0L) {
        currentPositionMicros = seekTarget
        resetTracksToTargetMicros(seekTarget)
        lastWallNs = System.nanoTime()
      }

      val nowNs = System.nanoTime()
      if (nowNs - lastBucketStepNs >= 50_000_000L) {
        lastBucketStepNs = nowNs
        npsBucketIdx = (npsBucketIdx + 1) % 20
        npsRollingSum -= npsBuckets[npsBucketIdx]
        npsBuckets[npsBucketIdx] = 0
        liveNps = npsRollingSum.coerceAtLeast(0)
      }

      if (!isPlaying) {
        lastWallNs = nowNs
        try {
          Thread.sleep(10L)
        } catch (_: InterruptedException) {
          break
        }
        continue
      }

      val deltaNs = (nowNs - lastWallNs).coerceIn(0L, 100_000_000L)
      lastWallNs = nowNs
      val speed = playbackSpeed.coerceIn(0.25f, 2.0f)
      val advanceUs = ((deltaNs / 1000.0) * speed).toLong()
      val playheadUs = currentPositionMicros + advanceUs
      currentPositionMicros = playheadUs

      val mode = visualizerMode
      val lookaheadUs = if (mode == VisualizerMode.FALLING_NOTES) {
        currentVisibleWindowMicros() + 180_000L
      } else {
        ULTRA_LIGHT_LOOKAHEAD_US
      }
      val horizonUs = playheadUs + lookaheadUs

      // 1. Stream events from K-Way Min-Heap up to horizonUs (bounded by scheduled ring space)
      var parseQuota = 65536
      while (heapSize > 0 && parseQuota > 0 && (schedTail - schedHead) < (SCHED_RING_SIZE - 8)) {
        val topTrk = heap[0]
        val evTick = trkNextTick[topTrk]
        val evUs = tickToMicros(evTick)
        if (evUs > horizonUs) break
        parseQuota--

        val peek = peekTrackByte(topTrk)
        if (peek < 0) {
          trkEnded[topTrk] = true
          heapPopRoot()
          continue
        }

        val status = if ((peek and 0x80) != 0) {
          val s = readTrackByte(topTrk)
          if (s < 0xF0) trkRunningStatus[topTrk] = s
          else if (s in 0xF0..0xF7) trkRunningStatus[topTrk] = 0
          s
        } else {
          trkRunningStatus[topTrk]
        }

        val hi = status and 0xF0
        val ch = status and 0x0F
        var packedToSchedule = 0L

        when (hi) {
          0x90 -> {
            val key = readTrackByte(topTrk) and 0x7F
            val vel = readTrackByte(topTrk) and 0x7F
            if (vel > 0) {
              packedToSchedule = ZeroGcSynthEngine.packEvent(ZeroGcSynthEngine.CMD_NOTE_ON, ch, key, vel)
              if (mode == VisualizerMode.FALLING_NOTES && vel >= synth.velocityThreshold) {
                visualNotes.onNoteOn(evUs, ch, key, vel)
              }
            } else {
              packedToSchedule = ZeroGcSynthEngine.packEvent(ZeroGcSynthEngine.CMD_NOTE_OFF, ch, key, 0)
              if (mode == VisualizerMode.FALLING_NOTES) {
                visualNotes.onNoteOff(evUs, ch, key)
              }
            }
          }
          0x80 -> {
            val key = readTrackByte(topTrk) and 0x7F
            readTrackByte(topTrk) // release vel
            packedToSchedule = ZeroGcSynthEngine.packEvent(ZeroGcSynthEngine.CMD_NOTE_OFF, ch, key, 0)
            if (mode == VisualizerMode.FALLING_NOTES) {
              visualNotes.onNoteOff(evUs, ch, key)
            }
          }
          0xA0 -> skipTrackBytes(topTrk, 2)
          0xB0 -> {
            val cc = readTrackByte(topTrk) and 0x7F
            val v = readTrackByte(topTrk) and 0x7F
            packedToSchedule = ZeroGcSynthEngine.packEvent(ZeroGcSynthEngine.CMD_CONTROL_CHANGE, ch, cc, v)
          }
          0xC0 -> {
            val prog = readTrackByte(topTrk) and 0x7F
            packedToSchedule = ZeroGcSynthEngine.packEvent(ZeroGcSynthEngine.CMD_PROGRAM_CHANGE, ch, prog, 0)
          }
          0xD0 -> skipTrackBytes(topTrk, 1)
          0xE0 -> {
            val lsb = readTrackByte(topTrk) and 0x7F
            val msb = readTrackByte(topTrk) and 0x7F
            packedToSchedule = ZeroGcSynthEngine.packEvent(
              ZeroGcSynthEngine.CMD_PITCH_BEND,
              ch,
              0,
              (msb shl 7) or lsb,
            )
          }
          0xF0 -> {
            if (status == 0xFF) {
              val metaType = readTrackByte(topTrk)
              val len = readTrackVlq(topTrk).toInt()
              if (len < 0) {
                trkEnded[topTrk] = true
              } else if (metaType == 0x51 && len == 3) {
                val b0 = readTrackByte(topTrk)
                val b1 = readTrackByte(topTrk)
                val b2 = readTrackByte(topTrk)
                if (fullIndexRef.get() == null && b0 >= 0 && b1 >= 0 && b2 >= 0) {
                  liveFallbackMicrosRef = evUs
                  liveFallbackTickRef = evTick
                  liveUsPerQuarter = ((b0 shl 16) or (b1 shl 8) or b2).toLong().coerceIn(10_000L, 10_000_000L)
                }
              } else if (metaType == 0x2F) {
                skipTrackBytes(topTrk, len)
                trkEnded[topTrk] = true
              } else {
                skipTrackBytes(topTrk, len)
              }
            } else if (status == 0xF0 || status == 0xF7) {
              val len = readTrackVlq(topTrk).toInt()
              if (len >= 0) skipTrackBytes(topTrk, len) else trkEnded[topTrk] = true
            } else if (status == 0xF1 || status == 0xF3) {
              skipTrackBytes(topTrk, 1)
            } else if (status == 0xF2) {
              skipTrackBytes(topTrk, 2)
            }
          }
          else -> skipTrackBytes(topTrk, 1)
        }

        if (packedToSchedule != 0L) {
          val slot = schedTail and SCHED_RING_MASK
          schedMicros[slot] = evUs
          schedPacked[slot] = packedToSchedule
          schedTail++
        }

        if (!trkEnded[topTrk] && advanceTrackDelta(topTrk)) {
          heapSiftDownRoot()
        } else {
          trkEnded[topTrk] = true
          heapPopRoot()
        }
      }

      // 2. Dispatch all due events (schedMicros <= playheadUs) to ZeroGcSynthEngine & 88-Key state
      while (schedHead != schedTail) {
        val slot = schedHead and SCHED_RING_MASK
        if (schedMicros[slot] > playheadUs) break
        val packed = schedPacked[slot]
        schedHead++

        synth.enqueueEvent(packed)

        val cmd = ((packed ushr 24) and 0xFFL).toInt()
        val ch = ((packed ushr 16) and 0x0FL).toInt()
        val key = ((packed ushr 8) and 0x7FL).toInt()
        val vel = (packed and 0xFFL).toInt()

        if (cmd == ZeroGcSynthEngine.CMD_NOTE_ON && vel > 0) {
          playedNotesTotal++
          npsBuckets[npsBucketIdx]++
          npsRollingSum++
          liveNps = npsRollingSum
          activeKeyRefCount[key]++
          activeKeyboardState[key] = vel or (ch shl 8)
        } else if (cmd == ZeroGcSynthEngine.CMD_NOTE_OFF || (cmd == ZeroGcSynthEngine.CMD_NOTE_ON && vel == 0)) {
          val cnt = activeKeyRefCount[key]
          if (cnt <= 1) {
            activeKeyRefCount[key] = 0
            activeKeyboardState[key] = 0
          } else {
            activeKeyRefCount[key] = cnt - 1
          }
        }
      }

      // 3. Check end-of-song condition
      if (heapSize == 0 && schedHead == schedTail) {
        val knownDur = durationMicros
        if (knownDur == 0L || playheadUs >= knownDur + 300_000L) {
          isPlaying = false
          activeKeyboardState.fill(0)
          activeKeyRefCount.fill(0)
          liveNps = 0
        }
      }

      try {
        Thread.sleep(3L)
      } catch (_: InterruptedException) {
        break
      }
    }
  }
}
