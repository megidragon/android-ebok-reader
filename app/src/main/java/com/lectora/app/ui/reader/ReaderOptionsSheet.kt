package com.lectora.app.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lectora.app.data.PageMode
import com.lectora.app.data.ReaderTheme
import com.lectora.core.model.TranslationMode
import java.util.Locale

private fun languageName(code: String): String =
    Locale.forLanguageTag(code).getDisplayLanguage(Locale.forLanguageTag("es")).replaceFirstChar { it.titlecase() }

/**
 * Panel de opciones del lector: todo lo configurable de la lectura en un solo sitio
 * (apariencia, cómo pasar página, voz y narración, traducción y herramientas).
 * Los cambios se aplican al momento; los de voz/velocidad regeneran la narración en curso.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderOptionsSheet(
    vm: ReaderViewModel,
    isPdf: Boolean,
    showingOriginalPdf: Boolean,
    onToggleOriginalPdf: () -> Unit,
    onOpenFilter: () -> Unit,
    onOpenPrerender: () -> Unit,
    onDismiss: () -> Unit,
) {
    val reader by vm.readerSettings.collectAsState()
    val narration by vm.narrationSettings.collectAsState()
    val voices by vm.voices.collectAsState()
    val spoken = vm.spokenLanguage()
    LaunchedEffect(spoken) { vm.loadVoices() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
        ) {
            Text("Opciones de lectura", style = MaterialTheme.typography.titleLarge)

            // ---------- Apariencia ----------
            SectionTitle("Apariencia")
            Label("Tema")
            ChipRow(
                options = listOf(ReaderTheme.SYSTEM to "Sistema", ReaderTheme.LIGHT to "Claro", ReaderTheme.SEPIA to "Sepia", ReaderTheme.DARK to "Oscuro"),
                selected = reader.theme,
                onSelect = { vm.setTheme(it) },
            )
            SliderRow("Tamaño de letra", reader.fontScale, 0.7f..2.2f, steps = 14, format = { "${(it * 100).toInt()} %" }, onDone = { vm.setFontScale(it) })
            SliderRow("Interlineado", reader.lineHeight, 1.1f..2.2f, steps = 10, format = { "%.1f".format(it) }, onDone = { vm.setLineHeight(it) })

            // ---------- Pasar página ----------
            SectionTitle("Pasar página")
            ChipRow(
                options = listOf(PageMode.BUTTONS to "Botones", PageMode.SWIPE to "Deslizar", PageMode.CONTINUOUS to "Scroll continuo"),
                selected = reader.pageMode,
                onSelect = { vm.setPageMode(it) },
            )
            Hint(
                when (reader.pageMode) {
                    PageMode.BUTTONS -> "Una ${if (isPdf) "página" else "sección"} por pantalla, con botones Anterior/Siguiente al final."
                    PageMode.SWIPE -> "Desliza de derecha a izquierda para avanzar y al revés para volver, con animación."
                    PageMode.CONTINUOUS -> "El libro fluye sin cortes: las páginas se cargan solas al desplazarte."
                }
            )

            // ---------- Narración ----------
            SectionTitle("Narración")
            Label("Voz (${languageName(spoken).lowercase()})")
            VoicePicker(voices, narration.voiceByLanguage[spoken], onSelect = vm::setVoice, onPreview = vm::previewVoice)
            SliderRow("Velocidad", narration.speechRate, 0.5f..2.5f, steps = 19, format = { "%.2f×".format(it) }, onDone = { vm.setSpeechRate(it) })
            SliderRow("Tono", narration.pitch, 0.5f..2.0f, steps = 14, format = { "%.2f".format(it) }, onDone = { vm.setPitch(it) })
            SliderRow(
                "Pausa entre oraciones", narration.sentencePauseMs.toFloat(), 0f..800f, steps = 15,
                format = { "${it.toInt()} ms" }, onDone = { vm.setSentencePause(it.toInt()) },
            )
            SliderRow(
                "Pausa entre párrafos", narration.paragraphPauseMs.toFloat(), 0f..1500f, steps = 14,
                format = { "${it.toInt()} ms" }, onDone = { vm.setParagraphPause(it.toInt()) },
            )

            // ---------- Traducción ----------
            SectionTitle("Traducción")
            ChipRow(
                options = listOf(TranslationMode.ORIGINAL to "Original", TranslationMode.TRANSLATED to "Traducido", TranslationMode.BILINGUAL to "Bilingüe"),
                selected = narration.translationMode,
                onSelect = { vm.setTranslationMode(it) },
            )
            LanguagePicker("Traducir a", narration.targetLanguage, vm.translationLanguages, onSelect = vm::setTargetLanguage)
            Hint("Libro en ${languageName(vm.sourceLanguage).lowercase()}. Se traduce sin conexión; la primera vez se descarga el idioma.")

            // ---------- Herramientas ----------
            SectionTitle("Herramientas")
            ToolButton(Icons.Default.FilterAlt, "Filtro de texto (quitar caracteres rotos)") { onDismiss(); onOpenFilter() }
            ToolButton(Icons.Default.Download, "Pregenerar narración") { onDismiss(); onOpenPrerender() }
            if (isPdf) {
                ToolButton(
                    if (showingOriginalPdf) Icons.Default.TextFields else Icons.Default.Image,
                    if (showingOriginalPdf) "Ver como texto" else "Ver página original (PDF)",
                ) { onDismiss(); onToggleOriginalPdf() }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(16.dp))
    HorizontalDivider()
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun <T> ChipRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(label) },
                leadingIcon = if (value == selected) ({ Icon(Icons.Default.Check, null) }) else null,
            )
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    format: (Float) -> String,
    onDone: (Float) -> Unit,
) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.padding(top = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(format(local), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = local, onValueChange = { local = it }, onValueChangeFinished = { onDone(local) }, valueRange = range, steps = steps)
    }
}

@Composable
private fun VoicePicker(
    voices: List<com.lectora.engine.tts.VoiceInfo>,
    selectedKey: String?,
    onSelect: (com.lectora.engine.tts.VoiceInfo) -> Unit,
    onPreview: (com.lectora.engine.tts.VoiceInfo) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val selected = voices.firstOrNull { it.key == selectedKey }
    val shown = selected ?: voices.firstOrNull()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    when {
                        voices.isEmpty() -> "Cargando voces…"
                        selected == null -> "Automática · ${shown?.displayName}"
                        else -> selected.displayName
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                voices.forEach { v ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(v.displayName, maxLines = 2)
                                Text(
                                    if (v.engineId.startsWith("neural:")) "IA en el dispositivo" else "Voz del sistema",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        trailingIcon = { if (v.key == shown?.key) Icon(Icons.Default.Check, null) },
                        onClick = { open = false; onSelect(v) },
                    )
                }
            }
        }
        IconButton(onClick = { shown?.let(onPreview) }, enabled = shown != null) {
            Icon(Icons.AutoMirrored.Filled.VolumeUp, "Escuchar la voz")
        }
    }
}

@Composable
private fun LanguagePicker(label: String, selected: String, languages: List<String>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
        Text("$label:", style = MaterialTheme.typography.bodyMedium)
        Box(Modifier.padding(start = 8.dp)) {
            OutlinedButton(onClick = { open = true }) { Text(languageName(selected)) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                languages.forEach { lang ->
                    DropdownMenuItem(
                        text = { Text(languageName(lang)) },
                        trailingIcon = { if (lang == selected) Icon(Icons.Default.Check, null) },
                        onClick = { open = false; onSelect(lang) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Icon(icon, null)
        Spacer(Modifier.padding(start = 8.dp))
        Text(label, modifier = Modifier.weight(1f))
    }
}
