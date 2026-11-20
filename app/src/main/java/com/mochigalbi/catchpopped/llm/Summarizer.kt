package com.mochigalbi.catchpopped.llm

class SummaryException(message: String) : Exception(message)

/** Turns a transcript into the one-page Markdown document. Throws [SummaryException]. */
interface Summarizer {
    suspend fun summarize(transcript: String): String
}
