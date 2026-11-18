package com.example.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Zero-GC Kotlin Software Synthesizer designed specifically for extreme Black MIDI polyphony.
 *
 * Anti-Crackle & Overload Architecture:
 * 1. Structure-of-Arrays (SoA) primitive voice pool (FloatArray / IntArray) — zero heap allocations
 *    on the real-time audio thread.
 * 2. Intrusive O(1) per-(Channel, Key) doubly-linked voice lists — eliminates O(N) scans during
 *    dense Note-On/Note-Off cascades.
 * 3. O(1) 12-probe clock tournament voice stealing paired with a 64-sample sum-of-steps de-click
 *    ramp buffer so voice eviction never causes step-discontinuity buzz/clicks.
 * 4. Per-sample smoothed master gain + soft-knee brickwall limiter (zero block-boundary zipper noise).
 * 5. Real-time Adaptive CPU Overload Guard + cushioned media AudioTrack buffer so CPU spikes shed
 *    quiet release tails gracefully before AudioFlinger can ever underrun ("バリバリ" prevention).
 */
class ZeroGcSynthEngine(
  val sampleRate: Int = 44100,
  private val blockFrames: Int = 512,
) {
  companion object {
    const val MAX_VOICE_CAPACITY = 4096
    private const val EVENT_RING_SIZE = 131072 // Power of 2 (128K events)
    private const val EVENT_RING_MASK = EVENT_RING_SIZE - 1

    private const val SINE_LUT_SIZE = 4096
    private const val SINE_LUT_MASK = SINE_LUT_SIZE - 1
    private const val DECLICK_RAMP_FRAMES = 64
    private const val STEAL_TOURNAMENT_PROBES = 12

    const val CMD_NOTE_ON = 1
    const val CMD_NOTE_OFF = 2
    const val CMD_PROGRAM_CHANGE = 3
    const val CMD_CONTROL_CHANGE = 4
    const val CMD_PITCH_BEND = 5
    const val CMD_ALL_NOTES_OFF = 6
    const val CMD_ALL_SOUND_OFF = 7

    private const val ENV_OFF = 0
    private const val ENV_ATTACK = 1
    private const val ENV_DECAY = 2
    private const val ENV_SUSTAIN = 3
    private const val ENV_RELEASE = 4
    private const val ENV_FAST_KILL = 5

    fun packEvent(cmd: Int, channel: Int, data1: Int, data2: Int): Long {
      return ((cmd.toLong() and 0xFFL) shl 24) or
        ((channel.toLong() and 0x0FL) shl 16) or
        ((data1.toLong() and 0xFFL) shl 8) or
        (data2.toLong() and 0xFFFFL)
    }
  }

  // Lock-free SPSC Event Ring Buffer
  private val eventRing = LongArray(EVENT_RING_SIZE)
  private val writeIndex = AtomicInteger(0)
  private val readIndex = AtomicInteger(0)

  // Hot-swappable SoundBank reference
  private val soundBankRef = AtomicReference(CompiledSoundBank.createBuiltInSine())
  private var currentBankSnapshot: CompiledSoundBank = soundBankRef.get()

  // Live Real-time Synth Parameters (@Volatile for zero-lock reads per block)
  @Volatile var maxPolyphony: Int = 1024
  @Volatile var velocityThreshold: Int = 4
  @Volatile var maxVoicesPerKey: Int = 3
  @Volatile var limiterEnabled: Boolean = true
  @Volatile var sineRichHarmonics: Boolean = true
  @Volatile var masterGain: Float = 1.0f

  // Adaptive CPU Overload Ceiling (dynamically lowers under heavy DSP load to prevent AudioTrack underruns)
  @Volatile private var dynamicPolyphonyCap: Int = MAX_VOICE_CAPACITY

  // Live Telemetry (read by UI at ~20Hz without locking)
  @Volatile var activeVoiceCountTelemetry: Int = 0
    private set
  @Volatile var peakLevelLeft: Float = 0f
    private set
  @Volatile var peakLevelRight: Float = 0f
    private set
  @Volatile var limiterActiveTelemetry: Boolean = false
    private set
  @Volatile var audioCpuLoadPercent: Int = 0
    private set
  @Volatile var cpuGuardActiveTelemetry: Boolean = false
    private set

  // Pre-computed Sine & Harmonic Pluck LUTs
  private val pureSineLut = FloatArray(SINE_LUT_SIZE) { i ->
    sin(2.0 * PI * i / SINE_LUT_SIZE).toFloat()
  }
  private val richSineLut = FloatArray(SINE_LUT_SIZE) { i ->
    val ph = 2.0 * PI * i / SINE_LUT_SIZE
    (0.76 * sin(ph) + 0.16 * sin(2.0 * ph) + 0.08 * sin(3.0 * ph)).toFloat()
  }
  private val midiKeyFreq = FloatArray(128) { k ->
    (440.0 * 2.0.pow((k - 69) / 12.0)).toFloat()
  }

  // MIDI Channel State (16 channels)
  private val chProgram = IntArray(16)
  private val chBankMsb = IntArray(16)
  private val chVolume = FloatArray(16) { 100f / 127f }
  private val chExpression = FloatArray(16) { 1.0f }
  private val chPan = FloatArray(16) { 0.0f }
  private val chPitchBendSemis = FloatArray(16) { 0.0f }
  private val chPitchRatio = FloatArray(16) { 1.0f }
  private val chSustainPedal = BooleanArray(16)

  // Structure-of-Arrays (SoA) Voice Pool
  private val activeSlots = IntArray(MAX_VOICE_CAPACITY)
  private val freeSlots = IntArray(MAX_VOICE_CAPACITY) { MAX_VOICE_CAPACITY - 1 - it }
  private var activeCount = 0
  private var freeCount = MAX_VOICE_CAPACITY
  private var stealClock = 0

  private val vChannelKey = IntArray(MAX_VOICE_CAPACITY) // (channel shl 7) or key

  private val vIsSine = BooleanArray(MAX_VOICE_CAPACITY)
  private val vBufferIndex = IntArray(MAX_VOICE_CAPACITY)
  private val vPosInt = IntArray(MAX_VOICE_CAPACITY)
  private val vPosFrac = FloatArray(MAX_VOICE_CAPACITY)
  private val vBaseStep = FloatArray(MAX_VOICE_CAPACITY)
  private val vEndFrame = IntArray(MAX_VOICE_CAPACITY)
  private val vLoopStart = IntArray(MAX_VOICE_CAPACITY)
  private val vLoopEnd = IntArray(MAX_VOICE_CAPACITY)
  private val vLoopMode = IntArray(MAX_VOICE_CAPACITY)

  private val vEnvStage = IntArray(MAX_VOICE_CAPACITY)
  private val vEnvLevel = FloatArray(MAX_VOICE_CAPACITY)
  private val vAttackInc = FloatArray(MAX_VOICE_CAPACITY)
  private val vDecayDec = FloatArray(MAX_VOICE_CAPACITY)
  private val vSustainLvl = FloatArray(MAX_VOICE_CAPACITY)
  private val vReleaseDec = FloatArray(MAX_VOICE_CAPACITY)
  private val vHeldBySustain = BooleanArray(MAX_VOICE_CAPACITY)

  private val vGainL = FloatArray(MAX_VOICE_CAPACITY)
  private val vGainR = FloatArray(MAX_VOICE_CAPACITY)
  private val vLastOutL = FloatArray(MAX_VOICE_CAPACITY)
  private val vLastOutR = FloatArray(MAX_VOICE_CAPACITY)

  // Intrusive O(1) Doubly-Linked List per (channel, key) [0..2047] for non-releasing voices
  private val keyActiveCounts = IntArray(2048)
  private val keyHeadSlot = IntArray(2048) { -1 } // Oldest active voice on (channel, key)
  private val keyTailSlot = IntArray(2048) { -1 } // Newest active voice on (channel, key)
  private val vPrevInKey = IntArray(MAX_VOICE_CAPACITY) { -1 }
  private val vNextInKey = IntArray(MAX_VOICE_CAPACITY) { -1 }
  private val vInKeyList = BooleanArray(MAX_VOICE_CAPACITY)

  // Sum-of-steps anti-click ramp state & per-sample smoothed post-gain
  private var pendingDeclickL = 0f
  private var pendingDeclickR = 0f
  private var smoothedPostGain = -1f

  // Mixing Scratch Buffers (allocated once)
  private var mixLeft = FloatArray(max(blockFrames, 512))
  private var mixRight = FloatArray(max(blockFrames, 512))
  private val interleavedOut = FloatArray(blockFrames * 2)
  private val fastKillDec = 1.0f / (sampleRate * 0.003f).coerceAtLeast(12f)

  // Audio Thread Management
  @Volatile private var running = false
  private var audioThread: Thread? = null

  fun setSoundBank(bank: CompiledSoundBank) {
    soundBankRef.set(bank)
    enqueueEvent(packEvent(CMD_ALL_SOUND_OFF, 0, 0, 0))
  }

  fun getSoundBank(): CompiledSoundBank = soundBankRef.get()

  fun enqueueEvent(packedEvent: Long): Boolean {
    val w = writeIndex.get()
    val r = readIndex.get()
    if (w - r >= EVENT_RING_SIZE - 1) {
      return false
    }
    eventRing[w and EVENT_RING_MASK] = packedEvent
    writeIndex.lazySet(w + 1)
    return true
  }

  fun panicAllNotesOff() {
    enqueueEvent(packEvent(CMD_ALL_NOTES_OFF, 0, 0, 0))
  }

  fun resetAllControllersAndSilence() {
    enqueueEvent(packEvent(CMD_ALL_SOUND_OFF, 0, 0, 1))
  }

  fun startAudioOutput() {
    if (running) return
    running = true
    audioThread = Thread({ runAudioLoop() }, "Obsidian-ZeroGC-Audio").apply {
      isDaemon = true
      start()
    }
  }

  fun stopAudioOutput() {
    running = false
    audioThread?.join(400)
    audioThread = null
    activeVoiceCountTelemetry = 0
    peakLevelLeft = 0f
    peakLevelRight = 0f
    cpuGuardActiveTelemetry = false
  }

  private fun runAudioLoop() {
    try {
      Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
    } catch (_: Throwable) {
      // Ignore on JVM unit tests
    }

    val bytesPerFrame = 2 * 4 // Stereo PCM_FLOAT
    val minBufBytes = AudioTrack.getMinBufferSize(
      sampleRate,
      AudioFormat.CHANNEL_OUT_STEREO,
      AudioFormat.ENCODING_PCM_FLOAT,
    ).coerceAtLeast(blockFrames * bytesPerFrame * 2)

    // Use a cushioned ~105ms media streaming buffer (without LOW_LATENCY fast-mixer starvation)
    // so transient Black MIDI bursts or OS thread scheduling jitter never cause "バリバリ" underruns.
    val targetBufBytes = maxOf(
      minBufBytes * 4,
      (sampleRate / 9) * bytesPerFrame,
      blockFrames * bytesPerFrame * 8,
    )

    val trackBuilder = AudioTrack.Builder()
      .setAudioAttributes(
        AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_MEDIA)
          .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
          .build()
      )
      .setAudioFormat(
        AudioFormat.Builder()
          .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
          .setSampleRate(sampleRate)
          .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
          .build()
      )
      .setBufferSizeInBytes(targetBufBytes)
      .setTransferMode(AudioTrack.MODE_STREAM)

    val audioTrack = try {
      trackBuilder.build()
    } catch (_: Throwable) {
      running = false
      return
    }

    try {
      audioTrack.play()
      val blockBudgetNs = (blockFrames * 1_000_000_000L) / sampleRate
      var smoothCpu = 0f
      var overloadHoldBlocks = 0
      dynamicPolyphonyCap = MAX_VOICE_CAPACITY

      while (running) {
        val t0 = System.nanoTime()
        renderBlock(interleavedOut, blockFrames)
        val elapsedNs = System.nanoTime() - t0
        val rawLoadPct = ((elapsedNs.toFloat() / blockBudgetNs.toFloat()) * 100f).coerceIn(0f, 200f)
        smoothCpu = smoothCpu * 0.85f + rawLoadPct.coerceAtMost(100f) * 0.15f
        audioCpuLoadPercent = smoothCpu.toInt()

        // Adaptive Real-Time CPU Overload Guard:
        // Shed quiet release/sustain voices BEFORE render time reaches 100% of blockBudgetNs!
        val userMax = maxPolyphony.coerceIn(64, MAX_VOICE_CAPACITY)
        if (rawLoadPct > 74f) {
          val curBase = min(userMax, min(dynamicPolyphonyCap, activeCount.coerceAtLeast(64)))
          dynamicPolyphonyCap = max(48, (curBase * 0.80f).toInt())
          overloadHoldBlocks = 24
        } else if (overloadHoldBlocks > 0) {
          overloadHoldBlocks--
        } else if (smoothCpu < 52f && dynamicPolyphonyCap < MAX_VOICE_CAPACITY) {
          dynamicPolyphonyCap = min(MAX_VOICE_CAPACITY, dynamicPolyphonyCap + 24)
        }
        cpuGuardActiveTelemetry = dynamicPolyphonyCap < userMax && activeCount >= dynamicPolyphonyCap - 8

        val written = audioTrack.write(interleavedOut, 0, blockFrames * 2, AudioTrack.WRITE_BLOCKING)
        if (written < 0) break
      }
    } catch (_: Throwable) {
      // Safe shutdown if audio device disconnects
    } finally {
      try {
        audioTrack.stop()
      } catch (_: Throwable) {}
      audioTrack.release()
    }
  }

  /**
   * Renders [frames] stereo frames into [outInterleaved] (size >= frames * 2).
   * Public/internal for deterministic zero-GC unit & Robolectric testing without an emulator.
   */
  internal fun renderBlock(outInterleaved: FloatArray, frames: Int) {
    if (mixLeft.size < frames) {
      mixLeft = FloatArray(frames)
      mixRight = FloatArray(frames)
    }

    // Check if SoundBank swapped
    val latestBank = soundBankRef.get()
    if (latestBank !== currentBankSnapshot) {
      killAllVoicesImmediate()
      currentBankSnapshot = latestBank
    }

    val effectiveCap = min(maxPolyphony.coerceIn(32, MAX_VOICE_CAPACITY), dynamicPolyphonyCap)
      .coerceIn(32, MAX_VOICE_CAPACITY)

    // Drain SPSC MIDI event ring with extreme-burst Note-On surge protection
    var r = readIndex.get()
    val w = writeIndex.get()
    val maxNoteOnWindow = effectiveCap * 2
    while (r != w) {
      val ev = eventRing[r and EVENT_RING_MASK]
      val remaining = w - r
      if (remaining > maxNoteOnWindow) {
        val cmd = ((ev ushr 24) and 0xFFL).toInt()
        val vel = (ev and 0xFFFFL).toInt()
        // Under a massive >2x-polyphony single-block burst, skip older low/mid Note-Ons while
        // preserving 100% of Note-Offs, CC, Program Change, and Pitch Bend state transitions.
        if (cmd == CMD_NOTE_ON && vel in 1..95) {
          r++
          continue
        }
      }
      processEvent(ev, effectiveCap)
      r++
    }
    readIndex.lazySet(r)

    // Enforce live polyphony & adaptive overload cap cleanly via de-clicked voice eviction
    while (activeCount > effectiveCap) {
      stealBestCandidateVoice(forceImmediate = true)
    }

    mixLeft.fill(0f, 0, frames)
    mixRight.fill(0f, 0, frames)

    // Apply 64-sample linear anti-click fade for all voices immediately evicted prior to this block
    if (abs(pendingDeclickL) > 1e-6f || abs(pendingDeclickR) > 1e-6f) {
      val rampLen = min(frames, DECLICK_RAMP_FRAMES)
      val invRamp = 1.0f / rampLen.toFloat()
      val dcL = pendingDeclickL
      val dcR = pendingDeclickR
      for (f in 0 until rampLen) {
        val weight = 1.0f - (f.toFloat() * invRamp)
        mixLeft[f] += dcL * weight
        mixRight[f] += dcR * weight
      }
      pendingDeclickL = 0f
      pendingDeclickR = 0f
    }

    val bank = currentBankSnapshot
    val sampleBuffers = bank.sampleBuffers
    val sineLut = if (sineRichHarmonics) richSineLut else pureSineLut

    var idx = 0
    while (idx < activeCount) {
      val slot = activeSlots[idx]
      var stage = vEnvStage[slot]
      if (stage == ENV_OFF) {
        unlinkVoiceFromKeyList(slot)
        vLastOutL[slot] = 0f
        vLastOutR[slot] = 0f
        releaseSlotAt(idx)
        continue
      }

      val ch = vChannelKey[slot] ushr 7
      val step = vBaseStep[slot] * chPitchRatio[ch]
      var posInt = vPosInt[slot]
      var posFrac = vPosFrac[slot]
      var env = vEnvLevel[slot]
      val attInc = vAttackInc[slot]
      val decDec = vDecayDec[slot]
      val susLvl = vSustainLvl[slot]
      val relDec = vReleaseDec[slot]
      val gL = vGainL[slot]
      val gR = vGainR[slot]

      var voiceFinished = false
      var lastSample = 0f

      if (vIsSine[slot]) {
        for (f in 0 until frames) {
          when (stage) {
            ENV_ATTACK -> {
              env += attInc
              if (env >= 1f) {
                env = 1f
                stage = ENV_DECAY
              }
            }
            ENV_DECAY -> {
              env -= decDec
              if (env <= susLvl) {
                env = susLvl
                stage = ENV_SUSTAIN
              }
            }
            ENV_SUSTAIN -> {
              if (susLvl <= 0.0005f) {
                voiceFinished = true
                break
              }
            }
            ENV_RELEASE -> {
              env -= relDec
              if (env <= 0.0005f) {
                env = 0f
                voiceFinished = true
                break
              }
            }
            ENV_FAST_KILL -> {
              env -= fastKillDec
              if (env <= 0.0005f) {
                env = 0f
                voiceFinished = true
                break
              }
            }
          }

          val s0 = sineLut[posInt and SINE_LUT_MASK]
          val s1 = sineLut[(posInt + 1) and SINE_LUT_MASK]
          val sample = (s0 + (s1 - s0) * posFrac) * env
          lastSample = sample
          mixLeft[f] += sample * gL
          mixRight[f] += sample * gR

          posFrac += step
          val whole = posFrac.toInt()
          posInt = (posInt + whole) and SINE_LUT_MASK
          posFrac -= whole.toFloat()
        }
      } else {
        val bufIdx = vBufferIndex[slot]
        if (bufIdx !in sampleBuffers.indices) {
          unlinkVoiceFromKeyList(slot)
          vLastOutL[slot] = 0f
          vLastOutR[slot] = 0f
          releaseSlotAt(idx)
          continue
        }
        val pcm = sampleBuffers[bufIdx]
        val endFrame = vEndFrame[slot]
        val loopStart = vLoopStart[slot]
        val loopEnd = vLoopEnd[slot]
        val loopMode = vLoopMode[slot]
        val isLoopActive = loopMode == 1 || (loopMode == 3 && stage != ENV_RELEASE && stage != ENV_FAST_KILL)
        val loopLen = loopEnd - loopStart

        for (f in 0 until frames) {
          if (isLoopActive) {
            if (posInt >= loopEnd && loopLen > 0) {
              posInt = loopStart + ((posInt - loopStart) % loopLen)
            }
          } else if (posInt >= endFrame - 1) {
            voiceFinished = true
            break
          }

          when (stage) {
            ENV_ATTACK -> {
              env += attInc
              if (env >= 1f) {
                env = 1f
                stage = ENV_DECAY
              }
            }
            ENV_DECAY -> {
              env -= decDec
              if (env <= susLvl) {
                env = susLvl
                stage = ENV_SUSTAIN
              }
            }
            ENV_SUSTAIN -> {
              if (susLvl <= 0.0005f) {
                voiceFinished = true
                break
              }
            }
            ENV_RELEASE -> {
              env -= relDec
              if (env <= 0.0005f) {
                env = 0f
                voiceFinished = true
                break
              }
            }
            ENV_FAST_KILL -> {
              env -= fastKillDec
              if (env <= 0.0005f) {
                env = 0f
                voiceFinished = true
                break
              }
            }
          }

          val s0 = pcm.get(posInt) * (1f / 32768f)
          val nextIdx = if (isLoopActive && posInt + 1 >= loopEnd) loopStart else min(posInt + 1, endFrame - 1)
          val s1 = pcm.get(nextIdx) * (1f / 32768f)
          val sample = (s0 + (s1 - s0) * posFrac) * env
          lastSample = sample
          mixLeft[f] += sample * gL
          mixRight[f] += sample * gR

          posFrac += step
          val whole = posFrac.toInt()
          posInt += whole
          posFrac -= whole.toFloat()
        }
      }

      if (voiceFinished) {
        unlinkVoiceFromKeyList(slot)
        vLastOutL[slot] = 0f
        vLastOutR[slot] = 0f
        vEnvStage[slot] = ENV_OFF
        releaseSlotAt(idx)
      } else {
        vPosInt[slot] = posInt
        vPosFrac[slot] = posFrac
        vEnvStage[slot] = stage
        vEnvLevel[slot] = env
        vLastOutL[slot] = lastSample * gL
        vLastOutR[slot] = lastSample * gR
        idx++
      }
    }

    // Per-sample smoothed Master Gain + Auto Polyphony Headroom + Soft-Knee Brickwall Limiter
    val voiceNorm = 1.0f / sqrt(max(4f, activeCount * 0.48f))
    val targetPostGain = masterGain * voiceNorm
    var curGain = if (smoothedPostGain < 0f) targetPostGain else smoothedPostGain
    val gainSmoothCoeff = 0.008f
    val useLimiter = limiterEnabled
    var maxL = 0f
    var maxR = 0f
    var limited = false

    var outIdx = 0
    for (f in 0 until frames) {
      curGain += (targetPostGain - curGain) * gainSmoothCoeff
      var l = mixLeft[f] * curGain
      var rCh = mixRight[f] * curGain

      if (useLimiter) {
        if (abs(l) > 0.85f || abs(rCh) > 0.85f) limited = true
        l = fastSoftLimiter(l)
        rCh = fastSoftLimiter(rCh)
      } else {
        l = l.coerceIn(-1f, 1f)
        rCh = rCh.coerceIn(-1f, 1f)
      }

      val al = abs(l)
      val ar = abs(rCh)
      if (al > maxL) maxL = al
      if (ar > maxR) maxR = ar

      outInterleaved[outIdx++] = l
      outInterleaved[outIdx++] = rCh
    }
    smoothedPostGain = curGain

    activeVoiceCountTelemetry = activeCount
    peakLevelLeft = max(maxL, peakLevelLeft * 0.82f)
    peakLevelRight = max(maxR, peakLevelRight * 0.82f)
    limiterActiveTelemetry = limited
  }

  private fun fastSoftLimiter(x: Float): Float {
    if (x <= -3f) return -1f
    if (x >= 3f) return 1f
    val x2 = x * x
    return (x * (27f + x2)) / (27f + 9f * x2)
  }

  private fun processEvent(ev: Long, effectiveCap: Int) {
    val cmd = ((ev ushr 24) and 0xFFL).toInt()
    val ch = ((ev ushr 16) and 0x0FL).toInt()
    val d1 = ((ev ushr 8) and 0xFFL).toInt()
    val d2 = (ev and 0xFFFFL).toInt()

    when (cmd) {
      CMD_NOTE_ON -> {
        if (d2 == 0) {
          handleNoteOff(ch, d1)
        } else {
          handleNoteOn(ch, d1, d2 and 0x7F, effectiveCap)
        }
      }
      CMD_NOTE_OFF -> handleNoteOff(ch, d1)
      CMD_PROGRAM_CHANGE -> chProgram[ch] = d1 and 0x7F
      CMD_CONTROL_CHANGE -> handleControlChange(ch, d1, d2 and 0x7F)
      CMD_PITCH_BEND -> {
        val semis = ((d2 - 8192) / 8192f) * 2.0f
        chPitchBendSemis[ch] = semis
        chPitchRatio[ch] = 2.0.pow(semis / 12.0).toFloat()
      }
      CMD_ALL_NOTES_OFF -> releaseAllNotes(chFilter = -1)
      CMD_ALL_SOUND_OFF -> {
        killAllVoicesImmediate()
        if (d2 == 1) resetControllers()
      }
    }
  }

  private fun handleControlChange(ch: Int, cc: Int, value: Int) {
    when (cc) {
      0 -> if (ch != 9) chBankMsb[ch] = value
      7 -> chVolume[ch] = value / 127f
      10 -> chPan[ch] = ((value - 64) / 64f).coerceIn(-1f, 1f)
      11 -> chExpression[ch] = value / 127f
      64 -> {
        val down = value >= 64
        val wasDown = chSustainPedal[ch]
        chSustainPedal[ch] = down
        if (wasDown && !down) {
          for (i in 0 until activeCount) {
            val slot = activeSlots[i]
            if ((vChannelKey[slot] ushr 7) == ch && vHeldBySustain[slot]) {
              vHeldBySustain[slot] = false
              transitionToRelease(slot)
            }
          }
        }
      }
      120 -> killChannelVoices(ch)
      121 -> {
        chExpression[ch] = 1f
        chPitchBendSemis[ch] = 0f
        chPitchRatio[ch] = 1f
        chSustainPedal[ch] = false
      }
      123 -> releaseAllNotes(chFilter = ch)
    }
  }

  private fun handleNoteOn(ch: Int, key: Int, velocity: Int, effectiveCap: Int) {
    if (key !in 0..127) return
    if (velocity < velocityThreshold) return

    val chKey = (ch shl 7) or key
    val perKeyCap = maxVoicesPerKey.coerceIn(1, 16)
    while (keyActiveCounts[chKey] >= perKeyCap) {
      if (!fastKillOldestVoiceOnKey(chKey)) break
    }

    val bank = currentBankSnapshot
    val velNorm = (velocity / 127f)
    val velGain = velNorm * velNorm * chVolume[ch] * chExpression[ch]
    if (velGain <= 0.0002f) return

    if (bank.type == SoundBankType.SINE) {
      val slot = acquireVoiceSlot(effectiveCap) ?: return
      val freq = if (ch == 9) {
        midiKeyFreq[key.coerceIn(32, 76)] * 0.8f
      } else {
        midiKeyFreq[key]
      }
      val step = (freq * SINE_LUT_SIZE) / sampleRate.toFloat()
      val panAngle = ((chPan[ch] + 1f) * 0.25f * PI).toFloat()

      vChannelKey[slot] = chKey
      vIsSine[slot] = true
      vPosInt[slot] = 0
      vPosFrac[slot] = 0f
      vBaseStep[slot] = step
      vHeldBySustain[slot] = false

      vEnvStage[slot] = ENV_ATTACK
      vEnvLevel[slot] = 0f
      vAttackInc[slot] = 1f / (sampleRate * 0.0025f)
      if (ch == 9) {
        vDecayDec[slot] = 1f / (sampleRate * 0.09f)
        vSustainLvl[slot] = 0f
        vReleaseDec[slot] = 1f / (sampleRate * 0.06f)
      } else {
        vDecayDec[slot] = (1f - 0.55f) / (sampleRate * 0.45f)
        vSustainLvl[slot] = 0.55f
        vReleaseDec[slot] = 0.55f / (sampleRate * 0.16f)
      }
      vGainL[slot] = velGain * cos(panAngle)
      vGainR[slot] = velGain * sin(panAngle)
      linkVoiceToKeyTail(slot, chKey)
      return
    }

    val isDrum = (ch == 9)
    val regionIndices = bank.getMatchingRegionIndices(chBankMsb[ch], chProgram[ch], isDrum, key)
    val regions = bank.regions
    for (rIdx in regionIndices) {
      val reg = regions[rIdx]
      if (velocity < reg.loVel || velocity > reg.hiVel) continue

      val slot = acquireVoiceSlot(effectiveCap) ?: break
      val pitchSemis = (key - reg.rootKey) * reg.scaleTuning + (reg.tuningCents / 100f)
      val rateRatio = (reg.sampleRate.toDouble() / sampleRate.toDouble()) * 2.0.pow(pitchSemis / 12.0)
      val combinedPan = (chPan[ch] + reg.pan).coerceIn(-1f, 1f)
      val panAngle = ((combinedPan + 1f) * 0.25f * PI).toFloat()
      val totalGain = velGain * reg.gainLinear

      vChannelKey[slot] = chKey
      vIsSine[slot] = false
      vBufferIndex[slot] = reg.bufferIndex
      vPosInt[slot] = reg.startFrame
      vPosFrac[slot] = 0f
      vBaseStep[slot] = rateRatio.toFloat()
      vEndFrame[slot] = reg.endFrame
      vLoopStart[slot] = reg.loopStartFrame
      vLoopEnd[slot] = reg.loopEndFrame
      vLoopMode[slot] = reg.loopMode
      vHeldBySustain[slot] = false

      vEnvStage[slot] = ENV_ATTACK
      vEnvLevel[slot] = 0f
      vAttackInc[slot] = 1f / max(1f, sampleRate * reg.attackSec)
      val sus = reg.sustainLevel
      vDecayDec[slot] = max(0.00001f, (1f - sus) / max(1f, sampleRate * reg.decaySec))
      vSustainLvl[slot] = sus
      vReleaseDec[slot] = max(0.00005f, max(sus, 0.25f) / max(1f, sampleRate * reg.releaseSec))
      vGainL[slot] = totalGain * cos(panAngle)
      vGainR[slot] = totalGain * sin(panAngle)
      linkVoiceToKeyTail(slot, chKey)
    }
  }

  /**
   * O(1) Note-Off via intrusive per-(channel, key) doubly-linked list!
   */
  private fun handleNoteOff(ch: Int, key: Int) {
    if (key !in 0..127) return
    val targetChKey = (ch shl 7) or key
    val sustain = chSustainPedal[ch]
    var slot = keyHeadSlot[targetChKey]
    if (sustain) {
      while (slot >= 0) {
        vHeldBySustain[slot] = true
        slot = vNextInKey[slot]
      }
    } else {
      while (slot >= 0) {
        val next = vNextInKey[slot]
        transitionToRelease(slot)
        slot = next
      }
    }
  }

  private fun transitionToRelease(slot: Int) {
    val st = vEnvStage[slot]
    if (st != ENV_RELEASE && st != ENV_FAST_KILL && st != ENV_OFF) {
      unlinkVoiceFromKeyList(slot)
      vEnvStage[slot] = ENV_RELEASE
      val cur = max(vEnvLevel[slot], 0.05f)
      if (vReleaseDec[slot] < cur / (sampleRate * 4.0f)) {
        vReleaseDec[slot] = cur / (sampleRate * 0.4f)
      }
    }
  }

  /**
   * O(1) eviction of the oldest active voice on [chKey] via head pointer of doubly-linked list.
   */
  private fun fastKillOldestVoiceOnKey(chKey: Int): Boolean {
    val oldestSlot = keyHeadSlot[chKey]
    if (oldestSlot < 0) return false
    unlinkVoiceFromKeyList(oldestSlot)
    vHeldBySustain[oldestSlot] = false
    vEnvStage[oldestSlot] = ENV_FAST_KILL
    return true
  }

  private fun acquireVoiceSlot(effectiveCap: Int): Int? {
    if (activeCount >= effectiveCap || freeCount == 0) {
      stealBestCandidateVoice(forceImmediate = true)
    }
    if (freeCount == 0) return null
    val slot = freeSlots[--freeCount]
    vLastOutL[slot] = 0f
    vLastOutR[slot] = 0f
    activeSlots[activeCount++] = slot
    return slot
  }

  /**
   * O(1) 12-probe clock tournament voice stealing with 64-sample sum-of-steps anti-click tail.
   */
  private fun stealBestCandidateVoice(forceImmediate: Boolean) {
    if (activeCount == 0) return
    val count = activeCount
    val probes = min(count, STEAL_TOURNAMENT_PROBES)
    var bestIdx = 0
    var bestScore = Float.MAX_VALUE
    var idx = stealClock % count

    for (p in 0 until probes) {
      val slot = activeSlots[idx]
      val st = vEnvStage[slot]
      val stateBias = when (st) {
        ENV_FAST_KILL -> 0.008f
        ENV_RELEASE -> 0.08f
        ENV_ATTACK -> 1.35f
        else -> 1.0f
      }
      val score = vEnvLevel[slot] * stateBias
      if (score < bestScore) {
        bestScore = score
        bestIdx = idx
      }
      idx++
      if (idx >= count) idx = 0
    }
    stealClock = idx

    val victimSlot = activeSlots[bestIdx]
    unlinkVoiceFromKeyList(victimSlot)
    vHeldBySustain[victimSlot] = false

    if (forceImmediate) {
      pendingDeclickL += vLastOutL[victimSlot]
      pendingDeclickR += vLastOutR[victimSlot]
      vLastOutL[victimSlot] = 0f
      vLastOutR[victimSlot] = 0f
      vEnvStage[victimSlot] = ENV_OFF
      releaseSlotAt(bestIdx)
    } else {
      vEnvStage[victimSlot] = ENV_FAST_KILL
    }
  }

  private fun linkVoiceToKeyTail(slot: Int, chKey: Int) {
    if (vInKeyList[slot]) unlinkVoiceFromKeyList(slot)
    val tail = keyTailSlot[chKey]
    vPrevInKey[slot] = tail
    vNextInKey[slot] = -1
    vInKeyList[slot] = true
    if (tail >= 0) {
      vNextInKey[tail] = slot
    } else {
      keyHeadSlot[chKey] = slot
    }
    keyTailSlot[chKey] = slot
    keyActiveCounts[chKey]++
  }

  private fun unlinkVoiceFromKeyList(slot: Int) {
    if (!vInKeyList[slot]) return
    val chKey = vChannelKey[slot]
    val prev = vPrevInKey[slot]
    val next = vNextInKey[slot]
    if (prev >= 0) {
      vNextInKey[prev] = next
    } else {
      keyHeadSlot[chKey] = next
    }
    if (next >= 0) {
      vPrevInKey[next] = prev
    } else {
      keyTailSlot[chKey] = prev
    }
    vPrevInKey[slot] = -1
    vNextInKey[slot] = -1
    vInKeyList[slot] = false
    val cur = keyActiveCounts[chKey]
    if (cur > 0) keyActiveCounts[chKey] = cur - 1
  }

  private fun releaseSlotAt(activeIdx: Int) {
    val slot = activeSlots[activeIdx]
    val lastIdx = activeCount - 1
    if (activeIdx != lastIdx) {
      activeSlots[activeIdx] = activeSlots[lastIdx]
    }
    activeCount = lastIdx
    freeSlots[freeCount++] = slot
  }

  private fun releaseAllNotes(chFilter: Int) {
    for (i in 0 until activeCount) {
      val slot = activeSlots[i]
      val ch = vChannelKey[slot] ushr 7
      if (chFilter < 0 || ch == chFilter) {
        vHeldBySustain[slot] = false
        transitionToRelease(slot)
      }
    }
  }

  private fun killChannelVoices(ch: Int) {
    var i = 0
    while (i < activeCount) {
      val slot = activeSlots[i]
      if ((vChannelKey[slot] ushr 7) == ch) {
        unlinkVoiceFromKeyList(slot)
        pendingDeclickL += vLastOutL[slot]
        pendingDeclickR += vLastOutR[slot]
        vLastOutL[slot] = 0f
        vLastOutR[slot] = 0f
        vEnvStage[slot] = ENV_OFF
        releaseSlotAt(i)
      } else {
        i++
      }
    }
  }

  private fun killAllVoicesImmediate() {
    while (activeCount > 0) {
      val idx = activeCount - 1
      val slot = activeSlots[idx]
      pendingDeclickL += vLastOutL[slot]
      pendingDeclickR += vLastOutR[slot]
      vLastOutL[slot] = 0f
      vLastOutR[slot] = 0f
      vInKeyList[slot] = false
      vPrevInKey[slot] = -1
      vNextInKey[slot] = -1
      vEnvStage[slot] = ENV_OFF
      releaseSlotAt(idx)
    }
    keyActiveCounts.fill(0)
    keyHeadSlot.fill(-1)
    keyTailSlot.fill(-1)
  }

  private fun resetControllers() {
    for (ch in 0 until 16) {
      chProgram[ch] = 0
      chBankMsb[ch] = 0
      chVolume[ch] = 100f / 127f
      chExpression[ch] = 1f
      chPan[ch] = 0f
      chPitchBendSemis[ch] = 0f
      chPitchRatio[ch] = 1f
      chSustainPedal[ch] = false
    }
  }
}
