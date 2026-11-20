package com.mochigalbi.catchpopped.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Stores ideas in a single JSON file inside the app's private storage.
 * Simple on purpose; to be replaced by Room once the data model settles.
 */
class IdeaRepository private constructor(private val file: File) {

    private val _ideas = MutableStateFlow(load())
    val ideas: StateFlow<List<Idea>> = _ideas.asStateFlow()

    fun find(id: Long): Idea? = _ideas.value.firstOrNull { it.id == id }

    @Synchronized
    fun add(idea: Idea): Idea {
        commit(listOf(idea) + _ideas.value)
        return idea
    }

    @Synchronized
    fun update(idea: Idea) {
        commit(_ideas.value.map { if (it.id == idea.id) idea else it })
    }

    /** Removes the idea and its audio files. */
    @Synchronized
    fun delete(id: Long) {
        val idea = find(id)
        commit(_ideas.value.filterNot { it.id == id })
        idea?.audioPath?.let { File(it).delete() }
        idea?.cleanAudioPath?.let { File(it).delete() }
    }

    private fun commit(list: List<Idea>) {
        _ideas.value = list
        save(list)
    }

    private fun load(): List<Idea> {
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                val status = runCatching { IdeaStatus.valueOf(item.optString("status", "DONE")) }
                    .getOrDefault(IdeaStatus.DONE)
                // Processing does not survive an app restart, so anything still marked
                // as in progress was interrupted.
                val interrupted = status == IdeaStatus.PROCESSING
                Idea(
                    id = item.getLong("id"),
                    createdAt = item.getLong("createdAt"),
                    durationMs = item.optLong("durationMs"),
                    transcript = item.optString("transcript"),
                    documentMd = item.stringOrNull("documentMd"),
                    audioPath = item.stringOrNull("audioPath"),
                    cleanAudioPath = item.stringOrNull("cleanAudioPath"),
                    status = if (interrupted) IdeaStatus.FAILED else status,
                    error = if (interrupted) "처리가 중단됐습니다. 다시 받아쓰기를 눌러 주세요." else item.stringOrNull("error"),
                    transcriptSource = item.stringOrNull("transcriptSource"),
                    documentSource = item.stringOrNull("documentSource"),
                )
            }
        } catch (e: Exception) {
            // Keep the unreadable file so nothing is lost when the next save overwrites it.
            runCatching { file.copyTo(File(file.parentFile, "ideas.broken.json"), overwrite = true) }
            emptyList()
        }
    }

    private fun save(list: List<Idea>) {
        val array = JSONArray()
        for (idea in list) {
            array.put(
                JSONObject()
                    .put("id", idea.id)
                    .put("createdAt", idea.createdAt)
                    .put("durationMs", idea.durationMs)
                    .put("transcript", idea.transcript)
                    .put("documentMd", idea.documentMd ?: JSONObject.NULL)
                    .put("audioPath", idea.audioPath ?: JSONObject.NULL)
                    .put("cleanAudioPath", idea.cleanAudioPath ?: JSONObject.NULL)
                    .put("status", idea.status.name)
                    .put("error", idea.error ?: JSONObject.NULL)
                    .put("transcriptSource", idea.transcriptSource ?: JSONObject.NULL)
                    .put("documentSource", idea.documentSource ?: JSONObject.NULL)
            )
        }
        // Write to a temp file first so a crash mid-write cannot corrupt the real file.
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(array.toString())
        if (!temp.renameTo(file)) {
            file.writeText(array.toString())
            temp.delete()
        }
    }

    private fun JSONObject.stringOrNull(key: String): String? =
        if (isNull(key)) null else getString(key)

    companion object {
        @Volatile
        private var instance: IdeaRepository? = null

        fun get(context: Context): IdeaRepository =
            instance ?: synchronized(this) {
                instance ?: IdeaRepository(
                    File(context.applicationContext.filesDir, "ideas.json")
                ).also { instance = it }
            }
    }
}
