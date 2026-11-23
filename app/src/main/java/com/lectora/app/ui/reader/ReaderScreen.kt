package com.lectora.app.ui.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.lectora.app.data.ReaderTheme
import com.lectora.app.playback.PlayerActions
import com.lectora.app.ui.player.ReaderPlayerBar
import com.lectora.app.ui.theme.PageColors
import com.lectora.app.ui.theme.pageColors
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.lectora.engine.text.TocEntry
import com.lectora.core.model.ReadingPosition
import com.lectora.core.model.TranslationMode
import com.lectora.narration.Utterance
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    vm: ReaderViewModel,
    bookId: Long,
    player: PlayerActions,
    onBack: () -> Unit,
    onOpenTranslationSettings: () -> Unit,
) {
    val ui by vm.ui.collectAsState()
    val readerSettings by vm.readerSettings.collectAsState()
    val narrationSettings by vm.narrationSettings.collectAsState()
    val narration by player.state.collectAsState()
    val isThisBook = narration.bookId == bookId
    val current: Utterance? = if (isThisBook) narration.current else null
    val colors = pageColors(readerSettings.theme)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var follow by rememberSaveable { mutableStateOf(true) }
    var showToc by remember { mutableStateOf(false) }
    var showOriginalPdf by rememberSaveable { mutableStateOf(false) }
    val mode = narrationSettings.translationMode
    val translating = narrationSettings.translates(vm.sourceLanguage)
    // En modo Traducido el título original tampoco se muestra.
    val shownTitle = when {
        translating && mode == TranslationMode.TRANSLATED -> ui.translatedTitle
        translating && mode == TranslationMode.BILINGUAL && ui.translatedTitle != null ->
            "${ui.sectionTitle} · ${ui.translatedTitle}"
        else -> ui.sectionTitle
    }

    // Desplazamiento pedido por el ViewModel (cambio de sección o posición guardada).
    LaunchedEffect(ui.scrollRequest) {
        if (ui.paragraphs.isNotEmpty()) listState.scrollToItem(ui.scrollToParagraph + 1)
    }

    // Seguir la narración: cambiar de sección y desplazar al párrafo que suena.
    LaunchedEffect(current?.position?.section, current?.position?.paragraph, follow) {
        val pos = current?.position ?: return@LaunchedEffect
        if (!follow) return@LaunchedEffect
        if (pos.section != ui.section) {
            vm.loadSection(pos.section, pos.paragraph)
        } else {
            val target = pos.paragraph + 1
            val visible = listState.layoutInfo.visibleItemsInfo
            val onScreen = visible.any { it.index == target } && visible.lastOrNull()?.index != target
            if (!onScreen) listState.animateScrollToItem(target, scrollOffset = -120)
        }
    }

    // Progreso de lectura manual (si se está narrando este libro, la narración lo guarda).
    val narrationOwnsProgress by androidx.compose.runtime.rememberUpdatedState(isThisBook && narration.isActive)
    LaunchedEffect(listState, ui.section) {
        snapshotFlow { listState.firstVisibleItemIndex }.distinctUntilChanged().collect { index ->
            vm.onParagraphVisible((index - 1).coerceAtLeast(0), narrationOwnsProgress)
        }
    }

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.background,
                    titleContentColor = colors.text,
                    navigationIconContentColor = colors.text,
                    actionIconContentColor = colors.text,
                ),
                title = {
                    Column {
                        Text(ui.book?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Text(
                            listOfNotNull(shownTitle, if (ui.sectionCount > 0) "${ui.section + 1}/${ui.sectionCount}" else null).joinToString(" · "),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.secondaryText,
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") } },
                actions = {
                    TranslationMenu(mode, onSelect = vm::setTranslationMode)
                    if (ui.isPdf) {
                        IconButton(onClick = { showOriginalPdf = !showOriginalPdf }) {
                            Icon(if (showOriginalPdf) Icons.Default.TextFields else Icons.Default.Image, "Vista original / texto")
                        }
                    }
                    IconButton(onClick = { showToc = true }) { Icon(Icons.AutoMirrored.Filled.List, "Índice") }
                    AppearanceMenu(readerSettings.fontScale, readerSettings.theme, vm)
                },
            )
        },
        bottomBar = {
            ReaderPlayerBar(
                state = narration,
                isThisBook = isThisBook,
                speechRate = narrationSettings.speechRate,
                onToggle = {
                    val visibleParagraph = (listState.firstVisibleItemIndex - 1).coerceAtLeast(0)
                    when {
                        // Sonando: pausar.
                        isThisBook && narration.isActive -> player.toggle()
                        // En pausa y el usuario sigue en la misma página: retomar donde se quedó.
                        isThisBook && narration.position.section == ui.section -> {
                            follow = true
                            player.toggle()
                        }
                        // Otro libro, o el usuario navegó a otra página/capítulo: narrar lo que está viendo.
                        else -> {
                            follow = true
                            player.playBook(bookId, ReadingPosition(ui.section, visibleParagraph, 0))
                        }
                    }
                },
                onPrevParagraph = player::previousParagraph,
                onNextParagraph = player::nextParagraph,
                onPrevSection = player::previousSection,
                onNextSection = player::nextSection,
                onRate = player::setSpeechRate,
                onSleep = player::setSleepTimer,
            )
        },
        floatingActionButton = {
            if (isThisBook && narration.isActive && !follow) {
                SmallFloatingActionButton(onClick = { follow = true }) { Icon(Icons.Default.MyLocation, "Seguir narración") }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().background(colors.background)) {
        if (translating && ui.translationError != null) {
            Box(Modifier.padding(horizontal = 12.dp)) {
                TranslationBanner(ui.translationError!!, onDownload = vm::downloadMissingModels, onSettings = onOpenTranslationSettings)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                ui.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                ui.error != null -> Text(ui.error!!, color = colors.text, modifier = Modifier.align(Alignment.Center).padding(24.dp))
                ui.isPdf && showOriginalPdf -> PdfPageView(vm, ui.section, colors, onPrev = vm::previousSection, onNext = vm::nextSection)
                else -> LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(key = "header") {
                        Column {
                            // Muchos EPUB repiten el título como primer párrafo: no duplicarlo.
                            shownTitle?.takeIf { ui.paragraphs.firstOrNull()?.trim() != ui.sectionTitle?.trim() }?.let {
                                Text(
                                    it,
                                    color = colors.secondaryText,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontFamily = FontFamily.Serif,
                                    modifier = Modifier.padding(vertical = 12.dp),
                                )
                            }
                            if (ui.paragraphs.isEmpty()) {
                                Text("(Esta sección no contiene texto.)", color = colors.secondaryText, modifier = Modifier.padding(vertical = 24.dp))
                            }
                        }
                    }
                    itemsIndexed(ui.paragraphs, key = { i, _ -> "p$i" }) { index, original ->
                        val highlight = current?.takeIf { it.position.section == ui.section && it.position.paragraph == index }
                        ParagraphItem(
                            original = original,
                            translation = ui.translations[index],
                            mode = if (translating) mode else TranslationMode.ORIGINAL,
                            highlight = highlight,
                            colors = colors,
                            fontScale = readerSettings.fontScale,
                            lineHeight = readerSettings.lineHeight,
                            onNarrateFromHere = {
                                follow = true
                                player.playBook(bookId, ReadingPosition(ui.section, index, 0))
                            },
                        )
                    }
                    item(key = "footer") {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 32.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            if (ui.section > 0) OutlinedButton(onClick = vm::previousSection) { Text("Anterior") } else Spacer(Modifier)
                            if (ui.section < ui.sectionCount - 1) Button(onClick = vm::nextSection) { Text("Siguiente") }
                        }
                    }
                }
            }
        }
        }
    }

    // Arrastre manual durante la narración => desactivar "seguir".
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is androidx.compose.foundation.interaction.DragInteraction.Start) follow = false
        }
    }

    if (showToc) {
        val entries by produceState<List<TocEntry>?>(null) { value = vm.tableOfContents() }
        val unit = if (ui.isPdf) "página" else "sección"
        var target by remember { mutableStateOf("") }
        val targetNumber = target.toIntOrNull()?.takeIf { it in 1..ui.sectionCount }
        fun go(section: Int) {
            showToc = false
            follow = false
            vm.loadSection(section)
        }
        AlertDialog(
            onDismissRequest = { showToc = false },
            title = { Text("Índice") },
            text = {
                Column {
                    // Ir directo a una página: imprescindible en libros de miles de páginas.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = target,
                            onValueChange = { v -> target = v.filter { it.isDigit() }.take(6) },
                            label = { Text("Ir a $unit (1–${ui.sectionCount})") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = { targetNumber?.let { go(it - 1) } }),
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { targetNumber?.let { go(it - 1) } }, enabled = targetNumber != null) { Text("Ir") }
                    }
                    Spacer(Modifier.height(8.dp))
                    val list = entries
                    when {
                        list == null -> Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        list.isEmpty() -> Text(
                            "Este libro no trae índice de capítulos. Usa \"Ir a $unit\".",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                        else -> {
                            // Capítulo actual = último que empieza en o antes de la sección visible.
                            val current = list.indexOfLast { it.section <= ui.section }
                            val tocState = rememberLazyListState(initialFirstVisibleItemIndex = (current - 3).coerceAtLeast(0))
                            LazyColumn(state = tocState, modifier = Modifier.height(380.dp)) {
                                itemsIndexed(list) { i, entry ->
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable { go(entry.section) }
                                            .padding(start = (entry.level * 16).dp, top = 10.dp, bottom = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            entry.title,
                                            fontWeight = if (i == current) FontWeight.Bold else FontWeight.Normal,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f),
                                        )
                                        if (ui.isPdf) {
                                            Text(
                                                "${entry.section + 1}",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(start = 8.dp),
                                            )
                                        }
                                    }
                                    HorizontalDivider()
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showToc = false }) { Text("Cerrar") } },
        )
    }
}

