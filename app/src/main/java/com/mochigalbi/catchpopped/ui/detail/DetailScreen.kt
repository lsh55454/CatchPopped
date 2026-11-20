package com.mochigalbi.catchpopped.ui.detail

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.media.MediaPlayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mochigalbi.catchpopped.data.IdeaRepository
import com.mochigalbi.catchpopped.data.IdeaStatus
import com.mochigalbi.catchpopped.llm.Prompts
import com.mochigalbi.catchpopped.pipeline.IdeaProcessor
import com.mochigalbi.catchpopped.settings.AppSettings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DetailScreen(id: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { IdeaRepository.get(context) }
    val processor = remember { IdeaProcessor.get(context) }
    val ideas by repository.ideas.collectAsState()
    val idea = ideas.firstOrNull { it.id == id }

    if (idea == null) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("아이디어를 찾을 수 없습니다.")
            TextButton(onClick = onBack) { Text("뒤로") }
        }
        return
    }

    val clipboard = remember {
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
    val formatter = remember { SimpleDateFormat("yyyy년 M월 d일 HH:mm", Locale.KOREAN) }
    // Reset when a new document is generated or saved.
    var document by remember(idea.id, idea.documentMd) { mutableStateOf(idea.documentMd.orEmpty()) }
    val llmModel by remember { AppSettings.get(context) }.llmModel.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }

    // One player at a time; released when leaving the screen.
    val player = remember { arrayOfNulls<MediaPlayer>(1) }
    val stopPlayback = {
        player[0]?.release()
        player[0] = null
    }
    val play: (String) -> Unit = { path ->
        stopPlayback()
        player[0] = runCatching {
            MediaPlayer().apply {
                setDataSource(path)
                prepare()
                start()
            }
        }.getOrNull()
    }
    DisposableEffect(Unit) {
        onDispose { stopPlayback() }
    }

    val processing = idea.status == IdeaStatus.PROCESSING

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) { Text("뒤로") }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = { confirmDelete = true }) {
                Text("삭제", color = MaterialTheme.colorScheme.error)
            }
        }

        Text(text = idea.title, style = MaterialTheme.typography.headlineSmall)
        Text(
            text = formatter.format(Date(idea.createdAt)) + " · " + (idea.durationMs / 1000) + "초",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = if (idea.transcriptSource != null) "전사본 (${idea.transcriptSource})" else "전사본",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(4.dp))
        when {
            processing && idea.transcript.isEmpty() -> Text(
                text = "받아쓰는 중입니다…",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            idea.transcript.isNotEmpty() -> SelectionContainer {
                Text(text = idea.transcript, style = MaterialTheme.typography.bodyLarge)
            }
            else -> Text(
                text = "전사본이 없습니다.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val failure = idea.error
        if (idea.status == IdeaStatus.FAILED && failure != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = failure, color = MaterialTheme.colorScheme.error)
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            enabled = idea.transcript.isNotBlank(),
            onClick = {
                clipboard.setPrimaryClip(
                    ClipData.newPlainText("CatchPopped prompt", Prompts.document(idea.transcript))
                )
            },
        ) {
            Text("프롬프트와 함께 복사")
        }

        val rawPath = idea.audioPath
        if (rawPath != null) {
            val cleanPath = idea.cleanAudioPath
            Spacer(modifier = Modifier.height(24.dp))
            Text(text = "녹음 파일", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { play(rawPath) }) { Text("원본 재생") }
                OutlinedButton(
                    enabled = cleanPath != null,
                    onClick = { if (cleanPath != null) play(cleanPath) },
                ) {
                    Text("디노이즈 재생")
                }
                TextButton(onClick = stopPlayback) { Text("정지") }
            }
            Text(
                text = "같은 녹음을 두 가지로 받아써서 비교할 수 있습니다. 기존 전사본은 바뀝니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = !processing,
                    onClick = { processor.process(idea.id, useClean = false) },
                ) {
                    Text("원본으로 받아쓰기")
                }
                OutlinedButton(
                    enabled = !processing,
                    onClick = { processor.process(idea.id, useClean = true) },
                ) {
                    Text("디노이즈로 받아쓰기")
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = if (idea.documentSource != null) "문서 (${idea.documentSource})" else "문서",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(4.dp))
        if (processing && idea.transcript.isNotEmpty()) {
            Text(
                text = "처리 중입니다…",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(
            enabled = !processing && idea.transcript.isNotBlank() && llmModel != null,
            onClick = { processor.document(idea.id) },
        ) {
            Text(if (idea.documentMd == null) "문서 만들기" else "문서 다시 만들기")
        }
        if (llmModel == null) {
            Text(
                text = "설정에서 문서 생성 모델을 고르면 사용할 수 있습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = document,
            onValueChange = { document = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 6,
            placeholder = { Text("모델이 만든 문서를 여기에 붙여 넣으세요.") },
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = {
                    val clip = clipboard.primaryClip
                    if (clip != null && clip.itemCount > 0) {
                        val pasted = clip.getItemAt(0).coerceToText(context)?.toString()
                        if (!pasted.isNullOrEmpty()) document = pasted
                    }
                },
            ) {
                Text("붙여넣기")
            }
            Button(
                enabled = document != idea.documentMd.orEmpty(),
                // A hand-edited or pasted document no longer matches the model label.
                onClick = {
                    repository.update(
                        idea.copy(documentMd = document.ifBlank { null }, documentSource = null)
                    )
                },
            ) {
                Text("저장")
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("삭제할까요?") },
            text = { Text("이 아이디어의 전사본, 문서, 녹음 파일이 지워집니다.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        stopPlayback()
                        onBack()
                        repository.delete(idea.id)
                    },
                ) {
                    Text("삭제")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("취소") }
            },
        )
    }
}
