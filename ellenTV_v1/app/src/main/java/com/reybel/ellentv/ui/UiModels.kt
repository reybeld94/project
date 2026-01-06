package com.reybel.ellentv.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import com.reybel.ellentv.data.api.EpgProgram
import java.time.Instant

// =====================
// Data Classes Optimizados
// =====================
@Immutable
data class ChannelData(
    val id: String,
    val displayName: String,
    val logoUrl: String?,
    val channelNumber: Int?
)

@Immutable
data class EpgRowData(
    val liveId: String,
    val channelData: ChannelData,
    val clampedPrograms: List<ClampedProgram>
)

@Immutable
data class ClampedProgram(
    val program: EpgProgram,
    val startInstant: Instant,
    val endInstant: Instant,
    val title: String,
    val isCurrent: Boolean
)

@Immutable
data class TimeWindow(
    val start: Instant,
    val end: Instant,
    val durationMinutes: Long,
    val totalWidth: Dp
)

data class ProgramWithTime(val p: EpgProgram, val s: Instant, val e: Instant)