@Composable
private fun ParagraphItem(
    original: String,
    translation: String?,
    mode: TranslationMode,
    highlight: Utterance?,
    colors: PageColors,
    fontScale: Float,
    lineHeight: Float,
    onNarrateFromHere: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val size = (18 * fontScale).sp
    val textStyle = MaterialTheme.typography.bodyLarge.copy(
        fontFamily = FontFamily.Serif,
        fontSize = size,
        lineHeight = lineHeight.em,
        color = colors.text,
        textAlign = TextAlign.Justify,
    )
    fun styled(text: String, highlighted: Boolean): AnnotatedString {
        val h = highlight
        if (!highlighted || h == null || h.end > text.length) return AnnotatedString(text)
        return buildAnnotatedString {
            append(text)
            addStyle(SpanStyle(background = colors.highlight), h.start, h.end)
        }
    }
    Box(Modifier.fillMaxWidth().clickable { menu = true }.padding(vertical = 6.dp)) {
        Column {
            when (mode) {
                TranslationMode.ORIGINAL -> Text(styled(original, highlight?.translated == false), style = textStyle)
                TranslationMode.TRANSLATED -> {
                    // Solo la traducción: el original nunca se muestra en este modo.
                    if (translation != null) {
                        Text(styled(translation, highlight?.translated == true), style = textStyle)
                    } else {
                        Text(
                            "Traduciendo…",
                            style = textStyle.copy(color = colors.secondaryText, fontStyle = FontStyle.Italic),
                        )
                    }
                }
                TranslationMode.BILINGUAL -> {
                    Text(styled(original, highlight?.translated == false), style = textStyle.copy(fontSize = size * 0.9f, color = colors.secondaryText))
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (translation != null) styled(translation, highlight?.translated == true) else AnnotatedString("…"),
                        style = textStyle.copy(fontStyle = FontStyle.Normal),
                    )
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Narrar desde aquí") },
                leadingIcon = { Icon(Icons.Default.PlayArrow, null) },
                onClick = { menu = false; onNarrateFromHere() },
            )
        }
    }
}

