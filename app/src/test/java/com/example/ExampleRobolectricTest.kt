package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.midi.MidiScanProgress
import com.example.midi.ScanPhase
import com.example.ui.components.AnalysisProgressCard
import com.example.ui.theme.ObsidianMidiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @get:Rule
  val composeTestRule = createComposeRule()

  @Test
  fun `app name matches Obsidian Midi and analysis progress card renders n of m tracks`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    assertEquals("Obsidian Midi", context.getString(R.string.app_name))

    var playNowClicked = false
    composeTestRule.setContent {
      ObsidianMidiTheme {
        AnalysisProgressCard(
          progress = MidiScanProgress(
            phase = ScanPhase.SCANNING_TRACKS,
            fileName = "Impossible_Black_MIDI.mid",
            analyzedTracks = 512,
            totalTracks = 1280,
            scannedBytes = 42L * 1024L * 1024L,
            totalBytes = 100L * 1024L * 1024L,
            throughputMbPerSec = 84.5f,
            detectedNotes = 15_420_000L,
          ),
          canPlayImmediately = true,
          onPlayNow = { playNowClicked = true },
          onCancel = {},
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("analysis_progress_card").assertIsDisplayed()
    composeTestRule.onNodeWithText("512 / 1280", substring = true).assertIsDisplayed()
    composeTestRule.onNodeWithTag("play_while_scanning_button").performClick()
    assertEquals(true, playNowClicked)
  }
}
