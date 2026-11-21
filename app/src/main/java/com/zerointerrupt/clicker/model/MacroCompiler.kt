package com.zerointerrupt.clicker.model

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

data class MacroRecordedPoint(
    val tMs: Long,
    val x: Float,
    val y: Float
)

data class MacroRecordedStroke(
    val id: Int,
    val downMs: Long,
    val upMs: Long,
    val points: List<MacroRecordedPoint>
)

data class MacroCompiledPlan(
    val suggestedBaseNumber: Int,
    val mainPreset: ClickPreset,
    val subPresets: List<ClickPreset>,
    val totalDurationMs: Long,
    val rawStrokeCount: Int,
    val mergedContinuousHolds: Int,
    val screenW: Int,
    val screenH: Int
) {
    fun withCustomMainName(newName: String): MacroCompiledPlan {
        val clean = newName.trim().ifEmpty { mainPreset.name }
        val updatedSubs = subPresets.mapIndexed { idx, sub ->
            sub.copy(name = "$clean.${idx + 1}", isFrozen = true)
        }
        val updatedMain = mainPreset.copy(name = clean, isFrozen = false)
        return copy(mainPreset = updatedMain, subPresets = updatedSubs)
    }

    fun withLoopCount(loopCount: Int): MacroCompiledPlan {
        val safe = loopCount.coerceIn(ClickTarget.MIN_REPEAT_COUNT, ClickTarget.MAX_REPEAT_COUNT)
        return copy(mainPreset = mainPreset.copy(presetLoopCount = safe))
    }
}

/**
 * Compiles raw physical evdev touch strokes into a human-readable
 * SEQUENTIAL parent ClickPreset ("Plan N") referencing CONCURRENT child sub-presets ("Plan N.1", ...).
 *
 * - Stationary clicks and long-presses use ActionType.HOLD (retaining independent press & gap timings).
 * - Multi-finger overlapping intervals become CONCURRENT sub-presets inserted as ActionType.SUB_PRESET steps.
 * - Continuous holds spanning across consecutive slices use 0ms stage gaps so hardware slots stay pressed
 *   seamlessly without brief touch disconnects.
 */
object MacroCompiler {

    private const val TAP_MAX_NET_DIST_PX = 18f
    private const val SWIPE_MIN_NET_DIST_PX = 8f
    private const val SWIPE_MIN_PATH_LEN_PX = 14f
    private const val CURVE_SPATIOTEMPORAL_EPS_PX = 1.8f
    private const val MAX_SWIPE_VERTICES = 256
    private const val COINCIDENT_ALIGN_MS = 8L
    private const val MIN_SLICE_MS = 8L
    private const val DEFAULT_TAIL_GAP_MS = 120L

    private data class PrimitiveSeg(
        val strokeId: Int,
        val parentStrokeId: Int,
        val isSwipe: Boolean,
        val isMultiSegment: Boolean = false,
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        var startMs: Long,
        var endMs: Long
    ) {
        fun lerpPoint(atMs: Long): Pair<Float, Float> {
            val total = (endMs - startMs).coerceAtLeast(1L).toFloat()
            val t = ((atMs - startMs).toFloat() / total).coerceIn(0f, 1f)
            return Pair(x1 + (x2 - x1) * t, y1 + (y2 - y1) * t)
        }
    }

    private sealed interface TimelineBlock {
        var gapAfterMs: Long

        data class Single(
            val primitiveId: Int,
            val actionType: ActionType,
            val x1: Float,
            val y1: Float,
            val x2: Float,
            val y2: Float,
            val durationMs: Long,
            var repeatCount: Int = 1,
            override var gapAfterMs: Long = 0L,
            val carryInFromPrev: Boolean = false,
            val carryOutToNext: Boolean = false,
            val disableJitter: Boolean = false
        ) : TimelineBlock

        data class ConcurrentSlice(
            val durationMs: Long,
            val items: MutableList<SliceItem>,
            override var gapAfterMs: Long = 0L
        ) : TimelineBlock
    }

