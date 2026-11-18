package com.example.ui.components

import java.text.NumberFormat
import java.util.Locale

object FormatUtils {
  private val intFormat = NumberFormat.getIntegerInstance(Locale.US)

  fun formatNumber(value: Long): String = intFormat.format(value)

  fun formatNumber(value: Int): String = intFormat.format(value.toLong())

  fun formatMicrosTime(micros: Long): String {
    val totalSec = (micros.coerceAtLeast(0L) / 1_000_000L).toInt()
    val hours = totalSec / 3600
    val mins = (totalSec % 3600) / 60
    val secs = totalSec % 60
    return if (hours > 0) {
      String.format(Locale.US, "%d:%02d:%02d", hours, mins, secs)
    } else {
      String.format(Locale.US, "%02d:%02d", mins, secs)
    }
  }

  fun formatBytesMb(bytes: Long): String {
    if (bytes <= 0L) return "0.0 MB"
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 100.0) {
      String.format(Locale.US, "%.0f MB", mb)
    } else if (mb >= 1.0) {
      String.format(Locale.US, "%.1f MB", mb)
    } else {
      val kb = bytes / 1024.0
      String.format(Locale.US, "%.0f KB", kb)
    }
  }
}
