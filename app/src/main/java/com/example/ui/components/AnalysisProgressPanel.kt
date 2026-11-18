package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.midi.MidiScanProgress
import com.example.midi.ScanPhase
import com.example.ui.SoundBankProgressState
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.ObsidianOutline
import com.example.ui.theme.ObsidianSurfaceContainer
import com.example.ui.theme.TelemetryEmerald
import java.util.Locale

@Composable
fun AnalysisProgressCard(
  progress: MidiScanProgress,
  canPlayImmediately: Boolean,
  onPlayNow: () -> Unit,
  onCancel: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Card(
    modifier = modifier
      .fillMaxWidth()
      .testTag("analysis_progress_card"),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(
      containerColor = ObsidianSurfaceContainer.copy(alpha = 0.95f),
    ),
    border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.45f)),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(
            modifier = Modifier
              .size(8.dp)
              .clip(CircleShape)
              .background(ElectricCyan)
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = when (progress.phase) {
              ScanPhase.PREPARING_STREAM -> "ストリームを準備中..."
              ScanPhase.SCANNING_HEADERS -> "MTrkチャンク境界を高速ジャンプ検出中..."
              ScanPhase.SCANNING_TRACKS -> stringResource(
                R.string.label_tracks_analyzed,
                progress.analyzedTracks,
                progress.totalTracks,
                progress.combinedProgressPercent,
              )
              else -> progress.fileName
            },
            style = MaterialTheme.typography.titleMedium,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("tracks_analyzed_label"),
          )
        }
      }

      if (progress.phase == ScanPhase.SCANNING_HEADERS) {
        LinearProgressIndicator(
          modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp)),
          color = ElectricCyan,
          trackColor = ObsidianOutline,
        )
      } else {
        LinearProgressIndicator(
          progress = { (progress.combinedProgressPercent / 100f).coerceIn(0f, 1f) },
          modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .testTag("analysis_progress_bar"),
          color = ElectricCyan,
          trackColor = ObsidianOutline,
        )
      }

      // Detailed telemetry metrics row: Byte progress + MB/s + Live Detected Notes
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        val byteSummary = if (progress.totalBytes > 0L) {
          val speedStr = if (progress.throughputMbPerSec > 0.05f) {
            String.format(Locale.US, " • %.1f MB/s", progress.throughputMbPerSec)
          } else ""
          "${FormatUtils.formatBytesMb(progress.scannedBytes)} / ${FormatUtils.formatBytesMb(progress.totalBytes)}$speedStr"
        } else {
          FormatUtils.formatBytesMb(progress.scannedBytes)
        }

        Text(
          text = byteSummary,
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
          text = "${FormatUtils.formatNumber(progress.detectedNotes)} notes",
          style = MaterialTheme.typography.labelMedium,
          color = TelemetryEmerald,
          fontWeight = FontWeight.Bold,
          modifier = Modifier.testTag("live_detected_notes_text"),
        )
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        OutlinedButton(
          onClick = onCancel,
          modifier = Modifier
            .defaultMinSize(minHeight = 48.dp)
            .testTag("cancel_scan_button"),
          border = BorderStroke(1.dp, ObsidianOutline),
        ) {
          Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = stringResource(R.string.action_cancel_scan),
            modifier = Modifier.size(18.dp),
          )
          Spacer(modifier = Modifier.width(6.dp))
          Text(text = stringResource(R.string.action_cancel_scan))
        }

        if (canPlayImmediately) {
          Button(
            onClick = onPlayNow,
            modifier = Modifier
              .defaultMinSize(minHeight = 48.dp)
              .testTag("play_while_scanning_button"),
            colors = ButtonDefaults.buttonColors(
              containerColor = ElectricCyan,
              contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
          ) {
            Icon(
              imageVector = Icons.Filled.PlayArrow,
              contentDescription = null,
              modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
              text = stringResource(R.string.action_play_now_while_scanning),
              fontWeight = FontWeight.Bold,
            )
          }
        }
      }
    }
  }
}

@Composable
fun CompactScanHudBadge(
  progress: MidiScanProgress,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier.testTag("compact_scan_hud_badge"),
    shape = RoundedCornerShape(999.dp),
    color = ObsidianSurfaceContainer.copy(alpha = 0.92f),
    border = BorderStroke(1.dp, ElectricCyan.copy(alpha = 0.5f)),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Box(
        modifier = Modifier
          .size(7.dp)
          .clip(CircleShape)
          .background(ElectricCyan)
      )
      Text(
        text = "${progress.analyzedTracks}/${progress.totalTracks} トラック解析中 (${progress.combinedProgressPercent}%)",
        style = MaterialTheme.typography.labelSmall,
        color = ElectricCyan,
        fontWeight = FontWeight.Bold,
      )
    }
  }
}

@Composable
fun SoundBankLoadingBanner(
  progress: SoundBankProgressState,
  modifier: Modifier = Modifier,
) {
  if (!progress.isLoading) return
  Surface(
    modifier = modifier
      .fillMaxWidth()
      .testTag("soundbank_loading_banner"),
    shape = RoundedCornerShape(12.dp),
    color = ObsidianSurfaceContainer.copy(alpha = 0.95f),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.6f)),
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
      verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Text(
        text = stringResource(
          R.string.label_soundbank_loading,
          progress.loadedCount,
          progress.totalCount,
          progress.percent,
        ),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface,
      )
      LinearProgressIndicator(
        progress = { (progress.percent / 100f).coerceIn(0f, 1f) },
        modifier = Modifier
          .fillMaxWidth()
          .height(6.dp)
          .clip(RoundedCornerShape(3.dp)),
        color = MaterialTheme.colorScheme.secondary,
        trackColor = ObsidianOutline,
      )
    }
  }
}
