package com.example.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ScreenLockLandscape
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.audio.SoundBankType
import com.example.midi.ScanPhase
import com.example.midi.VisualizerMode
import com.example.ui.components.AnalysisProgressCard
import com.example.ui.components.CompactScanHudBadge
import com.example.ui.components.FallingNotesPianoCanvas
import com.example.ui.components.FormatUtils
import com.example.ui.components.SoundBankLoadingBanner
import com.example.ui.components.SynthControlSheet
import com.example.ui.components.UltraLightTelemetryMonitor
import com.example.ui.theme.ClipCrimson
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.ObsidianOutline
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceContainer
import com.example.ui.theme.TelemetryAmber
import com.example.ui.theme.TelemetryEmerald
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun ObsidianPlayerRoute(
  viewModel: PlayerViewModel,
  modifier: Modifier = Modifier,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current

  // Apply Landscape Lock preference to Activity seamlessly
  LaunchedEffect(uiState.landscapeLocked) {
    val activity = context.findActivity() ?: return@LaunchedEffect
    activity.requestedOrientation = if (uiState.landscapeLocked) {
      ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    } else {
      ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
  }

  val midiPicker = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument(),
    onResult = { uri -> uri?.let { viewModel.openMidiUri(it) } },
  )

  val sf2Picker = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument(),
    onResult = { uri -> uri?.let { viewModel.openSf2Uri(it) } },
  )

  val sfzFolderPicker = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocumentTree(),
    onResult = { treeUri -> treeUri?.let { viewModel.openSfzFolderUri(it) } },
  )

  var showControlSheet by rememberSaveable { mutableStateOf(false) }

  BackHandler(enabled = showControlSheet) {
    showControlSheet = false
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(ObsidianBg)
  ) {
    ObsidianPlayerContent(
      uiState = uiState,
      viewModel = viewModel,
      onLaunchMidiPicker = { midiPicker.launch(arrayOf("audio/midi", "audio/x-midi", "*/*")) },
      onOpenSettingsSheet = { showControlSheet = true },
    )

    if (showControlSheet) {
      SynthControlSheet(
        uiState = uiState,
        onDismiss = { showControlSheet = false },
        onPickMidiFile = {
          showControlSheet = false
          midiPicker.launch(arrayOf("audio/midi", "audio/x-midi", "*/*"))
        },
        onSelectSineBank = { viewModel.selectBuiltInSineBank() },
        onPickSf2File = { sf2Picker.launch(arrayOf("*/*")) },
        onPickSfzFolder = { sfzFolderPicker.launch(null) },
        onSelectSfzSubFile = { docId ->
          val treeStr = uiState.activeSfzTreeUri ?: return@SynthControlSheet
          viewModel.openSfzFolderUri(android.net.Uri.parse(treeStr), preferredSfzDocId = docId)
        },
        onOpenRecentMidi = { uri ->
          showControlSheet = false
          viewModel.openMidiUri(uri)
        },
        onOpenRecentSoundBank = { uri, type ->
          when (type) {
            SoundBankType.SF2 -> viewModel.openSf2Uri(uri)
            SoundBankType.SFZ -> viewModel.openSfzFolderUri(uri)
            SoundBankType.SINE -> viewModel.selectBuiltInSineBank()
          }
        },
        onMaxPolyphonyChange = viewModel::setMaxPolyphony,
        onVelocityThresholdChange = viewModel::setVelocityThreshold,
        onMaxVoicesPerKeyChange = viewModel::setMaxVoicesPerKey,
        onLimiterToggle = viewModel::setLimiterEnabled,
        onSineHarmonicsToggle = viewModel::setSineRichHarmonics,
        onMasterGainChange = viewModel::setMasterGain,
        onPlaybackSpeedChange = viewModel::setPlaybackSpeed,
        onNoteZoomChange = viewModel::setNoteZoom,
      )
    }
  }
}

