package com.mochigalbi.catchpopped.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mochigalbi.catchpopped.data.Idea
import com.mochigalbi.catchpopped.data.IdeaRepository
import com.mochigalbi.catchpopped.data.IdeaStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(onRecord: () -> Unit, onOpen: (Long) -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { IdeaRepository.get(context) }
    val ideas by repository.ideas.collectAsState()
    HomeContent(ideas = ideas, onRecord = onRecord, onOpen = onOpen, onSettings = onSettings)
}

@Composable
private fun HomeContent(
    ideas: List<Idea>,
    onRecord: () -> Unit,
    onOpen: (Long) -> Unit,
    onSettings: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "아이디어",
                    style = MaterialTheme.typography.headlineLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onSettings) { Text("설정") }
            }

            if (ideas.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "아직 아이디어가 없습니다.\n아래 버튼을 눌러 말해 보세요.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(ideas, key = { it.id }) { idea ->
                        IdeaRow(idea = idea, onClick = { onOpen(idea.id) })
                        HorizontalDivider()
                    }
                }
            }
        }

        ExtendedFloatingActionButton(
            onClick = onRecord,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(24.dp),
        ) {
            Text("녹음")
        }
    }
}

@Composable
private fun IdeaRow(idea: Idea, onClick: () -> Unit) {
    val formatter = remember { SimpleDateFormat("M월 d일 HH:mm", Locale.KOREAN) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Text(
            text = idea.title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = formatter.format(Date(idea.createdAt)) + " · " + when {
                idea.status == IdeaStatus.PROCESSING -> "처리 중"
                idea.status == IdeaStatus.FAILED -> "실패"
                idea.documentMd != null -> "문서"
                else -> "전사본"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HomePreview() {
    MaterialTheme {
        HomeContent(
            ideas = listOf(
                Idea(id = 2, createdAt = 0, durationMs = 42_000, transcript = "샤워하다가 떠오른 생각인데", documentMd = "# 샤워 중 메모 앱"),
                Idea(id = 1, createdAt = 0, durationMs = 15_000, transcript = "산책하면서 녹음한 아이디어"),
            ),
            onRecord = {},
            onOpen = {},
            onSettings = {},
        )
    }
}