    private data class SliceItem(
        val primitiveId: Int,
        val actionType: ActionType,
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val pressMs: Long,
        val intervalMs: Long,
        val repeatCount: Int,
        val disableJitter: Boolean = false
    )

    fun compileFromJson(
        rawJson: String,
        existingPresets: List<ClickPreset>,
        fallbackScreenW: Int,
        fallbackScreenH: Int,
        microJitter: Boolean = true
    ): MacroCompiledPlan? {
        if (rawJson.isBlank()) return null
        val obj = try {
            JSONObject(rawJson)
        } catch (_: Throwable) {
            return null
        }
        val screenW = obj.optInt("screenW", fallbackScreenW).coerceAtLeast(1)
        val screenH = obj.optInt("screenH", fallbackScreenH).coerceAtLeast(1)
        val arr = obj.optJSONArray("strokes") ?: return null

        val strokes = mutableListOf<MacroRecordedStroke>()
        for (i in 0 until arr.length()) {
            val sObj = arr.optJSONObject(i) ?: continue
            val ptsArr = sObj.optJSONArray("points")
            val pts = mutableListOf<MacroRecordedPoint>()
            if (ptsArr != null) {
                for (j in 0 until ptsArr.length()) {
                    val pObj = ptsArr.optJSONObject(j) ?: continue
                    pts += MacroRecordedPoint(
                        tMs = pObj.optLong("t", 0L).coerceAtLeast(0L),
                        x = pObj.optDouble("x", 0.0).toFloat(),
                        y = pObj.optDouble("y", 0.0).toFloat()
                    )
                }
            }
            if (pts.isEmpty()) continue
            val downMs = sObj.optLong("downMs", pts.first().tMs).coerceAtLeast(0L)
            val upMs = sObj.optLong("upMs", pts.last().tMs).coerceAtLeast(downMs + 8L)
            strokes += MacroRecordedStroke(
                id = sObj.optInt("id", i + 1),
                downMs = downMs,
                upMs = upMs,
                points = pts
            )
        }
        if (strokes.isEmpty()) return null

        val baseNum = nextPresetNumber(existingPresets)
        return compileStrokes(
            strokes = strokes,
            baseNumber = baseNum,
            screenW = screenW,
            screenH = screenH,
            microJitter = microJitter
        )
    }

    private fun nextPresetNumber(existingPresets: List<ClickPreset>): Int =
        ClickConfigStore.nextMainPresetNumber(existingPresets)

