package com.reybel.ellentv.ui

import com.reybel.ellentv.data.api.EpgGridItem
import com.reybel.ellentv.data.api.EpgGridResponse
import com.reybel.ellentv.data.api.EpgProgram
import com.reybel.ellentv.data.api.EpgWindow
import java.time.Duration
import java.time.Instant
import java.util.LinkedHashMap
import java.util.LinkedHashSet

fun mergeEpgGrids(
    current: EpgGridResponse?,
    incoming: EpgGridResponse,
    preserveExistingWindow: Boolean = true
): EpgGridResponse {
    if (current == null) return incoming

    val currentStart = parseInstantFlexible(current.window.start)
    val currentEnd = parseInstantFlexible(current.window.end)
    val incomingStart = parseInstantFlexible(incoming.window.start)
    val incomingEnd = parseInstantFlexible(incoming.window.end)

    val keepOldStart = preserveExistingWindow &&
        currentStart.isBefore(incomingStart) &&
        Duration.between(currentStart, incomingStart).toMinutes() <= 30

    val mergedStartInstant = when {
        incomingStart.isBefore(currentStart) -> incomingStart
        keepOldStart -> currentStart
        else -> incomingStart
    }

    val mergedEndInstant = maxInstant(currentEnd, incomingEnd)

    val mergedWindow = EpgWindow(
        start = when {
            incomingStart.isBefore(currentStart) -> incoming.window.start
            keepOldStart -> current.window.start
            else -> incoming.window.start
        },
        end = if (incomingEnd.isAfter(currentEnd)) incoming.window.end else current.window.end
    )

    val mergedItems = mergeGridItems(
        current = current,
        incoming = incoming,
        windowStart = mergedStartInstant,
        windowEnd = mergedEndInstant
    )

    val mergedGrid = incoming.copy(
        window = mergedWindow,
        count = mergedItems.size,
        items = mergedItems
    )

    if (mergedGrid.window == current.window && mergedGrid.items == current.items) {
        return current
    }

    return mergedGrid
}

fun epgWindowDurationMinutes(grid: EpgGridResponse): Long {
    val start = parseInstantFlexible(grid.window.start)
    val end = parseInstantFlexible(grid.window.end)
    return Duration.between(start, end).toMinutes().coerceAtLeast(1)
}

private fun mergeGridItems(
    current: EpgGridResponse,
    incoming: EpgGridResponse,
    windowStart: Instant,
    windowEnd: Instant
): List<EpgGridItem> {
    val currentMap = current.items.associateBy { it.liveId }
    val incomingMap = incoming.items.associateBy { it.liveId }

    val mergedIds = LinkedHashSet<String>().apply {
        addAll(current.items.map { it.liveId })
        addAll(incoming.items.map { it.liveId })
    }

    return mergedIds.map { liveId ->
        val existing = currentMap[liveId]
        val fresh = incomingMap[liveId]
        val base = fresh ?: existing!!

        val mergedPrograms = mergePrograms(
            existing?.programs.orEmpty(),
            fresh?.programs.orEmpty(),
            windowStart,
            windowEnd
        )

        base.copy(
            name = fresh?.name ?: existing?.name ?: base.name,
            logo = fresh?.logo ?: existing?.logo,
            epgSourceId = fresh?.epgSourceId ?: existing?.epgSourceId,
            programs = mergedPrograms
        )
    }
}

private fun mergePrograms(
    existing: List<EpgProgram>,
    fresh: List<EpgProgram>,
    windowStart: Instant,
    windowEnd: Instant
): List<EpgProgram> {
    if (existing.isEmpty()) return fresh
    if (fresh.isEmpty()) return existing

    val merged = LinkedHashMap<String, EpgProgram>()

    fun add(list: List<EpgProgram>, preferFresh: Boolean) {
        list.forEach { program ->
            val key = "${program.start}__${program.end}"
            if (preferFresh || !merged.containsKey(key)) {
                merged[key] = program
            }
        }
    }

    add(existing, preferFresh = false)
    add(fresh, preferFresh = true)

    return merged.values
        .filter { program ->
            val s = runCatching { parseInstantFlexible(program.start) }.getOrNull() ?: return@filter true
            val e = runCatching { parseInstantFlexible(program.end) }.getOrNull() ?: return@filter true
            val clampedStart = maxInstant(s, windowStart)
            val clampedEnd = minInstant(e, windowEnd)
            clampedEnd.isAfter(clampedStart)
        }
        .sortedBy { program ->
            runCatching { parseInstantFlexible(program.start) }.getOrElse { windowStart }
        }
}
