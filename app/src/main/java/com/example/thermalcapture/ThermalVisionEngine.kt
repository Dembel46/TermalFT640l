package com.example.thermalcapture

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.hypot

enum class TargetType {
    MOVING,         // Движущийся объект (Красный)
    HUMAN_VEHICLE,  // Человек, техника или крупный объект (Бледно-желтый)
    OTHER           // Иные неподвижные тепловые объекты (Бледно-зеленый)
}

data class DetectedTarget(
    val id: Int,
    val bounds: RectF,
    val silhouettePoints: List<PointF>,
    val widthPx: Int,
    val heightPx: Int,
    val contrastScore: Float,
    val type: TargetType,
    val isThermalSource: Boolean
)

class ThermalVisionEngine {

    private val procW = 160
    private val procH = 120
    private val totalPixels = procW * procH

    private val rawLuma = IntArray(totalPixels)
    private val filteredLuma = IntArray(totalPixels)
    private val openedLuma = IntArray(totalPixels)
    private val candidateMask = BooleanArray(totalPixels)
    private val visited = BooleanArray(totalPixels)

    private val rowMinX = IntArray(procH)
    private val rowMaxX = IntArray(procH)

    private var nextTargetId = 1
    private val targetHistory = mutableMapOf<Int, TrackedObject>()

    private class TrackedObject(
        val id: Int,
        var anchorX: Float,
        var anchorY: Float,
        var lastX: Float,
        var lastY: Float,
        var smoothedBounds: RectF,
        var smoothedSilhouette: List<PointF>,
        var motionHoldFrames: Int = 0,
        var stationaryFrames: Int = 0,
        var holdShapeCounter: Int = 0
    )

    fun processFrame(frameBitmap: Bitmap, isThermal: Boolean): List<DetectedTarget> {
        val scaled = if (frameBitmap.width == procW && frameBitmap.height == procH) {
            frameBitmap
        } else {
            Bitmap.createScaledBitmap(frameBitmap, procW, procH, false)
        }

        val pixels = IntArray(totalPixels)
        scaled.getPixels(pixels, 0, procW, 0, 0, procW, procH)
        if (scaled != frameBitmap) {
            scaled.recycle()
        }

        for (i in 0 until totalPixels) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            rawLuma[i] = (r * 77 + g * 150 + b * 29) shr 8
        }

        // Фильтр 3x3 для сглаживания аналогового шума тепловизора
        var sumLuma = 0L
        for (y in 0 until procH) {
            val row = y * procW
            for (x in 0 until procW) {
                val idx = row + x
                val smooth = if (x in 1 until procW - 1 && y in 1 until procH - 1) {
                    (rawLuma[idx] * 4 +
                     rawLuma[idx - 1] + rawLuma[idx + 1] +
                     rawLuma[idx - procW] + rawLuma[idx + procW]) / 8
                } else {
                    rawLuma[idx]
                }
                filteredLuma[idx] = smooth
                sumLuma += smooth
            }
        }

        val avgSceneLuma = (sumLuma / totalPixels).toInt()

        // 1. Top-Hat морфология (ядро 7x7)
        val kRadius = 3
        val eroded = IntArray(totalPixels)
        for (y in kRadius until procH - kRadius) {
            val row = y * procW
            for (x in kRadius until procW - kRadius) {
                var minVal = 255
                for (dy in -kRadius..kRadius) {
                    val kRow = (y + dy) * procW
                    for (dx in -kRadius..kRadius) {
                        val v = filteredLuma[kRow + x + dx]
                        if (v < minVal) minVal = v
                    }
                }
                eroded[row + x] = minVal
            }
        }

        for (y in kRadius until procH - kRadius) {
            val row = y * procW
            for (x in kRadius until procW - kRadius) {
                var maxVal = 0
                for (dy in -kRadius..kRadius) {
                    val kRow = (y + dy) * procW
                    for (dx in -kRadius..kRadius) {
                        val v = eroded[kRow + x + dx]
                        if (v > maxVal) maxVal = v
                    }
                }
                openedLuma[row + x] = maxVal
            }
        }

