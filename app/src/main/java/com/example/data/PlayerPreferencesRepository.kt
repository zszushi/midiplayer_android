package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.audio.SoundBankType
import com.example.midi.VisualizerMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.obsidianDataStore: DataStore<Preferences> by preferencesDataStore(
  name = "obsidian_midi_preferences"
)

data class RecentMidiBookmark(
  val uriString: String,
  val displayName: String,
  val sizeBytes: Long,
)

data class RecentSoundBankBookmark(
  val uriString: String,
  val displayName: String,
  val type: SoundBankType,
)

data class PersistedSettings(
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
)

class PlayerPreferencesRepository(private val context: Context) {

  private object Keys {
    val MAX_POLYPHONY = intPreferencesKey("max_polyphony")
    val VELOCITY_THRESHOLD = intPreferencesKey("velocity_threshold")
    val MAX_VOICES_PER_KEY = intPreferencesKey("max_voices_per_key")
    val LIMITER_ENABLED = booleanPreferencesKey("limiter_enabled")
    val SINE_RICH_HARMONICS = booleanPreferencesKey("sine_rich_harmonics")
    val MASTER_GAIN = floatPreferencesKey("master_gain")
    val NOTE_ZOOM = floatPreferencesKey("note_zoom")
    val VISUALIZER_MODE = stringPreferencesKey("visualizer_mode")
    val LANDSCAPE_LOCKED = booleanPreferencesKey("landscape_locked")
    val RECENT_MIDIS = stringPreferencesKey("recent_midis_v1")
    val RECENT_SOUNDBANKS = stringPreferencesKey("recent_soundbanks_v1")
  }

  val settingsFlow: Flow<PersistedSettings> = context.obsidianDataStore.data.map { prefs ->
    val modeName = prefs[Keys.VISUALIZER_MODE]
    val mode = runCatching {
      if (modeName != null) VisualizerMode.valueOf(modeName) else VisualizerMode.FALLING_NOTES
    }.getOrDefault(VisualizerMode.FALLING_NOTES)

    PersistedSettings(
      maxPolyphony = (prefs[Keys.MAX_POLYPHONY] ?: 1024).coerceIn(64, 4096),
      velocityThreshold = (prefs[Keys.VELOCITY_THRESHOLD] ?: 4).coerceIn(0, 96),
      maxVoicesPerKey = (prefs[Keys.MAX_VOICES_PER_KEY] ?: 3).coerceIn(1, 16),
      limiterEnabled = prefs[Keys.LIMITER_ENABLED] ?: true,
      sineRichHarmonics = prefs[Keys.SINE_RICH_HARMONICS] ?: true,
      masterGain = (prefs[Keys.MASTER_GAIN] ?: 1.0f).coerceIn(0.1f, 2.5f),
      noteZoom = (prefs[Keys.NOTE_ZOOM] ?: 1.0f).coerceIn(0.25f, 4.0f),
      visualizerMode = mode,
      landscapeLocked = prefs[Keys.LANDSCAPE_LOCKED] ?: false,
      recentMidis = decodeRecentMidis(prefs[Keys.RECENT_MIDIS].orEmpty()),
      recentSoundBanks = decodeRecentSoundBanks(prefs[Keys.RECENT_SOUNDBANKS].orEmpty()),
    )
  }

  suspend fun updateSynthParams(
    maxPolyphony: Int? = null,
    velocityThreshold: Int? = null,
    maxVoicesPerKey: Int? = null,
    limiterEnabled: Boolean? = null,
    sineRichHarmonics: Boolean? = null,
    masterGain: Float? = null,
  ) {
    context.obsidianDataStore.edit { prefs ->
      maxPolyphony?.let { prefs[Keys.MAX_POLYPHONY] = it }
      velocityThreshold?.let { prefs[Keys.VELOCITY_THRESHOLD] = it }
      maxVoicesPerKey?.let { prefs[Keys.MAX_VOICES_PER_KEY] = it }
      limiterEnabled?.let { prefs[Keys.LIMITER_ENABLED] = it }
      sineRichHarmonics?.let { prefs[Keys.SINE_RICH_HARMONICS] = it }
      masterGain?.let { prefs[Keys.MASTER_GAIN] = it }
    }
  }

  suspend fun setNoteZoom(zoom: Float) {
    context.obsidianDataStore.edit { it[Keys.NOTE_ZOOM] = zoom.coerceIn(0.25f, 4.0f) }
  }

  suspend fun setVisualizerMode(mode: VisualizerMode) {
    context.obsidianDataStore.edit { it[Keys.VISUALIZER_MODE] = mode.name }
  }

  suspend fun setLandscapeLocked(locked: Boolean) {
    context.obsidianDataStore.edit { it[Keys.LANDSCAPE_LOCKED] = locked }
  }

  suspend fun addRecentMidi(bookmark: RecentMidiBookmark) {
    context.obsidianDataStore.edit { prefs ->
      val current = decodeRecentMidis(prefs[Keys.RECENT_MIDIS].orEmpty())
        .filterNot { it.uriString == bookmark.uriString }
      val updated = (listOf(bookmark) + current).take(6)
      prefs[Keys.RECENT_MIDIS] = encodeRecentMidis(updated)
    }
  }

  suspend fun addRecentSoundBank(bookmark: RecentSoundBankBookmark) {
    context.obsidianDataStore.edit { prefs ->
      val current = decodeRecentSoundBanks(prefs[Keys.RECENT_SOUNDBANKS].orEmpty())
        .filterNot { it.uriString == bookmark.uriString }
      val updated = (listOf(bookmark) + current).take(6)
      prefs[Keys.RECENT_SOUNDBANKS] = encodeRecentSoundBanks(updated)
    }
  }

  private fun encodeRecentMidis(items: List<RecentMidiBookmark>): String {
    return items.joinToString("\n") { item ->
      val cleanName = item.displayName.replace('\t', ' ').replace('\n', ' ')
      "${item.uriString}\t$cleanName\t${item.sizeBytes}"
    }
  }

  private fun decodeRecentMidis(raw: String): List<RecentMidiBookmark> {
    if (raw.isBlank()) return emptyList()
    return raw.lineSequence().mapNotNull { line ->
      val parts = line.split('\t')
      if (parts.size >= 3) {
        RecentMidiBookmark(
          uriString = parts[0],
          displayName = parts[1],
          sizeBytes = parts[2].toLongOrNull() ?: 0L,
        )
      } else null
    }.toList()
  }

  private fun encodeRecentSoundBanks(items: List<RecentSoundBankBookmark>): String {
    return items.joinToString("\n") { item ->
      val cleanName = item.displayName.replace('\t', ' ').replace('\n', ' ')
      "${item.uriString}\t$cleanName\t${item.type.name}"
    }
  }

  private fun decodeRecentSoundBanks(raw: String): List<RecentSoundBankBookmark> {
    if (raw.isBlank()) return emptyList()
    return raw.lineSequence().mapNotNull { line ->
      val parts = line.split('\t')
      if (parts.size >= 3) {
        val type = runCatching { SoundBankType.valueOf(parts[2]) }.getOrNull() ?: return@mapNotNull null
        RecentSoundBankBookmark(
          uriString = parts[0],
          displayName = parts[1],
          type = type,
        )
      } else null
    }.toList()
  }
}
