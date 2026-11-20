package com.mochigalbi.catchpopped.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.mochigalbi.catchpopped.settings.AppSettings
import com.mochigalbi.catchpopped.settings.RecordMode
import com.mochigalbi.catchpopped.settings.SttEngine
import com.mochigalbi.catchpopped.models.ModelFiles
import com.mochigalbi.catchpopped.models.ModelKind
import com.mochigalbi.catchpopped.stt.WhisperNative
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { AppSettings.get(context) }
    val mode by settings.recordMode.collectAsState()
    val denoise by settings.denoise.collectAsState()
    val engine by settings.sttEngine.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        TextButton(onClick = onBack) { Text("뒤로") }
        Text(text = "설정", style = MaterialTheme.typography.headlineSmall)

        Spacer(modifier = Modifier.height(24.dp))
        Text(text = "녹음 방식", style = MaterialTheme.typography.titleMedium)
        ModeRow(
            selected = mode == RecordMode.LIVE,
            title = "실시간 받아쓰기",
            description = "말하는 대로 글자가 나타납니다. 녹음 파일은 남지 않습니다.",
            onClick = { settings.setRecordMode(RecordMode.LIVE) },
        )
        ModeRow(
            selected = mode == RecordMode.FILE,
            title = "파일 녹음 후 받아쓰기 (실험)",
            description = "녹음을 파일로 저장한 뒤 받아씁니다. 디노이즈를 적용할 수 있습니다.",
            onClick = { settings.setRecordMode(RecordMode.FILE) },
        )

        Spacer(modifier = Modifier.height(24.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "디노이즈", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "파일 녹음에서 저음 잡음을 줄이고 음량을 맞춘 뒤 받아씁니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(checked = denoise, onCheckedChange = { settings.setDenoise(it) })
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text(text = "받아쓰기 엔진 (파일 녹음)", style = MaterialTheme.typography.titleMedium)
        ModeRow(
            selected = engine == SttEngine.SYSTEM,
            title = "시스템 인식기",
            description = "휴대폰에 내장된 음성 인식. 한국어만 인식합니다.",
            onClick = { settings.setSttEngine(SttEngine.SYSTEM) },
        )
        ModeRow(
            selected = engine == SttEngine.WHISPER,
            title = "Whisper",
            description = "휴대폰에서 직접 돌리는 모델. 느리지만 정확도가 높을 수 있습니다.",
            onClick = { settings.setSttEngine(SttEngine.WHISPER) },
        )

        if (engine == SttEngine.WHISPER) {
            Spacer(modifier = Modifier.height(16.dp))
            WhisperSection(settings)
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text(text = "문서 생성 모델", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "모델을 고르면 받아쓰기가 끝난 뒤 자동으로 문서를 만듭니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val llmModel by settings.llmModel.collectAsState()
        ModeRow(
            selected = llmModel == null,
            title = "사용 안 함",
            description = "전사본만 저장합니다.",
            onClick = { settings.setLlmModel(null) },
        )
        ModelPicker(
            kind = ModelKind.LLM,
            selected = llmModel,
            emptyHint = "Gemma .task 파일을 휴대폰에 받은 뒤 가져오세요.",
            onSelect = { settings.setLlmModel(it) },
        )
        Spacer(modifier = Modifier.height(24.dp))
    }
}

/** Lists imported model files of one [kind] as choices, with an import button. */
@Composable
private fun ModelPicker(
    kind: ModelKind,
    selected: String?,
    emptyHint: String,
    onSelect: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var models by remember { mutableStateOf(ModelFiles.list(context, kind)) }
    var importStatus by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            importStatus = try {
                val file = ModelFiles.import(context, uri, kind) { copied ->
                    importStatus = "가져오는 중… ${copied / (1 shl 20)}MB"
                }
                models = ModelFiles.list(context, kind)
                if (selected == null) onSelect(file.name)
                "가져왔습니다: ${file.name}"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "가져오지 못했습니다: ${e.message}"
            } finally {
                importing = false
            }
        }
    }

    if (models.isEmpty()) {
        Text(
            text = "모델이 없습니다. $emptyHint",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    for (model in models) {
        ModeRow(
            selected = model.name == selected,
            title = ModelFiles.label(model.name),
            description = "${model.length() / (1 shl 20)}MB",
            onClick = { onSelect(model.name) },
        )
    }
    OutlinedButton(enabled = !importing, onClick = { picker.launch(arrayOf("*/*")) }) {
        Text("모델 가져오기")
    }
    importStatus?.let {
        Text(text = it, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun WhisperSection(settings: AppSettings) {
    val selectedModel by settings.whisperModel.collectAsState()
    val language by settings.whisperLanguage.collectAsState()

    Text(text = "Whisper 모델", style = MaterialTheme.typography.titleSmall)
    ModelPicker(
        kind = ModelKind.WHISPER,
        selected = selectedModel,
        emptyHint = "ggml-….bin 파일을 휴대폰에 받은 뒤 가져오세요.",
        onSelect = { settings.setWhisperModel(it) },
    )
    if (!WhisperNative.loaded) {
        Text(
            text = "Whisper 엔진이 아직 빌드에 포함되지 않았습니다.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    Spacer(modifier = Modifier.height(16.dp))
    Text(text = "인식 언어", style = MaterialTheme.typography.titleSmall)
    ModeRow(
        selected = language == "ko",
        title = "한국어",
        description = "한국어로 고정합니다.",
        onClick = { settings.setWhisperLanguage("ko") },
    )
    ModeRow(
        selected = language == "auto",
        title = "자동 감지",
        description = "조각마다 언어를 추정합니다. 영어로만 말할 때 사용하세요.",
        onClick = { settings.setWhisperLanguage("auto") },
    )
}

@Composable
private fun ModeRow(selected: Boolean, title: String, description: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
