package com.hoststorm.livestorm

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LocalRecordingUtils {

    fun createTempFile(context: Context, prefix: String): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: File(context.filesDir, "movies")
        val folder = File(base, "LiveStorm")
        if (!folder.exists()) folder.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return File(folder, "${prefix}_${stamp}.mp4")
    }

    fun publish(context: Context, source: File, onComplete: (String) -> Unit) {
        Thread {
            val result = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    publishWithMediaStore(context, source)
                } else {
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(source.absolutePath),
                        arrayOf("video/mp4"),
                        null
                    )
                    source.absolutePath
                }
            }.getOrElse { "Falha ao salvar gravação: ${it.message ?: "erro desconhecido"}" }
            Handler(Looper.getMainLooper()).post { onComplete(result) }
        }.start()
    }

    private fun publishWithMediaStore(context: Context, source: File): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, source.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/LiveStorm")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("não foi possível criar o vídeo na galeria")
        try {
            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: error("não foi possível abrir o arquivo de destino")
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            source.delete()
            return "Movies/LiveStorm/${source.name}"
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }
}
