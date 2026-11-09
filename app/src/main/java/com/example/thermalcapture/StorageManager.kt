package com.example.thermalcapture

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Всё сохранение фото/видео: MediaStore, /ThermalCapture, копирование.
 * Вынесено из MainActivity для читаемости.
 */
class StorageManager(private val context: Context) {

    companion object {
        private const val PREFS = "ThermalCapturePrefs"
        private const val KEY_LOCATION = "storage_location"
        const val LOCATION_STANDARD = "standard"
        const val LOCATION_ROOT = "root"
        private const val SUBFOLDER_PHOTOS = "Photos"
        private const val SUBFOLDER_VIDEOS = "Videos"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getStorageLocation(): String? = prefs.getString(KEY_LOCATION, null)

    fun setStorageLocation(location: String) {
        prefs.edit().putString(KEY_LOCATION, location).apply()
    }

    fun isRootConfigured(): Boolean {
        return getStorageLocation() == LOCATION_ROOT &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                Environment.isExternalStorageManager()
    }

    /** Временная папка для mp4 до экспорта в галерею/корень. */
    fun getTempMovieDir(): File {
        return context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.cacheDir
    }

    fun makeTempMovieFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(getTempMovieDir(), "REC_$timeStamp.mp4")
    }

    /** Сохранить фото в галерею или корень. */
    fun savePhoto(bitmap: android.graphics.Bitmap) {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "IMG_$timeStamp.jpg"

        try {
            if (isRootConfigured()) {
                val rootDir = File(Environment.getExternalStorageDirectory(), "ThermalCapture/$SUBFOLDER_PHOTOS").apply { mkdirs() }
                val file = File(rootDir, fileName)
                FileOutputStream(file).use { out ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
                }
                MediaScannerConnection.scanFile(
                    context, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null
                )
                Toast.makeText(context, "Фото сохранено: ${file.name}", Toast.LENGTH_SHORT).show()
            } else {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/ThermalCapture")
                    }
                }
                val uri: Uri? = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out)
                    }
                    Toast.makeText(context, "Фото сохранено в Галерею: $fileName", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Ошибка сохранения фото: ${e.message}", Toast.LENGTH_SHORT).show()
        } finally {
            bitmap.recycle()
        }
    }

    /** Экспорт готового временного mp4 в галерею или корень. */
    fun exportVideo(sourceFile: File) {
        if (!sourceFile.exists() || sourceFile.length() <= 0) return
        val fileName = sourceFile.name

        try {
            if (isRootConfigured()) {
                val rootDir = File(Environment.getExternalStorageDirectory(), "ThermalCapture/$SUBFOLDER_VIDEOS").apply { mkdirs() }
                val destFile = File(rootDir, fileName)
                sourceFile.inputStream().use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
                sourceFile.delete()
                MediaScannerConnection.scanFile(
                    context, arrayOf(destFile.absolutePath), arrayOf("video/mp4"), null
                )
                Toast.makeText(context, "Видео сохранено: $fileName", Toast.LENGTH_SHORT).show()
            } else {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ThermalCapture")
                    }
                }
                val uri: Uri? = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        sourceFile.inputStream().use { it.copyTo(out) }
                    }
                    sourceFile.delete()
                    Toast.makeText(context, "Видео сохранено в Галерею: $fileName", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Ошибка экспорта видео: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** Запрос MANAGE_EXTERNAL_STORAGE для режима "своя папка". */
    fun requestAllFilesPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            try {
                val intent = android.content.Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (_: Exception) {}
        }
    }
}