        val minDiff = 22
        for (i in 0 until totalPixels) {
            val localDiff = filteredLuma[i] - openedLuma[i]
            val isHighContrast = localDiff >= minDiff && filteredLuma[i] > (avgSceneLuma + 12)
            candidateMask[i] = isHighContrast
            visited[i] = false
        }

        // 2. Кластеризация контуров (BFS)
        val rawBlobs = mutableListOf<RawBlob>()
        val queueX = IntArray(2048)
        val queueY = IntArray(2048)

        for (y in 2 until procH - 2) {
            val row = y * procW
            for (x in 2 until procW - 2) {
                val idx = row + x
                if (candidateMask[idx] && !visited[idx]) {
                    var head = 0
                    var tail = 0
                    queueX[tail] = x
                    queueY[tail] = y
                    tail++
                    visited[idx] = true

                    var minX = x
                    var maxX = x
                    var minY = y
                    var maxY = y
                    var maxL = filteredLuma[idx]
                    var count = 0

                    for (r in 0 until procH) {
                        rowMinX[r] = procW
                        rowMaxX[r] = -1
                    }

                    fun checkAndPush(nx: Int, ny: Int) {
                        if (nx in 0 until procW && ny in 0 until procH) {
                            val nIdx = ny * procW + nx
                            if (candidateMask[nIdx] && !visited[nIdx]) {
                                visited[nIdx] = true
                                if (tail < 2040) {
                                    queueX[tail] = nx
                                    queueY[tail] = ny
                                    tail++
                                }
                            }
                        }
                    }

                    while (head < tail && tail < 2040) {
                        val qx = queueX[head]
                        val qy = queueY[head]
                        head++
                        count++

                        if (qx < minX) minX = qx
                        if (qx > maxX) maxX = qx
                        if (qy < minY) minY = qy
                        if (qy > maxY) maxY = qy

                        if (qx < rowMinX[qy]) rowMinX[qy] = qx
                        if (qx > rowMaxX[qy]) rowMaxX[qy] = qx

                        val l = filteredLuma[qy * procW + qx]
                        if (l > maxL) maxL = l

                        checkAndPush(qx + 1, qy)
                        checkAndPush(qx - 1, qy)
                        checkAndPush(qx, qy + 1)
                        checkAndPush(qx, qy - 1)
                    }

                    val silhouette = mutableListOf<PointF>()
                    val bw = maxX - minX + 1
                    val bh = maxY - minY + 1

                    for (ry in minY..maxY) {
                        val rx = rowMaxX[ry]
                        if (rx >= 0) {
                            silhouette.add(PointF(rx.toFloat() / procW, ry.toFloat() / procH))
                        }
                    }
                    for (ry in maxY downTo minY) {
                        val lx = rowMinX[ry]
                        if (lx < procW) {
                            silhouette.add(PointF(lx.toFloat() / procW, ry.toFloat() / procH))
                        }
                    }

                    if (count in 4..2500) {
                        rawBlobs.add(RawBlob(minX, minY, maxX, maxY, maxL, count, silhouette))
                    }
                }
            }
        }

        // 3. Траекторный трекинг с защитой от аналогового шума и удержанием метки движения
        val finalTargets = mutableListOf<DetectedTarget>()
        val activeIds = mutableSetOf<Int>()

