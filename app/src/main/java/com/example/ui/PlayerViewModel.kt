package com.example.ui

import android.app.Application
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.CompiledSoundBank
import com.example.audio.Sf2Loader
import com.example.audio.SfzLoader
import com.example.audio.SoundBankType
import com.example.audio.ZeroGcSynthEngine
import com.example.data.PlayerPreferencesRepository
import com.example.data.RecentMidiBookmark
import com.example.data.RecentSoundBankBookmark
import com.example.midi.MidiFastScanner
import com.example.midi.MidiScanProgress
import com.example.midi.ScanPhase
import com.example.midi.StreamingMidiSequencer
import com.example.midi.VisualNoteRingBuffer
import com.example.midi.VisualizerMode
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SoundBankProgressState(
  val isLoading: Boolean = false,
  val loadingLabel: String = "",
  val loadedCount: Int = 0,
  val totalCount: Int = 0,
  val errorMessage: String? = null,
) {
  val percent: Int
    get() = if (totalCount > 0) ((loadedCount * 100) / totalCount).coerceIn(0, 100) else 0
}

data class PlayerUiState(
  val hasMidiLoaded: Boolean = false,
  val midiTitle: String = "",
  val midiFileSizeBytes: Long = 0L,
  val trackCount: Int = 0,
  val totalNotes: Long = 0L,
  val isPlaying: Boolean = false,
  val currentPositionMicros: Long = 0L,
  val durationMicros: Long = 0L,
  val playbackSpeed: Float = 1.0f,
  val scanProgress: MidiScanProgress = MidiScanProgress(),
  val soundBankName: String = "内蔵サイン波 (Pure Sine LUT)",
  val soundBankType: SoundBankType = SoundBankType.SINE,
  val soundBankRegionCount: Int = 0,
  val sfzSubFiles: List<Pair<String, String>> = emptyList(),
  val activeSfzDocId: String? = null,
  val activeSfzTreeUri: String? = null,
  val soundBankProgress: SoundBankProgressState = SoundBankProgressState(),
  val liveNps: Int = 0,
  val activeVoices: Int = 0,
  val audioCpuPercent: Int = 0,
  val peakLeft: Float = 0f,
  val peakRight: Float = 0f,
  val limiterTriggered: Boolean = false,
  val cpuGuardActive: Boolean = false,
  val maxPolyphony: Int = 1024,
  val velocityThreshold: Int = 4,
  val maxVoicesPerKey: Int = 3,
  val limiterEnabled: Boolean = true,
  val sineRichHarmonics: Boolean = true,
  val masterGain: Float = 1.0f,
  val noteZoom: Float = 1.0f,
  val visualizerMode: VisualizerMode = VisualizerMode.FALLING_NOTES,
  val landscapeLocked: Boolean = false,
  val recentMidis: List<RecentMidiBookmark> = emptyList(),
  val recentSoundBanks: List<RecentSoundBankBookmark> = emptyList(),
  val statusBannerMessage: String? = null,
)

class PlayerViewModel(application: Application) : AndroidViewModel(application) {

  private val prefsRepo = PlayerPreferencesRepository(application.applicationContext)
  val synthEngine = ZeroGcSynthEngine()
  val sequencer = StreamingMidiSequencer(synthEngine)

  val visualNoteBuffer: VisualNoteRingBuffer
    get() = sequencer.visualNotes

  val activeKeyboardState: IntArray
    get() = sequencer.activeKeyboardState

  private val _uiState = MutableStateFlow(PlayerUiState())
  val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

  private var activeMidiResources: MutableList<Closeable> = mutableListOf()
  private var activeSf2Resources: MutableList<Closeable> = mutableListOf()
  private var midiLoadAndScanJob: Job? = null
  private var soundBankLoadJob: Job? = null
  private var noteZoomSaveJob: Job? = null

