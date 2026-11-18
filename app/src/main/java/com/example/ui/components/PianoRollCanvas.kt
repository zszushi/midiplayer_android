package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.midi.StreamingMidiSequencer
import com.example.midi.VisualNoteRingBuffer
import com.example.ui.PlayerUiState
import com.example.ui.theme.ClipCrimson
import com.example.ui.theme.DeepViolet
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.MidiChannelColors
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.ObsidianOutline
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceContainer
import com.example.ui.theme.TelemetryAmber
import com.example.ui.theme.TelemetryEmerald
import kotlin.math.max
import kotlin.math.min

private const val FIRST_PIANO_KEY = 21 // A0
private const val LAST_PIANO_KEY = 108 // C8
private const val WHITE_KEY_COUNT = 52
private const val VISIBLE_WINDOW_US = 1_400_000L // 1.4s waterfall height

// Precomputed 88-key horizontal layout tables (normalized 0..52 white-key units)
private val KEY_IS_BLACK = BooleanArray(128) { k ->
  when (k % 12) {
    1, 3, 6, 8, 10 -> true
    else -> false
  }
}

private val KEY_NORM_X = FloatArray(128)
private val KEY_NORM_W = FloatArray(128)

private val initPianoGeometry = run {
  var whiteIdx = 0
  for (k in FIRST_PIANO_KEY..LAST_PIANO_KEY) {
    if (!KEY_IS_BLACK[k]) {
      KEY_NORM_X[k] = whiteIdx.toFloat() / WHITE_KEY_COUNT
      KEY_NORM_W[k] = 1.0f / WHITE_KEY_COUNT
      whiteIdx++
    }
  }
  for (k in FIRST_PIANO_KEY..LAST_PIANO_KEY) {
    if (KEY_IS_BLACK[k]) {
      // Center black key between adjacent white keys
      val prevWhiteX = KEY_NORM_X[(k - 1).coerceAtLeast(FIRST_PIANO_KEY)]
      val blackW = 0.62f / WHITE_KEY_COUNT
      KEY_NORM_X[k] = prevWhiteX + (1.0f / WHITE_KEY_COUNT) - (blackW * 0.5f)
      KEY_NORM_W[k] = blackW
    }
  }
  true
}

/**
 * High-FPS Zero-Allocation Falling Notes + 88-Key Piano Canvas.
 * Uses DrawScope dimensions dynamically so Portrait <-> Landscape transitions never flicker.
 */
