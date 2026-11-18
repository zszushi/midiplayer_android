package com.example.audio

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.util.Locale
import kotlin.math.pow

/**
 * SFZ (.sfz) parser & WAV sample loader over Android SAF Document Trees.
 * Supports <control>, <global>, <master>, <group>, <region>, #define macros,
 * default_path, note names (e.g. c4, f#3), and 8/16/24/32-bit PCM & IEEE Float WAVs.
 */
object SfzLoader {

  private class DecodedWav(
    val buffer: ShortBuffer,
    val sampleRate: Int,
    val totalFrames: Int,
    val embeddedLoopStart: Int,
    val embeddedLoopEnd: Int,
  )

  private class RawSfzRegion(
    val opcodes: Map<String, String>,
  )

  fun loadFromDocumentTree(
    contentResolver: ContentResolver,
    treeUri: Uri,
    preferredSfzDocId: String? = null,
    onProgress: (loadedSamples: Int, totalSamples: Int) -> Unit,
  ): CompiledSoundBank {
    val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
    val sfzFiles = ArrayList<Triple<String, String, String>>() // (relPath, displayName, docId)
    val audioByRelPath = HashMap<String, String>() // normalized lower relPath -> docId
    val audioByFileName = HashMap<String, String>() // fallback filename -> docId

    fun scanDirectory(parentDocId: String, prefixPath: String, depth: Int) {
      if (depth > 8) return
      val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
      contentResolver.query(
        childrenUri,
        arrayOf(
          DocumentsContract.Document.COLUMN_DOCUMENT_ID,
          DocumentsContract.Document.COLUMN_DISPLAY_NAME,
          DocumentsContract.Document.COLUMN_MIME_TYPE,
        ),
        null,
        null,
        null,
      )?.use { cursor ->
        while (cursor.moveToNext()) {
          val docId = cursor.getString(0) ?: continue
          val name = cursor.getString(1) ?: continue
          val mime = cursor.getString(2) ?: ""
          val relPath = if (prefixPath.isEmpty()) name else "$prefixPath/$name"
          if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
            scanDirectory(docId, relPath, depth + 1)
          } else {
            val lowerName = name.lowercase(Locale.ROOT)
            val normRel = normalizePath(relPath)
            if (lowerName.endsWith(".sfz")) {
              sfzFiles.add(Triple(normRel, name, docId))
            } else if (lowerName.endsWith(".wav")) {
              audioByRelPath[normRel] = docId
              audioByFileName.putIfAbsent(lowerName, docId)
            }
          }
        }
      }
    }

    scanDirectory(rootDocId, "", 0)
    if (sfzFiles.isEmpty()) {
      throw IOException("選択されたフォルダ内に .sfz ファイルが見つかりません")
    }

