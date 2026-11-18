package com.mochigalbi.catchpopped.data

enum class IdeaStatus { DONE, PROCESSING, FAILED }

data class Idea(
    val id: Long,
    val createdAt: Long,
    val durationMs: Long,
    val transcript: String,
    val documentMd: String? = null,
    /** Recording as captured. Null for ideas made with live dictation. */
    val audioPath: String? = null,
    /** Denoised copy of the recording, if one was made. */
    val cleanAudioPath: String? = null,
    val status: IdeaStatus = IdeaStatus.DONE,
    val error: String? = null,
    /** Which audio the current transcript came from: "원본" or "디노이즈". */
    val transcriptSource: String? = null,
) {
    /** Document heading if there is one, otherwise the start of the transcript. */
    val title: String
        get() {
            val heading = documentMd
                ?.lineSequence()
                ?.firstOrNull { it.startsWith("# ") }
                ?.removePrefix("# ")
                ?.trim()
            if (!heading.isNullOrEmpty()) return heading

            val firstLine = transcript.trim().lineSequence().firstOrNull().orEmpty()
            return when {
                firstLine.isEmpty() && status == IdeaStatus.PROCESSING -> "받아쓰는 중…"
                firstLine.isEmpty() && status == IdeaStatus.FAILED -> "받아쓰기 실패"
                firstLine.isEmpty() -> "(빈 녹음)"
                firstLine.length > 30 -> firstLine.take(30) + "…"
                else -> firstLine
            }
        }
}
