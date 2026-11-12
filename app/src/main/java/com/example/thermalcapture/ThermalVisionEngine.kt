package com.example.thermalcapture

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class TargetType { MOVING, HUMAN_VEHICLE, OTHER }

data class DetectedTarget(
    val id: Int,
    val bounds: RectF,                 // нормализованные 0..1 координаты в кадре тепловизора
    val silhouettePoints: List<PointF>,// нормализованный контур (0..1)
    val widthPx: Int,                  // размер в пикселях исходного кадра (для порога «крупный»)
    val heightPx: Int,
    val contrastScore: Float,          // средняя motion energy (0..1)
    val type: TargetType,
    val isThermalSource: Boolean = true
)

class ThermalVisionEngine {

    companion object {
        private const val PROC_W = 160
        private const val PROC_H = 120
        private const val TOTAL = PROC_W * PROC_H

        // Порог движения по luma (0..255). Ниже — шум, выше — реальное движение.
        private const val BASE_MOTION_THRESHOLD = 14f
        // Множитель адаптивного шумового порога (median abs diff * k)
        private const val NOISE_FACTOR = 3.0f
        // Минимальная площадь компоненты (в пикселях proc-сетки) — отсекает шум
        private const val MIN_AREA = 8
        // Минимальная «энергия движения» компоненты (сумма diff / площадь)
        private const val MIN_MOTION_ENERGY = 10f
        // Сколько кадров трек живёт без обновления
        private const val MAX_MISSED_FRAMES = 6
        // Смещение (в норм. координатах), после которого трек считается MOVING
        private const val MOVING_DISPLACEMENT = 0.012f
        // «Крупный» объект для HUMAN_VEHICLE (доля площади кадра)
        private const val LARGE_AREA_FRAC = 0.012f
    }

    private val currentLuma = IntArray(TOTAL)
    private val prevLuma = IntArray(TOTAL)
    private val blurred = IntArray(TOTAL)
    private val diffMask = BooleanArray(TOTAL)
    private val morphMask = BooleanArray(TOTAL)
    private val visited = BooleanArray(TOTAL)
    private val queue = IntArray(TOTAL) // BFS-очередь

    private var hasPrev = false
    private var nextId = 1

    private class TrackState(
        val id: Int,
        var cx: Float,            // норм. 0..1
        var cy: Float,
        var lastCx: Float,
        var lastCy: Float,
        var missed: Int = 0,
        var type: TargetType = TargetType.OTHER,
        var smoothedBounds: RectF = RectF(),
        var smoothedSilhouette: List<PointF> = emptyList()
    )

    private val tracks = mutableMapOf<Int, TrackState>()

    /** Главный вход. frameBitmap — любой Bitmap (палитра не важна, берём luma). */
    fun processFrame(frameBitmap: Bitmap): List<DetectedTarget> {
        if (frameBitmap.width <= 0 || frameBitmap.height <= 0) return emptyList()

        // 1) downscale + luma
        val scaled = Bitmap.createScaledBitmap(frameBitmap, PROC_W, PROC_H, false)
        val pixels = IntArray(TOTAL)
        scaled.getPixels(pixels, 0, PROC_W, 0, 0, PROC_W, PROC_H)
        scaled.recycle()

        for (i in 0 until TOTAL) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            // luma по Rec.601 — устойчиво к палитре (яркость остаётся яркостью)
            currentLuma[i] = (r * 77 + g * 150 + b * 29) shr 8
        }

        // 2) лёгкое размытие 3x3 (подавление одиночного шума)
        boxBlur3x3(currentLuma, blurred)

        if (!hasPrev) {
            blurred.copyInto(prevLuma)
            hasPrev = true
            return emptyList()
        }

        // 3) адаптивный порог по медианному шуму кадра
        val noiseMedian = medianAbsDiff(blurred, prevLuma)
        val threshold = max(BASE_MOTION_THRESHOLD, noiseMedian * NOISE_FACTOR)

        // 4) маска движения
        for (i in 0 until TOTAL) {
            diffMask[i] = abs(blurred[i] - prevLuma[i]) > threshold
        }

        // 5) морфология: erosion затем dilation (открытие) — убирает точки
        morphOpen(diffMask, morphMask)

        // 6) BFS-компоненты
        visited.fill(false)
        val rawComponents = mutableListOf<Component>()

        for (start in 0 until TOTAL) {
            if (!morphMask[start] || visited[start]) continue
            val comp = bfsComponent(start)
            if (comp.area >= MIN_AREA && comp.motionEnergy >= MIN_MOTION_ENERGY) {
                rawComponents.add(comp)
            }
        }

        // 7) обновление треков
        val nowTargets = associateAndBuildTargets(rawComponents, frameBitmap.width, frameBitmap.height)