    private fun compileStrokes(
        strokes: List<MacroRecordedStroke>,
        baseNumber: Int,
        screenW: Int,
        screenH: Int,
        microJitter: Boolean
    ): MacroCompiledPlan? {
        val sortedStrokes = strokes.sortedBy { it.downMs }
        val minStart = sortedStrokes.minOf { it.downMs }
        val normalized = sortedStrokes.map { s ->
            val shiftedPts = s.points.map { p ->
                p.copy(tMs = (p.tMs - minStart).coerceAtLeast(0L))
            }
            s.copy(
                downMs = (s.downMs - minStart).coerceAtLeast(0L),
                upMs = (s.upMs - minStart).coerceAtLeast((s.downMs - minStart) + 8L),
                points = shiftedPts
            )
        }

        val totalDurationMs = normalized.maxOf { it.upMs }.coerceAtLeast(8L)
        val primitives = mutableListOf<PrimitiveSeg>()
        for (stroke in normalized) {
            primitives += classifyStroke(stroke)
        }
        if (primitives.isEmpty()) return null

        alignCoincidentEndpoints(primitives)

        val blocks = buildEventSlices(primitives)
        if (blocks.isEmpty()) return null

        // Pack rhythmic multi-finger bursts cleanly before folding sequential repeats
        val packedBlocks = packPeriodicConcurrentBursts(blocks)
        val finalBlocks = foldSequentialStationaryRepeats(packedBlocks)

        // Ensure macro tail does not default to 0ms unwanted carry unless user manually configures 0ms
        finalBlocks.lastOrNull()?.let { last ->
            if (last.gapAfterMs <= 0L) {
                last.gapAfterMs = DEFAULT_TAIL_GAP_MS
            }
        }

        val mainName = "Plan $baseNumber"
        val subPresets = mutableListOf<ClickPreset>()
        val mainTargets = mutableListOf<ClickTarget>()
        var subIndex = 0
        var targetIdSeq = 1

        for (block in finalBlocks) {
            when (block) {
                is TimelineBlock.Single -> {
                    val gap = block.gapAfterMs.coerceIn(ClickTarget.MIN_STAGE_GAP_MS, ClickTarget.MAX_INTERVAL_MS)
                    val target = if (block.actionType == ActionType.SWIPE) {
                        ClickTarget(
                            id = targetIdSeq++,
                            index = mainTargets.size + 1,
                            x = block.x1,
                            y = block.y1,
                            intervalMs = gap,
                            pressDurationMs = ClickTarget.DEFAULT_PRESS_DURATION_MS,
                            microJitter = false,
                            enabled = true,
                            actionType = ActionType.SWIPE,
                            endX = block.x2,
                            endY = block.y2,
                            swipeDurationMs = block.durationMs.coerceIn(ClickTarget.MIN_SWIPE_MS, ClickTarget.MAX_SWIPE_MS),
                            repeatCount = block.repeatCount.coerceIn(1, ClickTarget.MAX_REPEAT_COUNT)
                        )
                    } else {
                        ClickTarget(
                            id = targetIdSeq++,
                            index = mainTargets.size + 1,
                            x = block.x1,
                            y = block.y1,
                            intervalMs = gap,
                            pressDurationMs = block.durationMs.coerceIn(ClickTarget.MIN_PRESS_MS, ClickTarget.MAX_PRESS_MS),
                            microJitter = false,
                            enabled = true,
                            actionType = ActionType.HOLD,
                            repeatCount = block.repeatCount.coerceIn(1, ClickTarget.MAX_REPEAT_COUNT)
                        )
                    }
                    target.setStartPixel(target.x, target.y, screenW, screenH)
                    target.setEndPixel(target.endX, target.endY, screenW, screenH)
                    mainTargets += target
                }

                is TimelineBlock.ConcurrentSlice -> {
                    subIndex++
                    val tempSubId = "macro_sub_$subIndex"
                    val subTargets = mutableListOf<ClickTarget>()
                    block.items.forEachIndexed { childIdx, item ->
                        val child = if (item.actionType == ActionType.SWIPE) {
                            ClickTarget(
                                id = childIdx + 1,
                                index = childIdx + 1,
                                x = item.x1,
                                y = item.y1,
                                intervalMs = item.intervalMs.coerceIn(ClickTarget.MIN_INTERVAL_MS, ClickTarget.MAX_INTERVAL_MS),
                                pressDurationMs = ClickTarget.DEFAULT_PRESS_DURATION_MS,
                                microJitter = false,
                                enabled = true,
                                actionType = ActionType.SWIPE,
                                endX = item.x2,
                                endY = item.y2,
                                swipeDurationMs = item.pressMs.coerceIn(ClickTarget.MIN_SWIPE_MS, ClickTarget.MAX_SWIPE_MS),
                                repeatCount = item.repeatCount.coerceIn(0, ClickTarget.MAX_REPEAT_COUNT)
                            )
                        } else {
                            ClickTarget(
                                id = childIdx + 1,
                                index = childIdx + 1,
                                x = item.x1,
                                y = item.y1,
                                intervalMs = item.intervalMs.coerceIn(ClickTarget.MIN_INTERVAL_MS, ClickTarget.MAX_INTERVAL_MS),
                                pressDurationMs = item.pressMs.coerceIn(ClickTarget.MIN_PRESS_MS, ClickTarget.MAX_PRESS_MS),
                                microJitter = false,
                                enabled = true,
                                actionType = ActionType.HOLD,
                                repeatCount = item.repeatCount.coerceIn(0, ClickTarget.MAX_REPEAT_COUNT)
                            )
                        }
                        child.setStartPixel(child.x, child.y, screenW, screenH)
                        child.setEndPixel(child.endX, child.endY, screenW, screenH)
                        subTargets += child
                    }
                    val subPreset = ClickPreset(
                        id = tempSubId,
                        name = "$mainName.$subIndex",
                        executionMode = ExecutionMode.CONCURRENT,
                        isFrozen = true,
                        isRecordedMacro = true,
                        targets = subTargets
                    )
                    subPresets += subPreset

                    val step = ClickTarget(
                        id = targetIdSeq++,
                        index = mainTargets.size + 1,
                        x = screenW * 0.5f,
                        y = screenH * 0.5f,
                        intervalMs = block.gapAfterMs.coerceIn(ClickTarget.MIN_STAGE_GAP_MS, ClickTarget.MAX_INTERVAL_MS),
                        pressDurationMs = ClickTarget.DEFAULT_PRESS_DURATION_MS,
                        microJitter = false,
                        enabled = true,
                        actionType = ActionType.SUB_PRESET,
                        repeatCount = 1,
                        refPresetId = tempSubId,
                        subDurationMs = block.durationMs.coerceIn(ClickTarget.MIN_SUB_DURATION_MS, ClickTarget.MAX_SUB_DURATION_MS)
                    )
                    mainTargets += step
                }
            }
        }

        if (mainTargets.isEmpty()) return null

        val mainPreset = ClickPreset(
            id = "macro_main",
            name = mainName,
            executionMode = ExecutionMode.SEQUENTIAL,
            isRecordedMacro = true,
            targets = mainTargets
        )
        val tempAll = subPresets + mainPreset
        val mergedHolds = ClickConfigStore.findSeamlessMergeStepIds(
            presets = tempAll,
            activePreset = mainPreset,
            screenW = screenW,
            screenH = screenH
        ).size

        return MacroCompiledPlan(
            suggestedBaseNumber = baseNumber,
            mainPreset = mainPreset,
            subPresets = subPresets,
            totalDurationMs = totalDurationMs,
            rawStrokeCount = normalized.size,
            mergedContinuousHolds = mergedHolds,
            screenW = screenW,
            screenH = screenH
        )
    }