        for (blob in rawBlobs) {
            val bw = blob.maxX - blob.minX + 1
            val bh = blob.maxY - blob.minY + 1
            val contrast = (blob.maxLuma - avgSceneLuma).toFloat()

            val rawBounds = RectF(
                blob.minX.toFloat() / procW,
                blob.minY.toFloat() / procH,
                blob.maxX.toFloat() / procW,
                blob.maxY.toFloat() / procH
            )
            val curCenterX = rawBounds.centerX()
            val curCenterY = rawBounds.centerY()

            var assignedId = -1
            var bestDist = 0.15f
            for ((id, hist) in targetHistory) {
                val dist = hypot((hist.smoothedBounds.centerX() - curCenterX).toDouble(),
                                 (hist.smoothedBounds.centerY() - curCenterY).toDouble()).toFloat()
                if (dist < bestDist) {
                    bestDist = dist
                    assignedId = id
                }
            }

            if (assignedId == -1) {
                assignedId = nextTargetId++
            }
            activeIds.add(assignedId)

            var track = targetHistory[assignedId]
            if (track == null) {
                track = TrackedObject(
                    id = assignedId,
                    anchorX = curCenterX,
                    anchorY = curCenterY,
                    lastX = curCenterX,
                    lastY = curCenterY,
                    smoothedBounds = RectF(rawBounds),
                    smoothedSilhouette = blob.silhouettePoints
                )
                targetHistory[assignedId] = track
            }

            // Замер смещения центра масс
            val instDist = hypot((curCenterX - track.lastX).toDouble(), (curCenterY - track.lastY).toDouble()).toFloat()
            val anchorDist = hypot((curCenterX - track.anchorX).toDouble(), (curCenterY - track.anchorY).toDouble()).toFloat()

            track.lastX = curCenterX
            track.lastY = curCenterY

            // ДЕТЕКЦИЯ ДВИЖЕНИЯ: истинное перемещение объекта по экрану
            if (instDist > 0.007f || anchorDist > 0.018f) {
                track.motionHoldFrames = 30 // Удержание метки 3 секунды (30 кадров при 10 fps)
                track.stationaryFrames = 0
                track.anchorX = curCenterX
                track.anchorY = curCenterY
            } else {
                track.stationaryFrames++
                if (track.stationaryFrames > 8) {
                    track.anchorX = curCenterX
                    track.anchorY = curCenterY
                }
            }

            val isMoving = track.motionHoldFrames > 0
            if (track.motionHoldFrames > 0) {
                track.motionHoldFrames--
            }

            // Сглаживание координат рамки (EMA)
            track.smoothedBounds.left += (rawBounds.left - track.smoothedBounds.left) * 0.35f
            track.smoothedBounds.top += (rawBounds.top - track.smoothedBounds.top) * 0.35f
            track.smoothedBounds.right += (rawBounds.right - track.smoothedBounds.right) * 0.35f
            track.smoothedBounds.bottom += (rawBounds.bottom - track.smoothedBounds.bottom) * 0.35f

            // Сглаживание силуэта
            track.holdShapeCounter++
            if (track.holdShapeCounter >= 4 || track.smoothedSilhouette.isEmpty()) {
                track.holdShapeCounter = 0
                track.smoothedSilhouette = blob.silhouettePoints
            }

            val targetType = when {
                isMoving -> TargetType.MOVING // КРАСНЫЙ (при движении и в течение 3 сек после)
                (bh >= bw * 1.25f && bh >= 9) || (bw >= bh * 1.3f && bw >= 11) || blob.pixelCount >= 30 ->
                    TargetType.HUMAN_VEHICLE // БЛЕДНО-ЖЕЛТЫЙ
                else ->
                    TargetType.OTHER // БЛЕДНО-ЗЕЛЕНЫЙ
            }

            finalTargets.add(
                DetectedTarget(
                    id = assignedId,
                    bounds = RectF(track.smoothedBounds),
                    silhouettePoints = track.smoothedSilhouette,
                    widthPx = bw,
                    heightPx = bh,
                    contrastScore = contrast,
                    type = targetType,
                    isThermalSource = isThermal
                )
            )
        }

        targetHistory.keys.retainAll(activeIds)

        return finalTargets.sortedByDescending { it.contrastScore }.take(4)
    }

    private data class RawBlob(
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int,
        val maxLuma: Int,
        val pixelCount: Int,
        val silhouettePoints: List<PointF>
    )
}
