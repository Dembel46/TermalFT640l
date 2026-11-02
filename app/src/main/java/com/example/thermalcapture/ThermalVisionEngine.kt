package com.example.thermalcapture

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.abs

data class DetectedTarget(
    val id: Int,
    val bounds: RectF,
    val silhouettePoints: List<PointF>,
    val widthPx: Int,
    val heightPx: Int,
    val contrastScore: Float,
    val isMoving: Boolean,
    val isThermalSource: Boolean
)

class ThermalVisionEngine {

    private val procW = 160
    private val procH = 120
    private val totalPixels = procW * procH

    private val currentLuma = IntArray(totalPixels)
    private val prevLuma = IntArray(totalPixels)
    private val openedLuma = IntArray(totalPixels)
    private val candidateMask = BooleanArray(totalPixels)
    private val visited = BooleanArray(totalPixels)

    private val rowMinX = IntArray(procH)
    private val rowMaxX = IntArray(procH)

    private var nextTargetId = 1
    private val targetHistory = mutableMapOf<Int, TrackedHistory>()
    private var hasPrevFrame = false

    private class TrackedHistory(
        var smoothedBounds: RectF,
        var smoothedSilhouette: List<PointF>,
        var lastCentroidX: Float,
        var lastCentroidY: Float,
        var holdCounter: Int
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

        var sumLuma = 0L
        for (i in 0 until totalPixels) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val l = (r * 77 + g * 150 + b * 29) shr 8
            currentLuma[i] = l
            sumLuma += l
        }

        val avgSceneLuma = (sumLuma / totalPixels).toInt()

        // 1. Вычитание фона Top-Hat (ядро 7x7)
        val kRadius = 3
        val eroded = IntArray(totalPixels)
        for (y in kRadius until procH - kRadius) {
            val row = y * procW
            for (x in kRadius until procW - kRadius) {
                var minVal = 255
                for (dy in -kRadius..kRadius) {
                    val kRow = (y + dy) * procW
                    for (dx in -kRadius..kRadius) {
                        val v = currentLuma[kRow + x + dx]
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

        val minDiff = 25
        for (i in 0 until totalPixels) {
            val localDiff = currentLuma[i] - openedLuma[i]
            val isHighContrast = localDiff >= minDiff && currentLuma[i] > (avgSceneLuma + 15)
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
                    var maxL = currentLuma[idx]
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

                        val l = currentLuma[qy * procW + qx]
                        if (l > maxL) maxL = l

                        checkAndPush(qx + 1, qy)
                        checkAndPush(qx - 1, qy)
                        checkAndPush(qx, qy + 1)
                        checkAndPush(qx, qy - 1)
                    }

                    val silhouette = mutableListOf<PointF>()
                    val bw = maxX - minX + 1
                    val bh = maxY - minY + 1

                    if (bw > 9 || bh > 9) {
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
                    }

                    if (count in 3..2500) {
                        rawBlobs.add(RawBlob(minX, minY, maxX, maxY, maxL, silhouette))
                    }
                }
            }
        }

        // 3. Анализ движения
        val movingTargets = mutableListOf<DetectedTarget>()
        val activeIds = mutableSetOf<Int>()

        for (blob in rawBlobs) {
            var motionPixels = 0
            if (hasPrevFrame) {
                for (by in blob.minY..blob.maxY) {
                    val r = by * procW
                    for (bx in blob.minX..blob.maxX) {
                        val d = abs(currentLuma[r + bx] - prevLuma[r + bx])
                        if (d > 16) motionPixels++
                    }
                }
            }

            val isMoving = motionPixels >= 2

            if (isMoving) {
                val bw = blob.maxX - blob.minX + 1
                val bh = blob.maxY - blob.minY + 1
                val contrast = (blob.maxLuma - avgSceneLuma).toFloat()

                val rawBounds = RectF(
                    blob.minX.toFloat() / procW,
                    blob.minY.toFloat() / procH,
                    blob.maxX.toFloat() / procW,
                    blob.maxY.toFloat() / procH
                )

                // Поиск соответствия с предыдущим кадром
                var assignedId = -1
                var bestDist = 0.12f
                for ((id, hist) in targetHistory) {
                    val dx = abs(hist.smoothedBounds.centerX() - rawBounds.centerX())
                    val dy = abs(hist.smoothedBounds.centerY() - rawBounds.centerY())
                    val dist = dx + dy
                    if (dist < bestDist) {
                        bestDist = dist
                        assignedId = id
                    }
                }

                if (assignedId == -1) {
                    assignedId = nextTargetId++
                }
                activeIds.add(assignedId)

                // Инерционное сглаживание рамок и контура (устраняет дергание)
                val hist = targetHistory[assignedId]
                val finalBounds: RectF
                val finalSilhouette: List<PointF>

                if (hist != null) {
                    // Плавное следование за целью (EMA 70/30)
                    hist.smoothedBounds.left += (rawBounds.left - hist.smoothedBounds.left) * 0.35f
                    hist.smoothedBounds.top += (rawBounds.top - hist.smoothedBounds.top) * 0.35f
                    hist.smoothedBounds.right += (rawBounds.right - hist.smoothedBounds.right) * 0.35f
                    hist.smoothedBounds.bottom += (rawBounds.bottom - hist.smoothedBounds.bottom) * 0.35f
                    finalBounds = RectF(hist.smoothedBounds)

                    val curCentroidX = finalBounds.centerX()
                    val curCentroidY = finalBounds.centerY()
                    val dx = curCentroidX - hist.lastCentroidX
                    val dy = curCentroidY - hist.lastCentroidY
                    hist.lastCentroidX = curCentroidX
                    hist.lastCentroidY = curCentroidY

                    // Контур обновляется с задержкой (раз в 4 кадра ~350 мс), а между ними плавно сдвигается за объектом
                    hist.holdCounter++
                    if (hist.holdCounter >= 4 || hist.smoothedSilhouette.isEmpty()) {
                        hist.holdCounter = 0
                        hist.smoothedSilhouette = blob.silhouettePoints
                    } else {
                        hist.smoothedSilhouette = hist.smoothedSilhouette.map { PointF(it.x + dx, it.y + dy) }
                    }
                    finalSilhouette = hist.smoothedSilhouette
                } else {
                    finalBounds = RectF(rawBounds)
                    finalSilhouette = blob.silhouettePoints
                    targetHistory[assignedId] = TrackedHistory(
                        RectF(rawBounds),
                        blob.silhouettePoints,
                        rawBounds.centerX(),
                        rawBounds.centerY(),
                        0
                    )
                }

                movingTargets.add(
                    DetectedTarget(
                        id = assignedId,
                        bounds = finalBounds,
                        silhouettePoints = finalSilhouette,
                        widthPx = bw,
                        heightPx = bh,
                        contrastScore = contrast,
                        isMoving = true,
                        isThermalSource = isThermal
                    )
                )
            }
        }

        // Очистка старых потерянных целей
        targetHistory.keys.retainAll(activeIds)

        System.arraycopy(currentLuma, 0, prevLuma, 0, totalPixels)
        hasPrevFrame = true

        return movingTargets.sortedByDescending { it.contrastScore }.take(3)
    }

    private data class RawBlob(
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int,
        val maxLuma: Int,
        val silhouettePoints: List<PointF>
    )
}