    private fun classifyStroke(stroke: MacroRecordedStroke): List<PrimitiveSeg> {
        val rawPts = stroke.points
        if (rawPts.isEmpty()) return emptyList()

        // Preserve monotonic real timestamps bounded by [downMs, upMs]
        val pts = ArrayList<MacroRecordedPoint>(rawPts.size + 2)
        val firstRaw = rawPts.first()
        if (firstRaw.tMs > stroke.downMs) {
            pts += MacroRecordedPoint(stroke.downMs, firstRaw.x, firstRaw.y)
        }
        var prevT = stroke.downMs
        for (p in rawPts) {
            val clampedT = p.tMs.coerceIn(prevT, max(prevT, stroke.upMs))
            if (pts.isEmpty() || clampedT != pts.last().tMs || p.x != pts.last().x || p.y != pts.last().y) {
                pts += MacroRecordedPoint(clampedT, p.x, p.y)
                prevT = clampedT
            }
        }
        val lastPt = pts.last()
        val targetUpMs = max(stroke.downMs + ClickTarget.MIN_SWIPE_MS, stroke.upMs)
        if (lastPt.tMs < targetUpMs) {
            pts += MacroRecordedPoint(targetUpMs, lastPt.x, lastPt.y)
        }

        val first = pts.first()
        val last = pts.last()

        var pathLen = 0f
        for (i in 1 until pts.size) {
            pathLen += hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y)
        }
        val netDist = hypot(last.x - first.x, last.y - first.y)

        if (netDist < SWIPE_MIN_NET_DIST_PX && pathLen < SWIPE_MIN_PATH_LEN_PX) {
            // Stationary click / hold: use centroid across points for crisp aim accuracy
            val cx = pts.map { it.x }.average().toFloat()
            val cy = pts.map { it.y }.average().toFloat()
            return listOf(
                PrimitiveSeg(
                    strokeId = stroke.id,
                    parentStrokeId = stroke.id,
                    isSwipe = false,
                    isMultiSegment = false,
                    x1 = cx,
                    y1 = cy,
                    x2 = cx,
                    y2 = cy,
                    startMs = stroke.downMs,
                    endMs = max(stroke.downMs + 8L, stroke.upMs)
                )
            )
        }

