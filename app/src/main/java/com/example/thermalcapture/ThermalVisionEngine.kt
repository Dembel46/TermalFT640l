package com.example.thermalcapture

import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class DetectedTarget(
    val id: Int,
    val bounds: RectF,          // Координаты рамки 0.0..1.0
    val maxLuma: Int,           // Яркость 0..255
    val estimatedTemp: Float,   // Температура в °C
    val isMoving: Boolean,      // Движение
    val isThermalSource: Boolean // Источник: true - тепловизор, false - оптика
)

class ThermalVisionEngine {

    var tempSlope: Float = 0.392f
    var tempOffset: Float = -10.0f
    var contrastThreshold: Int = 18

    private val procW = 160
    private val procH = 120
    private val totalPixels = procW * procH

    private val currentLuma = IntArray(totalPixels)
    private val prevLuma = IntArray(totalPixels)
    private val openedLuma = IntArray(totalPixels)
    private val candidateMask = BooleanArray(totalPixels)
    private val visited = BooleanArray(totalPixels)

    private var nextTargetId = 1
    private val prevTargets = mutableListOf<DetectedTarget>()
    private var hasPrevFrame = false

    fun calibrate(luma1: Int, temp1: Float, luma2: Int, temp2: Float) {
        if (luma1 != luma2) {
            tempSlope = (temp2 - temp1) / (luma2 - luma1)
            tempOffset = temp1 - tempSlope * luma1
        }
    }

    fun calculateTemp(luma: Int): Float {
        return tempSlope * luma + tempOffset
    }

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
        val absoluteHotThreshold = max(135, avgSceneLuma + 35)

        // 2. Морфология Top-Hat (ядро 7x7)
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

        // 3. Выявление кандидатов (Top-Hat ИЛИ яркие горячие пятна)
        for (i in 0 until totalPixels) {
            val localDiff = currentLuma[i] - openedLuma[i]
            val isTopHatHot = localDiff > contrastThreshold
            val isAbsoluteHot = currentLuma[i] >= absoluteHotThreshold

            candidateMask[i] = isTopHatHot || isAbsoluteHot
            visited[i] = false
        }

        // 4. Поиск связанных областей (BFS)
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

                        val l = currentLuma[qy * procW + qx]
                        if (l > maxL) maxL = l

                        checkAndPush(qx + 1, qy)
                        checkAndPush(qx - 1, qy)
                        checkAndPush(qx, qy + 1)
                        checkAndPush(qx, qy - 1)
                    }

                    if (count in 3..2500) {
                        rawBlobs.add(RawBlob(minX, minY, maxX, maxY, maxL))
                    }
                }
            }
        }

        // 5. Детекция движения и формирование списка целей
        val currentTargets = mutableListOf<DetectedTarget>()
        for (blob in rawBlobs) {
            var motionEnergy = 0
            if (hasPrevFrame) {
                for (by in blob.minY..blob.maxY) {
                    val r = by * procW
                    for (bx in blob.minX..blob.maxX) {
                        val d = abs(currentLuma[r + bx] - prevLuma[r + bx])
                        if (d > 14) motionEnergy++
                    }
                }
            }
            val isMoving = motionEnergy > 2

            val normRect = RectF(
                blob.minX.toFloat() / procW,
                blob.minY.toFloat() / procH,
                blob.maxX.toFloat() / procW,
                blob.maxY.toFloat() / procH
            )

            val temp = calculateTemp(blob.maxLuma)

            var assignedId = nextTargetId++
            for (prev in prevTargets) {
                val dx = abs(prev.bounds.centerX() - normRect.centerX())
                val dy = abs(prev.bounds.centerY() - normRect.centerY())
                if (dx < 0.12f && dy < 0.12f) {
                    assignedId = prev.id
                    break
                }
            }

            currentTargets.add(
                DetectedTarget(
                    id = assignedId,
                    bounds = normRect,
                    maxLuma = blob.maxLuma,
                    estimatedTemp = temp,
                    isMoving = isMoving,
                    isThermalSource = isThermal
                )
            )
        }

        System.arraycopy(currentLuma, 0, prevLuma, 0, totalPixels)
        hasPrevFrame = true
        prevTargets.clear()
        prevTargets.addAll(currentTargets)

        return currentTargets
    }

    private data class RawBlob(
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int,
        val maxLuma: Int
    )
}
