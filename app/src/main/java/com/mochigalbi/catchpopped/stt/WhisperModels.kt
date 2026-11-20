package com.mochigalbi.catchpopped.stt

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Whisper model files (ggml `.bin`) kept in the app's private storage. */
object WhisperModels {

    fun dir(context: Context): File = File(context.filesDir, "models").apply { mkdirs() }

    fun list(context: Context): List<File> =
        dir(context).listFiles { file -> file.isFile && file.name.endsWith(".bin") }
            ?.sortedBy { it.name }
            ?: emptyList()

    /** Short name for labels: `ggml-small.bin` → `small`. */
    fun label(name: String): String = name.removePrefix("ggml-").removeSuffix(".bin")

    /** Copies a model picked by the user into app storage. [onProgress] gets copied bytes. */
    suspend fun import(context: Context, uri: Uri, onProgress: (Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                ?: throw IOException("파일 이름을 읽지 못했습니다.")
            if (!name.endsWith(".bin")) throw IOException("whisper.cpp 모델(.bin)이 아닙니다: $name")

            val safeName = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val target = File(dir(context), safeName)
            val temp = File(dir(context), "$safeName.part")
            try {
                val input = resolver.openInputStream(uri) ?: throw IOException("파일을 열지 못했습니다.")
                input.use {
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(1 shl 20)
                        var copied = 0L
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            onProgress(copied)
                        }
                    }
                }
                if (target.exists()) target.delete()
                if (!temp.renameTo(target)) throw IOException("모델 파일을 저장하지 못했습니다.")
                target
            } finally {
                if (temp.exists()) temp.delete()
            }
        }
}
