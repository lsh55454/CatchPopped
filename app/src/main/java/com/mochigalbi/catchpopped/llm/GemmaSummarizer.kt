package com.mochigalbi.catchpopped.llm

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Runs a Gemma model on the phone with MediaPipe LLM Inference.
 *
 * The model is loaded for each document and released afterwards, so it does not hold
 * about a gigabyte of memory between uses.
 */
class GemmaSummarizer(
    context: Context,
    private val model: File,
    private val maxTokens: Int = 2048,
) : Summarizer {

    private val appContext = context.applicationContext

    override suspend fun summarize(transcript: String): String = withContext(Dispatchers.Default) {
        if (!model.isFile) throw SummaryException("LLM 모델 파일이 없습니다: ${model.name}")
        if (transcript.isBlank()) throw SummaryException("전사본이 비어 있습니다.")

        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(model.absolutePath)
            // Prompt and answer together; must not exceed what the model file was built for.
            .setMaxTokens(maxTokens)
            .build()
        val llm = try {
            LlmInference.createFromOptions(appContext, options)
        } catch (e: Exception) {
            throw SummaryException("LLM 모델을 불러오지 못했습니다: ${e.message}")
        }
        try {
            val prompt = Prompts.document(transcript)
            // Whether a .task file applies the Gemma chat template itself depends on the
            // file, so it is added here. An empty answer is the usual sign of a wrong
            // template; then the plain prompt is tried once.
            var answer = llm.generateResponse(gemmaTurn(prompt)).trim()
            if (answer.isEmpty()) answer = llm.generateResponse(prompt).trim()
            if (answer.isEmpty()) throw SummaryException("모델이 빈 답을 냈습니다.")
            cleanUp(answer)
        } catch (e: SummaryException) {
            throw e
        } catch (e: Exception) {
            throw SummaryException("문서 생성 중 오류: ${e.message}")
        } finally {
            llm.close()
        }
    }

    /** Gemma 2/3 chat template for a single user turn. */
    private fun gemmaTurn(prompt: String): String =
        "<start_of_turn>user\n$prompt<end_of_turn>\n<start_of_turn>model\n"

    /** Small models often wrap the answer in a ```markdown fence; remove it. */
    private fun cleanUp(answer: String): String {
        val lines = answer.lines()
        val fenced = lines.firstOrNull()?.trim()?.startsWith("```") == true &&
            lines.lastOrNull()?.trim() == "```"
        return if (fenced) lines.drop(1).dropLast(1).joinToString("\n").trim() else answer
    }
}