  init {
    synthEngine.startAudioOutput()

    // Restore persisted user settings & recent bookmarks
    viewModelScope.launch {
      prefsRepo.settingsFlow.collect { saved ->
        synthEngine.maxPolyphony = saved.maxPolyphony
        synthEngine.velocityThreshold = saved.velocityThreshold
        synthEngine.maxVoicesPerKey = saved.maxVoicesPerKey
        synthEngine.limiterEnabled = saved.limiterEnabled
        synthEngine.sineRichHarmonics = saved.sineRichHarmonics
        synthEngine.masterGain = saved.masterGain
        sequencer.noteZoom = saved.noteZoom
        sequencer.visualizerMode = saved.visualizerMode

        _uiState.update { st ->
          st.copy(
            maxPolyphony = saved.maxPolyphony,
            velocityThreshold = saved.velocityThreshold,
            maxVoicesPerKey = saved.maxVoicesPerKey,
            limiterEnabled = saved.limiterEnabled,
            sineRichHarmonics = saved.sineRichHarmonics,
            masterGain = saved.masterGain,
            noteZoom = saved.noteZoom,
            visualizerMode = saved.visualizerMode,
            landscapeLocked = saved.landscapeLocked,
            recentMidis = saved.recentMidis,
            recentSoundBanks = saved.recentSoundBanks,
          )
        }
      }
    }

    // Lightweight 20Hz live telemetry collector
    viewModelScope.launch {
      while (isActive) {
        _uiState.update { st ->
          st.copy(
            isPlaying = sequencer.isPlaying,
            currentPositionMicros = sequencer.currentPositionMicros,
            durationMicros = maxOf(st.durationMicros, sequencer.durationMicros),
            liveNps = sequencer.liveNps,
            activeVoices = synthEngine.activeVoiceCountTelemetry,
            audioCpuPercent = synthEngine.audioCpuLoadPercent,
            peakLeft = synthEngine.peakLevelLeft,
            peakRight = synthEngine.peakLevelRight,
            limiterTriggered = synthEngine.limiterActiveTelemetry,
            cpuGuardActive = synthEngine.cpuGuardActiveTelemetry,
          )
        }
        delay(50L)
      }
    }
  }