        if (pts.size <= 2) {
            return listOf(
                PrimitiveSeg(
                    strokeId = stroke.id,
                    parentStrokeId = stroke.id,
                    isSwipe = true,
                    isMultiSegment = false,
                    x1 = first.x,
                    y1 = first.y,
                    x2 = last.x,
                    y2 = last.y,
                    startMs = first.tMs,
                    endMs = max(first.tMs + ClickTarget.MIN_SWIPE_MS, last.tMs)
                )
            )
        }

        // High-fidelity 3D Spatio-Temporal Douglas-Peucker: fits both spatial curves and
        // speed variations (accelerations, decelerations, pauses) using real timestamps.
        var eps = CURVE_SPATIOTEMPORAL_EPS_PX
        var simplified = spatioTemporalRdp(pts, eps)
        while (simplified.size > MAX_SWIPE_VERTICES && eps < 48f) {
            eps *= 1.35f
            simplified = spatioTemporalRdp(pts, eps)
        }

        val timedVertices = ArrayList<MacroRecordedPoint>(simplified.size)
        timedVertices += first
        for (i in 1 until simplified.lastIndex) {
            val cand = simplified[i]
            if ((cand.tMs - timedVertices.last().tMs) >= ClickTarget.MIN_SWIPE_MS &&
                (last.tMs - cand.tMs) >= ClickTarget.MIN_SWIPE_MS
            ) {
                timedVertices += cand
            }
        }
        timedVertices += last

        if (timedVertices.size <= 2) {
            return listOf(
                PrimitiveSeg(
                    strokeId = stroke.id,
                    parentStrokeId = stroke.id,
                    isSwipe = true,
                    isMultiSegment = false,
                    x1 = timedVertices.first().x,
                    y1 = timedVertices.first().y,
                    x2 = timedVertices.last().x,
                    y2 = timedVertices.last().y,
                    startMs = timedVertices.first().tMs,
                    endMs = max(timedVertices.first().tMs + ClickTarget.MIN_SWIPE_MS, timedVertices.last().tMs)
                )
            )
        }

