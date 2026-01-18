package com.reybel.ellentv.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import com.reybel.ellentv.data.api.ApiClient
import com.reybel.ellentv.data.api.EpgProgram
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// =====================
// Helpers Optimizados
// =====================
fun clampPrograms(
    programs: List<EpgProgram>,
    windowStart: Instant,
    windowEnd: Instant,
    now: Instant
): List<ClampedProgram> {
    return programs.mapNotNull { p ->
        val ps = runCatching { parseInstantFlexible(p.start) }.getOrNull() ?: return@mapNotNull null
        val pe = runCatching { parseInstantFlexible(p.end) }.getOrNull() ?: return@mapNotNull null
        val s = maxInstant(ps, windowStart)
        val e = minInstant(pe, windowEnd)
        if (!e.isAfter(s)) return@mapNotNull null

        val isCurrent = now.isAfter(s) && now.isBefore(e)
        val title = if (p.title.isBlank()) "(sin título)" else p.title

        ClampedProgram(p, s, e, title, isCurrent)
    }.sortedBy { it.startInstant }
}

fun minutesToWidth(minutes: Long, hourWidth: Dp): Dp {
    val fracHours = minutes.toFloat() / 60f
    return hourWidth * fracHours
}

fun parseInstantFlexible(text: String): Instant {
    runCatching { return Instant.parse(text) }
    runCatching { return OffsetDateTime.parse(text).toInstant() }
    val normalized = text.replace("+00:00", "Z")
    return Instant.parse(normalized)
}

fun maxInstant(a: Instant, b: Instant): Instant = if (a.isAfter(b)) a else b
fun minInstant(a: Instant, b: Instant): Instant = if (a.isBefore(b)) a else b

fun Modifier.drawNowLine(
    now: Instant,
    windowStart: Instant,
    windowEnd: Instant
): Modifier = this.drawBehind {
    val totalMs = Duration.between(windowStart, windowEnd).toMillis().toFloat().coerceAtLeast(1f)
    val ms = Duration.between(windowStart, now).toMillis().toFloat()
    val frac = (ms / totalMs)

    if (frac in 0f..1f) {
        val x = size.width * frac
        val coreColor = Color(0xFFFF3D5A)
        val glowColor = Color(0xFFFF8A80)
        val topDotY = 10f

        drawLine(
            color = glowColor.copy(alpha = 0.35f),
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = 10f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = coreColor.copy(alpha = 0.9f),
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = 4f,
            cap = StrokeCap.Round
        )
        drawCircle(
            color = glowColor.copy(alpha = 0.7f),
            radius = 7f,
            center = Offset(x, topDotY)
        )
        drawCircle(
            color = coreColor,
            radius = 4.5f,
            center = Offset(x, topDotY)
        )
    }
}

fun pickNowNext(
    programs: List<EpgProgram>,
    now: Instant
): Pair<ProgramWithTime?, ProgramWithTime?> {
    val parsed = programs.mapNotNull { p ->
        val s = runCatching { parseInstantFlexible(p.start) }.getOrNull() ?: return@mapNotNull null
        val e = runCatching { parseInstantFlexible(p.end) }.getOrNull() ?: return@mapNotNull null
        if (!e.isAfter(s)) return@mapNotNull null
        ProgramWithTime(p, s, e)
    }.sortedBy { it.s }

    val nowP = parsed.firstOrNull { now.isAfter(it.s) && now.isBefore(it.e) }
    val nextP = parsed.firstOrNull { it.s.isAfter(now) }
    return nowP to nextP
}

fun formatClock12(i: Instant): String {
    val z = i.atZone(ZoneId.systemDefault())
    return z.format(DateTimeFormatter.ofPattern("h:mma", Locale.US))
}

fun epgDescription(p: EpgProgram): String {
    return listOf(p.description, p.desc, p.shortDesc, p.longDesc)
        .firstOrNull { !it.isNullOrBlank() }
        ?.trim()
        .orEmpty()
}

fun roundDownToHalfHour(instant: Instant): Instant {
    val zoned = instant.atZone(ZoneId.systemDefault())
    val minute = zoned.minute

    val rounded = when {
        minute < 30 -> zoned.withMinute(0).withSecond(0).withNano(0)
        else -> zoned.withMinute(30).withSecond(0).withNano(0)
    }

    return Instant.ofEpochSecond(rounded.toEpochSecond())
}

fun roundUpToHalfHour(instant: Instant): Instant {
    val zoned = instant.atZone(ZoneId.systemDefault())
    val minute = zoned.minute

    val rounded = when {
        minute == 0 -> return instant
        minute <= 30 -> zoned.withMinute(30).withSecond(0).withNano(0)
        else -> zoned.plusHours(1).withMinute(0).withSecond(0).withNano(0)
    }

    return Instant.ofEpochSecond(rounded.toEpochSecond())
}

fun absUrl(u: String?): String? {
    if (u.isNullOrBlank()) return null
    if (u.startsWith("http://") || u.startsWith("https://")) return u
    val base = ApiClient.BASE_URL.trimEnd('/')
    val path = if (u.startsWith("/")) u else "/$u"
    return base + path
}
fun clampProgramsStatic(
    programs: List<EpgProgram>,
    windowStart: Instant,
    windowEnd: Instant
): List<ClampedProgram> {
    return programs.mapNotNull { p ->
        val ps = runCatching { parseInstantFlexible(p.start) }.getOrNull() ?: return@mapNotNull null
        val pe = runCatching { parseInstantFlexible(p.end) }.getOrNull() ?: return@mapNotNull null

        val s = maxInstant(ps, windowStart)
        val e = minInstant(pe, windowEnd)
        if (!e.isAfter(s)) return@mapNotNull null

        val title = if (p.title.isBlank()) "(sin título)" else p.title

        // isCurrent lo dejamos falso: no lo estás usando para estilo ahora mismo
        ClampedProgram(p, s, e, title, isCurrent = false)
    }.sortedBy { it.startInstant }
}