  fun openMidiUri(uri: Uri) {
    val app = getApplication<Application>()
    val resolver = app.contentResolver
    tryPersistReadPermission(resolver, uri)

    midiLoadAndScanJob?.cancel()
    midiLoadAndScanJob = viewModelScope.launch {
      val (displayName, reportedSize) = queryDisplayNameAndSize(resolver, uri, "Track.mid")
      _uiState.update {
        it.copy(
          statusBannerMessage = null,
          scanProgress = MidiScanProgress(
            phase = ScanPhase.PREPARING_STREAM,
            fileName = displayName,
            totalBytes = reportedSize,
          ),
        )
      }

      try {
        val opened = withContext(Dispatchers.IO) {
          openSeekableChannel(
            resolver = resolver,
            uri = uri,
            stagingFileName = "active_stream.mid",
            expectedBytes = reportedSize,
            onStagingProgress = { copiedBytes ->
              _uiState.update { st ->
                st.copy(
                  scanProgress = st.scanProgress.copy(
                    phase = ScanPhase.PREPARING_STREAM,
                    scannedBytes = copiedBytes,
                    totalBytes = maxOf(reportedSize, copiedBytes),
                  )
                )
              }
            },
          )
        }

        replaceMidiResources(opened.closeables)

        _uiState.update { st ->
          st.copy(
            scanProgress = st.scanProgress.copy(
              phase = ScanPhase.SCANNING_HEADERS,
              fileName = displayName,
            )
          )
        }

        // Stage 1: Instant MTrk header boundary jump (< 5ms even on 1,000+ tracks!)
        val header = withContext(Dispatchers.IO) {
          MidiFastScanner.scanHeaderAndTrackBoundaries(opened.channel, displayName)
        }

        sequencer.loadMidiSource(opened.channel, header)
        prefsRepo.addRecentMidi(RecentMidiBookmark(uri.toString(), displayName, header.fileSizeBytes))

        _uiState.update { st ->
          st.copy(
            hasMidiLoaded = true,
            midiTitle = displayName.removeSuffix(".mid").removeSuffix(".midi").removeSuffix(".MID"),
            midiFileSizeBytes = header.fileSizeBytes,
            trackCount = header.trackCount,
            totalNotes = 0L,
            currentPositionMicros = 0L,
            durationMicros = 0L,
            scanProgress = MidiScanProgress(
              phase = ScanPhase.SCANNING_TRACKS,
              fileName = displayName,
              analyzedTracks = 0,
              totalTracks = header.trackCount,
              scannedBytes = 0L,
              totalBytes = header.totalTrackPayloadBytes,
            ),
          )
        }

        // Stage 2: Multi-core parallel track analysis with real-time n/m progress & index caching
        val fullIndex = withContext(Dispatchers.Default) {
          MidiFastScanner.scanTracksInParallel(
            channel = opened.channel,
            header = header,
            cacheDir = app.cacheDir,
            onProgress = { prog ->
              _uiState.update { st -> st.copy(scanProgress = prog, totalNotes = prog.detectedNotes) }
            },
          )
        }

        sequencer.attachFullIndex(fullIndex)
        _uiState.update { st ->
          st.copy(
            totalNotes = fullIndex.totalNotes,
            durationMicros = fullIndex.durationMicros,
            scanProgress = st.scanProgress.copy(
              phase = ScanPhase.COMPLETED,
              analyzedTracks = header.trackCount,
              totalTracks = header.trackCount,
              detectedNotes = fullIndex.totalNotes,
              isFromCache = fullIndex.isFromCache,
            ),
          )
        }
      } catch (ce: CancellationException) {
        _uiState.update { st ->
          st.copy(scanProgress = st.scanProgress.copy(phase = ScanPhase.CANCELLED))
        }
        throw ce
      } catch (e: Throwable) {
        val msg = e.localizedMessage ?: "MIDIファイルの読込に失敗しました"
        _uiState.update { st ->
          st.copy(
            statusBannerMessage = msg,
            scanProgress = st.scanProgress.copy(
              phase = ScanPhase.ERROR,
              errorMessage = msg,
            ),
          )
        }
      }
    }
  }

  fun cancelBackgroundScan() {
    midiLoadAndScanJob?.cancel()
    midiLoadAndScanJob = null
    _uiState.update { st ->
      st.copy(
        scanProgress = st.scanProgress.copy(phase = ScanPhase.CANCELLED),
      )
    }
  }

  fun selectBuiltInSineBank() {
    soundBankLoadJob?.cancel()
    val sine = CompiledSoundBank.createBuiltInSine()
    synthEngine.setSoundBank(sine)
    closeSf2Resources()
    _uiState.update { st ->
      st.copy(
        soundBankName = sine.name,
        soundBankType = SoundBankType.SINE,
        soundBankRegionCount = 0,
        sfzSubFiles = emptyList(),
        activeSfzDocId = null,
        activeSfzTreeUri = null,
        soundBankProgress = SoundBankProgressState(),
      )
    }
  }