@Composable
fun ObsidianPlayerContent(
  uiState: PlayerUiState,
  viewModel: PlayerViewModel,
  onLaunchMidiPicker: () -> Unit,
  onOpenSettingsSheet: () -> Unit,
) {
  BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val isLandscape = maxWidth > maxHeight || maxWidth >= 640.dp
    var hudVisible by rememberSaveable { mutableStateOf(true) }

    val isActivelyScanning = uiState.scanProgress.phase in setOf(
      ScanPhase.PREPARING_STREAM,
      ScanPhase.SCANNING_HEADERS,
      ScanPhase.SCANNING_TRACKS,
    )

    if (!uiState.hasMidiLoaded) {
      ZeroStateStudioLanding(
        uiState = uiState,
        isLandscape = isLandscape,
        isActivelyScanning = isActivelyScanning,
        onLaunchMidiPicker = onLaunchMidiPicker,
        onOpenSettingsSheet = onOpenSettingsSheet,
        onCancelScan = viewModel::cancelBackgroundScan,
        onOpenRecentMidi = viewModel::openMidiUri,
        onDismissBanner = viewModel::dismissStatusBanner,
      )
    } else {
      // Active Player Layout (Immersive Landscape & Adaptive Portrait)
      Box(modifier = Modifier.fillMaxSize()) {
        // Layer 1: Visualizer Surface (Falling Notes + 88 Keys OR Ultra-Light Monitor)
        if (uiState.visualizerMode == VisualizerMode.FALLING_NOTES) {
          FallingNotesPianoCanvas(
            sequencer = viewModel.sequencer,
            visualNotes = viewModel.visualNoteBuffer,
            activeKeyboardState = viewModel.activeKeyboardState,
            isPlaying = uiState.isPlaying,
            noteZoom = uiState.noteZoom,
            onNoteZoomChange = viewModel::setNoteZoom,
            onCanvasTap = { if (isLandscape) hudVisible = !hudVisible },
            modifier = Modifier.fillMaxSize(),
          )
        } else {
          UltraLightTelemetryMonitor(
            uiState = uiState,
            isLandscape = isLandscape,
            modifier = Modifier
              .fillMaxSize()
              .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { if (isLandscape) hudVisible = !hudVisible },
              ),
          )
        }

        // Layer 2: Floating Top Studio HUD Bar
        AnimatedVisibility(
          visible = hudVisible || !isLandscape || !uiState.isPlaying,
          enter = fadeIn(),
          exit = fadeOut(),
          modifier = Modifier.align(Alignment.TopCenter),
        ) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .windowInsetsPadding(WindowInsets.safeDrawing)
              .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            TopTelemetryHudBar(
              uiState = uiState,
              isLandscape = isLandscape,
              onToggleVisualizer = viewModel::toggleVisualizerMode,
              onToggleLandscape = viewModel::toggleLandscapeLock,
              onOpenMidi = onLaunchMidiPicker,
              onOpenSettings = onOpenSettingsSheet,
            )

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically,
            ) {
              if (isActivelyScanning && uiState.isPlaying) {
                CompactScanHudBadge(
                  progress = uiState.scanProgress,
                  modifier = Modifier.weight(1f, fill = false),
                )
              } else {
                Spacer(modifier = Modifier.width(1.dp))
              }

              if (uiState.visualizerMode == VisualizerMode.FALLING_NOTES) {
                NoteZoomQuickPill(
                  noteZoom = uiState.noteZoom,
                  onNoteZoomChange = viewModel::setNoteZoom,
                )
              }
            }

            SoundBankLoadingBanner(progress = uiState.soundBankProgress)

            uiState.statusBannerMessage?.let { msg ->
              ErrorBannerStrip(message = msg, onDismiss = viewModel::dismissStatusBanner)
            }
          }
        }

        // Layer 3: Prominent n/m Track Analysis Progress Card when paused/waiting on large Black MIDI
        if (isActivelyScanning && !uiState.isPlaying) {
          Box(
            modifier = Modifier
              .align(Alignment.Center)
              .padding(horizontal = 20.dp)
              .widthIn(max = 540.dp)
          ) {
            AnalysisProgressCard(
              progress = uiState.scanProgress,
              canPlayImmediately = uiState.hasMidiLoaded,
              onPlayNow = viewModel::playImmediatelyWhileScanning,
              onCancel = viewModel::cancelBackgroundScan,
            )
          }
        }

        // Layer 4: Floating Bottom Transport & Seek Console
        AnimatedVisibility(
          visible = hudVisible || !isLandscape || !uiState.isPlaying,
          enter = fadeIn(),
          exit = fadeOut(),
          modifier = Modifier.align(Alignment.BottomCenter),
        ) {
          BottomTransportBar(
            uiState = uiState,
            onPlayPause = viewModel::togglePlayPause,
            onStop = viewModel::stopPlayback,
            onSeekFraction = viewModel::seekToFraction,
            modifier = Modifier
              .fillMaxWidth()
              .navigationBarsPadding()
              .padding(horizontal = 12.dp, vertical = 8.dp),
          )
        }
      }
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ZeroStateStudioLanding(
  uiState: PlayerUiState,
  isLandscape: Boolean,
  isActivelyScanning: Boolean,
  onLaunchMidiPicker: () -> Unit,
  onOpenSettingsSheet: () -> Unit,
  onCancelScan: () -> Unit,
  onOpenRecentMidi: (android.net.Uri) -> Unit,
  onDismissBanner: () -> Unit,
) {
  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(ObsidianBg)
      .statusBarsPadding()
      .navigationBarsPadding(),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      modifier = Modifier
        .widthIn(max = if (isLandscape) 780.dp else 520.dp)
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp, vertical = 16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      // Hero Studio Card with generated artwork backdrop
      Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
        border = BorderStroke(1.dp, ObsidianOutline),
      ) {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .height(if (isLandscape) 170.dp else 210.dp)
        ) {
          Image(
            painter = painterResource(id = R.drawable.img_studio_hero),
            contentDescription = stringResource(R.string.cd_hero_banner),
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
          )
          Box(
            modifier = Modifier
              .fillMaxSize()
              .background(
                Brush.verticalGradient(
                  colors = listOf(
                    ObsidianBg.copy(alpha = 0.25f),
                    ObsidianBg.copy(alpha = 0.92f),
                  )
                )
              )
          )
          Column(
            modifier = Modifier
              .align(Alignment.BottomStart)
              .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Text(
              text = stringResource(R.string.app_name),
              style = MaterialTheme.typography.headlineMedium,
              color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
              text = stringResource(R.string.hero_subtitle),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }

      uiState.statusBannerMessage?.let { msg ->
        ErrorBannerStrip(message = msg, onDismiss = onDismissBanner)
      }

      SoundBankLoadingBanner(progress = uiState.soundBankProgress)

      if (isActivelyScanning) {
        AnalysisProgressCard(
          progress = uiState.scanProgress,
          canPlayImmediately = false,
          onPlayNow = {},
          onCancel = onCancelScan,
        )
      }

      // Primary Actions Card
      Surface(
        shape = RoundedCornerShape(20.dp),
        color = ObsidianSurfaceContainer,
        border = BorderStroke(1.dp, ObsidianOutline),
      ) {
        Column(
          modifier = Modifier.padding(20.dp),
          verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
          Text(
            text = stringResource(R.string.empty_state_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Button(
              onClick = onLaunchMidiPicker,
              modifier = Modifier
                .weight(1.2f)
                .defaultMinSize(minHeight = 52.dp)
                .testTag("hero_open_midi_button"),
              colors = ButtonDefaults.buttonColors(
                containerColor = ElectricCyan,
                contentColor = MaterialTheme.colorScheme.onPrimary,
              ),
              shape = RoundedCornerShape(14.dp),
            ) {
              Icon(imageVector = Icons.Filled.FolderOpen, contentDescription = null)
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                text = stringResource(R.string.action_open_midi),
                fontWeight = FontWeight.Bold,
              )
            }

            OutlinedButton(
              onClick = onOpenSettingsSheet,
              modifier = Modifier
                .weight(1f)
                .defaultMinSize(minHeight = 52.dp)
                .testTag("open_settings_button"),
              border = BorderStroke(1.dp, ObsidianOutline),
              shape = RoundedCornerShape(14.dp),
            ) {
              Icon(imageVector = Icons.Filled.Tune, contentDescription = null)
              Spacer(modifier = Modifier.width(8.dp))
              Text(text = stringResource(R.string.action_open_settings))
            }
          }

          // Active Synth Engine Status Strip
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = "音源: ${uiState.soundBankName}",
              style = MaterialTheme.typography.labelMedium,
              color = ElectricCyan,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f),
            )
            Text(
              text = "上限 ${uiState.maxPolyphony} ボイス",
              style = MaterialTheme.typography.labelMedium,
              fontFamily = JetBrainsMonoFamily,
              color = TelemetryEmerald,
            )
          }

          if (uiState.recentMidis.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Text(
                text = stringResource(R.string.label_recent_midi),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
              )
              FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                uiState.recentMidis.forEach { rec ->
                  AssistChip(
                    onClick = { onOpenRecentMidi(android.net.Uri.parse(rec.uriString)) },
                    leadingIcon = {
                      Icon(
                        imageVector = Icons.Filled.LibraryMusic,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                      )
                    },
                    label = {
                      Text(
                        text = rec.displayName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                      )
                    },
                  )
                }
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun TopTelemetryHudBar(
  uiState: PlayerUiState,
  isLandscape: Boolean,
  onToggleVisualizer: () -> Unit,
  onToggleLandscape: () -> Unit,
  onOpenMidi: () -> Unit,
  onOpenSettings: () -> Unit,
) {
  Surface(
    shape = RoundedCornerShape(16.dp),
    color = ObsidianSurface.copy(alpha = 0.88f),
    border = BorderStroke(1.dp, ObsidianOutline.copy(alpha = 0.8f)),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        Text(
          text = uiState.midiTitle.ifBlank { "Untitled MIDI" },
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        val guardSuffix = if (uiState.cpuGuardActive) " • GUARD" else ""
        Text(
          text = "NPS ${FormatUtils.formatNumber(uiState.liveNps)} • " +
            "VOX ${uiState.activeVoices}/${uiState.maxPolyphony} • " +
            uiState.soundBankType.name + guardSuffix,
          style = MaterialTheme.typography.labelSmall,
          fontFamily = JetBrainsMonoFamily,
          color = if (uiState.cpuGuardActive) TelemetryAmber else ElectricCyan,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.testTag("hud_telemetry_text"),
        )
      }

      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        IconButton(
          onClick = onToggleVisualizer,
          modifier = Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .testTag("toggle_visualizer_button"),
        ) {
          Icon(
            imageVector = if (uiState.visualizerMode == VisualizerMode.FALLING_NOTES) {
              Icons.Filled.Speed
            } else {
              Icons.Filled.Piano
            },
            contentDescription = stringResource(R.string.action_toggle_visualizer),
            tint = if (uiState.visualizerMode == VisualizerMode.ULTRA_LIGHT) {
              TelemetryEmerald
            } else {
              MaterialTheme.colorScheme.onSurface
            },
          )
        }

        IconButton(
          onClick = onToggleLandscape,
          modifier = Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .testTag("toggle_landscape_button"),
        ) {
          Icon(
            imageVector = if (uiState.landscapeLocked) {
              Icons.Filled.ScreenLockLandscape
            } else {
              Icons.Filled.ScreenRotation
            },
            contentDescription = stringResource(R.string.action_toggle_landscape),
            tint = if (uiState.landscapeLocked) ElectricCyan else MaterialTheme.colorScheme.onSurface,
          )
        }

        IconButton(
          onClick = onOpenMidi,
          modifier = Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .testTag("hud_open_midi_button"),
        ) {
          Icon(
            imageVector = Icons.Filled.FolderOpen,
            contentDescription = stringResource(R.string.action_open_midi),
          )
        }

        IconButton(
          onClick = onOpenSettings,
          modifier = Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .testTag("open_settings_button"),
        ) {
          Icon(
            imageVector = Icons.Filled.Tune,
            contentDescription = stringResource(R.string.action_open_settings),
            tint = ElectricCyan,
          )
        }
      }
    }
  }
}

