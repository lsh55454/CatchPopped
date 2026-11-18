package com.mochigalbi.catchpopped.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.mochigalbi.catchpopped.settings.AppSettings
import com.mochigalbi.catchpopped.settings.RecordMode

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { AppSettings.get(context) }
    val mode by settings.recordMode.collectAsState()
    val denoise by settings.denoise.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
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
    }
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