@Composable
private fun TranslationMenu(mode: TranslationMode, onSelect: (TranslationMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(
                Icons.Default.Translate,
                "Traducción",
                // Sin traducción: el mismo color que el resto de iconos de la barra.
                tint = if (mode != TranslationMode.ORIGINAL) MaterialTheme.colorScheme.primary else androidx.compose.material3.LocalContentColor.current,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(
                TranslationMode.ORIGINAL to "Original",
                TranslationMode.TRANSLATED to "Traducido",
                TranslationMode.BILINGUAL to "Bilingüe",
            ).forEach { (m, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    trailingIcon = { if (m == mode) Icon(Icons.Default.Check, null) },
                    onClick = { open = false; onSelect(m) },
                )
            }
        }
    }
}

@Composable
private fun AppearanceMenu(fontScale: Float, theme: ReaderTheme, vm: ReaderViewModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Default.TextFields, "Apariencia") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { vm.setFontScale(fontScale - 0.1f) }) { Text("A−", fontSize = 14.sp) }
                Text("${(fontScale * 100).toInt()}%")
                TextButton(onClick = { vm.setFontScale(fontScale + 0.1f) }) { Text("A+", fontSize = 20.sp) }
            }
            HorizontalDivider()
            listOf(
                ReaderTheme.SYSTEM to "Tema del sistema",
                ReaderTheme.LIGHT to "Claro",
                ReaderTheme.SEPIA to "Sepia",
                ReaderTheme.DARK to "Oscuro",
            ).forEach { (t, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    trailingIcon = { if (t == theme) Icon(Icons.Default.Check, null) },
                    onClick = { vm.setTheme(t) },
                )
            }
        }
    }
}

@Composable
private fun TranslationBanner(message: String, onDownload: () -> Unit, onSettings: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(message, style = MaterialTheme.typography.bodyMedium)
            Row {
                TextButton(onClick = onDownload) { Text("Descargar ahora") }
                TextButton(onClick = onSettings) { Text("Ajustes") }
            }
        }
    }
}

@Composable
private fun PdfPageView(vm: ReaderViewModel, page: Int, colors: PageColors, onPrev: () -> Unit, onNext: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = with(LocalDensity.current) { maxWidth.roundToPx() }
        val bitmap by produceState<Bitmap?>(null, page, widthPx) { value = vm.renderPdfPage(page, widthPx) }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            val b = bitmap
            if (b != null) {
                Image(b.asImageBitmap(), "Página ${page + 1}", contentScale = ContentScale.FillWidth, modifier = Modifier.fillMaxWidth())
            } else {
                Box(Modifier.fillMaxWidth().height(400.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                OutlinedButton(onClick = onPrev) { Text("Página anterior") }
                Button(onClick = onNext) { Text("Página siguiente") }
            }
            Text(
                "La narración y la traducción usan el texto extraído; cambia a la vista de texto para ver el resaltado.",
                color = colors.secondaryText,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}
