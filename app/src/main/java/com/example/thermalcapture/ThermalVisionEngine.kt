package com.example.thermalcapture

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class DetectedTarget(
    val id: Int,
    val bounds: RectF,                  // Координаты 0.0..1.0
    val silhouettePoints: List<PointF>, // Точки силуэтного контура 0.0..1.0
    val widthPx: Int,                   // Ширина в пикселях матрицы анализа
    val heightPx: Int,                  // Высота в пикселях матрицы анализа
    val contrastScore: Float,           // Контрастный вес цели
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

    // Буферы для быстрого построения силуэта по строкам
    private val rowMinX = IntArray(procH)
    private val rowMaxX = IntArray(procH)

    private var nextTargetId = 1
    private val prevTargets = mutableListOf<DetectedTarget>()
    private var hasPrevFrame = false

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

        // 1. Морфологическое вычитание фона Top-Hat (ядро 7x7)
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

                    // Сброс границ строк для текущего объекта
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

                        // Запоминаем крайние левые и правые точки каждой строки для силуэта
                        if (qx < rowMinX[qy]) rowMinX[qy] = qx
                        if (qx > rowMaxX[qy]) rowMaxX[qy] = qx

                        val l = currentLuma[qy * procW + qx]
                        if (l > maxL) maxL = l

                        checkAndPush(qx + 1, qy)
                        checkAndPush(qx - 1, qy)
                        checkAndPush(qx, qy + 1)
                        checkAndPush(qx, qy - 1)
                    }

                    // Построение замкнутого силуэтного контура
                    val silhouette = mutableListOf<PointF>()
                    val bw = maxX - minX + 1
                    val bh = maxY - minY + 1

                    // Силуэт строим, если объект больше 9x9
                    if (bw > 9 || bh > 9) {
                        // Правый край сверху вниз
                        for (ry in minY..maxY) {
                            val rx = rowMaxX[ry]
                            if (rx >= 0) {
                                silhouette.add(PointF(rx.toFloat() / procW, ry.toFloat() / procH))
                            }
                        }
                        // Левый край снизу вверх
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

        // 3. Анализ движения (только движущиеся цели)
        val movingTargets = mutableListOf<DetectedTarget>()
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

                val normRect = RectF(
                    blob.minX.toFloat() / procW,
                    blob.minY.toFloat() / procH,
                    blob.maxX.toFloat() / procW,
                    blob.maxY.toFloat() / procH
                )

                var assignedId = nextTargetId++
                for (prev in prevTargets) {
                    val dx = abs(prev.bounds.centerX() - normRect.centerX())
                    val dy = abs(prev.bounds.centerY() - normRect.centerY())
                    if (dx < 0.12f && dy < 0.12f) {
                        assignedId = prev.id
                        break
                    }
                }

                movingTargets.add(
                    DetectedTarget(
                        id = assignedId,
                        bounds = normRect,
                        silhouettePoints = blob.silhouettePoints,
                        widthPx = bw,
                        heightPx = bh,
                        contrastScore = contrast,
                        isMoving = true,
                        isThermalSource = isThermal
                    )
                )
            }
        }

        System.arraycopy(currentLuma, 0, prevLuma, 0, totalPixels)
        hasPrevFrame = true

        val topTargets = movingTargets.sortedByDescending { it.contrastScore }.take(3)
        prevTargets.clear()
        prevTargets.addAll(topTargets)

        return topTargets
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