        // 8) сохранить текущий кадр как prev
        blurred.copyInto(prevLuma)

        return nowTargets
    }

    // ---------- внутреннее ----------

    private fun boxBlur3x3(src: IntArray, dst: IntArray) {
        for (y in 0 until PROC_H) {
            for (x in 0 until PROC_W) {
                var sum = 0
                var cnt = 0
                for (dy in -1..1) {
                    val yy = y + dy
                    if (yy < 0 || yy >= PROC_H) continue
                    for (dx in -1..1) {
                        val xx = x + dx
                        if (xx < 0 || xx >= PROC_W) continue
                        sum += src[yy * PROC_W + xx]
                        cnt++
                    }
                }
                dst[y * PROC_W + x] = sum / cnt
            }
        }
    }

    /** Медиана |a-b| по кадру — оценка шума. */
    private fun medianAbsDiff(a: IntArray, b: IntArray): Float {
        // Для скорости — сэмплируем каждый 4-й пиксель
        val samples = IntArray(TOTAL / 4 + 1)
        var n = 0
        var i = 0
        while (i < TOTAL) {
            samples[n++] = abs(a[i] - b[i])
            i += 4
        }
        val arr = samples.copyOf(n)
        arr.sort()
        return if (n == 0) 0f else arr[n / 2].toFloat()
    }

    private fun morphOpen(src: BooleanArray, dst: BooleanArray) {
        // erosion
        val tmp = BooleanArray(TOTAL)
        for (y in 0 until PROC_H) {
            for (x in 0 until PROC_W) {
                val idx = y * PROC_W + x
                if (!src[idx]) { tmp[idx] = false; continue }
                var all = true
                for (dy in -1..1) {
                    val yy = y + dy
                    if (yy !in 0 until PROC_H) { all = false; break }
                    for (dx in -1..1) {
                        val xx = x + dx
                        if (xx !in 0 until PROC_W) { all = false; break }
                        if (!src[yy * PROC_W + xx]) { all = false; break }
                    }
                    if (!all) break
                }
                tmp[idx] = all
            }
        }
        // dilation
        for (y in 0 until PROC_H) {
            for (x in 0 until PROC_W) {
                val idx = y * PROC_W + x
                if (tmp[idx]) { dst[idx] = true; continue }
                var any = false
                for (dy in -1..1) {
                    val yy = y + dy
                    if (yy !in 0 until PROC_H) continue
                    for (dx in -1..1) {
                        val xx = x + dx
                        if (xx !in 0 until PROC_W) continue
                        if (tmp[yy * PROC_W + xx]) { any = true; break }
                    }
                    if (any) break
                }
                dst[idx] = any
            }
        }
    }

    private class Component(
        val minX: Int, val maxX: Int, val minY: Int, val maxY: Int,
        val area: Int, val motionEnergy: Float,
        val cxNorm: Float, val cyNorm: Float,
        val silhouette: List<PointF>
    ) {
        val widthNorm: Float get() = (maxX - minX + 1).toFloat() / PROC_W
        val heightNorm: Float get() = (maxY - minY + 1).toFloat() / PROC_H
    }

    private fun bfsComponent(start: Int): Component {
        var head = 0
        var tail = 0
        queue[tail++] = start
        visited[start] = true

        var minX = PROC_W; var maxX = 0
        var minY = PROC_H; var maxY = 0
        var area = 0
        var sumEnergy = 0f
        var sumX = 0f; var sumY = 0f
        val boundary = mutableListOf<PointF>()

        while (head < tail) {
            val idx = queue[head++]
            val x = idx % PROC_W
            val y = idx / PROC_W
            area++
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
            sumX += x; sumY += y
            sumEnergy += abs(blurred[idx] - prevLuma[idx])

            // граничные точки (для силуэта) — только крайние
            if (x == 0 || x == PROC_W - 1 || y == 0 || y == PROC_H - 1 ||
                !morphMask[idx - 1] || !morphMask[idx + 1] ||
                !morphMask[idx - PROC_W] || !morphMask[idx + PROC_W]
            ) {
                boundary.add(PointF(x.toFloat() / PROC_W, y.toFloat() / PROC_H))
            }

            for (dy in -1..1) {
                val yy = y + dy
                if (yy !in 0 until PROC_H) continue
                for (dx in -1..1) {
                    val xx = x + dx
                    if (xx !in 0 until PROC_W) continue
                    val nIdx = yy * PROC_W + xx
                    if (!visited[nIdx] && morphMask[nIdx]) {
                        visited[nIdx] = true
                        queue[tail++] = nIdx
                    }
                }
            }
        }

        // Упростим силуэт: не более 24 точек, прореживание
        val simplified = if (boundary.size <= 24) boundary else {
            val step = boundary.size / 24
            (0 until 24).map { boundary[it * step] }
        }

        return Component(
            minX, maxX, minY, maxY,
            area,
            sumEnergy / area.coerceAtLeast(1),
            (sumX / area) / PROC_W,
            (sumY / area) / PROC_H,
            simplified
        )
    }

    private fun associateAndBuildTargets(
        components: List<Component>,
        frameW: Int, frameH: Int
    ): List<DetectedTarget> {
        // помечаем все треки как «не обновлённые»
        val usedTracks = mutableSetOf<Int>()
        val result = mutableListOf<DetectedTarget>()

        for (comp in components) {
            // ищем ближайший трек
            var bestId = -1
            var bestDist = Float.MAX_VALUE
            for ((id, tr) in tracks) {
                if (id in usedTracks) continue
                val d = hypot(tr.cx - comp.cxNorm, tr.cy - comp.cyNorm)
                if (d < bestDist && d < 0.08f) { // радиус ассоциации
                    bestDist = d; bestId = id
                }
            }

            val id: Int
            val track: TrackState
            if (bestId >= 0) {
                id = bestId
                track = tracks[id]!!
                usedTracks.add(id)
                val prevCx = track.cx
                val prevCy = track.cy
                track.lastCx = prevCx
                track.lastCy = prevCy
                track.cx = comp.cxNorm
                track.cy = comp.cyNorm
                track.missed = 0
            } else {
                id = nextId++
                track = TrackState(
                    id = id,
                    cx = comp.cxNorm, cy = comp.cyNorm,
                    lastCx = comp.cxNorm, lastCy = comp.cyNorm
                )
                tracks[id] = track
                usedTracks.add(id)
            }

            // сглаживание bounds и силуэта
            val newBounds = RectF(
                comp.minX.toFloat() / PROC_W,
                comp.minY.toFloat() / PROC_H,
                (comp.maxX + 1).toFloat() / PROC_W,
                (comp.maxY + 1).toFloat() / PROC_H
            )
            if (track.smoothedBounds.isEmpty) {
                track.smoothedBounds = newBounds
            } else {
                smoothRect(track.smoothedBounds, newBounds, 0.5f)
            }
            track.smoothedSilhouette = smoothSilhouette(track.smoothedSilhouette, comp.silhouette)

            // классификация
            val displacement = hypot(track.cx - track.lastCx, track.cy - track.lastCy)
            val areaFrac = comp.widthNorm * comp.heightNorm
            track.type = when {
                displacement > MOVING_DISPLACEMENT && areaFrac > LARGE_AREA_FRAC -> TargetType.HUMAN_VEHICLE
                displacement > MOVING_DISPLACEMENT -> TargetType.MOVING
                comp.motionEnergy > MIN_MOTION_ENERGY * 2 -> TargetType.MOVING
                else -> TargetType.OTHER
            }

            // размер в пикселях исходного кадра
            val wPx = (comp.widthNorm * frameW).toInt()
            val hPx = (comp.heightNorm * frameH).toInt()

            result.add(
                DetectedTarget(
                    id = id,
                    bounds = RectF(track.smoothedBounds),
                    silhouettePoints = track.smoothedSilhouette,
                    widthPx = wPx,
                    heightPx = hPx,
                    contrastScore = (comp.motionEnergy / 255f).coerceIn(0f, 1f),
                    type = track.type,
                    isThermalSource = true
                )
            )
        }

        // увеличить missed у неиспользованных треков и удалить старые
        val toRemove = mutableListOf<Int>()
        for ((id, tr) in tracks) {
            if (id !in usedTracks) {
                tr.missed++
                if (tr.missed > MAX_MISSED_FRAMES) toRemove.add(id)
            }
        }
        toRemove.forEach { tracks.remove(it) }

        return result
    }

    private fun smoothRect(dst: RectF, src: RectF, alpha: Float) {
        dst.left = dst.left * (1 - alpha) + src.left * alpha
        dst.top = dst.top * (1 - alpha) + src.top * alpha
        dst.right = dst.right * (1 - alpha) + src.right * alpha
        dst.bottom = dst.bottom * (1 - alpha) + src.bottom * alpha
    }

    private fun smoothSilhouette(old: List<PointF>, new: List<PointF>): List<PointF> {
        if (old.isEmpty() || old.size != new.size) return new
        val out = ArrayList<PointF>(new.size)
        for (i in new.indices) {
            out.add(
                PointF(
                    old[i].x * 0.5f + new[i].x * 0.5f,
                    old[i].y * 0.5f + new[i].y * 0.5f
                )
            )
        }
        return out
    }
}