    sfzFiles.sortBy { it.second.lowercase(Locale.ROOT) }
    val selectedSfz = sfzFiles.firstOrNull { it.third == preferredSfzDocId } ?: sfzFiles.first()
    val sfzDirPrefix = selectedSfz.first.substringBeforeLast('/', "")
    val sfzUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, selectedSfz.third)

    val sfzText = contentResolver.openInputStream(sfzUri)?.use { input ->
      input.bufferedReader(Charsets.UTF_8).readText()
    } ?: throw IOException(".sfz ファイルを読み込めませんでした")

    return compileSfzText(
      sfzDisplayName = selectedSfz.second.removeSuffix(".sfz").removeSuffix(".SFZ"),
      sfzText = sfzText,
      sfzDirPrefix = sfzDirPrefix,
      availableSfzList = sfzFiles.map { it.second to it.third },
      activeSfzDocId = selectedSfz.third,
      resolveSampleStream = { rawSamplePath ->
        val normSample = normalizePath(rawSamplePath)
        val combined = if (sfzDirPrefix.isEmpty()) normSample else normalizePath("$sfzDirPrefix/$normSample")
        val targetDocId = audioByRelPath[combined]
          ?: audioByRelPath[normSample]
          ?: audioByFileName[normSample.substringAfterLast('/')]
        targetDocId?.let { docId ->
          val wavUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
          contentResolver.openInputStream(wavUri)
        }
      },
      onProgress = onProgress,
    )
  }

  internal fun compileSfzText(
    sfzDisplayName: String,
    sfzText: String,
    sfzDirPrefix: String,
    availableSfzList: List<Pair<String, String>>,
    activeSfzDocId: String?,
    resolveSampleStream: (String) -> java.io.InputStream?,
    onProgress: (loadedSamples: Int, totalSamples: Int) -> Unit,
  ): CompiledSoundBank {
    val parsedRegions = parseSfzRegions(sfzText)
    if (parsedRegions.isEmpty()) {
      throw IOException(".sfz 内に有効な <region> 定義が見つかりません")
    }

    // Collect unique sample paths first for accurate n/m sample loading progress
    val uniqueSamplePaths = LinkedHashSet<String>()
    for (r in parsedRegions) {
      val sampleVal = r.opcodes["sample"]?.trim().orEmpty()
      if (sampleVal.isNotEmpty() && !sampleVal.startsWith("*")) {
        val defaultPath = r.opcodes["default_path"]?.trim().orEmpty()
        uniqueSamplePaths.add(normalizePath(defaultPath + sampleVal))
      }
    }

    val totalSamples = uniqueSamplePaths.size.coerceAtLeast(1)
    onProgress(0, totalSamples)

    val sampleBuffers = ArrayList<ShortBuffer>(uniqueSamplePaths.size)
    val decodedMap = HashMap<String, Pair<Int, DecodedWav>>(uniqueSamplePaths.size * 2)
    var loadedCount = 0

    for (samplePath in uniqueSamplePaths) {
      val stream = resolveSampleStream(samplePath)
      if (stream != null) {
        try {
          stream.use { inp ->
            val wav = decodeWavToMonoShortBuffer(inp)
            if (wav.totalFrames >= 2) {
              val bufIdx = sampleBuffers.size
              sampleBuffers.add(wav.buffer)
              decodedMap[samplePath] = bufIdx to wav
            }
          }
        } catch (_: Exception) {
          // Skip corrupted individual WAV file gracefully
        }
      }
      loadedCount++
      onProgress(loadedCount, totalSamples)
    }

    val compiledRegions = ArrayList<SampleRegion>(parsedRegions.size)
    val regionIndices = ArrayList<Int>(parsedRegions.size)

    for (raw in parsedRegions) {
      val ops = raw.opcodes
      val sampleVal = ops["sample"]?.trim().orEmpty()
      if (sampleVal.isEmpty() || sampleVal.startsWith("*")) continue
      val defaultPath = ops["default_path"]?.trim().orEmpty()
      val fullKey = normalizePath(defaultPath + sampleVal)
      val (bufIdx, wav) = decodedMap[fullKey] ?: continue

      val singleKey = ops["key"]?.let(::parseMidiNote)
      val loKey = singleKey ?: ops["lokey"]?.let(::parseMidiNote) ?: 0
      val hiKey = singleKey ?: ops["hikey"]?.let(::parseMidiNote) ?: 127
      val rootKey = ops["pitch_keycenter"]?.let(::parseMidiNote) ?: singleKey ?: 60

      val loVel = ops["lovel"]?.toIntOrNull()?.coerceIn(0, 127) ?: 0
      val hiVel = ops["hivel"]?.toIntOrNull()?.coerceIn(0, 127) ?: 127
      if (loKey > hiKey || loVel > hiVel) continue

      val offsetFrame = (ops["offset"]?.toIntOrNull() ?: 0).coerceIn(0, wav.totalFrames - 2)
      val endFrame = ((ops["end"]?.toIntOrNull()?.plus(1)) ?: wav.totalFrames)
        .coerceIn(offsetFrame + 1, wav.totalFrames)

      val loopModeStr = ops["loop_mode"]?.lowercase(Locale.ROOT)
      val rawLoopStart = ops["loop_start"]?.toIntOrNull() ?: wav.embeddedLoopStart
      val rawLoopEnd = ops["loop_end"]?.toIntOrNull()?.plus(1) ?: wav.embeddedLoopEnd
      val hasValidLoop = rawLoopStart >= offsetFrame && rawLoopEnd > rawLoopStart + 2 && rawLoopEnd <= endFrame

      val loopMode = when (loopModeStr) {
        "no_loop", "one_shot" -> 0
        "loop_continuous" -> if (hasValidLoop) 1 else 0
        "loop_sustain" -> if (hasValidLoop) 3 else 0
        else -> if (hasValidLoop && wav.embeddedLoopEnd > wav.embeddedLoopStart) 1 else 0
      }

      val transpose = ops["transpose"]?.toFloatOrNull() ?: 0f
      val tune = ops["tune"]?.toFloatOrNull() ?: 0f
      val tuningCents = transpose * 100f + tune
      val pitchKeytrack = (ops["pitch_keytrack"]?.toFloatOrNull() ?: 100f).coerceIn(0f, 1200f) / 100f

      val volumeDb = (ops["volume"]?.toFloatOrNull() ?: 0f).coerceIn(-60f, 24f)
      val gainLinear = 10f.pow(volumeDb / 20f).coerceIn(0.001f, 3.0f)
      val pan = ((ops["pan"]?.toFloatOrNull() ?: 0f) / 100f).coerceIn(-1f, 1f)

      val attackSec = (ops["ampeg_attack"]?.toFloatOrNull() ?: 0.002f).coerceIn(0.001f, 8.0f)
      val decaySec = (ops["ampeg_decay"]?.toFloatOrNull() ?: 0.8f).coerceIn(0.005f, 12.0f)
      val sustainPct = (ops["ampeg_sustain"]?.toFloatOrNull() ?: 100f).coerceIn(0f, 100f)
      val releaseSec = (ops["ampeg_release"]?.toFloatOrNull() ?: 0.25f).coerceIn(0.005f, 8.0f)

      val idx = compiledRegions.size
      compiledRegions.add(
        SampleRegion(
          bufferIndex = bufIdx,
          startFrame = offsetFrame,
          endFrame = endFrame,
          loopStartFrame = if (hasValidLoop) rawLoopStart else offsetFrame,
          loopEndFrame = if (hasValidLoop) rawLoopEnd else endFrame,
          loopMode = loopMode,
          sampleRate = wav.sampleRate,
          rootKey = rootKey.coerceIn(0, 127),
          tuningCents = tuningCents,
          scaleTuning = pitchKeytrack,
          loKey = loKey.coerceIn(0, 127),
          hiKey = hiKey.coerceIn(0, 127),
          loVel = loVel,
          hiVel = hiVel,
          gainLinear = gainLinear,
          pan = pan,
          attackSec = attackSec,
          decaySec = decaySec,
          sustainLevel = sustainPct / 100f,
          releaseSec = releaseSec,
        )
      )
      regionIndices.add(idx)
    }

    if (compiledRegions.isEmpty()) {
      throw IOException("SFZの参照WAVサンプルを読み込めませんでした（フォルダ内にサンプルがあるか確認してください）")
    }

    val keyLookup = CompiledSoundBank.buildKeyLookup(compiledRegions, regionIndices)
    return CompiledSoundBank(
      name = sfzDisplayName,
      type = SoundBankType.SFZ,
      sampleBuffers = sampleBuffers.toTypedArray(),
      regions = compiledRegions.toTypedArray(),
      presetKeyMap = emptyMap(),
      fallbackMelodicKeyMap = keyLookup,
      fallbackDrumKeyMap = keyLookup,
      sfzAvailableFiles = availableSfzList,
      activeSfzName = activeSfzDocId,
    )
  }

  private fun parseSfzRegions(rawText: String): List<RawSfzRegion> {
    // Strip /* ... */ comments and // line comments, process #define macros
    val noBlockComments = rawText.replace(Regex("/\\*[\\s\\S]*?\\*/"), " ")
    val defines = LinkedHashMap<String, String>()
    val cleanBuilder = StringBuilder(noBlockComments.length)

    for (rawLine in noBlockComments.lineSequence()) {
      val line = rawLine.substringBefore("//").trim()
      if (line.isEmpty()) continue
      if (line.startsWith("#define")) {
        val parts = line.removePrefix("#define").trim().split(Regex("\\s+"), limit = 2)
        if (parts.size == 2) {
          defines[parts[0]] = parts[1]
        }
        continue
      }
      var expanded = line
      for ((k, v) in defines) {
        if (expanded.contains(k)) expanded = expanded.replace(k, v)
      }
      cleanBuilder.append(expanded).append('\n')
    }

    val cleanText = cleanBuilder.toString()
    val headerRegex = Regex("<([a-zA-Z0-9_]+)>")
    val opcodeRegex = Regex("([a-zA-Z0-9_]+)=")

    val controlOps = HashMap<String, String>()
    val globalOps = HashMap<String, String>()
    val masterOps = HashMap<String, String>()
    val groupOps = HashMap<String, String>()
    val regions = ArrayList<RawSfzRegion>()

    val headers = headerRegex.findAll(cleanText).toList()
    for (i in headers.indices) {
      val tag = headers[i].groupValues[1].lowercase(Locale.ROOT)
      val bodyStart = headers[i].range.last + 1
      val bodyEnd = if (i + 1 < headers.size) headers[i + 1].range.first else cleanText.length
      val body = cleanText.substring(bodyStart, bodyEnd)

      val sectionOps = HashMap<String, String>()
      val matches = opcodeRegex.findAll(body).toList()
      for (mIdx in matches.indices) {
        val key = matches[mIdx].groupValues[1].lowercase(Locale.ROOT)
        val valStart = matches[mIdx].range.last + 1
        val valEnd = if (mIdx + 1 < matches.size) matches[mIdx + 1].range.first else body.length
        val value = body.substring(valStart, valEnd).trim()
        sectionOps[key] = value
      }

      when (tag) {
        "control" -> controlOps.putAll(sectionOps)
        "global" -> {
          globalOps.clear()
          globalOps.putAll(sectionOps)
          masterOps.clear()
          groupOps.clear()
        }
        "master" -> {
          masterOps.clear()
          masterOps.putAll(sectionOps)
          groupOps.clear()
        }
        "group" -> {
          groupOps.clear()
          groupOps.putAll(sectionOps)
        }
        "region" -> {
          val merged = HashMap<String, String>()
          merged.putAll(controlOps)
          merged.putAll(globalOps)
          merged.putAll(masterOps)
          merged.putAll(groupOps)
          merged.putAll(sectionOps)
          regions.add(RawSfzRegion(merged))
        }
      }
    }
    return regions
  }

  private fun decodeWavToMonoShortBuffer(inputStream: java.io.InputStream): DecodedWav {
    val din = DataInputStream(BufferedInputStream(inputStream, 16384))
    val header = ByteArray(12)
    din.readFully(header)
    val riff = String(header, 0, 4, Charsets.US_ASCII)
    val wave = String(header, 8, 4, Charsets.US_ASCII)
    if (riff != "RIFF" || wave != "WAVE") {
      throw IOException("Unsupported WAV header")
    }

    var audioFormat = 1
    var numChannels = 1
    var sampleRate = 44100
    var bitsPerSample = 16
    var pcmData: ShortArray? = null
    var loopStart = -1
    var loopEnd = -1

    val chunkHead = ByteArray(8)
    while (true) {
      val read = din.read(chunkHead)
      if (read < 8) break
      val chunkId = String(chunkHead, 0, 4, Charsets.US_ASCII)
      val chunkSize = ByteBuffer.wrap(chunkHead, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL

      when (chunkId) {
        "fmt " -> {
          val fmtBytes = ByteArray(chunkSize.toInt())
          din.readFully(fmtBytes)
          val fb = ByteBuffer.wrap(fmtBytes).order(ByteOrder.LITTLE_ENDIAN)
          audioFormat = fb.short.toInt() and 0xFFFF
          numChannels = (fb.short.toInt() and 0xFFFF).coerceIn(1, 8)
          sampleRate = fb.int.coerceIn(4000, 192000)
          fb.int // byteRate
          fb.short // blockAlign
          bitsPerSample = fb.short.toInt() and 0xFFFF
          if (audioFormat == 0xFFFE && fmtBytes.size >= 40) {
            // WAVE_FORMAT_EXTENSIBLE: subFormat first uint16 at byte 24
            fb.position(24)
            audioFormat = fb.short.toInt() and 0xFFFF
          }
          if ((chunkSize and 1L) == 1L) din.skipBytes(1)
        }
        "smpl" -> {
          val smplBytes = ByteArray(chunkSize.toInt())
          din.readFully(smplBytes)
          if (smplBytes.size >= 60) {
            val sb = ByteBuffer.wrap(smplBytes).order(ByteOrder.LITTLE_ENDIAN)
            val numLoops = sb.getInt(28)
            if (numLoops >= 1) {
              loopStart = sb.getInt(36 + 8)
              loopEnd = sb.getInt(36 + 12) + 1
            }
          }
          if ((chunkSize and 1L) == 1L) din.skipBytes(1)
        }
        "data" -> {
          val bytesPerSample = (bitsPerSample / 8).coerceAtLeast(1)
          val frameByteSize = bytesPerSample * numChannels
          val maxBytes = chunkSize.coerceAtMost(48L * 1024L * 1024L).toInt()
          val rawBytes = ByteArray(maxBytes)
          din.readFully(rawBytes)
          val totalFrames = maxBytes / frameByteSize
          val outShorts = ShortArray(totalFrames)
          val bb = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN)

          for (f in 0 until totalFrames) {
            var sum = 0f
            val base = f * frameByteSize
            for (ch in 0 until numChannels) {
              val off = base + ch * bytesPerSample
              val s: Float = when {
                audioFormat == 3 && bitsPerSample == 32 -> bb.getFloat(off)
                bitsPerSample == 16 -> bb.getShort(off) / 32768f
                bitsPerSample == 24 -> {
                  val b0 = rawBytes[off].toInt() and 0xFF
                  val b1 = rawBytes[off + 1].toInt() and 0xFF
                  val b2 = rawBytes[off + 2].toInt() // signed MSB
                  val v = (b2 shl 16) or (b1 shl 8) or b0
                  v / 8388608f
                }
                bitsPerSample == 32 -> bb.getInt(off) / 2147483648f
                bitsPerSample == 8 -> ((rawBytes[off].toInt() and 0xFF) - 128) / 128f
                else -> 0f
              }
              sum += s
            }
            val mono = (sum / numChannels).coerceIn(-1f, 1f)
            outShorts[f] = (mono * 32767f).toInt().toShort()
          }
          pcmData = outShorts
          val remaining = chunkSize - maxBytes
          if (remaining > 0) skipFully(din, remaining)
          if ((chunkSize and 1L) == 1L) din.skipBytes(1)
        }
        else -> {
          skipFully(din, chunkSize + (chunkSize and 1L))
        }
      }
    }

    val finalPcm = pcmData ?: ShortArray(0)
    return DecodedWav(
      buffer = ShortBuffer.wrap(finalPcm),
      sampleRate = sampleRate,
      totalFrames = finalPcm.size,
      embeddedLoopStart = loopStart,
      embeddedLoopEnd = loopEnd,
    )
  }

  private fun skipFully(din: DataInputStream, bytes: Long) {
    var left = bytes
    while (left > 0) {
      val skipped = din.skip(left)
      if (skipped <= 0) {
        if (din.read() < 0) break
        left--
      } else {
        left -= skipped
      }
    }
  }

  internal fun parseMidiNote(raw: String): Int? {
    val trimmed = raw.trim().lowercase(Locale.ROOT)
    trimmed.toIntOrNull()?.let { return it.coerceIn(0, 127) }
    val match = Regex("^([a-g])([#b]?)(-?\\d+)$").matchEntire(trimmed) ?: return null
    val base = when (match.groupValues[1]) {
      "c" -> 0
      "d" -> 2
      "e" -> 4
      "f" -> 5
      "g" -> 7
      "a" -> 9
      "b" -> 11
      else -> return null
    }
    val acc = when (match.groupValues[2]) {
      "#" -> 1
      "b" -> -1
      else -> 0
    }
    val octave = match.groupValues[3].toIntOrNull() ?: return null
    return ((octave + 1) * 12 + base + acc).coerceIn(0, 127)
  }

  private fun normalizePath(path: String): String {
    return path
      .replace('\\', '/')
      .trim()
      .trimStart('/')
      .replace(Regex("/+"), "/")
      .lowercase(Locale.ROOT)
  }
}