  fun openSf2Uri(uri: Uri) {
    val app = getApplication<Application>()
    val resolver = app.contentResolver
    tryPersistReadPermission(resolver, uri)

    soundBankLoadJob?.cancel()
    soundBankLoadJob = viewModelScope.launch {
      val (displayName, expectedSize) = queryDisplayNameAndSize(resolver, uri, "SoundFont.sf2")
      _uiState.update { st ->
        st.copy(
          statusBannerMessage = null,
          soundBankProgress = SoundBankProgressState(
            isLoading = true,
            loadingLabel = displayName,
            loadedCount = 0,
            totalCount = 100,
          ),
        )
      }

      try {
        val opened = withContext(Dispatchers.IO) {
          openSeekableChannel(
            resolver = resolver,
            uri = uri,
            stagingFileName = "active_soundfont.sf2",
            expectedBytes = expectedSize,
            onStagingProgress = {},
          )
        }

        val compiled = withContext(Dispatchers.Default) {
          Sf2Loader.load(
            channel = opened.channel,
            fallbackName = displayName.removeSuffix(".sf2").removeSuffix(".SF2"),
            onProgress = { done, total ->
              _uiState.update { st ->
                st.copy(
                  soundBankProgress = SoundBankProgressState(
                    isLoading = true,
                    loadingLabel = displayName,
                    loadedCount = done,
                    totalCount = total,
                  )
                )
              }
            },
          )
        }

        replaceSf2Resources(opened.closeables)
        synthEngine.setSoundBank(compiled)
        prefsRepo.addRecentSoundBank(
          RecentSoundBankBookmark(uri.toString(), compiled.name, SoundBankType.SF2)
        )

        _uiState.update { st ->
          st.copy(
            soundBankName = compiled.name,
            soundBankType = SoundBankType.SF2,
            soundBankRegionCount = compiled.regionCount,
            sfzSubFiles = emptyList(),
            activeSfzDocId = null,
            activeSfzTreeUri = null,
            soundBankProgress = SoundBankProgressState(isLoading = false),
          )
        }
      } catch (ce: CancellationException) {
        _uiState.update { it.copy(soundBankProgress = SoundBankProgressState(isLoading = false)) }
        throw ce
      } catch (e: Throwable) {
        val err = e.localizedMessage ?: "SF2音源の読込に失敗しました"
        _uiState.update { st ->
          st.copy(
            statusBannerMessage = err,
            soundBankProgress = SoundBankProgressState(isLoading = false, errorMessage = err),
          )
        }
      }
    }
  }

  fun openSfzFolderUri(treeUri: Uri, preferredSfzDocId: String? = null) {
    val app = getApplication<Application>()
    val resolver = app.contentResolver
    tryPersistTreePermission(resolver, treeUri)

    soundBankLoadJob?.cancel()
    soundBankLoadJob = viewModelScope.launch {
      val folderLabel = queryTreeDisplayName(resolver, treeUri) ?: "SFZ Instrument"
      _uiState.update { st ->
        st.copy(
          statusBannerMessage = null,
          soundBankProgress = SoundBankProgressState(
            isLoading = true,
            loadingLabel = folderLabel,
            loadedCount = 0,
            totalCount = 1,
          ),
        )
      }

      try {
        val compiled = withContext(Dispatchers.IO) {
          SfzLoader.loadFromDocumentTree(
            contentResolver = resolver,
            treeUri = treeUri,
            preferredSfzDocId = preferredSfzDocId,
            onProgress = { loaded, total ->
              _uiState.update { st ->
                st.copy(
                  soundBankProgress = SoundBankProgressState(
                    isLoading = true,
                    loadingLabel = folderLabel,
                    loadedCount = loaded,
                    totalCount = total,
                  )
                )
              }
            },
          )
        }

        closeSf2Resources()
        synthEngine.setSoundBank(compiled)
        prefsRepo.addRecentSoundBank(
          RecentSoundBankBookmark(treeUri.toString(), compiled.name, SoundBankType.SFZ)
        )

        _uiState.update { st ->
          st.copy(
            soundBankName = compiled.name,
            soundBankType = SoundBankType.SFZ,
            soundBankRegionCount = compiled.regionCount,
            sfzSubFiles = compiled.sfzAvailableFiles,
            activeSfzDocId = compiled.activeSfzName,
            activeSfzTreeUri = treeUri.toString(),
            soundBankProgress = SoundBankProgressState(isLoading = false),
          )
        }
      } catch (ce: CancellationException) {
        _uiState.update { it.copy(soundBankProgress = SoundBankProgressState(isLoading = false)) }
        throw ce
      } catch (e: Throwable) {
        val err = e.localizedMessage ?: "SFZフォルダの読込に失敗しました"
        _uiState.update { st ->
          st.copy(
            statusBannerMessage = err,
            soundBankProgress = SoundBankProgressState(isLoading = false, errorMessage = err),
          )
        }
      }
    }
  }

