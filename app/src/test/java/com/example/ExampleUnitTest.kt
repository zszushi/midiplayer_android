package com.example

import com.example.audio.Sf2Loader
import com.example.audio.SfzLoader
import com.example.audio.ZeroGcSynthEngine
import com.example.midi.MidiFastScanner
import com.example.midi.MidiScanProgress
import com.example.midi.ScanPhase
import com.example.midi.StreamingMidiSequencer
import com.example.midi.VisualNoteRingBuffer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

  @Test
  fun `parallel midi scanner tracks n of m progress and caches 200-track black midi`() = runBlocking {
    val tempMidi = File.createTempFile("black_midi_test_", ".mid")
    val tempCache = File(tempMidi.parentFile, "cache_test_${System.nanoTime()}").apply { mkdirs() }
    try {
      val trackCount = 200
      val notesPerTrack = 50
      writeSyntheticMultiTrackMidi(tempMidi, trackCount = trackCount, notesPerTrack = notesPerTrack)

      RandomAccessFile(tempMidi, "r").use { raf ->
        val channel = raf.channel
        val header = MidiFastScanner.scanHeaderAndTrackBoundaries(channel, tempMidi.name)
        assertEquals(trackCount, header.trackCount)
        assertEquals(480, header.ticksPerQuarter)

        val observedProgress = mutableListOf<MidiScanProgress>()
        val fullIndex = MidiFastScanner.scanTracksInParallel(
          channel = channel,
          header = header,
          cacheDir = tempCache,
          onProgress = { observedProgress.add(it) },
        )

        assertEquals((trackCount * notesPerTrack).toLong(), fullIndex.totalNotes)
        assertTrue(fullIndex.durationMicros > 0L)
        val completed = observedProgress.last()
        assertEquals(ScanPhase.COMPLETED, completed.phase)
        assertEquals(trackCount, completed.analyzedTracks)
        assertEquals(trackCount, completed.totalTracks)

        // Second scan must restore instantaneously from binary index cache
        val cachedIndex = MidiFastScanner.scanTracksInParallel(
          channel = channel,
          header = header,
          cacheDir = tempCache,
          onProgress = {},
        )
        assertTrue(cachedIndex.isFromCache)
        assertEquals(fullIndex.totalNotes, cachedIndex.totalNotes)
      }
    } finally {
      tempMidi.delete()
      tempCache.deleteRecursively()
    }
  }

  @Test
  fun `zero-gc synth enforces velocity cutoff, per-key stealing, and brickwall limiter`() {
    val synth = ZeroGcSynthEngine(sampleRate = 44100, blockFrames = 128)
    synth.velocityThreshold = 16
    synth.maxVoicesPerKey = 2
    synth.maxPolyphony = 64
    synth.limiterEnabled = true

    // Below velocity threshold (vel = 8 < 16) -> should be ignored
    synth.enqueueEvent(ZeroGcSynthEngine.packEvent(ZeroGcSynthEngine.CMD_NOTE_ON, 0, 60, 8))
    val out = FloatArray(128 * 2)
    synth.renderBlock(out, 128)
    assertEquals(0, synth.activeVoiceCountTelemetry)

    // Stack 10 simultaneous Note-On hits on same key 60 -> per-key stealing caps sustained count
    repeat(10) {
      synth.enqueueEvent(ZeroGcSynthEngine.packEvent(ZeroGcSynthEngine.CMD_NOTE_ON, 0, 60, 120))
    }
    // Render enough blocks for fast-killed voices (3ms) to finish
    repeat(4) { synth.renderBlock(out, 128) }
    assertTrue(synth.activeVoiceCountTelemetry in 1..2)
    assertTrue(out.any { kotlin.math.abs(it) > 0.001f })
    assertTrue(out.all { it in -1.0f..1.0f })

    // Stress test: 4,000-event Black MIDI burst with O(1) per-key lists & de-click voice stealing
    for (i in 0 until 4000) {
      val ch = i and 0x0F
      val key = 24 + (i % 72)
      synth.enqueueEvent(ZeroGcSynthEngine.packEvent(ZeroGcSynthEngine.CMD_NOTE_ON, ch, key, 108))
      if (i % 3 == 0) {
        synth.enqueueEvent(ZeroGcSynthEngine.packEvent(ZeroGcSynthEngine.CMD_NOTE_OFF, ch, key, 0))
      }
    }
    synth.renderBlock(out, 128)
    assertTrue(synth.activeVoiceCountTelemetry <= 64)
    assertTrue(out.all { it.isFinite() && it in -1.0f..1.0f })
  }

  @Test
  fun `visual ring buffer coalesces sub-8ms retriggers and sequencer scales lookahead with zoom`() {
    val ring = VisualNoteRingBuffer(1024)
    // 5 hits within 6ms on same (ch=0, key=60) coalesce into a single deterministic slot
    ring.onNoteOn(100_000L, channel = 0, key = 60, velocity = 70)
    ring.onNoteOn(102_000L, channel = 0, key = 60, velocity = 95)
    ring.onNoteOn(105_000L, channel = 0, key = 60, velocity = 115)
    assertEquals(1L, ring.writeCount)
    assertEquals(115, (ring.packedMeta[0] ushr 16) and 0x7F)

    // Subsequent hit 30ms later allocates a cleanly separated second bar without overlap
    ring.onNoteOn(135_000L, channel = 0, key = 60, velocity = 100)
    assertEquals(2L, ring.writeCount)
    assertTrue(ring.endMicros[0] <= ring.startMicros[1])

    // Dynamic zoom lookahead scaling
    val synth = ZeroGcSynthEngine(sampleRate = 44100, blockFrames = 256)
    val seq = StreamingMidiSequencer(synth, ring)
    seq.noteZoom = 1.0f
    assertEquals(1_400_000L, seq.currentVisibleWindowMicros())
    seq.noteZoom = 2.0f
    assertEquals(700_000L, seq.currentVisibleWindowMicros())
    seq.noteZoom = 0.25f
    assertEquals(5_600_000L, seq.currentVisibleWindowMicros())
  }

  @Test
  fun `sfz and sf2 loaders compile regions and synthesize audio without error`() {
    val wavBytes = createTinySineWavBytes(frames = 512, sampleRate = 44100)
    val sfzText = """
      #define ${'$'}ROOT c4
      <global> ampeg_release=0.3
      <group> lovel=1 hivel=127
      <region> sample=piano_c4.wav lokey=0 hikey=127 pitch_keycenter=${'$'}ROOT loop_mode=loop_continuous loop_start=32 loop_end=480
    """.trimIndent()

    var lastLoaded = 0
    var lastTotal = 0
    val sfzBank = SfzLoader.compileSfzText(
      sfzDisplayName = "TestGrand",
      sfzText = sfzText,
      sfzDirPrefix = "",
      availableSfzList = listOf("TestGrand.sfz" to "doc1"),
      activeSfzDocId = "doc1",
      resolveSampleStream = { ByteArrayInputStream(wavBytes) },
      onProgress = { loaded, total ->
        lastLoaded = loaded
        lastTotal = total
      },
    )
    assertEquals(1, lastLoaded)
    assertEquals(1, lastTotal)
    assertEquals(1, sfzBank.regionCount)

    val synth = ZeroGcSynthEngine(sampleRate = 44100, blockFrames = 128)
    val out = FloatArray(128 * 2)
    synth.setSoundBank(sfzBank)
    synth.renderBlock(out, 128) // Process SoundBank swap
    synth.enqueueEvent(ZeroGcSynthEngine.packEvent(ZeroGcSynthEngine.CMD_NOTE_ON, 0, 64, 110))
    synth.renderBlock(out, 128)
    assertTrue(out.any { kotlin.math.abs(it) > 0.0005f })

    // Also verify minimal valid SF2 RIFF sfbk parsing
    val tempSf2 = File.createTempFile("minimal_test_", ".sf2")
    try {
      writeMinimalSf2File(tempSf2)
      RandomAccessFile(tempSf2, "r").use { raf ->
        val sf2Bank = Sf2Loader.load(raf.channel, "Fallback") { _, _ -> }
        assertEquals("MiniPianoSF2", sf2Bank.name)
        assertEquals(1, sf2Bank.regionCount)
      }
    } finally {
      tempSf2.delete()
    }
  }

  private fun writeSyntheticMultiTrackMidi(file: File, trackCount: Int, notesPerTrack: Int) {
    DataOutputStream(file.outputStream().buffered()).use { out ->
      out.writeBytes("MThd")
      out.writeInt(6)
      out.writeShort(1) // Format 1
      out.writeShort(trackCount)
      out.writeShort(480) // 480 PPQ

      for (t in 0 until trackCount) {
        val trk = ByteArrayOutputStream(notesPerTrack * 8 + 16)
        if (t == 0) {
          // Tempo meta event: 150 BPM (400,000 us/quarter)
          trk.write(0x00)
          trk.write(0xFF)
          trk.write(0x51)
          trk.write(0x03)
          trk.write((400_000 ushr 16) and 0xFF)
          trk.write((400_000 ushr 8) and 0xFF)
          trk.write(400_000 and 0xFF)
        }
        val ch = t % 16
        trk.write(0x00)
        trk.write(0x90 or ch)
        trk.write(60)
        trk.write(100)
        // Use running status for subsequent events!
        for (n in 1 until notesPerTrack) {
          trk.write(0x10) // delta 16 ticks, running status 0x90|ch
          val key = 36 + (n % 48)
          trk.write(key)
          trk.write(96)
          trk.write(0x08) // note off via velocity 0
          trk.write(key)
          trk.write(0)
        }
        // End of Track
        trk.write(0x00)
        trk.write(0xFF)
        trk.write(0x2F)
        trk.write(0x00)

        val payload = trk.toByteArray()
        out.writeBytes("MTrk")
        out.writeInt(payload.size)
        out.write(payload)
      }
    }
  }

  private fun createTinySineWavBytes(frames: Int, sampleRate: Int): ByteArray {
    val dataBytes = frames * 2
    val buf = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
    buf.put("RIFF".toByteArray(Charsets.US_ASCII))
    buf.putInt(36 + dataBytes)
    buf.put("WAVE".toByteArray(Charsets.US_ASCII))
    buf.put("fmt ".toByteArray(Charsets.US_ASCII))
    buf.putInt(16)
    buf.putShort(1) // PCM
    buf.putShort(1) // Mono
    buf.putInt(sampleRate)
    buf.putInt(sampleRate * 2)
    buf.putShort(2)
    buf.putShort(16)
    buf.put("data".toByteArray(Charsets.US_ASCII))
    buf.putInt(dataBytes)
    for (i in 0 until frames) {
      val s = (kotlin.math.sin(2.0 * Math.PI * i / 32.0) * 18000).toInt().toShort()
      buf.putShort(s)
    }
    return buf.array()
  }

  private fun writeMinimalSf2File(file: File) {
    fun subChunk(id: String, body: ByteArray): ByteArray {
      val pad = body.size and 1
      val bb = ByteBuffer.allocate(8 + body.size + pad).order(ByteOrder.LITTLE_ENDIAN)
      bb.put(id.toByteArray(Charsets.US_ASCII))
      bb.putInt(body.size)
      bb.put(body)
      if (pad == 1) bb.put(0)
      return bb.array()
    }

    fun listChunk(listType: String, vararg children: ByteArray): ByteArray {
      val totalBody = 4 + children.sumOf { it.size }
      val bb = ByteBuffer.allocate(8 + totalBody).order(ByteOrder.LITTLE_ENDIAN)
      bb.put("LIST".toByteArray(Charsets.US_ASCII))
      bb.putInt(totalBody)
      bb.put(listType.toByteArray(Charsets.US_ASCII))
      children.forEach { bb.put(it) }
      return bb.array()
    }

    val inam = subChunk("INAM", "MiniPianoSF2\u0000".toByteArray(Charsets.US_ASCII))
    val infoList = listChunk("INFO", inam)

    val smplBuf = ByteBuffer.allocate(256 * 2).order(ByteOrder.LITTLE_ENDIAN)
    for (i in 0 until 256) smplBuf.putShort(((i % 32) * 500).toShort())
    val sdtaList = listChunk("sdta", subChunk("smpl", smplBuf.array()))

    // phdr: 2 entries x 38 bytes (Preset 0 + EOP)
    val phdr = ByteBuffer.allocate(76).order(ByteOrder.LITTLE_ENDIAN).apply {
      put("Grand".toByteArray(Charsets.US_ASCII).copyOf(20))
      putShort(0); putShort(0); putShort(0); putInt(0); putInt(0); putInt(0)
      put("EOP".toByteArray(Charsets.US_ASCII).copyOf(20))
      putShort(0); putShort(0); putShort(1); putInt(0); putInt(0); putInt(0)
    }.array()

    val pbag = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply {
      putShort(0); putShort(0)
      putShort(1); putShort(0)
    }.array()

    val pgen = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply {
      putShort(41); putShort(0) // instrument = 0
      putShort(0); putShort(0)
    }.array()

    val inst = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
      put("Inst0".toByteArray(Charsets.US_ASCII).copyOf(20)); putShort(0)
      put("EOI".toByteArray(Charsets.US_ASCII).copyOf(20)); putShort(1)
    }.array()

    val ibag = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply {
      putShort(0); putShort(0)
      putShort(1); putShort(0)
    }.array()

    val igen = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).apply {
      putShort(53); putShort(0) // sampleID = 0
      putShort(0); putShort(0)
    }.array()

    val shdr = ByteBuffer.allocate(92).order(ByteOrder.LITTLE_ENDIAN).apply {
      put("Sine0".toByteArray(Charsets.US_ASCII).copyOf(20))
      putInt(0); putInt(200); putInt(16); putInt(180); putInt(44100)
      put(60.toByte()); put(0.toByte()); putShort(0); putShort(1)
      put("EOS".toByteArray(Charsets.US_ASCII).copyOf(20))
      putInt(0); putInt(0); putInt(0); putInt(0); putInt(0)
      put(0.toByte()); put(0.toByte()); putShort(0); putShort(0)
    }.array()

    val pdtaList = listChunk(
      "pdta",
      subChunk("phdr", phdr),
      subChunk("pbag", pbag),
      subChunk("pmod", ByteArray(10)),
      subChunk("pgen", pgen),
      subChunk("inst", inst),
      subChunk("ibag", ibag),
      subChunk("imod", ByteArray(10)),
      subChunk("igen", igen),
      subChunk("shdr", shdr),
    )

    val riffSize = 4 + infoList.size + sdtaList.size + pdtaList.size
    val full = ByteBuffer.allocate(8 + riffSize).order(ByteOrder.LITTLE_ENDIAN)
    full.put("RIFF".toByteArray(Charsets.US_ASCII))
    full.putInt(riffSize)
    full.put("sfbk".toByteArray(Charsets.US_ASCII))
    full.put(infoList)
    full.put(sdtaList)
    full.put(pdtaList)
    file.writeBytes(full.array())
  }
}
