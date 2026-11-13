# CatchPopped 구현 명세

## 1. 결정 사항

| ID | 항목 | 내용 |
|---|---|---|
| D1 | minSdk | 30. S21이 13 이상이면 33 |
| D2 | 녹음 포맷 | `AudioRecord` 16kHz mono PCM16 → WAV (약 1.9MB/분) |
| D3 | 원본 보존 | `raw.wav` 항상 저장, 디노이즈 결과는 `clean.wav` |
| D4 | STT | `Transcriber` 인터페이스 + System / Whisper |
| D5 | LLM | `Summarizer` 인터페이스, MediaPipe LLM Inference + Gemma 3 최소 모델 (270M vs 1B 비교) |
| D6 | 모델 배포 | APK 미포함. 설정에서 파일 가져오기 |
| D7 | 파이프라인 | WorkManager: Denoise → Transcribe → Summarize |
| D8 | 문서 렌더 | 자체 Markdown 렌더러(#, ##, -, 굵게) + `PdfDocument` |
| D9 | 언어 | `values/`(영어) + `values-ko/`, 앱별 언어 |
| D10 | ABI | `arm64-v8a`만 |
| D11 | 클라우드 | 미구현. `INTERNET` 권한 없음 |

## 2. 디노이즈

"말이 중간에 사라짐"의 원인 후보:

1. SpeechRecognizer 끝점 검출: 말을 멈추면 세션 종료 → 세션 재시작 + 이어붙이기, 또는 파일 입력
2. Whisper의 무음 구간 환각/누락 → VAD로 발화 구간 분할(겹침 0.5s)
3. 과도한 노이즈 억제 → 원본 보존, 억제 강도 설정

| 단계 | 방법 | 의존성 |
|---|---|---|
| S1 (P1) | 플랫폼 `NoiseSuppressor` + 고역통과 필터 100Hz + 피크 정규화 | 없음 |
| S2 (선택) | RNNoise (16k→48k→16k 리샘플) | NDK |

- 노이즈 게이트는 쓰지 않음
- 측정: raw / S1 / S2 각각 STT → 오류율 비교

## 3. STT

```kotlin
interface Transcriber {
    val id: String
    suspend fun transcribe(wav: File, lang: String = "ko"): Transcript
}
data class Transcript(val text: String, val segments: List<Segment>, val engine: String, val elapsedMs: Long)
```

| 구현 | 방식 | 확인 필요 |
|---|---|---|
| `SystemTranscriber` | `EXTRA_AUDIO_SOURCE`(API 33)로 WAV 입력 | S21 인식 서비스의 파일 입력 지원. 미지원 시 실시간 인식 |
| `WhisperTranscriber` | whisper.cpp JNI, VAD 분할, 스레드 4 | tiny/base/small 속도·한국어 품질 |

- 측정 화면(디버그): WAV × 엔진 → 소요시간·결과 저장, CSV
- 오류율(CER)은 PC에서 계산: `tools/cer.py`

## 4. LLM

```kotlin
interface Summarizer { suspend fun summarize(input: SummaryInput, onToken: (String) -> Unit = {}): IdeaDocument }
```

```
# 제목
## 한 줄 요약 (One-liner)
## 배경 / 문제 (Problem)
## 아이디어 (Idea)
## 실행 방법 (How)
## 기대 효과 (Impact)
## 다음 할 일 (Next steps)
## 검토 포인트 (Tackle points)
```

- 본문 한국어, 핵심 용어 영어 병기
- 단일 프롬프트 + 파서 검증. 양식이 깨지면 섹션별 생성
- 긴 전사본: 청크 요약 → 병합
- 스트리밍 출력
- 측정: 첫 토큰 시간, 전체 시간, 피크 메모리

## 5. 데이터 (Room)

```kotlin
@Entity data class Idea(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val createdAt: Long, val updatedAt: Long,
  val title: String?,
  val rawAudioPath: String, val cleanAudioPath: String?,
  val durationMs: Long,
  val transcript: String?, val sttEngine: String?, val sttElapsedMs: Long?,
  val documentMd: String?, val llmModel: String?, val llmElapsedMs: Long?,
  val userEdited: Boolean = false,
  val status: IdeaStatus,   // RECORDED, DENOISING, TRANSCRIBING, SUMMARIZING, DONE, FAILED
  val errorMsg: String?
)
```

- 설정: DataStore (언어, STT 엔진, 디노이즈, 모델 경로, 열면 바로 녹음)
- 확장: `IdeaLink(fromId, toId, note)`

## 6. 화면

| 화면 | 구성 |
|---|---|
| Home | 큰 제목, 검색, 목록/격자, 카드(제목·한 줄 요약·날짜·상태), 스와이프 삭제 + 실행취소, 녹음 버튼 |
| Record | 타이머, 레벨 미터, 저장/취소. 알림에서 정지 |
| Document | 섹션 렌더. 검토 포인트 콜아웃(탭 → 메모/반박 → 재생성 반영). PDF·공유, 편집, 재생성. 탭: 문서/전사본/오디오 |
| Settings | 언어, STT 엔진, 디노이즈, 모델 가져오기/삭제, 열면 바로 녹음, (디버그) 측정 |

## 7. 즉시 실행

| 경로 | 구현 |
|---|---|
| 공통 진입점 | `RecordActivity`, 액션 `com.mochigalbi.catchpopped.action.RECORD`, 딥링크 `catchpopped://record` |
| App Shortcut | `shortcuts.xml` |
| QS 타일 | `RecordTileService` |
| 측면 버튼 | 두 번 누르기 → 앱 열기 + "열면 바로 녹음" (S21 확인 필요) |
| Bixby | "CatchPopped 열어줘", 빠른 명령어 (딥링크 호출·Capsule 확인 필요) |
| 위젯 | 여유 시 |

## 8. 권한

`RECORD_AUDIO`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `FOREGROUND_SERVICE_DATA_SYNC`

## 9. 의존성

| 라이브러리 | 용도 |
|---|---|
| navigation-compose | 화면 이동 (추가함) |
| room + KSP | DB. KSP 호환 안 되면 JSON 파일 저장 |
| work-runtime-ktx | 파이프라인 |
| lifecycle-viewmodel-compose | ViewModel |
| appcompat | 앱별 언어 |
| datastore-preferences | 설정 |
| mediapipe tasks-genai | 로컬 LLM |
| NDK + CMake | whisper.cpp, RNNoise (미설치) |

## 10. 일정

| 기간 | 단계 | 내용 |
|---|---|---|
| ~11/15 | P0 | Navigation, Room, DataStore, 테마 |
| ~11/17 | P1 | FGS 녹음, WAV, 목록·재생, 디노이즈 S1 |
| ~11/19 | P2 | SystemTranscriber, NDK, WhisperTranscriber |
| ~11/21 | P3 | MediaPipe, 프롬프트 v1, WorkManager 체인 |
| 11/22 | 중간발표 준비 | 데모 영상 백업, 초기 측정치 |
| 11/24–12/6 | P4 | 렌더러, 검토 포인트, 편집·재생성, PDF, 비교 측정 |
| 12/7–12/13 | P5 | 바로가기, 타일, 측면 버튼·Bixby 테스트, 언어 설정 |
| 12/14–12/20 | P6 | 오류 처리, 리허설, 보고서, 데모 영상 |

중간 데모: 녹음 → 디노이즈 → STT 2종 → 요약 표시. 12/14 기능 동결.

## 11. 남은 준비

- [ ] S21 Android 버전 확인
- [ ] NDK, CMake 설치 (SDK Manager)
- [ ] whisper.cpp 소스 → `third_party/whisper.cpp`
- [ ] Whisper 모델 (tiny 약 75MB, base 약 142MB, small 약 466MB)
- [ ] Gemma 모델 (`.task`, 라이선스 동의)