  fun togglePlayPause() {
    if (!_uiState.value.hasMidiLoaded) return
    if (sequencer.isPlaying) {
      sequencer.pause()
    } else {
      if (sequencer.durationMicros > 0L &&
        sequencer.currentPositionMicros >= sequencer.durationMicros
      ) {
        sequencer.seekToMicros(0L)
      }
      sequencer.play()
    }
  }

  fun playImmediatelyWhileScanning() {
    if (!_uiState.value.hasMidiLoaded) return
    sequencer.play()
  }

  fun stopPlayback() {
    sequencer.stopAndRewind()
  }

  fun seekToFraction(fraction: Float) {
    val dur = _uiState.value.durationMicros
    if (dur <= 0L) return
    val targetUs = (dur * fraction.coerceIn(0f, 1f).toDouble()).toLong()
    sequencer.seekToMicros(targetUs)
  }

  fun setPlaybackSpeed(speed: Float) {
    val clamped = speed.coerceIn(0.25f, 2.0f)
    sequencer.playbackSpeed = clamped
    _uiState.update { it.copy(playbackSpeed = clamped) }
  }

  fun setNoteZoom(zoom: Float) {
    val clamped = zoom.coerceIn(
      StreamingMidiSequencer.MIN_NOTE_ZOOM,
      StreamingMidiSequencer.MAX_NOTE_ZOOM,
    )
    sequencer.noteZoom = clamped
    _uiState.update { it.copy(noteZoom = clamped) }
    noteZoomSaveJob?.cancel()
    noteZoomSaveJob = viewModelScope.launch {
      delay(180L)
      prefsRepo.setNoteZoom(clamped)
    }
  }

  fun toggleVisualizerMode() {
    val next = if (_uiState.value.visualizerMode == VisualizerMode.FALLING_NOTES) {
      VisualizerMode.ULTRA_LIGHT
    } else {
      VisualizerMode.FALLING_NOTES
    }
    sequencer.visualizerMode = next
    _uiState.update { it.copy(visualizerMode = next) }
    viewModelScope.launch { prefsRepo.setVisualizerMode(next) }
  }

  fun toggleLandscapeLock() {
    val next = !_uiState.value.landscapeLocked
    _uiState.update { it.copy(landscapeLocked = next) }
    viewModelScope.launch { prefsRepo.setLandscapeLocked(next) }
  }

  fun setMaxPolyphony(polyphony: Int) {
    val v = polyphony.coerceIn(64, ZeroGcSynthEngine.MAX_VOICE_CAPACITY)
    synthEngine.maxPolyphony = v
    _uiState.update { it.copy(maxPolyphony = v) }
    viewModelScope.launch { prefsRepo.updateSynthParams(maxPolyphony = v) }
  }

  fun setVelocityThreshold(threshold: Int) {
    val v = threshold.coerceIn(0, 96)
    synthEngine.velocityThreshold = v
    _uiState.update { it.copy(velocityThreshold = v) }
    viewModelScope.launch { prefsRepo.updateSynthParams(velocityThreshold = v) }
  }

  fun setMaxVoicesPerKey(limit: Int) {
    val v = limit.coerceIn(1, 16)
    synthEngine.maxVoicesPerKey = v
    _uiState.update { it.copy(maxVoicesPerKey = v) }
    viewModelScope.launch { prefsRepo.updateSynthParams(maxVoicesPerKey = v) }
  }

  fun setLimiterEnabled(enabled: Boolean) {
    synthEngine.limiterEnabled = enabled
    _uiState.update { it.copy(limiterEnabled = enabled) }
    viewModelScope.launch { prefsRepo.updateSynthParams(limiterEnabled = enabled) }
  }

  fun setSineRichHarmonics(enabled: Boolean) {
    synthEngine.sineRichHarmonics = enabled
    _uiState.update { it.copy(sineRichHarmonics = enabled) }
    viewModelScope.launch { prefsRepo.updateSynthParams(sineRichHarmonics = enabled) }
  }

