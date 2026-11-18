package com.mochigalbi.catchpopped.pipeline

import android.content.Context
import com.mochigalbi.catchpopped.audio.Denoiser
import com.mochigalbi.catchpopped.data.IdeaRepository
import com.mochigalbi.catchpopped.data.IdeaStatus
import com.mochigalbi.catchpopped.settings.AppSettings
import com.mochigalbi.catchpopped.stt.FileTranscriber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Turns a recorded file into a transcript: optional denoising, then speech recognition.
 *
 * Work runs in an app-wide scope, so it continues when the user leaves the screen but
 * stops if the app process is killed. Interrupted ideas are marked as failed on the next
 * launch and can be retried from the detail screen.
 */
class IdeaProcessor private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val repository = IdeaRepository.get(appContext)
    private val settings = AppSettings.get(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val inProgress = mutableSetOf<Long>()

    /** [useClean] chooses the denoised copy over the original as input to recognition. */
    fun process(id: Long, useClean: Boolean = settings.denoise.value) {
        if (!inProgress.add(id)) return
        scope.launch {
            try {
                val idea = repository.find(id) ?: return@launch
                val rawPath = idea.audioPath ?: return@launch
                repository.update(idea.copy(status = IdeaStatus.PROCESSING, error = null))

                val raw = File(rawPath)
                var source = raw
                var cleanPath = idea.cleanAudioPath
                if (useClean) {
                    val clean = File(raw.parentFile, "$id.clean.wav")
                    withContext(Dispatchers.Default) { Denoiser.file(raw, clean) }
                    source = clean
                    cleanPath = clean.absolutePath
                }

                val text = FileTranscriber(appContext).transcribe(source).trim()

                val latest = repository.find(id) ?: return@launch
                repository.update(
                    latest.copy(
                        transcript = text,
                        cleanAudioPath = cleanPath,
                        status = if (text.isEmpty()) IdeaStatus.FAILED else IdeaStatus.DONE,
                        error = if (text.isEmpty()) "인식된 내용이 없습니다." else null,
                        transcriptSource = if (useClean) "디노이즈" else "원본",
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                repository.find(id)?.let { current ->
                    repository.update(
                        current.copy(
                            status = IdeaStatus.FAILED,
                            error = e.message ?: e.javaClass.simpleName,
                        )
                    )
                }
            } finally {
                inProgress.remove(id)
            }
        }
    }

    companion object {
        @Volatile
        private var instance: IdeaProcessor? = null

        fun get(context: Context): IdeaProcessor =
            instance ?: synchronized(this) {
                instance ?: IdeaProcessor(context).also { instance = it }
            }
    }
}