@Composable
fun FallingNotesPianoCanvas(
  sequencer: StreamingMidiSequencer,
  visualNotes: VisualNoteRingBuffer,
  activeKeyboardState: IntArray,
  isPlaying: Boolean,
  modifier: Modifier = Modifier,
) {
  var frameTick by remember { mutableLongStateOf(0L) }

  LaunchedEffect(isPlaying) {
    if (isPlaying) {
      while (true) {
        withFrameNanos { nanos ->
          frameTick = nanos
        }
      }
    }
  }

  // Scratch occlusion bitmap per MIDI key x 64 vertical buckets (8192 booleans = 8KB, allocated once!)
  // Prevents GPU overdraw when 30,000+ Black MIDI notes overlap on the exact same key & pixel row.
  val keyRowOccupancy = remember { BooleanArray(128 * 64) }

  Canvas(
    modifier = modifier
      .fillMaxSize()
      .background(ObsidianBg)
      .testTag("falling_notes_canvas")
  ) {
    // Read frameTick so Compose redraws smoothly on every VSYNC while playing
    val unusedTick = frameTick
    if (unusedTick == Long.MIN_VALUE) return@Canvas

    val w = size.width
    val h = size.height
    if (w <= 8f || h <= 8f) return@Canvas

    val keyboardHeight = (h * 0.17f).coerceIn(44.dp.toPx(), 96.dp.toPx())
    val rollHeight = (h - keyboardHeight).coerceAtLeast(1f)

    // 1. Subtle studio vertical octave grid lines (every C key: C1..C8)
    var cKey = 24 // C1
    while (cKey <= LAST_PIANO_KEY) {
      val gx = KEY_NORM_X[cKey] * w
      drawLine(
        color = ObsidianOutline.copy(alpha = 0.35f),
        start = Offset(gx, 0f),
        end = Offset(gx, rollHeight),
        strokeWidth = 1f,
      )
      cKey += 12
    }

    // 2. Render Falling Note Bars from lock-free VisualNoteRingBuffer
    val nowUs = sequencer.currentPositionMicros
    val horizonUs = nowUs + VISIBLE_WINDOW_US
    val totalWritten = visualNotes.writeCount
    val cap = visualNotes.capacity
    val mask = cap - 1
    val startSeq = (totalWritten - cap).coerceAtLeast(0L)

    keyRowOccupancy.fill(false)

    // Traverse newest to oldest so foreground notes claim occlusion buckets first
    var seq = totalWritten - 1L
    while (seq >= startSeq) {
      val slot = seq.toInt() and mask
      val sUs = visualNotes.startMicros[slot]
      val eUs = visualNotes.endMicros[slot]

      if (eUs >= nowUs && sUs <= horizonUs) {
        val meta = visualNotes.packedMeta[slot]
        val rawKey = meta and 0x7F
        val key = rawKey.coerceIn(FIRST_PIANO_KEY, LAST_PIANO_KEY)
        val ch = (meta ushr 8) and 0x0F

        val clampedStart = max(sUs, nowUs)
        val clampedEnd = min(eUs, horizonUs)

        // Bottom of rollHeight corresponds to nowUs; top (0f) corresponds to horizonUs
        val yBottom = rollHeight * (1f - ((clampedStart - nowUs).toFloat() / VISIBLE_WINDOW_US))
        val yTop = rollHeight * (1f - ((clampedEnd - nowUs).toFloat() / VISIBLE_WINDOW_US))
        val barH = max(2.5f, yBottom - yTop)

        // Sub-cell occlusion check for sliver notes
        val bucket = ((yTop / rollHeight) * 63f).toInt().coerceIn(0, 63)
        val occIdx = (key shl 6) or bucket
        val isShortNote = barH < (rollHeight / 48f)
        if (!isShortNote || !keyRowOccupancy[occIdx]) {
          if (isShortNote) keyRowOccupancy[occIdx] = true

          val barX = KEY_NORM_X[key] * w + 0.5f
          val barW = max(2.0f, KEY_NORM_W[key] * w - 1.0f)
          val color = MidiChannelColors[ch]

          drawRoundRect(
            color = color,
            topLeft = Offset(barX, yTop),
            size = Size(barW, barH),
            cornerRadius = CornerRadius(2.5f, 2.5f),
          )
        }
      }
      seq--
    }

    // Glowing strike-line separator above the 88-key keyboard
    drawRect(
      brush = Brush.verticalGradient(
        colors = listOf(Color.Transparent, ElectricCyan.copy(alpha = 0.35f)),
        startY = (rollHeight - 10f).coerceAtLeast(0f),
        endY = rollHeight,
      ),
      topLeft = Offset(0f, (rollHeight - 10f).coerceAtLeast(0f)),
      size = Size(w, min(10f, rollHeight)),
    )

    // 3. Render 88-Key Grand Piano Keyboard (Pass 1: 52 White Keys, Pass 2: 36 Black Keys)
    val keyTop = rollHeight
    for (k in FIRST_PIANO_KEY..LAST_PIANO_KEY) {
      if (!KEY_IS_BLACK[k]) {
        val kx = KEY_NORM_X[k] * w
        val kw = KEY_NORM_W[k] * w
        val activeState = activeKeyboardState[k]
        val fill = if (activeState != 0) {
          val ch = (activeState ushr 8) and 0x0F
          MidiChannelColors[ch]
        } else {
          Color(0xFFE6ECF8)
        }
        drawRect(
          color = fill,
          topLeft = Offset(kx + 0.5f, keyTop),
          size = Size((kw - 1.0f).coerceAtLeast(1f), keyboardHeight),
        )
      }
    }

    val blackKeyHeight = keyboardHeight * 0.63f
    for (k in FIRST_PIANO_KEY..LAST_PIANO_KEY) {
      if (KEY_IS_BLACK[k]) {
        val kx = KEY_NORM_X[k] * w
        val kw = KEY_NORM_W[k] * w
        val activeState = activeKeyboardState[k]
        val fill = if (activeState != 0) {
          val ch = (activeState ushr 8) and 0x0F
          MidiChannelColors[ch]
        } else {
          Color(0xFF141824)
        }
        drawRoundRect(
          color = fill,
          topLeft = Offset(kx, keyTop),
          size = Size(kw.coerceAtLeast(1.5f), blackKeyHeight),
          cornerRadius = CornerRadius(2f, 2f),
        )
      }
    }
  }
}

/**
 * Ultra-Light Telemetry Monitor Mode:
 * Zero Canvas note loops, zero frame-by-frame waterfall rendering — dedicates ~100% of CPU
 * to streaming MIDI & audio synthesis while presenting crisp precision studio readouts.
 */
