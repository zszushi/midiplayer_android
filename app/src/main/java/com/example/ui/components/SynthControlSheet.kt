package com.example.ui.components

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.audio.SoundBankType
import com.example.ui.PlayerUiState
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.ObsidianOutline
import com.example.ui.theme.ObsidianSurface
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SynthControlSheet(
  uiState: PlayerUiState,
  onDismiss: () -> Unit,
  onPickMidiFile: () -> Unit,
  onSelectSineBank: () -> Unit,
  onPickSf2File: () -> Unit,
  onPickSfzFolder: () -> Unit,
  onSelectSfzSubFile: (String) -> Unit,
  onOpenRecentMidi: (Uri) -> Unit,
  onOpenRecentSoundBank: (Uri, SoundBankType) -> Unit,
  onMaxPolyphonyChange: (Int) -> Unit,
  onVelocityThresholdChange: (Int) -> Unit,
  onMaxVoicesPerKeyChange: (Int) -> Unit,
  onLimiterToggle: (Boolean) -> Unit,
  onSineHarmonicsToggle: (Boolean) -> Unit,
  onMasterGainChange: (Float) -> Unit,
  onPlaybackSpeedChange: (Float) -> Unit,
  onNoteZoomChange: (Float) -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    sheetState = sheetState,
    containerColor = ObsidianSurface,
    modifier = Modifier.testTag("synth_control_sheet"),
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .navigationBarsPadding()
        .padding(horizontal = 20.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      // Section 1: MIDI & SoundBank File Loader
      Text(
        text = "ファイル＆音源管理 (MIDI / SF2 / SFZ / Sine)",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
      )

      Button(
        onClick = onPickMidiFile,
        modifier = Modifier
          .fillMaxWidth()
          .defaultMinSize(minHeight = 50.dp)
          .testTag("sheet_open_midi_button"),
        colors = ButtonDefaults.buttonColors(
          containerColor = ElectricCyan,
          contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        shape = RoundedCornerShape(12.dp),
      ) {
        Icon(imageVector = Icons.Filled.LibraryMusic, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = stringResource(R.string.action_open_midi),
          fontWeight = FontWeight.Bold,
        )
      }

      if (uiState.recentMidis.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(
            text = stringResource(R.string.label_recent_midi),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            uiState.recentMidis.forEach { item ->
              AssistChip(
                onClick = { onOpenRecentMidi(Uri.parse(item.uriString)) },
                label = {
                  Text(
                    text = "${item.displayName} (${FormatUtils.formatBytesMb(item.sizeBytes)})",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                },
              )
            }
          }
        }
      }

      SoundBankLoadingBanner(progress = uiState.soundBankProgress)

      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          text = "${stringResource(R.string.label_soundbank_current)}: ${uiState.soundBankName}" +
            if (uiState.soundBankRegionCount > 0) " (${uiState.soundBankRegionCount} regions)" else "",
          style = MaterialTheme.typography.bodyMedium,
          color = ElectricCyan,
          fontWeight = FontWeight.Medium,
        )

        FlowRow(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          FilterChip(
            selected = uiState.soundBankType == SoundBankType.SINE,
            onClick = onSelectSineBank,
            label = { Text(stringResource(R.string.action_use_sine)) },
            leadingIcon = {
              Icon(
                imageVector = Icons.Filled.GraphicEq,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
              )
            },
            modifier = Modifier
              .defaultMinSize(minHeight = 48.dp)
              .testTag("select_sine_chip"),
          )

          OutlinedButton(
            onClick = onPickSf2File,
            modifier = Modifier
              .defaultMinSize(minHeight = 48.dp)
              .testTag("open_sf2_button"),
            border = BorderStroke(
              1.dp,
              if (uiState.soundBankType == SoundBankType.SF2) ElectricCyan else ObsidianOutline,
            ),
          ) {
            Icon(
              imageVector = Icons.Filled.AudioFile,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(stringResource(R.string.action_open_sf2))
          }

          OutlinedButton(
            onClick = onPickSfzFolder,
            modifier = Modifier
              .defaultMinSize(minHeight = 48.dp)
              .testTag("open_sfz_folder_button"),
            border = BorderStroke(
              1.dp,
              if (uiState.soundBankType == SoundBankType.SFZ) ElectricCyan else ObsidianOutline,
            ),
          ) {
            Icon(
              imageVector = Icons.Filled.FolderOpen,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(stringResource(R.string.action_open_sfz_folder))
          }
        }

        Text(
          text = stringResource(R.string.sfz_folder_hint),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Sub-instrument selector when an SFZ folder contains multiple .sfz files
        if (uiState.soundBankType == SoundBankType.SFZ && uiState.sfzSubFiles.size > 1) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            uiState.sfzSubFiles.forEach { (name, docId) ->
              FilterChip(
                selected = docId == uiState.activeSfzDocId,
                onClick = { onSelectSfzSubFile(docId) },
                label = { Text(name, maxLines = 1) },
              )
            }
          }
        }

        if (uiState.recentSoundBanks.isNotEmpty()) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            uiState.recentSoundBanks.forEach { sb ->
              AssistChip(
                onClick = { onOpenRecentSoundBank(Uri.parse(sb.uriString), sb.type) },
                label = {
                  Text("[${sb.type.name}] ${sb.displayName}", maxLines = 1)
                },
              )
            }
          }
        }
      }

      HorizontalDivider(color = ObsidianOutline)

      // Section 2: Falling Notes Zoom & Display Controls
      val windowSeconds = 1.40f / uiState.noteZoom.coerceIn(0.25f, 4.0f)
      LabeledSliderRow(
        label = stringResource(R.string.label_note_zoom),
        valueText = String.format(Locale.US, "%.2fx (表示幅 %.2fs)", uiState.noteZoom, windowSeconds),
        value = uiState.noteZoom,
        valueRange = 0.25f..4.0f,
        onValueChange = { onNoteZoomChange((it * 20f).roundToInt() / 20f) },
        testTag = "slider_note_zoom",
      )

      Row(
        modifier = Modifier
          .fillMaxWidth()
          .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        listOf(0.5f, 1.0f, 1.5f, 2.0f, 3.0f).forEach { preset ->
          FilterChip(
            selected = kotlin.math.abs(uiState.noteZoom - preset) < 0.04f,
            onClick = { onNoteZoomChange(preset) },
            label = {
              Text(
                text = String.format(Locale.US, "%.1fx", preset),
                fontFamily = JetBrainsMonoFamily,
              )
            },
            modifier = Modifier
              .defaultMinSize(minHeight = 48.dp)
              .testTag("zoom_preset_${(preset * 10).roundToInt()}"),
          )
        }
      }

      Text(
        text = stringResource(R.string.hint_pinch_zoom),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      HorizontalDivider(color = ObsidianOutline)

      // Section 3: Black MIDI Synth Load & Audio Protection Controls
      Text(
        text = "Black MIDI 負荷制御・DSP音質保護",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
      )

      LabeledSliderRow(
        label = stringResource(R.string.label_max_polyphony),
        valueText = "${uiState.maxPolyphony} voices",
        value = uiState.maxPolyphony.toFloat(),
        valueRange = 64f..4096f,
        onValueChange = { onMaxPolyphonyChange(((it / 32f).roundToInt() * 32).coerceIn(64, 4096)) },
        testTag = "slider_max_polyphony",
      )

      LabeledSliderRow(
        label = stringResource(R.string.label_velocity_threshold),
        valueText = if (uiState.velocityThreshold == 0) "OFF (0)" else "< ${uiState.velocityThreshold} 足切り",
        value = uiState.velocityThreshold.toFloat(),
        valueRange = 0f..96f,
        onValueChange = { onVelocityThresholdChange(it.roundToInt()) },
        testTag = "slider_velocity_threshold",
      )

      LabeledSliderRow(
        label = stringResource(R.string.label_per_key_limit),
        valueText = "${uiState.maxVoicesPerKey} 重なり/鍵",
        value = uiState.maxVoicesPerKey.toFloat(),
        valueRange = 1f..16f,
        onValueChange = { onMaxVoicesPerKeyChange(it.roundToInt()) },
        testTag = "slider_per_key_limit",
      )

      LabeledSliderRow(
        label = stringResource(R.string.label_master_gain),
        valueText = "${(uiState.masterGain * 100).roundToInt()}%",
        value = uiState.masterGain,
        valueRange = 0.1f..2.5f,
        onValueChange = onMasterGainChange,
        testTag = "slider_master_gain",
      )

      LabeledSliderRow(
        label = stringResource(R.string.label_playback_speed),
        valueText = String.format(Locale.US, "%.2fx", uiState.playbackSpeed),
        value = uiState.playbackSpeed,
        valueRange = 0.25f..2.0f,
        onValueChange = { onPlaybackSpeedChange((it * 20f).roundToInt() / 20f) },
        testTag = "slider_playback_speed",
      )

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = stringResource(R.string.label_hard_limiter),
          style = MaterialTheme.typography.bodyLarge,
          modifier = Modifier.weight(1f),
        )
        Switch(
          checked = uiState.limiterEnabled,
          onCheckedChange = onLimiterToggle,
          modifier = Modifier.testTag("switch_hard_limiter"),
        )
      }

      if (uiState.soundBankType == SoundBankType.SINE) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = stringResource(R.string.label_sine_harmonics),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
          )
          Switch(
            checked = uiState.sineRichHarmonics,
            onCheckedChange = onSineHarmonicsToggle,
            modifier = Modifier.testTag("switch_sine_harmonics"),
          )
        }
      }

      Spacer(modifier = Modifier.height(16.dp))
    }
  }
}

@Composable
private fun LabeledSliderRow(
  label: String,
  valueText: String,
  value: Float,
  valueRange: ClosedFloatingPointRange<Float>,
  onValueChange: (Float) -> Unit,
  testTag: String,
) {
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = valueText,
        style = MaterialTheme.typography.labelMedium,
        fontFamily = JetBrainsMonoFamily,
        color = ElectricCyan,
        fontWeight = FontWeight.Bold,
      )
    }
    Slider(
      value = value,
      onValueChange = onValueChange,
      valueRange = valueRange,
      modifier = Modifier
        .fillMaxWidth()
        .testTag(testTag),
    )
  }
}
