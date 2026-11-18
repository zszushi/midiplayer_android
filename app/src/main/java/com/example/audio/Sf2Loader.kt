package com.example.audio

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.nio.channels.FileChannel
import kotlin.math.pow

/**
 * Memory-mapped SoundFont 2 (.sf2) parser & zone resolver.
 * Keeps the massive 'smpl' 16-bit PCM chunk memory-mapped via FileChannel
 * so multi-hundred-MB SoundFonts consume virtually zero JVM heap.
 */
object Sf2Loader {

  private class Sf2PresetHeader(
    val name: String,
    val preset: Int,
    val bank: Int,
    val bagIndex: Int,
  )

  private class Sf2InstHeader(
    val bagIndex: Int,
  )

  private class Sf2SampleHeader(
    val start: Int,
    val end: Int,
    val startLoop: Int,
    val endLoop: Int,
    val sampleRate: Int,
    val originalPitch: Int,
    val pitchCorrection: Int,
    val sampleType: Int,
  )

  private class GeneratorBag(
    val genStart: Int,
    val genEnd: Int,
  )

  fun load(
    channel: FileChannel,
    fallbackName: String,
    onProgress: (loadedPresets: Int, totalPresets: Int) -> Unit,
  ): CompiledSoundBank {
    val fileSize = channel.size()
    if (fileSize < 64L) throw IOException("SF2ファイルが小さすぎます")

    val headerBuf = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
    readFully(channel, 0L, headerBuf)
    headerBuf.flip()
    val riffTag = readFourCC(headerBuf)
    headerBuf.int // riffSize
    val sfbkTag = readFourCC(headerBuf)
    if (riffTag != "RIFF" || sfbkTag != "sfbk") {
      throw IOException("有効なSoundFont 2 (.sf2) ファイルではありません")
    }

    var soundFontName = fallbackName
    var smplDataOffset = -1L
    var smplDataBytes = 0L

    var phdrBytes: ByteBuffer? = null
    var pbagBytes: ByteBuffer? = null
    var pgenBytes: ByteBuffer? = null
    var instBytes: ByteBuffer? = null
    var ibagBytes: ByteBuffer? = null
    var igenBytes: ByteBuffer? = null
    var shdrBytes: ByteBuffer? = null

    var pos = 12L
    val chunkHead = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
    val listTypeBuf = ByteBuffer.allocate(4)

    while (pos + 8L <= fileSize) {
      chunkHead.clear()
      readFully(channel, pos, chunkHead)
      chunkHead.flip()
      val chunkId = readFourCC(chunkHead)
      val chunkSize = chunkHead.int.toLong() and 0xFFFFFFFFL
      val dataPos = pos + 8L

      if (chunkId == "LIST" && dataPos + 4L <= fileSize) {
        listTypeBuf.clear()
        readFully(channel, dataPos, listTypeBuf)
        listTypeBuf.flip()
        val listType = readFourCC(listTypeBuf)
        var subPos = dataPos + 4L
        val listEnd = (dataPos + chunkSize).coerceAtMost(fileSize)

        while (subPos + 8L <= listEnd) {
          chunkHead.clear()
          readFully(channel, subPos, chunkHead)
          chunkHead.flip()
          val subId = readFourCC(chunkHead)
          val subSize = chunkHead.int.toLong() and 0xFFFFFFFFL
          val subDataPos = subPos + 8L

          when (listType) {
            "INFO" -> {
              if (subId == "INAM" && subSize in 1..256) {
                val strBytes = ByteArray(subSize.toInt())
                readFully(channel, subDataPos, ByteBuffer.wrap(strBytes))
                val parsed = decodeNullTermAscii(strBytes)
                if (parsed.isNotBlank()) soundFontName = parsed
              }
            }
            "sdta" -> {
              if (subId == "smpl") {
                smplDataOffset = subDataPos
                smplDataBytes = subSize
              }
            }
            "pdta" -> {
              if (subSize > 0 && subSize <= 64L * 1024L * 1024L) {
                val buf = ByteBuffer.allocate(subSize.toInt()).order(ByteOrder.LITTLE_ENDIAN)
                readFully(channel, subDataPos, buf)
                buf.flip()
                when (subId) {
                  "phdr" -> phdrBytes = buf
                  "pbag" -> pbagBytes = buf
                  "pgen" -> pgenBytes = buf
                  "inst" -> instBytes = buf
                  "ibag" -> ibagBytes = buf
                  "igen" -> igenBytes = buf
                  "shdr" -> shdrBytes = buf
                }
              }
            }
          }
          subPos = subDataPos + subSize + (subSize and 1L)
        }
      }
      pos = dataPos + chunkSize + (chunkSize and 1L)
    }

    if (smplDataOffset < 0L || smplDataBytes <= 0L) {
      throw IOException("SF2内にPCM波形データ(smpl)が見つかりません")
    }
    val phdr = phdrBytes ?: throw IOException("SF2のプリセットヘッダ(phdr)が欠落しています")
    val pbag = pbagBytes ?: throw IOException("SF2のpbagチャンクが欠落しています")
    val pgen = pgenBytes ?: throw IOException("SF2のpgenチャンクが欠落しています")
    val inst = instBytes ?: throw IOException("SF2のinstチャンクが欠落しています")
    val ibag = ibagBytes ?: throw IOException("SF2のibagチャンクが欠落しています")
    val igen = igenBytes ?: throw IOException("SF2のigenチャンクが欠落しています")
    val shdr = shdrBytes ?: throw IOException("SF2のshdrチャンクが欠落しています")

    val smplShortBuffer: ShortBuffer = channel
      .map(FileChannel.MapMode.READ_ONLY, smplDataOffset, smplDataBytes)
      .order(ByteOrder.LITTLE_ENDIAN)
      .asShortBuffer()
    val totalSampleFrames = smplShortBuffer.limit()

    // Parse Preset Headers (38 bytes each, last is terminal EOP)
    val presetCount = phdr.remaining() / 38
    val presets = ArrayList<Sf2PresetHeader>(presetCount)
    val nameTmp = ByteArray(20)
    for (i in 0 until presetCount) {
      phdr.position(i * 38)
      phdr.get(nameTmp)
      val pName = decodeNullTermAscii(nameTmp)
      val presetNum = phdr.short.toInt() and 0xFFFF
      val bankNum = phdr.short.toInt() and 0xFFFF
      val bagIdx = phdr.short.toInt() and 0xFFFF
      presets.add(Sf2PresetHeader(pName, presetNum, bankNum, bagIdx))
    }

    // Parse Preset Bags (4 bytes each)
    val pbagCount = pbag.remaining() / 4
    val presetGenIndices = IntArray(pbagCount) { i ->
      pbag.position(i * 4)
      pbag.short.toInt() and 0xFFFF
    }

    // Parse Preset Generators (4 bytes each: oper uint16, amount int16)
    val pgenCount = pgen.remaining() / 4
    val pgenOper = IntArray(pgenCount)
    val pgenAmount = IntArray(pgenCount)
    for (i in 0 until pgenCount) {
      pgen.position(i * 4)
      pgenOper[i] = pgen.short.toInt() and 0xFFFF
      pgenAmount[i] = pgen.short.toInt() // signed 16-bit
    }

    // Parse Instrument Headers (22 bytes each, last is terminal EOI)
    val instCount = inst.remaining() / 22
    val instruments = ArrayList<Sf2InstHeader>(instCount)
    for (i in 0 until instCount) {
      inst.position(i * 22 + 20)
      val bagIdx = inst.short.toInt() and 0xFFFF
      instruments.add(Sf2InstHeader(bagIdx))
    }

    // Parse Instrument Bags (4 bytes each)
    val ibagCount = ibag.remaining() / 4
    val instGenIndices = IntArray(ibagCount) { i ->
      ibag.position(i * 4)
      ibag.short.toInt() and 0xFFFF
    }

    // Parse Instrument Generators (4 bytes each)
    val igenCount = igen.remaining() / 4
    val igenOper = IntArray(igenCount)
    val igenAmount = IntArray(igenCount)
    for (i in 0 until igenCount) {
      igen.position(i * 4)
      igenOper[i] = igen.short.toInt() and 0xFFFF
      igenAmount[i] = igen.short.toInt() // signed 16-bit
    }

    // Parse Sample Headers (46 bytes each, last is terminal EOS)
    val shdrCount = shdr.remaining() / 46
    val samples = ArrayList<Sf2SampleHeader>(shdrCount)
    for (i in 0 until shdrCount) {
      shdr.position(i * 46 + 20)
      val start = shdr.int
      val end = shdr.int
      val startLoop = shdr.int
      val endLoop = shdr.int
      val sampleRate = shdr.int
      val origPitch = shdr.get().toInt() and 0xFF
      val pitchCorr = shdr.get().toInt() // signed int8 cents
      shdr.short // sampleLink
      val sampleType = shdr.short.toInt() and 0xFFFF
      samples.add(
        Sf2SampleHeader(
          start = start,
          end = end,
          startLoop = startLoop,
          endLoop = endLoop,
          sampleRate = sampleRate.coerceIn(4000, 192000),
          originalPitch = if (origPitch in 0..127) origPitch else 60,
          pitchCorrection = pitchCorr,
          sampleType = sampleType,
        )
      )
    }

    val validPresets = (presetCount - 1).coerceAtLeast(0)
    onProgress(0, validPresets.coerceAtLeast(1))

    val allRegions = ArrayList<SampleRegion>(1024)
    val presetKeyMap = HashMap<Int, Array<IntArray>>(validPresets * 2)
    var firstMelodicMap: Array<IntArray>? = null
    var firstDrumMap: Array<IntArray>? = null

    val instBaseGens = IntArray(60)
    val instZoneGens = IntArray(60)
    val presetBaseGens = IntArray(60)
    val presetZoneGens = IntArray(60)

    for (pIdx in 0 until validPresets) {
      val pHeader = presets[pIdx]
      val pBagStart = pHeader.bagIndex
      val pBagEnd = presets[pIdx + 1].bagIndex.coerceAtMost(pbagCount - 1)
      if (pBagStart >= pBagEnd) continue

      resetPresetGens(presetBaseGens)
      val presetRegionIndices = ArrayList<Int>(64)

      for (pb in pBagStart until pBagEnd) {
        val gStart = presetGenIndices[pb]
        val gEnd = if (pb + 1 < pbagCount) presetGenIndices[pb + 1] else pgenCount
        var instId = -1
        System.arraycopy(presetBaseGens, 0, presetZoneGens, 0, 60)

        for (g in gStart until gEnd.coerceAtMost(pgenCount)) {
          val op = pgenOper[g]
          val amt = pgenAmount[g]
          if (op == 41) {
            instId = amt and 0xFFFF
          } else if (op in 0 until 60) {
            presetZoneGens[op] = amt
          }
        }

        if (instId < 0) {
          // Global preset zone if first bag has no instrument generator
          if (pb == pBagStart) {
            System.arraycopy(presetZoneGens, 0, presetBaseGens, 0, 60)
          }
          continue
        }

        if (instId >= instruments.size - 1) continue
        val pLoKey = presetZoneGens[43] and 0xFF
        val pHiKey = (presetZoneGens[43] ushr 8) and 0xFF
        val pLoVel = presetZoneGens[44] and 0xFF
        val pHiVel = (presetZoneGens[44] ushr 8) and 0xFF

        val iBagStart = instruments[instId].bagIndex
        val iBagEnd = instruments[instId + 1].bagIndex.coerceAtMost(ibagCount - 1)
        resetInstGens(instBaseGens)

        for (ib in iBagStart until iBagEnd) {
          val igStart = instGenIndices[ib]
          val igEnd = if (ib + 1 < ibagCount) instGenIndices[ib + 1] else igenCount
          var sampleId = -1
          System.arraycopy(instBaseGens, 0, instZoneGens, 0, 60)

          for (ig in igStart until igEnd.coerceAtMost(igenCount)) {
            val op = igenOper[ig]
            val amt = igenAmount[ig]
            if (op == 53) {
              sampleId = amt and 0xFFFF
            } else if (op in 0 until 60) {
              instZoneGens[op] = amt
            }
          }

          if (sampleId < 0) {
            if (ib == iBagStart) {
              System.arraycopy(instZoneGens, 0, instBaseGens, 0, 60)
            }
            continue
          }

          if (sampleId >= samples.size - 1) continue
          val sHdr = samples[sampleId]
          // Skip ROM samples (bit 15 set in sampleType) or Right-only linked stereo duplicates if invalid
          if ((sHdr.sampleType and 0x8000) != 0) continue

          val iLoKey = instZoneGens[43] and 0xFF
          val iHiKey = (instZoneGens[43] ushr 8) and 0xFF
          val iLoVel = instZoneGens[44] and 0xFF
          val iHiVel = (instZoneGens[44] ushr 8) and 0xFF

          val loKey = maxOf(pLoKey, iLoKey)
          val hiKey = minOf(pHiKey, iHiKey)
          val loVel = maxOf(pLoVel, iLoVel)
          val hiVel = minOf(pHiVel, iHiVel)
          if (loKey > hiKey || loVel > hiVel) continue

          val startFrame = (
            sHdr.start +
              instZoneGens[0] +
              instZoneGens[4] * 32768
            ).coerceIn(0, (totalSampleFrames - 2).coerceAtLeast(0))
          val endFrame = (
            sHdr.end +
              instZoneGens[1] +
              instZoneGens[12] * 32768
            ).coerceIn(startFrame + 1, totalSampleFrames)
          if (endFrame - startFrame < 2) continue

          val loopStart = (
            sHdr.startLoop +
              instZoneGens[2] +
              instZoneGens[45] * 32768
            ).coerceIn(startFrame, endFrame - 1)
          val loopEnd = (
            sHdr.endLoop +
              instZoneGens[3] +
              instZoneGens[50] * 32768
            ).coerceIn(loopStart + 1, endFrame)

          val sampleMode = instZoneGens[54] and 0x03
          val rootKey = if (instZoneGens[58] in 0..127) instZoneGens[58] else sHdr.originalPitch
          val coarseSemi = instZoneGens[51] + presetZoneGens[51]
          val fineCents = instZoneGens[52] + presetZoneGens[52] + sHdr.pitchCorrection
          val tuningCents = coarseSemi * 100f + fineCents.toFloat()
          val scaleTuningCents = (instZoneGens[56] + presetZoneGens[56]).coerceIn(0, 1200)
          val scaleTuning = scaleTuningCents / 100f

          // Attenuation in centibels (0.1 dB); SF2 spec scales 0.4x real dB or standard centibels
          val attenCb = (instZoneGens[48] + presetZoneGens[48]).coerceIn(0, 1440)
          val gainDb = -attenCb * 0.04f
          val gainLinear = 10f.pow(gainDb / 20f).coerceIn(0.001f, 2.0f)

          // Pan in 0.1% (-500 .. +500)
          val rawPan = (instZoneGens[17] + presetZoneGens[17]).coerceIn(-500, 500)
          val pan = rawPan / 500f

          val attackTc = instZoneGens[34] + presetZoneGens[34]
          val decayTc = instZoneGens[36] + presetZoneGens[36]
          val sustainCb = (instZoneGens[37] + presetZoneGens[37]).coerceIn(0, 1440)
          val releaseTc = instZoneGens[38] + presetZoneGens[38]

          val attackSec = timecentsToSec(attackTc).coerceIn(0.001f, 8.0f)
          val decaySec = timecentsToSec(decayTc).coerceIn(0.005f, 12.0f)
          val sustainLevel = 10f.pow(-sustainCb / 200f).coerceIn(0.0f, 1.0f)
          val releaseSec = timecentsToSec(releaseTc).coerceIn(0.005f, 8.0f)

          val regionIndex = allRegions.size
          allRegions.add(
            SampleRegion(
              bufferIndex = 0,
              startFrame = startFrame,
              endFrame = endFrame,
              loopStartFrame = loopStart,
              loopEndFrame = loopEnd,
              loopMode = if (loopEnd - loopStart >= 4) sampleMode else 0,
              sampleRate = sHdr.sampleRate,
              rootKey = rootKey,
              tuningCents = tuningCents,
              scaleTuning = scaleTuning,
              loKey = loKey,
              hiKey = hiKey,
              loVel = loVel,
              hiVel = hiVel,
              gainLinear = gainLinear,
              pan = pan,
              attackSec = attackSec,
              decaySec = decaySec,
              sustainLevel = sustainLevel,
              releaseSec = releaseSec,
            )
          )
          presetRegionIndices.add(regionIndex)
        }
      }

      if (presetRegionIndices.isNotEmpty()) {
        val keyLookup = CompiledSoundBank.buildKeyLookup(allRegions, presetRegionIndices)
        val packed = CompiledSoundBank.packBankProgram(pHeader.bank, pHeader.preset)
        presetKeyMap[packed] = keyLookup
        if (pHeader.bank == 128 && firstDrumMap == null) {
          firstDrumMap = keyLookup
        } else if (pHeader.bank != 128 && firstMelodicMap == null) {
          firstMelodicMap = keyLookup
        }
        if (pHeader.bank == 0 && pHeader.preset == 0) {
          firstMelodicMap = keyLookup
        }
      }

      if ((pIdx and 7) == 7 || pIdx == validPresets - 1) {
        onProgress(pIdx + 1, validPresets.coerceAtLeast(1))
      }
    }

    if (allRegions.isEmpty()) {
      throw IOException("SF2内に再生可能なインストゥルメント・リージョンがありません")
    }

    val defaultMelodic = firstMelodicMap
      ?: presetKeyMap.values.firstOrNull()
      ?: Array(128) { CompiledSoundBank.EMPTY_INT_ARRAY }

    return CompiledSoundBank(
      name = soundFontName,
      type = SoundBankType.SF2,
      sampleBuffers = arrayOf(smplShortBuffer),
      regions = allRegions.toTypedArray(),
      presetKeyMap = presetKeyMap,
      fallbackMelodicKeyMap = defaultMelodic,
      fallbackDrumKeyMap = firstDrumMap,
    )
  }

