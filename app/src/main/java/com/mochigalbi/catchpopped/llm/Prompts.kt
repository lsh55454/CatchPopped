package com.mochigalbi.catchpopped.llm

object Prompts {

    private val DOCUMENT_INSTRUCTIONS = """
        다음은 사용자가 말로 녹음한 아이디어의 전사본이다.
        전사본에 있는 내용만 사용해서 아래 양식의 한국어 문서로 정리하라.
        전사본에 없는 내용은 추가하지 말고, 추측은 "검토 포인트"에만 적어라.
        핵심 용어는 영어를 함께 적어라.

        # 제목
        ## 한 줄 요약
        ## 배경 / 문제
        ## 아이디어
        ## 실행 방법
        ## 기대 효과
        ## 다음 할 일
        ## 검토 포인트

        전사본:
    """.trimIndent()

    /** Full prompt for turning a transcript into the one-page document. */
    fun document(transcript: String): String = DOCUMENT_INSTRUCTIONS + "\n" + transcript.trim()
}
