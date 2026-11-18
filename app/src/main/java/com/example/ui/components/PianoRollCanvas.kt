package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val FIRST_PIANO_KEY = 21 // A0
private const val LAST_PIANO_KEY = 108 // C8
private const val WHITE_KEY_COUNT = 52

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
      val prevWhiteX = KEY_NORM_X[(k - 1).coerceAtLeast(FIRST_PIANO_KEY)]
      val blackW = 0.62f / WHITE_KEY_COUNT
      KEY_NORM_X[k] = prevWhiteX + (1.0f / WHITE_KEY_COUNT) - (blackW * 0.5f)
      KEY_NORM_W[k] = blackW
    }
  }
  true
}

// Precomputed Zero-GC 3D Beveled Note Color Palettes for all 16 MIDI Channels
private val NoteDarkBorderColors = Array(16) { ch ->
  lerp(MidiChannelColors[ch], Color(0xFF04060A), 0.74f)
}
private val NoteMidBodyColors = Array(16) { ch ->
  lerp(MidiChannelColors[ch], Color(0xFF0A0E17), 0.18f)
}
private val NoteBrightBodyColors = Array(16) { ch ->
  lerp(MidiChannelColors[ch], Color.White, 0.12f)
}
private val NoteBlackKeyBodyColors = Array(16) { ch ->
  lerp(MidiChannelColors[ch], Color(0xFF070A12), 0.10f)
}
private val NoteSpecularCapColors = Array(16) { ch ->
  lerp(MidiChannelColors[ch], Color.White, 0.58f)
}
private val ActiveKeyGlowColors = Array(16) { ch ->
  MidiChannelColors[ch].copy(alpha = 0.28f)
}

private val StageTopColor = Color(0xFF07090F)
private val StageBottomColor = Color(0xFF0E1320)
private val OctaveStripeColor = Color(0xFF121829).copy(alpha = 0.42f)
private val WhiteKeyRestTop = Color(0xFFF2F6FF)
private val WhiteKeyRestLip = Color(0xFFBEC8DE)
private val BlackKeyOuter = Color(0xFF0C0F18)
private val BlackKeyInnerTop = Color(0xFF252D40)
private val GrandPianoFeltCrimson = Color(0xFF7D182B)

/**
 * High-FPS Zero-Allocation Studio Falling Notes + 3D 88-Key Grand Piano Canvas.
 *
 * Anti-Z-Fighting & Visual Polish Architecture:
 * 1. Deterministic Oldest -> Newest sequence ordering (eliminates screen-space bucket strobing).
 * 2. Two-Pass White-Key-Then-Black-Key lane layering (eliminates horizontal overlap edge fighting).
 * 3. 3D beveled notes (dark obsidian rim + velocity-lit body + glossy specular cap) with
 *    automatic high-density LOD for locked 60fps during extreme Black MIDI cascades.
 * 4. Multi-touch 2-finger pinch-to-zoom + 1-finger tap HUD toggle.
 */
