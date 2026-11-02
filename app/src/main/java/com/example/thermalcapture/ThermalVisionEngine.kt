package com.example.thermalcapture

import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class DetectedTarget(
    val id: Int,
    val bounds: RectF,          // Координаты рамки (нормализованные 0.0..1.0)
    val maxLuma: Int,           // Пиковая яркость (0..255)
    val estimatedTemp: Float,   // Расчетная температура в °C
    val isMoving: Boolean       // Флаг движения
)

class ThermalVisionEngine {

    // Коэффициенты формулы температуры: T = tempSlope * Luma + tempOffset
    // По умолчанию настроено на диапазон типичного тепловизора: Luma 0 -> -10°C, Luma 255 -> +90°C
    var tempSlope: Float = 0.392f
    var tempOffset: Float = -10.0f

    // Порог выделения контрастной точки (Top-Hat threshold)
    var contrastThreshold: Int = 35

    // Разрешение сетки анализа (для обеспечения скорости 30 FPS без нагрузки на процессор)
    private val procW = 160
    private val procH = 120
    private val totalPixels = procW * procH

    private val currentLuma = IntArray(totalPixels)
    private val prevLuma = IntArray(totalPixels)
    private val openedLuma = IntArray(totalPixels)
    private val topHatMask = BooleanArray(totalPixels)
    private val visited = BooleanArray(totalPixels)

    private var nextTargetId = 1
    private val prevTargets = mutableListOf<DetectedTarget>()
    private var hasPrevFrame = false

    /**
     * Двухточечная калибровка температуры по двум известным опорным объектам
     */
    fun calibrate(luma1: Int, temp1: Float, luma2: Int, temp2: Float) {
        if (luma1 != luma2) {
            tempSlope = (temp2 - temp1) / (luma2 - luma1)
            tempOffset = temp1 - tempSlope * luma1
        }
    }

    /**
     * Расчет температуры по яркости пикселя (0..255)
     */
    fun calculateTemp(luma: Int): Float {
        return tempSlope * luma + tempOffset
    }

    /**
     * Анализ кадра: морфология Top-Hat + детекция движения + трекинг
     */
    fun processFrame(frameBitmap: Bitmap): List<DetectedTarget> {
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

        // 1. Быстрое извлечение канала яркости Luma
        for (i in 0 until totalPixels) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            currentLuma[i] = (r * 77 + g * 150 + b * 29) shr 8
        }

        // 2. Морфологическая операция Top-Hat (White Top-Hat = Original - Opening)
        // Эрозия (ядро 5x5)
        val kRadius = 2
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

        // Дилатация (восстановление фона)
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

        // 3. Вычитание фона: выявляем яркие контрастные микро-цели
        for (i in 0 until totalPixels) {
            val diff = currentLuma[i] - openedLuma[i]
            topHatMask[i] = diff > contrastThreshold
            visited[i] = false
        }

        // 4. Поиск связных областей (Blob Extraction / Bounding Boxes)
        val rawBlobs = mutableListOf<RawBlob>()
        val queueX = IntArray(1024)
        val queueY = IntArray(1024)

        for (y in 2 until procH - 2) {
            val row = y * procW
            for (x in 2 until procW - 2) {
                val idx = row + x
                if (topHatMask[idx] && !visited[idx]) {
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
                    var pixelCount = 0

                    while (head < tail && tail < 1020) {
                        val qx = queueX[head]
                        val qy = queueY[head]
                        head++
                        pixelCount++

                        minX = min(minX, qx)
                        maxX = max(maxX, qx)
                        minY = min(minY, qy)
                        maxY = max(maxY, qy)
                        val l = currentLuma[qy * procW + qx]
                        if (l > maxL) maxL = l

                        // Обход 4 соседей
                        val neighbors = arrayOf(Pair(qx + 1, qy), Pair(qx - 1, qy), Pair(qx, qy + 1), Pair(qx, qy - 1))
                        for (nb in neighbors) {
                            val nx = nb.first
                            val ny = nb.second
                            if (nx in 0 until procW && ny in 0 until procH) {
                                val nIdx = ny * procW + nx
                                if (topHatMask[nIdx] && !visited[nIdx]) {
                                    visited[nIdx] = true
                                    queueX[tail] = nx
                                    queueY[tail] = ny
                                    tail++
                                }
                            }
                        }
                    }

                    // Фильтр размера: отсекаем одиночные шумы (меньше 3 px) и гигантские засветки (больше 400 px)
                    if (pixelCount in 3..400) {
                        rawBlobs.add(RawBlob(minX, minY, maxX, maxY, maxL))
                    }
                }
            }
        }

        // 5. Анализ движения (межкадровая разность)
        val currentTargets = mutableListOf<DetectedTarget>()
        for (blob in rawBlobs) {
            var motionEnergy = 0
            if (hasPrevFrame) {
                for (by in blob.minY..blob.maxY) {
                    val r = by * procW
                    for (bx in blob.minX..blob.maxX) {
                        val d = abs(currentLuma[r + bx] - prevLuma[r + bx])
                        if (d > 18) motionEnergy++
                    }
                }
            }
            val isMoving = motionEnergy > 3

            // Нормализация координат рамки в диапазон 0.0..1.0
            val normRect = RectF(
                blob.minX.toFloat() / procW,
                blob.minY.toFloat() / procH,
                blob.maxX.toFloat() / procW,
                blob.maxY.toFloat() / procH
            )

            val temp = calculateTemp(blob.maxLuma)

            // Простой трекинг по близости центроидов
            var assignedId = nextTargetId++
            for (prev in prevTargets) {
                val dx = abs(prev.bounds.centerX() - normRect.centerX())
                val dy = abs(prev.bounds.centerY() - normRect.centerY())
                if (dx < 0.08f && dy < 0.08f) {
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
                    isMoving = isMoving
                )
            )
        }

        // Сохраняем текущий кадр для следующего шага
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
