package com.mochigalbi.catchpopped.pipeline

import android.content.Context
import android.os.SystemClock
import com.mochigalbi.catchpopped.audio.Denoiser
import com.mochigalbi.catchpopped.data.IdeaRepository
import com.mochigalbi.catchpopped.data.IdeaStatus
import com.mochigalbi.catchpopped.llm.GemmaSummarizer
import com.mochigalbi.catchpopped.models.ModelFiles
import com.mochigalbi.catchpopped.settings.AppSettings
import com.mochigalbi.catchpopped.settings.SttEngine
import com.mochigalbi.catchpopped.stt.FileTranscriber
import com.mochigalbi.catchpopped.stt.Transcriber
import com.mochigalbi.catchpopped.stt.TranscriptionException
import com.mochigalbi.catchpopped.stt.WhisperTranscriber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Turns a recorded file into a transcript (optional denoising, then speech recognition)
 * and the transcript into a document (local LLM, if one is selected in the settings).
 *
 * Work runs in an app-wide scope while [ProcessingService] keeps the process alive.
 * If the process is killed anyway, interrupted ideas are marked as failed on the next
 * launch and can be retried from the detail screen.
 */
class IdeaProcessor private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val repository = IdeaRepository.get(appContext)
    private val settings = AppSettings.get(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val inProgress = mutableSetOf<Long>()

    private val _active = MutableStateFlow(false)

    /** True while any idea is being processed. [ProcessingService] stops when it turns false. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    /**
     * Transcribes the recording, then writes the document if an LLM is selected.
     * [useClean] chooses the denoised copy over the original as input to recognition.
     */
    fun process(
        id: Long,
        useClean: Boolean = settings.denoise.value,
        engine: SttEngine = settings.sttEngine.value,
    ) = launchFor(id) {
        if (transcribe(id, useClean, engine) && settings.llmModel.value != null) writeDocument(id)
    }

    /** Writes the document from the current transcript. */
    fun document(id: Long) = launchFor(id) { writeDocument(id) }

    /** Runs [work] for one idea at a time; any failure is stored on the idea. */
    private fun launchFor(id: Long, work: suspend () -> Unit) {
        if (!inProgress.add(id)) return
        _active.value = true
        ProcessingService.start(appContext)
        scope.launch {
            try {
                work()
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
                _active.value = inProgress.isNotEmpty()
            }
        }
    }

    /** Returns true if a non-empty transcript was stored. */
    private suspend fun transcribe(id: Long, useClean: Boolean, engine: SttEngine): Boolean {
        val idea = repository.find(id) ?: return false
        val rawPath = idea.audioPath ?: return false
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

        val (transcriber, engineLabel) = transcriberFor(engine)
        val startedAt = SystemClock.elapsedRealtime()
        val text = transcriber.transcribe(source).trim()
        val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000

        val latest = repository.find(id) ?: return false
        repository.update(
            latest.copy(
                transcript = text,
                cleanAudioPath = cleanPath,
                status = if (text.isEmpty()) IdeaStatus.FAILED else IdeaStatus.DONE,
                error = if (text.isEmpty()) "인식된 내용이 없습니다." else null,
                transcriptSource = engineLabel +
                    (if (useClean) " · 디노이즈" else " · 원본") + " · ${seconds}초",
            )
        )
        return text.isNotEmpty()
    }

    private suspend fun writeDocument(id: Long) {
        val idea = repository.find(id) ?: return
        val name = settings.llmModel.value
            ?: throw IllegalStateException("설정에서 문서 생성 모델을 선택해 주세요.")
        repository.update(idea.copy(status = IdeaStatus.PROCESSING, error = null))

        val summarizer = GemmaSummarizer(appContext, File(ModelFiles.dir(appContext), name))
        val startedAt = SystemClock.elapsedRealtime()
        val document = try {
            summarizer.summarize(idea.transcript)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // Keep the transcript; only the document step failed.
            throw IllegalStateException("문서 생성 실패: ${e.message}")
        }
        val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000

        val latest = repository.find(id) ?: return
        repository.update(
            latest.copy(
                documentMd = document,
                documentSource = "${ModelFiles.label(name)} · ${seconds}초",
                status = IdeaStatus.DONE,
                error = null,
            )
        )
    }

    private fun transcriberFor(engine: SttEngine): Pair<Transcriber, String> = when (engine) {
        SttEngine.SYSTEM -> FileTranscriber(appContext) to "시스템"
        SttEngine.WHISPER -> {
            val name = settings.whisperModel.value
                ?: throw TranscriptionException("설정에서 Whisper 모델을 선택해 주세요.")
            WhisperTranscriber(
                model = File(ModelFiles.dir(appContext), name),
                language = settings.whisperLanguage.value,
            ) to "Whisper ${ModelFiles.label(name)}"
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