  fun setMasterGain(gain: Float) {
    val v = gain.coerceIn(0.1f, 2.5f)
    synthEngine.masterGain = v
    _uiState.update { it.copy(masterGain = v) }
    viewModelScope.launch { prefsRepo.updateSynthParams(masterGain = v) }
  }

  fun dismissStatusBanner() {
    _uiState.update { it.copy(statusBannerMessage = null) }
  }

  override fun onCleared() {
    super.onCleared()
    midiLoadAndScanJob?.cancel()
    soundBankLoadJob?.cancel()
    sequencer.release()
    synthEngine.stopAudioOutput()
    replaceMidiResources(emptyList())
    closeSf2Resources()
  }

  private class SeekableChannelHandle(
    val channel: FileChannel,
    val closeables: List<Closeable>,
  )

  private fun openSeekableChannel(
    resolver: ContentResolver,
    uri: Uri,
    stagingFileName: String,
    expectedBytes: Long,
    onStagingProgress: (Long) -> Unit,
  ): SeekableChannelHandle {
    val pfd: ParcelFileDescriptor? = runCatching { resolver.openFileDescriptor(uri, "r") }.getOrNull()
    if (pfd != null) {
      val statSize = pfd.statSize
      if (statSize > 0L) {
        val fis = FileInputStream(pfd.fileDescriptor)
        val ch = fis.channel
        val chSize = runCatching { ch.size() }.getOrDefault(-1L)
        if (chSize > 0L) {
          return SeekableChannelHandle(ch, listOf(ch, fis, pfd))
        }
        runCatching { fis.close() }
      }
      runCatching { pfd.close() }
    }

    // Fallback for cloud/pipe SAF providers that do not expose a seekable file descriptor
    val stagingFile = File(getApplication<Application>().cacheDir, stagingFileName)
    resolver.openInputStream(uri)?.use { inp ->
      FileOutputStream(stagingFile).use { out ->
        val buf = ByteArray(131072)
        var totalCopied = 0L
        while (true) {
          val n = inp.read(buf)
          if (n <= 0) break
          out.write(buf, 0, n)
          totalCopied += n
          onStagingProgress(totalCopied)
        }
      }
    } ?: throw IllegalStateException("選択されたファイルのストリームを開けませんでした")

    val raf = RandomAccessFile(stagingFile, "r")
    return SeekableChannelHandle(raf.channel, listOf(raf.channel, raf))
  }

  private fun replaceMidiResources(newList: List<Closeable>) {
    val old = activeMidiResources
    activeMidiResources = newList.toMutableList()
    old.forEach { runCatching { it.close() } }
  }

  private fun replaceSf2Resources(newList: List<Closeable>) {
    val old = activeSf2Resources
    activeSf2Resources = newList.toMutableList()
    old.forEach { runCatching { it.close() } }
  }

  private fun closeSf2Resources() {
    replaceSf2Resources(emptyList())
  }

  private fun queryDisplayNameAndSize(
    resolver: ContentResolver,
    uri: Uri,
    fallback: String,
  ): Pair<String, Long> {
    var name = fallback
    var size = 0L
    runCatching {
      resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
        ?.use { c ->
          if (c.moveToFirst()) {
            val nIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sIdx = c.getColumnIndex(OpenableColumns.SIZE)
            if (nIdx >= 0 && !c.isNull(nIdx)) name = c.getString(nIdx)
            if (sIdx >= 0 && !c.isNull(sIdx)) size = c.getLong(sIdx)
          }
        }
    }
    if (name == fallback) {
      uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }?.let { name = it }
    }
    return name to size
  }

  private fun queryTreeDisplayName(resolver: ContentResolver, treeUri: Uri): String? {
    return runCatching {
      val docId = DocumentsContract.getTreeDocumentId(treeUri)
      val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
      resolver.query(docUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: treeUri.lastPathSegment?.substringAfterLast(':')
  }

  private fun tryPersistReadPermission(resolver: ContentResolver, uri: Uri) {
    runCatching {
      resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
  }

  private fun tryPersistTreePermission(resolver: ContentResolver, treeUri: Uri) {
    runCatching {
      resolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
  }
}