        val segCount = timedVertices.size - 1
        val result = ArrayList<PrimitiveSeg>(segCount)
        for (i in 0 until segCount) {
            val a = timedVertices[i]
            val b = timedVertices[i + 1]
            result += PrimitiveSeg(
                strokeId = stroke.id * 1000 + i,
                parentStrokeId = stroke.id,
                isSwipe = true,
                isMultiSegment = true,
                x1 = a.x,
                y1 = a.y,
                x2 = b.x,
                y2 = b.y,
                startMs = a.tMs,
                endMs = max(a.tMs + ClickTarget.MIN_SWIPE_MS, b.tMs)
            )
        }
        return result
    }

    private fun alignCoincidentEndpoints(primitives: MutableList<PrimitiveSeg>) {
        if (primitives.size < 2) return
        val groups = LinkedHashMap<Int, MutableList<Int>>()
        for (i in primitives.indices) {
            groups.getOrPut(primitives[i].parentStrokeId) { mutableListOf() }.add(i)
        }
        if (groups.size < 2) return

        val rawPts = mutableListOf<Long>()
        for (indices in groups.values) {
            rawPts += primitives[indices.first()].startMs
            rawPts += primitives[indices.last()].endMs
        }
        rawPts.sort()
        val anchors = mutableListOf<Long>()
        for (t in rawPts) {
            if (anchors.isEmpty() || abs(t - anchors.last()) > COINCIDENT_ALIGN_MS) {
                anchors += t
            }
        }
        fun snap(t: Long): Long = anchors.minByOrNull { abs(it - t) } ?: t
        for (indices in groups.values) {
            val firstSeg = primitives[indices.first()]
            val firstMinDur = if (firstSeg.isSwipe) ClickTarget.MIN_SWIPE_MS else 8L
            val ns = snap(firstSeg.startMs)
            if (ns <= firstSeg.endMs - firstMinDur) {
                firstSeg.startMs = ns
            }
            val lastSeg = primitives[indices.last()]
            val lastMinDur = if (lastSeg.isSwipe) ClickTarget.MIN_SWIPE_MS else 8L
            val ne = snap(lastSeg.endMs)
            if (ne >= lastSeg.startMs + lastMinDur) {
                lastSeg.endMs = ne
            }
        }
    }

    private fun buildEventSlices(primitives: List<PrimitiveSeg>): MutableList<TimelineBlock> {
        val points = sortedSetOf<Long>()
        for (p in primitives) {
            points += p.startMs
            points += p.endMs
        }
        val boundaries = points.toList()
        val rawBlocks = mutableListOf<TimelineBlock>()

        for (i in 0 until boundaries.size - 1) {
            val tA = boundaries[i]
            val tB = boundaries[i + 1]
            val spanDur = tB - tA
            if (spanDur <= 0L) continue

            val active = primitives.filter { it.startMs <= tA && it.endMs >= tB }
            if (active.isEmpty()) {
                val prev = rawBlocks.lastOrNull()
                if (prev != null) {
                    prev.gapAfterMs = (prev.gapAfterMs + spanDur).coerceAtMost(ClickTarget.MAX_INTERVAL_MS)
                }
                continue
            }

            if (active.size == 1) {
                val p = active[0]
                if (!p.isSwipe && spanDur < MIN_SLICE_MS && rawBlocks.isNotEmpty()) {
                    rawBlocks.last().gapAfterMs += spanDur
                    continue
                }
                val (sx, sy) = if (p.isSwipe) p.lerpPoint(tA) else Pair(p.x1, p.y1)
                val (ex, ey) = if (p.isSwipe) p.lerpPoint(tB) else Pair(p.x2, p.y2)
                rawBlocks += TimelineBlock.Single(
                    primitiveId = p.strokeId,
                    actionType = if (p.isSwipe) ActionType.SWIPE else ActionType.HOLD,
                    x1 = sx,
                    y1 = sy,
                    x2 = ex,
                    y2 = ey,
                    durationMs = spanDur,
                    repeatCount = 1,
                    gapAfterMs = 0L,
                    carryInFromPrev = !p.isSwipe && (p.startMs < tA),
                    carryOutToNext = !p.isSwipe && (p.endMs > tB),
                    disableJitter = p.isMultiSegment
                )
            } else {
                val items = active.map { p ->
                    val (sx, sy) = if (p.isSwipe) p.lerpPoint(tA) else Pair(p.x1, p.y1)
                    val (ex, ey) = if (p.isSwipe) p.lerpPoint(tB) else Pair(p.x2, p.y2)
                    SliceItem(
                        primitiveId = p.strokeId,
                        actionType = if (p.isSwipe) ActionType.SWIPE else ActionType.HOLD,
                        x1 = sx,
                        y1 = sy,
                        x2 = ex,
                        y2 = ey,
                        pressMs = spanDur.coerceIn(if (p.isSwipe) ClickTarget.MIN_SWIPE_MS else 8L, 30_000L),
                        intervalMs = 60L,
                        repeatCount = if (p.isSwipe) 1 else 0,
                        disableJitter = p.isMultiSegment
                    )
                }.toMutableList()

                rawBlocks += TimelineBlock.ConcurrentSlice(
                    durationMs = max(ClickTarget.MIN_SUB_DURATION_MS, spanDur),
                    items = items,
                    gapAfterMs = 0L
                )
            }
        }
        return rawBlocks
    }

    /**
     * Compresses patterns where 1 anchor finger holds continuously while 1 secondary finger
     * performs periodic taps (CONCURRENT[A+B] -> SINGLE[A] -> CONCURRENT[A+B] ...) into a single
     * compact concurrent sub-preset containing HOLD(A, repeat=0) + HOLD(B, press, interval, repeat=N).
     */
    private fun packPeriodicConcurrentBursts(blocks: MutableList<TimelineBlock>): MutableList<TimelineBlock> {
        if (blocks.size < 3) return blocks
        val out = mutableListOf<TimelineBlock>()
        var i = 0
        while (i < blocks.size) {
            val first = blocks[i]
            if (first !is TimelineBlock.ConcurrentSlice || first.items.size != 2 || first.gapAfterMs > 0L) {
                out += first
                i++
                continue
            }
            val holdCarrier = first.items.firstOrNull { it.actionType == ActionType.HOLD && it.repeatCount == 0 }
            val tapItem = first.items.firstOrNull { it !== holdCarrier && it.actionType == ActionType.HOLD }
            if (holdCarrier == null || tapItem == null) {
                out += first
                i++
                continue
            }

            // Try consuming alternating pattern: Concurrent(A+B) -> Single(A) -> Concurrent(A+B) ...
            val pressDurs = mutableListOf(first.durationMs)
            val gapDurs = mutableListOf<Long>()
            var totalBurstDur = first.durationMs
            var cursor = i + 1
            var validEndIdx = -1

            while (cursor + 1 < blocks.size) {
                val mid = blocks[cursor]
                val next = blocks[cursor + 1]
                if (mid !is TimelineBlock.Single ||
                    mid.actionType != ActionType.HOLD ||
                    mid.primitiveId != holdCarrier.primitiveId ||
                    mid.gapAfterMs > 0L
                ) break
                if (next !is TimelineBlock.ConcurrentSlice ||
                    next.items.size != 2 ||
                    next.gapAfterMs > 0L && cursor + 2 < blocks.size
                ) break

                val nextCarrier = next.items.firstOrNull { it.primitiveId == holdCarrier.primitiveId }
                val nextTap = next.items.firstOrNull {
                    it !== nextCarrier &&
                        it.actionType == ActionType.HOLD &&
                        hypot(it.x1 - tapItem.x1, it.y1 - tapItem.y1) <= TAP_MAX_NET_DIST_PX
                }
                if (nextCarrier == null || nextTap == null) break

                gapDurs += mid.durationMs
                pressDurs += next.durationMs
                totalBurstDur += mid.durationMs + next.durationMs
                validEndIdx = cursor + 1
                cursor += 2
                if (next.gapAfterMs > 0L) break
            }

            if (validEndIdx > i && pressDurs.size >= 2 && isApproximatelyUniform(pressDurs) && isApproximatelyUniform(gapDurs)) {
                val avgPress = pressDurs.average().roundToLong().coerceIn(ClickTarget.MIN_PRESS_MS, ClickTarget.MAX_PRESS_MS)
                val avgGap = gapDurs.average().roundToLong().coerceIn(ClickTarget.MIN_INTERVAL_MS, ClickTarget.MAX_INTERVAL_MS)
                val trailingGap = blocks[validEndIdx].gapAfterMs
                out += TimelineBlock.ConcurrentSlice(
                    durationMs = max(ClickTarget.MIN_SUB_DURATION_MS, totalBurstDur),
                    items = mutableListOf(
                        holdCarrier.copy(repeatCount = 0),
                        tapItem.copy(
                            pressMs = avgPress,
                            intervalMs = avgGap,
                            repeatCount = pressDurs.size
                        )
                    ),
                    gapAfterMs = trailingGap
                )
                i = validEndIdx + 1
                continue
            }

            out += first
            i++
        }
        return out
    }

    private fun foldSequentialStationaryRepeats(blocks: MutableList<TimelineBlock>): MutableList<TimelineBlock> {
        if (blocks.size < 2) return blocks
        val out = mutableListOf<TimelineBlock>()
        var i = 0
        while (i < blocks.size) {
            val head = blocks[i]
            if (head !is TimelineBlock.Single ||
                head.actionType != ActionType.HOLD ||
                head.carryInFromPrev ||
                head.carryOutToNext ||
                head.gapAfterMs < ClickTarget.MIN_INTERVAL_MS
            ) {
                out += head
                i++
                continue
            }

            val group = mutableListOf(head)
            var j = i + 1
            while (j < blocks.size) {
                val cand = blocks[j]
                if (cand !is TimelineBlock.Single ||
                    cand.actionType != ActionType.HOLD ||
                    cand.carryInFromPrev ||
                    cand.carryOutToNext
                ) break
                val dist = hypot(cand.x1 - head.x1, cand.y1 - head.y1)
                if (dist > TAP_MAX_NET_DIST_PX) break
                if (!withinTolerance(cand.durationMs, head.durationMs, 0.25, 12L)) break
                val prevGap = group.last().gapAfterMs
                if (j + 1 < blocks.size && blocks[j].gapAfterMs > 0L &&
                    !withinTolerance(cand.gapAfterMs, prevGap, 0.25, 16L)
                ) break
                group += cand
                j++
            }

            if (group.size >= 2) {
                val avgPress = group.map { it.durationMs }.average().roundToLong()
                    .coerceIn(ClickTarget.MIN_PRESS_MS, ClickTarget.MAX_PRESS_MS)
                val innerGaps = group.dropLast(1).map { it.gapAfterMs }
                val avgGap = if (innerGaps.isNotEmpty()) {
                    innerGaps.average().roundToLong().coerceIn(ClickTarget.MIN_INTERVAL_MS, ClickTarget.MAX_INTERVAL_MS)
                } else head.gapAfterMs
                val folded = head.copy(
                    durationMs = avgPress,
                    repeatCount = group.size,
                    gapAfterMs = group.last().gapAfterMs.takeIf { it > 0L } ?: avgGap
                )
                out += folded
                i = j
            } else {
                out += head
                i++
            }
        }
        return out
    }

    private fun isApproximatelyUniform(values: List<Long>): Boolean {
        if (values.size <= 1) return true
        val avg = values.average().coerceAtLeast(1.0)
        return values.all { abs(it - avg) <= max(25.0, avg * 0.38) }
    }

    private fun withinTolerance(a: Long, b: Long, ratio: Double, floorMs: Long): Boolean {
        val diff = abs(a - b).toDouble()
        val limit = max(floorMs.toDouble(), max(a, b) * ratio)
        return diff <= limit
    }

    private fun spatioTemporalError(
        p: MacroRecordedPoint,
        a: MacroRecordedPoint,
        b: MacroRecordedPoint
    ): Float {
        val dt = (b.tMs - a.tMs).coerceAtLeast(0L)
        if (dt <= 0L) {
            return hypot(p.x - a.x, p.y - a.y)
        }
        val alpha = ((p.tMs - a.tMs).toFloat() / dt.toFloat()).coerceIn(0f, 1f)
        val predX = a.x + alpha * (b.x - a.x)
        val predY = a.y + alpha * (b.y - a.y)
        return hypot(p.x - predX, p.y - predY)
    }

    private fun spatioTemporalRdp(pts: List<MacroRecordedPoint>, epsilon: Float): List<MacroRecordedPoint> {
        if (pts.size <= 2) return pts
        val keep = BooleanArray(pts.size)
        keep[0] = true
        keep[pts.lastIndex] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(Pair(0, pts.lastIndex))
        while (stack.isNotEmpty()) {
            val (sIdx, eIdx) = stack.removeLast()
            if (eIdx - sIdx <= 1) continue
            val a = pts[sIdx]
            val b = pts[eIdx]
            var maxErr = -1f
            var maxIdx = -1
            for (i in (sIdx + 1) until eIdx) {
                val err = spatioTemporalError(pts[i], a, b)
                if (err > maxErr) {
                    maxErr = err
                    maxIdx = i
                }
            }
            if (maxErr > epsilon && maxIdx > sIdx) {
                keep[maxIdx] = true
                stack.addLast(Pair(sIdx, maxIdx))
                stack.addLast(Pair(maxIdx, eIdx))
            }
        }
        val out = ArrayList<MacroRecordedPoint>()
        for (i in pts.indices) {
            if (keep[i]) out += pts[i]
        }
        return out
    }
}