@Composable
fun UltraLightTelemetryMonitor(
  uiState: PlayerUiState,
  isLandscape: Boolean,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier = modifier
      .fillMaxSize()
      .background(
        Brush.radialGradient(
          colors = listOf(
            ObsidianSurfaceContainer,
            ObsidianBg,
          )
        )
      )
      .padding(horizontal = 20.dp, vertical = if (isLandscape) 12.dp else 24.dp)
      .testTag("ultra_light_monitor"),
    contentAlignment = Alignment.Center,
  ) {
    if (isLandscape) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        PrimaryNpsReadoutCard(
          nps = uiState.liveNps,
          soundBankName = uiState.soundBankName,
          modifier = Modifier.weight(1.1f),
        )
        SecondaryTelemetryGridCard(
          uiState = uiState,
          modifier = Modifier.weight(1.0f),
        )
      }
    } else {
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        PrimaryNpsReadoutCard(
          nps = uiState.liveNps,
          soundBankName = uiState.soundBankName,
          modifier = Modifier.fillMaxWidth(),
        )
        SecondaryTelemetryGridCard(
          uiState = uiState,
          modifier = Modifier.fillMaxWidth(),
        )
      }
    }
  }
}

@Composable
private fun PrimaryNpsReadoutCard(
  nps: Int,
  soundBankName: String,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(20.dp),
    color = ObsidianSurface.copy(alpha = 0.9f),
    border = BorderStroke(1.dp, ObsidianOutline),
  ) {
    Column(
      modifier = Modifier.padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = stringResource(R.string.label_nps),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          text = "ULTRA-LIGHT DSP",
          style = MaterialTheme.typography.labelSmall,
          color = TelemetryEmerald,
          fontWeight = FontWeight.Bold,
        )
      }
      Text(
        text = FormatUtils.formatNumber(nps),
        style = MaterialTheme.typography.displayLarge,
        fontFamily = JetBrainsMonoFamily,
        color = ElectricCyan,
        maxLines = 1,
      )
      Text(
        text = soundBankName,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
      )
    }
  }
}

@Composable
private fun SecondaryTelemetryGridCard(
  uiState: PlayerUiState,
  modifier: Modifier = Modifier,
) {
  val voiceRatio = (uiState.activeVoices.toFloat() / uiState.maxPolyphony.coerceAtLeast(1)).coerceIn(0f, 1f)
  val voiceColor = when {
    voiceRatio > 0.9f -> ClipCrimson
    voiceRatio > 0.7f -> TelemetryAmber
    else -> DeepViolet
  }

  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(20.dp),
    color = ObsidianSurface.copy(alpha = 0.9f),
    border = BorderStroke(1.dp, ObsidianOutline),
  ) {
    Column(
      modifier = Modifier.padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      // Polyphony Meter
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(
            text = stringResource(R.string.label_polyphony),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text = "${uiState.activeVoices} / ${uiState.maxPolyphony}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
          )
        }
        LinearProgressIndicator(
          progress = { voiceRatio },
          modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp)),
          color = voiceColor,
          trackColor = ObsidianOutline,
        )
      }

      // Stereo Output Level + Limiter LED
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(
            text = "OUTPUT L/R • CPU ${uiState.audioCpuPercent}%",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text = if (uiState.limiterTriggered) "LIMITER ACTIVE" else "HEADROOM OK",
            style = MaterialTheme.typography.labelSmall,
            color = if (uiState.limiterTriggered) TelemetryAmber else TelemetryEmerald,
            fontWeight = FontWeight.Bold,
          )
        }
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          LinearProgressIndicator(
            progress = { uiState.peakLeft.coerceIn(0f, 1f) },
            modifier = Modifier
              .weight(1f)
              .height(6.dp)
              .clip(RoundedCornerShape(3.dp)),
            color = if (uiState.peakLeft > 0.92f) ClipCrimson else TelemetryEmerald,
            trackColor = ObsidianOutline,
          )
          LinearProgressIndicator(
            progress = { uiState.peakRight.coerceIn(0f, 1f) },
            modifier = Modifier
              .weight(1f)
              .height(6.dp)
              .clip(RoundedCornerShape(3.dp)),
            color = if (uiState.peakRight > 0.92f) ClipCrimson else TelemetryEmerald,
            trackColor = ObsidianOutline,
          )
        }
      }

      // Track Count & Total Notes
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Column {
          Text(
            text = stringResource(R.string.label_tracks_count),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text = FormatUtils.formatNumber(uiState.trackCount),
            style = MaterialTheme.typography.titleMedium,
            fontFamily = JetBrainsMonoFamily,
          )
        }
        Column(horizontalAlignment = Alignment.End) {
          Text(
            text = stringResource(R.string.label_total_notes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text = FormatUtils.formatNumber(uiState.totalNotes),
            style = MaterialTheme.typography.titleMedium,
            fontFamily = JetBrainsMonoFamily,
            color = ElectricCyan,
          )
        }
      }
    }
  }
}
