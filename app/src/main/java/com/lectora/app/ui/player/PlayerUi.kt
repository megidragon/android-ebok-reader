package com.lectora.app.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lectora.app.ui.components.BookCover
import com.lectora.narration.NarrationState
import com.lectora.narration.NarrationStatus
import com.lectora.narration.SleepTimer

/** Barra inferior con lo que se está narrando (visible en toda la app). */
@Composable
fun MiniPlayer(state: NarrationState, onClick: () -> Unit, onToggle: () -> Unit) {
    val title = state.title
    if (state.bookId == null || title == null) return
    Surface(tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BookCover(state.coverPath, title, Modifier.size(width = 36.dp, height = 52.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    state.error ?: state.current?.text ?: state.sectionTitle.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            PlayPauseButton(state, onToggle, small = true)
        }
    }
}

@Composable
fun PlayPauseButton(state: NarrationState, onToggle: () -> Unit, small: Boolean = false) {
    val size = if (small) 44.dp else 60.dp
    Box(contentAlignment = Alignment.Center) {
        FilledIconButton(onClick = onToggle, modifier = Modifier.size(size)) {
            Icon(
                if (state.isActive) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (state.isActive) "Pausar" else "Narrar",
                modifier = Modifier.size(size * 0.55f),
            )
        }
        if (state.status == NarrationStatus.PREPARING) {
            CircularProgressIndicator(Modifier.size(size + 6.dp), strokeWidth = 2.dp)
        }
    }
}

/** Controles completos dentro del lector. */
@Composable
fun ReaderPlayerBar(
    state: NarrationState,
    isThisBook: Boolean,
    speechRate: Float,
    onToggle: () -> Unit,
    onPrevParagraph: () -> Unit,
    onNextParagraph: () -> Unit,
    onPrevSection: () -> Unit,
    onNextSection: () -> Unit,
    onRate: (Float) -> Unit,
    onSleep: (Int?) -> Unit,
) {
    val shown = if (isThisBook) state else NarrationState()
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp)) {
            if (isThisBook) {
                Text(
                    shown.error ?: shown.voiceName?.let { "Voz: $it" } ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (shown.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SpeedChip(speechRate, onRate)
                IconButton(onClick = onPrevSection, enabled = isThisBook) { Icon(Icons.Default.SkipPrevious, "Capítulo anterior") }
                IconButton(onClick = onPrevParagraph, enabled = isThisBook) { Icon(Icons.Default.FastRewind, "Párrafo anterior") }
                PlayPauseButton(shown, onToggle)
                IconButton(onClick = onNextParagraph, enabled = isThisBook) { Icon(Icons.Default.FastForward, "Párrafo siguiente") }
                IconButton(onClick = onNextSection, enabled = isThisBook) { Icon(Icons.Default.SkipNext, "Capítulo siguiente") }
                SleepButton(shown.sleepTimer, onSleep, enabled = isThisBook)
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}

@Composable
private fun SpeedChip(rate: Float, onRate: (Float) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AssistChip(onClick = { open = true }, label = { Text("${"%.2f".format(rate).trimEnd('0').trimEnd('.', ',')}×") })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(0.75f, 0.9f, 1f, 1.1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f).forEach { r ->
                DropdownMenuItem(text = { Text("$r×") }, onClick = { open = false; onRate(r) })
            }
        }
    }
}

@Composable
private fun SleepButton(timer: SleepTimer, onSleep: (Int?) -> Unit, enabled: Boolean) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = enabled) {
            Icon(
                Icons.Default.Bedtime,
                contentDescription = "Temporizador",
                tint = if (timer != SleepTimer.Off) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (timer != SleepTimer.Off) {
                DropdownMenuItem(text = { Text("Desactivar temporizador") }, onClick = { open = false; onSleep(null) })
            }
            DropdownMenuItem(text = { Text("Al terminar el capítulo") }, onClick = { open = false; onSleep(0) })
            listOf(10, 15, 30, 45, 60, 90).forEach { m ->
                DropdownMenuItem(text = { Text("$m minutos") }, onClick = { open = false; onSleep(m) })
            }
        }
    }
}