@Composable
private fun NoteZoomQuickPill(
  noteZoom: Float,
  onNoteZoomChange: (Float) -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier.testTag("hud_zoom_pill"),
    shape = RoundedCornerShape(24.dp),
    color = ObsidianSurface.copy(alpha = 0.88f),
    border = BorderStroke(1.dp, ObsidianOutline.copy(alpha = 0.85f)),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.padding(horizontal = 4.dp),
    ) {
      IconButton(
        onClick = {
          val stepped = (((noteZoom / 1.25f) * 20f).roundToInt() / 20f).coerceIn(0.25f, 4.0f)
          onNoteZoomChange(stepped)
        },
        modifier = Modifier
          .size(48.dp)
          .testTag("zoom_out_button"),
      ) {
        Icon(
          imageVector = Icons.Filled.Remove,
          contentDescription = stringResource(R.string.action_zoom_out),
          modifier = Modifier.size(18.dp),
        )
      }

      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
          .clip(RoundedCornerShape(12.dp))
          .clickable(onClickLabel = stringResource(R.string.action_zoom_reset)) {
            onNoteZoomChange(1.0f)
          }
          .defaultMinSize(minWidth = 56.dp, minHeight = 48.dp)
          .padding(horizontal = 6.dp)
          .testTag("zoom_reset_button"),
      ) {
        Icon(
          imageVector = Icons.Filled.ZoomIn,
          contentDescription = null,
          tint = ElectricCyan,
          modifier = Modifier.size(15.dp),
        )
        Text(
          text = String.format(Locale.US, "%.2fx", noteZoom),
          style = MaterialTheme.typography.labelMedium,
          fontFamily = JetBrainsMonoFamily,
          color = ElectricCyan,
          fontWeight = FontWeight.Bold,
        )
      }

      IconButton(
        onClick = {
          val stepped = (((noteZoom * 1.25f) * 20f).roundToInt() / 20f).coerceIn(0.25f, 4.0f)
          onNoteZoomChange(stepped)
        },
        modifier = Modifier
          .size(48.dp)
          .testTag("zoom_in_button"),
      ) {
        Icon(
          imageVector = Icons.Filled.Add,
          contentDescription = stringResource(R.string.action_zoom_in),
          modifier = Modifier.size(18.dp),
        )
      }
    }
  }
}