@Composable
fun FallingNotesPianoCanvas(
  sequencer: StreamingMidiSequencer,
  visualNotes: VisualNoteRingBuffer,
  activeKeyboardState: IntArray,
  isPlaying: Boolean,
  noteZoom: Float,
  onNoteZoomChange: (Float) -> Unit,
  onCanvasTap: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  var frameTick by remember { mutableLongStateOf(0L) }
  val currentZoomState by rememberUpdatedState(noteZoom)
  val currentOnZoomChange by rememberUpdatedState(onNoteZoomChange)
  val currentOnTap by rememberUpdatedState(onCanvasTap)

  LaunchedEffect(isPlaying) {
    if (isPlaying) {
      while (true) {
        withFrameNanos { nanos ->
          frameTick = nanos
        }
      }
    }
  }

  // Pre-allocated primitive partition arrays (allocated once per capacity, zero per-frame GC!)
  val whiteVisibleSlots = remember(visualNotes.capacity) { IntArray(visualNotes.capacity) }
  val blackVisibleSlots = remember(visualNotes.capacity) { IntArray(visualNotes.capacity) }

  Canvas(
    modifier = modifier
      .fillMaxSize()
      .background(ObsidianBg)
      .pointerInput(Unit) {
        awaitEachGesture {
          val down = awaitFirstDown(requireUnconsumed = false)
          val startPos = down.position
          var movedFar = false
          var wasMultiTouch = false
          var liveZoom = currentZoomState

          do {
            val event = awaitPointerEvent()
            val pressedCount = event.changes.count { it.pressed }
            if (pressedCount >= 2) {
              wasMultiTouch = true
              val zoomDelta = event.calculateZoom()
              if (zoomDelta != 1f && zoomDelta > 0f) {
                liveZoom = (liveZoom * zoomDelta).coerceIn(
                  StreamingMidiSequencer.MIN_NOTE_ZOOM,
                  StreamingMidiSequencer.MAX_NOTE_ZOOM,
                )
                currentOnZoomChange(liveZoom)
                event.changes.forEach { it.consume() }
              }
            } else if (pressedCount == 1 && !wasMultiTouch) {
              val pos = event.changes.firstOrNull()?.position ?: startPos
              if ((pos - startPos).getDistance() > viewConfiguration.touchSlop) {
                movedFar = true
              }
            }
          } while (event.changes.any { it.pressed })

          if (!wasMultiTouch && !movedFar) {
            currentOnTap()
          }
        }
      }
      .testTag("falling_notes_canvas")
  ) {
    // Subscribe to VSYNC frameTick & noteZoom changes
    val unusedTick = frameTick
    val clampedZoom = noteZoom.coerceIn(
      StreamingMidiSequencer.MIN_NOTE_ZOOM,
      StreamingMidiSequencer.MAX_NOTE_ZOOM,
    )
    if (unusedTick == Long.MIN_VALUE && !initPianoGeometry) return@Canvas

    val w = size.width
    val h = size.height
    if (w <= 8f || h <= 8f) return@Canvas

    val keyboardHeight = (h * 0.175f).coerceIn(46.dp.toPx(), 98.dp.toPx())
    val rollHeight = (h - keyboardHeight).coerceAtLeast(1f)

    // 1. Studio Stage Backdrop + Subtle Alternating Octave Bands & Reference Grid
    drawRect(
      brush = Brush.verticalGradient(
        colors = listOf(StageTopColor, StageBottomColor),
        startY = 0f,
        endY = rollHeight,
      ),
      topLeft = Offset.Zero,
      size = Size(w, rollHeight),
    )

    var octIdx = 0
    var cKey = 24 // C1..C8
    while (cKey <= LAST_PIANO_KEY) {
      val xStart = KEY_NORM_X[cKey] * w
      val nextC = (cKey + 12).coerceAtMost(LAST_PIANO_KEY)
      val xEnd = if (cKey + 12 <= LAST_PIANO_KEY) KEY_NORM_X[nextC] * w else w
      if (octIdx % 2 == 1 && xEnd > xStart) {
        drawRect(
          color = OctaveStripeColor,
          topLeft = Offset(xStart, 0f),
          size = Size(xEnd - xStart, rollHeight),
        )
      }
      // Crisp C-octave line
      drawLine(
        color = ObsidianOutline.copy(alpha = 0.50f),
        start = Offset(xStart, 0f),
        end = Offset(xStart, rollHeight),
        strokeWidth = 1.2f,
      )
      // Subtle F-natural sub-octave guide
      val fKey = cKey + 5
      if (fKey <= LAST_PIANO_KEY) {
        val fx = KEY_NORM_X[fKey] * w
        drawLine(
          color = ObsidianOutline.copy(alpha = 0.20f),
          start = Offset(fx, 0f),
          end = Offset(fx, rollHeight),
          strokeWidth = 1f,
        )
      }
      octIdx++
      cKey += 12
    }

    // 2. Single-Scan Partition into White & Black Visible Note Lists (Oldest -> Newest!)
    val visibleWindowUs = (StreamingMidiSequencer.BASE_VISIBLE_WINDOW_US.toDouble() / clampedZoom.toDouble())
      .toLong()
      .coerceIn(320_000L, 6_000_000L)
    val invWindowUs = 1.0f / visibleWindowUs.toFloat()

    val nowUs = sequencer.currentPositionMicros
    val horizonUs = nowUs + visibleWindowUs
    val totalWritten = visualNotes.writeCount
    val cap = visualNotes.capacity
    val mask = cap - 1
    val startSeq = (totalWritten - cap).coerceAtLeast(0L)

    var whiteCount = 0
    var blackCount = 0
    var seq = startSeq
    while (seq < totalWritten) {
      val slot = seq.toInt() and mask
      val sUs = visualNotes.startMicros[slot]
      val eUs = visualNotes.endMicros[slot]
      if (eUs >= nowUs && sUs <= horizonUs) {
        val rawKey = visualNotes.packedMeta[slot] and 0x7F
        val key = rawKey.coerceIn(FIRST_PIANO_KEY, LAST_PIANO_KEY)
        if (KEY_IS_BLACK[key]) {
          if (blackCount < blackVisibleSlots.size) blackVisibleSlots[blackCount++] = slot
        } else {
          if (whiteCount < whiteVisibleSlots.size) whiteVisibleSlots[whiteCount++] = slot
        }
      }
      seq++
    }

    val totalVisible = whiteCount + blackCount
    val highDetailBevel = totalVisible <= 5500
    val roundedBars = totalVisible <= 9000
    val whiteCorner = CornerRadius(3.2f, 3.2f)
    val blackCorner = CornerRadius(2.6f, 2.6f)

    fun renderNoteBatch(slots: IntArray, count: Int, isBlackPass: Boolean) {
      val corner = if (isBlackPass) blackCorner else whiteCorner
      for (i in 0 until count) {
        val slot = slots[i]
        val sUs = visualNotes.startMicros[slot]
        val eUs = visualNotes.endMicros[slot]
        if (eUs < nowUs || sUs > horizonUs) continue

        val meta = visualNotes.packedMeta[slot]
        val key = (meta and 0x7F).coerceIn(FIRST_PIANO_KEY, LAST_PIANO_KEY)
        val ch = (meta ushr 8) and 0x0F
        val vel = (meta ushr 16) and 0x7F

        val clampedStart = max(sUs, nowUs)
        val clampedEnd = min(eUs, horizonUs)

        val yBottom = rollHeight * (1f - ((clampedStart - nowUs).toFloat() * invWindowUs))
        val yTop = rollHeight * (1f - ((clampedEnd - nowUs).toFloat() * invWindowUs))
        val barH = max(3.0f, yBottom - yTop)

        val laneX = KEY_NORM_X[key] * w
        val laneW = KEY_NORM_W[key] * w
        val padX = if (isBlackPass) 0.25f else 0.55f
        val barX = laneX + padX
        val barW = max(2.2f, laneW - padX * 2f)

        val borderCol = NoteDarkBorderColors[ch]
        val bodyCol = when {
          vel >= 102 -> NoteBrightBodyColors[ch]
          isBlackPass -> NoteBlackKeyBodyColors[ch]
          else -> NoteMidBodyColors[ch]
        }

        // Layer A: Crisp Dark Border Rim (separates stacked chords & rapid notes cleanly)
        if (roundedBars && barH >= 5f) {
          drawRoundRect(
            color = borderCol,
            topLeft = Offset(barX, yTop),
            size = Size(barW, barH),
            cornerRadius = corner,
          )
        } else {
          drawRect(
            color = borderCol,
            topLeft = Offset(barX, yTop),
            size = Size(barW, barH),
          )
        }

        // Layer B: Inset Luminous Note Body
        val inset = if (barW >= 5.5f && barH >= 4.5f) 1.1f else 0.65f
        val innerW = (barW - inset * 2f).coerceAtLeast(1.0f)
        val innerH = (barH - inset * 2f).coerceAtLeast(1.4f)
        if (roundedBars && innerH >= 4f) {
          drawRoundRect(
            color = bodyCol,
            topLeft = Offset(barX + inset, yTop + inset),
            size = Size(innerW, innerH),
            cornerRadius = corner,
          )
        } else {
          drawRect(
            color = bodyCol,
            topLeft = Offset(barX + inset, yTop + inset),
            size = Size(innerW, innerH),
          )
        }

        // Layer C: 3D Specular Top Cap & Left Highlight Edge (when visible density allows 60fps)
        if (highDetailBevel && innerW >= 3.5f && innerH >= 5.5f) {
          val specCol = NoteSpecularCapColors[ch]
          val capH = min(2.4f, innerH * 0.28f)
          drawRect(
            color = specCol,
            topLeft = Offset(barX + inset, yTop + inset),
            size = Size(innerW, capH),
          )
          val edgeW = min(1.5f, innerW * 0.22f)
          drawRect(
            color = specCol.copy(alpha = 0.65f),
            topLeft = Offset(barX + inset, yTop + inset + capH),
            size = Size(edgeW, innerH - capH),
          )
        }
      }
    }

    // Pass 1: White-Key Falling Notes | Pass 2: Black-Key Falling Notes (Zero overlap fighting!)
    renderNoteBatch(whiteVisibleSlots, whiteCount, isBlackPass = false)
    renderNoteBatch(blackVisibleSlots, blackCount, isBlackPass = true)

    // 3. Strike-Line Horizon Glow + Active Key Impact Plumes
    val glowBandH = min(22.dp.toPx(), rollHeight)
    drawRect(
      brush = Brush.verticalGradient(
        colors = listOf(Color.Transparent, ElectricCyan.copy(alpha = 0.22f)),
        startY = (rollHeight - glowBandH).coerceAtLeast(0f),
        endY = rollHeight,
      ),
      topLeft = Offset(0f, (rollHeight - glowBandH).coerceAtLeast(0f)),
      size = Size(w, glowBandH),
    )

    val plumeH = min(26.dp.toPx(), rollHeight)
    for (k in FIRST_PIANO_KEY..LAST_PIANO_KEY) {
      val activeState = activeKeyboardState[k]
      if (activeState != 0) {
        val ch = (activeState ushr 8) and 0x0F
        val kx = KEY_NORM_X[k] * w
        val kw = KEY_NORM_W[k] * w
        drawRect(
          color = ActiveKeyGlowColors[ch],
          topLeft = Offset((kx - kw * 0.18f).coerceAtLeast(0f), (rollHeight - plumeH).coerceAtLeast(0f)),
          size = Size(kw * 1.36f, plumeH),
        )
      }
    }

    // Grand piano crimson felt rail at strike boundary
    val feltH = 2.5.dp.toPx()
    val keyTop = rollHeight
    drawRect(
      color = GrandPianoFeltCrimson,
      topLeft = Offset(0f, (keyTop - feltH * 0.5f).coerceAtLeast(0f)),
      size = Size(w, feltH),
    )

    // 4. Render 3D 88-Key Grand Piano Keyboard (Pass 1: 52 White Keys, Pass 2: 36 Black Keys)
    val lipHeight = (keyboardHeight * 0.09f).coerceAtLeast(3f)
    for (k in FIRST_PIANO_KEY..LAST_PIANO_KEY) {
      if (!KEY_IS_BLACK[k]) {
        val kx = KEY_NORM_X[k] * w
        val kw = (KEY_NORM_W[k] * w - 1.0f).coerceAtLeast(1f)
        val activeState = activeKeyboardState[k]
        if (activeState != 0) {
          val ch = (activeState ushr 8) and 0x0F
          val activeMain = MidiChannelColors[ch]
          val activeBright = NoteSpecularCapColors[ch]
          drawRect(
            color = activeMain,
            topLeft = Offset(kx + 0.5f, keyTop),
            size = Size(kw, keyboardHeight),
          )
          drawRect(
            color = activeBright,
            topLeft = Offset(kx + 0.5f, keyTop),
            size = Size(kw, keyboardHeight * 0.22f),
          )
        } else {
          drawRect(
            color = WhiteKeyRestTop,
            topLeft = Offset(kx + 0.5f, keyTop),
            size = Size(kw, keyboardHeight - lipHeight),
          )
          drawRect(
            color = WhiteKeyRestLip,
            topLeft = Offset(kx + 0.5f, keyTop + keyboardHeight - lipHeight),
            size = Size(kw, lipHeight),
          )
        }
      }
    }

    val blackKeyHeight = keyboardHeight * 0.64f
    val blackKeyCorner = CornerRadius(2.5f, 2.5f)
    for (k in FIRST_PIANO_KEY..LAST_PIANO_KEY) {
      if (KEY_IS_BLACK[k]) {
        val kx = KEY_NORM_X[k] * w
        val kw = (KEY_NORM_W[k] * w).coerceAtLeast(1.8f)
        val activeState = activeKeyboardState[k]
        if (activeState != 0) {
          val ch = (activeState ushr 8) and 0x0F
          drawRoundRect(
            color = NoteDarkBorderColors[ch],
            topLeft = Offset(kx, keyTop),
            size = Size(kw, blackKeyHeight),
            cornerRadius = blackKeyCorner,
          )
          val insetW = (kw - 1.6f).coerceAtLeast(1f)
          drawRoundRect(
            color = NoteSpecularCapColors[ch],
            topLeft = Offset(kx + 0.8f, keyTop),
            size = Size(insetW, blackKeyHeight * 0.88f),
            cornerRadius = blackKeyCorner,
          )
        } else {
          drawRoundRect(
            color = BlackKeyOuter,
            topLeft = Offset(kx, keyTop),
            size = Size(kw, blackKeyHeight),
            cornerRadius = blackKeyCorner,
          )
          if (kw >= 3.5f) {
            drawRoundRect(
              color = BlackKeyInnerTop,
              topLeft = Offset(kx + 0.8f, keyTop + 0.5f),
              size = Size((kw - 1.6f).coerceAtLeast(1f), blackKeyHeight * 0.84f),
              cornerRadius = CornerRadius(1.8f, 1.8f),
            )
          }
        }
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

      // Stereo Output Level + Limiter / Adaptive Guard LED
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
          val statusLabel = when {
            uiState.cpuGuardActive -> stringResource(R.string.label_cpu_guard_active)
            uiState.limiterTriggered -> "LIMITER ACTIVE"
            else -> "HEADROOM OK"
          }
          val statusColor = when {
            uiState.cpuGuardActive || uiState.limiterTriggered -> TelemetryAmber
            else -> TelemetryEmerald
          }
          Text(
            text = statusLabel,
            style = MaterialTheme.typography.labelSmall,
            color = statusColor,
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
