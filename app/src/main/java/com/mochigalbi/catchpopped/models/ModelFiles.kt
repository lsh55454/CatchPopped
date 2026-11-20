package com.mochigalbi.catchpopped.models

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Kinds of model files the app can import, by file extension. */
enum class ModelKind(val extension: String, val description: String) {
    WHISPER(".bin", "whisper.cpp 모델(.bin)"),
    LLM(".task", "MediaPipe LLM 모델(.task)"),
}

/** Model files kept in the app's private storage (`files/models`). */
object ModelFiles {

    fun dir(context: Context): File = File(context.filesDir, "models").apply { mkdirs() }

    fun list(context: Context, kind: ModelKind): List<File> =
        dir(context).listFiles { file -> file.isFile && file.name.endsWith(kind.extension) }
            ?.sortedBy { it.name }
            ?: emptyList()

    /** Short name for labels: `ggml-small.bin` → `small`, `gemma3-1b-it-int4.task` → `gemma3-1b-it-int4`. */
    fun label(name: String): String =
        name.removePrefix("ggml-").substringBeforeLast('.')

    /** Copies a model picked by the user into app storage. [onProgress] gets copied bytes. */
    suspend fun import(context: Context, uri: Uri, kind: ModelKind, onProgress: (Long) -> Unit): File =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                ?: throw IOException("파일 이름을 읽지 못했습니다.")
            if (!name.endsWith(kind.extension)) throw IOException("${kind.description}이 아닙니다: $name")

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
