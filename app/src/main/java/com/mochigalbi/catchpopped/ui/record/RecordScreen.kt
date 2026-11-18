package com.mochigalbi.catchpopped.ui.record

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mochigalbi.catchpopped.data.Idea
import com.mochigalbi.catchpopped.data.IdeaRepository
import com.mochigalbi.catchpopped.data.IdeaStatus
import com.mochigalbi.catchpopped.pipeline.IdeaProcessor
import com.mochigalbi.catchpopped.recording.WavRecorder
import com.mochigalbi.catchpopped.settings.AppSettings
import com.mochigalbi.catchpopped.settings.RecordMode
import com.mochigalbi.catchpopped.stt.SpeechTranscriber
import kotlinx.coroutines.delay
import java.io.File

@Composable
fun RecordScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    // Read once: the mode must not change in the middle of a recording.
    val mode = remember { AppSettings.get(context).recordMode.value }

    KeepScreenOn()
    MicPermissionGate(onCancel = onDone) {
        when (mode) {
            RecordMode.LIVE -> LiveRecording(onDone = onDone)
            RecordMode.FILE -> FileRecording(onDone = onDone)
        }
    }
}

/** Live dictation: text appears while the user speaks. */
@Composable
private fun LiveRecording(onDone: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { IdeaRepository.get(context) }
    val available = remember { SpeechRecognizer.isRecognitionAvailable(context) }

    val transcriber = remember { SpeechTranscriber(context) }
    val committed by transcriber.committed.collectAsState()
    val partial by transcriber.partial.collectAsState()
    val listening by transcriber.listening.collectAsState()
    val level by transcriber.level.collectAsState()
    val error by transcriber.error.collectAsState()

    var elapsedMs by remember { mutableLongStateOf(0L) }

    DisposableEffect(Unit) {
        onDispose { transcriber.destroy() }
    }
    LaunchedEffect(Unit) {
        if (available) {
            transcriber.start()
            runTimer { elapsedMs = it }
        }
    }

    RecordLayout(
        status = when {
            !available -> "음성 인식 사용 불가"
            error != null -> "중단됨"
            listening -> "듣는 중"
            else -> "준비 중"
        },
        elapsedMs = elapsedMs,
        level = level,
        canSave = committed.isNotBlank() || partial.isNotBlank(),
        onCancel = onDone,
        onSave = {
            val text = transcriber.stop().trim()
            if (text.isNotEmpty()) {
                val now = System.currentTimeMillis()
                repository.add(
                    Idea(id = now, createdAt = now, durationMs = elapsedMs, transcript = text)
                )
            }
            onDone()
        },
    ) {
        if (!available) {
            Text("이 기기에서 음성 인식 서비스를 찾을 수 없습니다.")
        } else {
            if (committed.isNotEmpty()) {
                Text(text = committed, style = MaterialTheme.typography.bodyLarge)
            }
            if (partial.isNotEmpty()) {
                Text(
                    text = partial,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (committed.isEmpty() && partial.isEmpty() && error == null) {
                Text(
                    text = "떠오른 생각을 말해 보세요.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val message = error
        if (message != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = message, color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = { transcriber.start() }) { Text("다시 시작") }
        }
    }
}

/** File recording: saves a WAV file, which is denoised and transcribed after saving. */
@Composable
private fun FileRecording(onDone: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { IdeaRepository.get(context) }
    val processor = remember { IdeaProcessor.get(context) }

    val recorder = remember { WavRecorder() }
    val level by recorder.level.collectAsState()

    val id = remember { System.currentTimeMillis() }
    val file = remember { File(File(context.filesDir, "audio"), "$id.raw.wav") }
    // Set when the recording is saved, so leaving the screen does not delete it.
    val kept = remember { booleanArrayOf(false) }

    var started by remember { mutableStateOf<Boolean?>(null) }
    var elapsedMs by remember { mutableLongStateOf(0L) }

    DisposableEffect(Unit) {
        onDispose {
            recorder.stop()
            if (!kept[0]) file.delete()
        }
    }
    LaunchedEffect(Unit) {
        val ok = recorder.start(file)
        started = ok
        if (ok) runTimer { elapsedMs = it }
    }

    RecordLayout(
        status = when (started) {
            null -> "준비 중"
            true -> "녹음 중"
            else -> "녹음 실패"
        },
        elapsedMs = elapsedMs,
        level = level,
        canSave = started == true && elapsedMs > 500,
        onCancel = onDone,
        onSave = {
            recorder.stop()
            kept[0] = true
            repository.add(
                Idea(
                    id = id,
                    createdAt = id,
                    durationMs = elapsedMs,
                    transcript = "",
                    audioPath = file.absolutePath,
                    status = IdeaStatus.PROCESSING,
                )
            )
            processor.process(id)
            onDone()
        },
    ) {
        if (started == false) {
            Text(
                text = "마이크를 열지 못했습니다. 다른 앱이 마이크를 쓰고 있는지 확인해 주세요.",
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            Text(
                text = "파일로 녹음하고 있습니다.\n저장하면 받아쓰기가 시작되고, 끝나면 목록에 표시됩니다.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RecordLayout(
    status: String,
    elapsedMs: Long,
    level: Float,
    canSave: Boolean,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    body: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = status,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = formatElapsed(elapsedMs), style = MaterialTheme.typography.displayLarge)

        Spacer(modifier = Modifier.height(12.dp))
        LevelBar(level = level)
        Spacer(modifier = Modifier.height(16.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            content = body,
        )

        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onCancel) { Text("취소") }
            Button(enabled = canSave, onClick = onSave) { Text("저장") }
        }
    }
}

/** Shows [content] once the microphone permission is granted, asking for it if needed. */
@Composable
private fun MicPermissionGate(onCancel: () -> Unit, content: @Composable () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { result -> granted = result }

    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(Manifest.permission.RECORD_AUDIO)
    }

    if (granted) {
        content()
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("녹음하려면 마이크 권한이 필요합니다.")
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onCancel) { Text("취소") }
                Button(onClick = { launcher.launch(Manifest.permission.RECORD_AUDIO) }) {
                    Text("권한 요청")
                }
            }
        }
    }
}

@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

@Composable
private fun LevelBar(level: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(level.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

private suspend fun runTimer(onTick: (Long) -> Unit) {
    val startedAt = SystemClock.elapsedRealtime()
    while (true) {
        onTick(SystemClock.elapsedRealtime() - startedAt)
        delay(200)
    }
}

private fun formatElapsed(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}
