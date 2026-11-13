# CatchPopped

Ideas pop up while walking, showering, or falling asleep — and they disappear just as fast.
CatchPopped lets you **speak an idea once** and turns the recording into **a one-page proposal document**, entirely **on-device**.

Team **Mochigalbi Brown** — Seunghyeon Lee, Myeonghak Lee

## How it works

```
Quick launch (Bixby / side key / shortcut / tile)
  → Record
  → Denoise
  → Speech-to-text
  → Local LLM summary
  → One-page proposal (view as PDF, share)
```

- **No typing**: one voice note is the only input.
- **Offline**: speech recognition and summarization run on the phone.
- **Feels like a document app, not a chatbot**: results are stored as documents you can search, edit, and export.
- **Tackle points**: the generated document highlights weak assumptions so you can push back on them.

## Tech stack

| Area | Choice |
|---|---|
| Language / UI | Kotlin, Jetpack Compose (Material 3) |
| Architecture | MVVM, single `app` module |
| Storage | Room, app-internal files (audio, documents) |
| Background | Foreground service (recording), WorkManager (processing) |
| Denoising | Platform `NoiseSuppressor` + high-pass filter (RNNoise as an option) |
| Speech-to-text | Android `SpeechRecognizer` and whisper.cpp — both implemented and compared |
| Local LLM | Lightest model that works on the target device (MediaPipe LLM Inference + small Gemma, llama.cpp as fallback) |
| Export | Markdown → `PdfDocument` PDF, share intent |

Target device: Samsung Galaxy S21.

## Output format

```
# Title
## One-liner
## Problem
## Idea
## How
## Impact
## Next steps
## Tackle points
```

Body text is Korean; key terms are kept in English.

## Screens

- **Library**: list of ideas, search, record button
- **Record**: timer, input level, stop / cancel
- **Document**: rendered proposal, transcript, audio; edit, regenerate, PDF / share
- **Settings**: app language, STT engine, denoising, model files

## Roadmap

- [x] Proposal (Oct 19)
- [ ] P0 Project skeleton: navigation, database, theme
- [ ] P1 Recording (foreground service, WAV) + denoising
- [ ] P2 Speech-to-text: SpeechRecognizer, whisper.cpp
- [ ] P3 Local LLM summary + processing pipeline
- [ ] Mid-term presentation (Nov 23 / 25)
- [ ] P4 Document UI, tackle points, PDF export, STT / model comparison
- [ ] P5 Quick launch: shortcut, tile, side key, Bixby; app language setting
- [ ] P6 Polish, demo, report
- [ ] Final presentation + live demo (Dec 21 / 23)

Details on implementation: [IMPLEMENTATION.md](docs/IMPLEMENTATION.md)
Future work: merging related ideas into one document.

## Build

Open the project in Android Studio and run the `app` configuration.
Model files are not included in the repository; import them from the Settings screen on the device.