@Composable
private fun BottomTransportBar(
  uiState: PlayerUiState,
  onPlayPause: () -> Unit,
  onStop: () -> Unit,
  onSeekFraction: (Float) -> Unit,
  modifier: Modifier = Modifier,
) {
  var isDragging by remember { mutableStateOf(false) }
  var dragFraction by remember { mutableFloatStateOf(0f) }

  val liveFraction = if (uiState.durationMicros > 0L) {
    (uiState.currentPositionMicros.toDouble() / uiState.durationMicros.toDouble())
      .toFloat()
      .coerceIn(0f, 1f)
  } else 0f

  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(18.dp),
    color = ObsidianSurface.copy(alpha = 0.90f),
    border = BorderStroke(1.dp, ObsidianOutline.copy(alpha = 0.85f)),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      FilledIconButton(
        onClick = onPlayPause,
        modifier = Modifier
          .size(48.dp)
          .testTag("play_pause_button"),
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(
          containerColor = ElectricCyan,
          contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
      ) {
        Icon(
          imageVector = if (uiState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
          contentDescription = if (uiState.isPlaying) {
            stringResource(R.string.action_pause)
          } else {
            stringResource(R.string.action_play)
          },
        )
      }

      IconButton(
        onClick = onStop,
        modifier = Modifier
          .size(48.dp)
          .testTag("stop_button"),
      ) {
        Icon(
          imageVector = Icons.Filled.Stop,
          contentDescription = stringResource(R.string.action_stop),
        )
      }

      Text(
        text = FormatUtils.formatMicrosTime(uiState.currentPositionMicros),
        style = MaterialTheme.typography.labelMedium,
        fontFamily = JetBrainsMonoFamily,
      )

      Slider(
        value = if (isDragging) dragFraction else liveFraction,
        enabled = uiState.durationMicros > 0L,
        onValueChange = {
          isDragging = true
          dragFraction = it
        },
        onValueChangeFinished = {
          onSeekFraction(dragFraction)
          isDragging = false
        },
        modifier = Modifier
          .weight(1f)
          .testTag("seek_slider"),
      )

      Text(
        text = if (uiState.durationMicros > 0L) {
          FormatUtils.formatMicrosTime(uiState.durationMicros)
        } else {
          "--:--"
        },
        style = MaterialTheme.typography.labelMedium,
        fontFamily = JetBrainsMonoFamily,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun ErrorBannerStrip(
  message: String,
  onDismiss: () -> Unit,
) {
  Surface(
    shape = RoundedCornerShape(12.dp),
    color = ClipCrimson.copy(alpha = 0.2f),
    border = BorderStroke(1.dp, ClipCrimson),
    modifier = Modifier
      .fillMaxWidth()
      .testTag("error_banner"),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White,
        modifier = Modifier.weight(1f),
      )
      IconButton(
        onClick = onDismiss,
        modifier = Modifier.size(48.dp),
      ) {
        Icon(
          imageVector = Icons.Filled.Close,
          contentDescription = stringResource(R.string.cd_close_sheet),
          tint = Color.White,
        )
      }
    }
  }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
  is Activity -> this
  is ContextWrapper -> baseContext.findActivity()
  else -> null
}