  private fun resetInstGens(gens: IntArray) {
    gens.fill(0)
    gens[43] = (127 shl 8) or 0 // keyRange 0..127
    gens[44] = (127 shl 8) or 0 // velRange 0..127
    gens[34] = -12000 // attackVolEnv (~1ms)
    gens[36] = -2000  // decayVolEnv
    gens[37] = 0      // sustainVolEnv (0 dB attenuation = full)
    gens[38] = -2400  // releaseVolEnv (~0.25s)
    gens[56] = 100    // scaleTuning 100 cents/semitone
    gens[58] = -1     // overridingRootKey (-1 = use sample header)
  }

  private fun resetPresetGens(gens: IntArray) {
    gens.fill(0)
    gens[43] = (127 shl 8) or 0
    gens[44] = (127 shl 8) or 0
  }

  private fun timecentsToSec(tc: Int): Float {
    if (tc <= -12000) return 0.001f
    return 2.0.pow(tc / 1200.0).toFloat()
  }

  private fun readFully(channel: FileChannel, offset: Long, dst: ByteBuffer) {
    var pos = offset
    while (dst.hasRemaining()) {
      val n = channel.read(dst, pos)
      if (n <= 0) break
      pos += n
    }
  }

  private fun readFourCC(buf: ByteBuffer): String {
    val b = ByteArray(4)
    buf.get(b)
    return String(b, Charsets.US_ASCII)
  }

  private fun decodeNullTermAscii(bytes: ByteArray): String {
    var len = 0
    while (len < bytes.size && bytes[len] != 0.toByte()) {
      len++
    }
    return String(bytes, 0, len, Charsets.UTF_8).trim()
  }
}
