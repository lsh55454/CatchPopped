# CatchPopped 구현 명세

상태 표기: ✅ 구현 · 🔶 일부 · ⬜ 미구현 (2026-11-20 기준)

## 1. 결정 사항

| ID | 항목 | 내용 | 상태 |
|---|---|---|---|
| D1 | minSdk | 30. S21은 Android 15. 파일 STT는 API 33 이상에서만 동작(런타임 확인). 33 상향은 미정 | ✅ |
| D2 | 녹음 포맷 | `AudioRecord` 16kHz mono PCM16 → WAV (약 1.9MB/분) | ✅ |
| D3 | 원본 보존 | `files/audio/<id>.raw.wav` 항상 저장, 디노이즈 결과는 `<id>.clean.wav` | ✅ |
| D4 | STT | `Transcriber` 인터페이스 + System(`FileTranscriber`) / `WhisperTranscriber` | ✅ |
| D5 | LLM | `Summarizer` 인터페이스, `GemmaSummarizer`(MediaPipe LLM Inference `tasks-genai` 0.10.35, `Gemma3-1B-IT_multi-prefill-seq_q4_ekv2048.task` 555MB). 문서마다 모델 로드·해제. LLM Inference는 Android에서 유지보수 전용(공식 안내) → 중간발표 후 LiteRT-LM(`litertlm-android`, `.litertlm`) 전환 예정 | 🔶 코드만, 기기 미확인 |
| D6 | 모델 배포 | APK 미포함. 설정 → 모델 가져오기 → `files/models/` (`.bin` Whisper, `.task` LLM) | ✅ |
| D7 | 파이프라인 | 앱 전역 `CoroutineScope`(`IdeaProcessor`) + `ProcessingService`(dataSync foreground service, partial wake lock)로 화면 꺼짐·백그라운드에서도 계속. WorkManager는 의존성 추가 없이 가려고 보류. 프로세스가 죽으면 다음 실행 때 실패로 표시 | ✅ |
| D8 | 문서 렌더 | 자체 Markdown 렌더러(#, ##, -, 굵게) + `PdfDocument` | ⬜ |
| D9 | 언어 | `values/`(영어) + `values-ko/`, 앱별 언어. 현재 문자열은 한국어로 코드에 직접 씀 | ⬜ |
| D10 | ABI | `arm64-v8a`만 (`abiFilters`) | ✅ |
| D11 | 클라우드 | 미구현. `INTERNET` 권한 없음 | ✅ |

## 2. 디노이즈

"말이 중간에 사라짐"의 원인 후보와 현재 대응:

1. SpeechRecognizer 끝점 검출: 말을 멈추면 세션 종료 → 무음에서 잘라 조각마다 새 세션 (`Chunker`) ✅
2. Whisper의 무음 구간 환각/누락 → 긴 무음은 잘라내고 발화 조각만 입력 ✅
3. 과도한 노이즈 억제 → 원본 보존, 노이즈 게이트 없음 ✅

| 단계 | 방법 | 상태 |
|---|---|---|
| S1 | 2차 Butterworth 고역통과 100Hz + 피크 정규화(목표 0.9, 최대 8배) (`audio/Denoiser.kt`) | ✅ |
| S1 | 플랫폼 `NoiseSuppressor` | ⬜ |
| S2 (선택) | RNNoise 또는 spectral subtraction | ⬜ |

- 설정의 디노이즈 스위치로 on/off. 상세 화면에서 원본/디노이즈 각각 다시 받아쓰기 가능
- 측정: raw / S1 / S2 각각 STT → 오류율 비교 ⬜ (차 소리 나는 실외 녹음 포함)

## 3. STT

```kotlin
interface Transcriber { suspend fun transcribe(wav: File): String }
```

소요시간은 `IdeaProcessor`가 재서 `transcriptSource`에 기록 (예: `Whisper small · 디노이즈 · 42초`).

### 무음 분할 (`audio/Chunker.kt`)

- 30ms 프레임, 잡음 바닥(하위 10%) + 6dB 초과면 발화
- 0.8초 이상 무음에서 분할, 무음 양끝 0.2초만 남김. 0.2초 미만 발화(클릭)는 버림
- 20초 초과 조각은 가장 조용한 프레임에서 자름
- 값은 첫 추정치. 작은 목소리가 무음으로 판정되면 빠질 수 있음

### 엔진

| 구현 | 방식 | 상태·확인 사항 |
|---|---|---|
| `FileTranscriber` (System) | `EXTRA_AUDIO_SOURCE`(API 33)로 조각을 pipe 입력. 온디바이스 인식기 먼저, 실패 시 기본 인식기. 인식기 1개를 조각 간 재사용. 코드 5/8/11은 1초 후 최대 3회 재시도 | ✅ S21 동작 확인. `ko-KR` 고정이라 영어 발화는 인식 안 됨. 언어 전환(API 34 `EXTRA_ENABLE_LANGUAGE_SWITCH`)은 미시험 |
| `WhisperTranscriber` | whisper.cpp JNI(`app/src/main/cpp`), greedy, 스레드 4, `no_context`. 조각을 0.3초 간격으로 이어 28초 이하 창으로 묶어 입력. 언어 `ko` / `auto` | ✅ 동작 확인. 체감 속도: 녹음 길이의 약 3배 |
| 실시간 받아쓰기 (`SpeechTranscriber`) | 마이크 직접 입력, 멈추면 세션 재시작 | ✅ |

- 모델: `ggml-large-v3-turbo-q5_0.bin` (Hugging Face `ggerganov/whisper.cpp`)
- `ggml-small.bin`: 시스템 받아쓰기보다 정확도가 낮아 실험에서 제외
- 측정 화면(디버그): WAV × 엔진 × 디노이즈 → 소요시간·결과, CSV ⬜
- 오류율(CER): 기준 문장 입력 후 앱 또는 PC에서 계산 ⬜

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

- 프롬프트 v1: `llm/Prompts.kt` ✅ (전사본에 없는 내용은 검토 포인트로만)
- 현재 흐름: 설정에서 문서 생성 모델을 고르면 받아쓰기(파일·실시간) 직후 자동 생성. 상세 화면 "문서 (다시) 만들기"로 수동 생성. 결과는 `documentMd`, 모델·소요시간은 `documentSource`
- 비교용: "프롬프트와 함께 복사" → 외부 LLM → 붙여넣기·저장 (이때 `documentSource`는 비움)
- `setMaxTokens(2048)`: 입력+출력 합계. 모델 파일의 KV 캐시(`ekv2048`) 이하여야 함. 긴 전사본은 넘칠 수 있음
- Gemma 대화 템플릿(`<start_of_turn>user … <start_of_turn>model`)을 직접 붙임. `.task`가 템플릿을 내장하는지는 파일마다 다름. 빈 답이면 템플릿 없이 1회 재시도
- 공식 안내상 Pixel 8·S23 이상 기준. S21 속도·안정성 미확인
- 본문 한국어, 핵심 용어 영어 병기
- 단일 프롬프트 + 파서 검증. 양식이 깨지면 섹션별 생성
- 긴 전사본: 청크 요약 → 병합
- 스트리밍 출력
- 측정: 첫 토큰 시간, 전체 시간, 피크 메모리. 비교: Gemma 1B / Claude Haiku(상한 기준)

## 5. 데이터

Room 대신 JSON 파일 (`files/ideas.json`, `data/IdeaRepository.kt`). 임시 파일에 쓴 뒤 교체, 읽기 실패 시 `ideas.broken.json`으로 백업.

```kotlin
data class Idea(
  val id: Long, val createdAt: Long, val durationMs: Long,
  val transcript: String, val documentMd: String? = null,
  val audioPath: String? = null, val cleanAudioPath: String? = null,
  val status: IdeaStatus = DONE,   // DONE, PROCESSING, FAILED
  val error: String? = null,
  val transcriptSource: String? = null,  // 엔진 · 입력 · 소요시간
  val documentSource: String? = null,    // LLM 모델 · 소요시간
)
```

- 설정: SharedPreferences (`settings/AppSettings.kt`) — 녹음 방식, 디노이즈, STT 엔진, Whisper 모델, Whisper 언어, LLM 모델
- 예정: 앱 언어, 열면 바로 녹음, LLM 모델/소요시간 필드
- 확장: `IdeaLink(fromId, toId, note)`

## 6. 화면

| 화면 | 현재 | 예정 |
|---|---|---|
| Home | 목록(제목·날짜·상태), 녹음 버튼, 설정 | 검색, 카드(한 줄 요약), 스와이프 삭제 + 실행취소 |
| Record | 타이머, 레벨 미터, 저장/취소, 화면 켜짐 유지. 실시간 / 파일 모드 | 알림에서 정지 (FGS) |
| Detail | 전사본(출처 표시), 오류, 원본/디노이즈 재생, 원본/디노이즈로 다시 받아쓰기, 문서 만들기(모델·시간 표시), 프롬프트 복사, 문서 붙여넣기·편집, 삭제 | 섹션 렌더, 검토 포인트 콜아웃, PDF·공유, 편집, 재생성 |
| Settings | 녹음 방식, 디노이즈, STT 엔진, Whisper 모델 가져오기·선택, Whisper 언어, 문서 생성 모델 가져오기·선택 | 앱 언어, 모델 삭제, 열면 바로 녹음, (디버그) 측정 |

## 7. 즉시 실행 ⬜

| 경로 | 구현 |
|---|---|
| 공통 진입점 | `RecordActivity`, 액션 `com.mochigalbi.catchpopped.action.RECORD`, 딥링크 `catchpopped://record` |
| App Shortcut | `shortcuts.xml` |
| QS 타일 | `RecordTileService` |
| 측면 버튼 | 두 번 누르기 → 앱 열기 + "열면 바로 녹음" (S21 확인 필요) |
| Bixby | "CatchPopped 열어줘", 빠른 명령어 (딥링크 호출·Capsule 확인 필요) |
| 위젯 | 여유 시 |

## 8. 권한

- 현재: `RECORD_AUDIO`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`(파일 녹음 화면에서 요청, 거부해도 처리는 진행), `WAKE_LOCK`, `<queries>` `android.speech.RecognitionService`
- 예정: `FOREGROUND_SERVICE_MICROPHONE` (화면 꺼진 상태 녹음)

## 9. 의존성

| 라이브러리 | 용도 | 상태 |
|---|---|---|
| navigation-compose | 화면 이동 | ✅ |
| NDK 30.0.16248370 + CMake 4.1.2 | whisper.cpp 빌드 | ✅ |
| whisper.cpp (git submodule `third_party/whisper.cpp`) | Whisper | ✅ |
| work-runtime-ktx | 백그라운드 처리 | 보류 (foreground service로 대체) |
| mediapipe tasks-genai 0.10.35 | 로컬 LLM | 🔶 추가함. 유지보수 전용 → 발표 후 LiteRT-LM 0.19.0 검토 (Kotlin 2.4 메타데이터라 Kotlin 상향 필요할 수 있음) |
| appcompat | 앱별 언어 | ⬜ |
| room + KSP, datastore | — | 사용 안 함 (JSON, SharedPreferences로 대체) |

## 10. 개발 환경 (Android Studio)

- 빌드 도구: AGP 9.4.1, Gradle 9.6.0, Kotlin 2.2.10, Compose BOM 2026.02.01, compileSdk/targetSdk 37
- 실행: Run 구성은 `app` 선택 (`testDebugUnitTest`는 단위 테스트만 돌림). Gradle Sync 후 빌드
- NDK·CMake: Settings → Languages & Frameworks → Android SDK → SDK Tools에서 설치
- 클론 후 whisper.cpp 받기: `git submodule update --init --depth 1`
- 네이티브 빌드
  - `app/src/main/cpp/CMakeLists.txt`: whisper/ggml 정적 링크, `GGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16`, OpenMP 끔
  - 디버그 빌드에서도 Release(-O3)로 컴파일. `app/.cxx/.../build.ninja`에서 확인함
  - 첫 빌드는 수 분, 이후 `.cxx` 캐시 사용
- 모델 넣기: 휴대폰 Download 폴더에 `.bin` → 앱 설정 → 모델 가져오기
- 디버깅: Logcat 검색창에 `speech` 등으로 필터

## 11. 해결한 문제

| 증상 | 원인 | 조치 |
|---|---|---|
| 파일 받아쓰기 오류 코드 2 | 기본 인식기가 네트워크 필요 | 온디바이스 인식기 먼저 시도 |
| 긴 침묵 뒤 받아쓰기 끊김 | 인식기가 침묵에서 세션 종료, 남은 오디오 유실 | 무음에서 분할, 조각마다 새 세션 |
| 오류 코드 11 (서비스 연결 끊김) | 조각마다 인식기 생성·파괴 (추정) | 인식기 1개 재사용, 재시도 |
| Whisper small도 느림 | 짧은 조각도 30초 창 전체를 계산 | 조각을 28초 이하 창으로 묶음 (효과 측정 중) |

## 12. 일정

| 기간 | 단계 | 내용 | 상태 |
|---|---|---|---|
| ~11/15 | P0 | Navigation, 저장소, 설정, 테마 | ✅ |
| ~11/17 | P1 | FGS 녹음, WAV, 목록·재생, 디노이즈 S1 | 🔶 FGS 남음 |
| ~11/19 | P2 | SystemTranscriber, NDK, WhisperTranscriber | 🔶 측정 남음 |
| ~11/21 | P3 | MediaPipe, 프롬프트 v1, WorkManager 체인 | 🔶 프롬프트만 |
| 11/22 | 중간발표 준비 | 데모 영상 백업, 초기 측정치 | ⬜ |
| 11/24–12/6 | P4 | 렌더러, 검토 포인트, 편집·재생성, PDF, 비교 측정 | ⬜ |
| 12/7–12/13 | P5 | 바로가기, 타일, 측면 버튼·Bixby 테스트, 언어 설정 | ⬜ |
| 12/14–12/20 | P6 | 오류 처리, 리허설, 보고서, 데모 영상 | ⬜ |

중간 데모: 녹음 → 디노이즈 → STT 2종 → 요약 표시. 12/14 기능 동결.

## 13. 남은 준비

- [x] S21 Android 버전 확인 (One UI 7.0, Android 15)
- [x] NDK, CMake 설치 (SDK Manager)
- [x] whisper.cpp 소스 → `third_party/whisper.cpp` (submodule)
- [x] Whisper 모델 (large-v3-turbo-q5_0. small은 실험 제외)
- [ ] Gemma 모델 (`.task`, 라이선스 동의). AI Edge Gallery 설치본과 별개로 앱용 파일 필요
