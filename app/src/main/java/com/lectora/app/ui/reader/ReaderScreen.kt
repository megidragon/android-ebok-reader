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
import com.lectora.app.data.PageMode
import com.lectora.app.data.displayTitle
import com.lectora.app.ui.components.LocalTitleSource
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.ui.graphics.luminance
import com.lectora.app.data.ReaderTheme
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.rememberUpdatedState
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
    val sections by vm.sections.collectAsState()
    val readerSettings by vm.readerSettings.collectAsState()
    val narrationSettings by vm.narrationSettings.collectAsState()
    val narration by player.state.collectAsState()
    val isThisBook = narration.bookId == bookId
    val current: Utterance? = if (isThisBook) narration.current else null
    val colors = pageColors(readerSettings.theme)
    val pageMode = readerSettings.pageMode

    // Iconos de la barra de estado legibles sobre la página (oscuros en temas claro/sepia).
    val view = androidx.compose.ui.platform.LocalView.current
    val lightPage = colors.background.luminance() > 0.5f
    androidx.compose.runtime.DisposableEffect(lightPage) {
        val window = (view.context as? android.app.Activity)?.window
        val controller = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        val previous = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = lightPage
        onDispose { if (previous != null) controller.isAppearanceLightStatusBars = previous }
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var follow by rememberSaveable { mutableStateOf(true) }
    var showToc by remember { mutableStateOf(false) }
    var showOriginalPdf by rememberSaveable { mutableStateOf(false) }
    var showFilter by remember { mutableStateOf(false) }
    var showPrerender by remember { mutableStateOf(false) }
    var showOptions by remember { mutableStateOf(false) }
    val mode = narrationSettings.translationMode
    val translating = narrationSettings.translates(vm.sourceLanguage)
    val effectiveMode = if (translating) mode else TranslationMode.ORIGINAL
    val currentContent = sections[ui.section]
    val shownTitle = displayTitle(ui.sectionTitle, currentContent?.translatedTitle, effectiveMode)

    // Estado de desplazamiento propio de cada modo.
    val pageListStates = remember { mutableMapOf<Int, LazyListState>() }
    fun pageState(section: Int) = pageListStates.getOrPut(section) { LazyListState() }
    val continuous = remember { ContinuousState(ui.section) }
    val narrationOwnsProgress by rememberUpdatedState(isThisBook && narration.isActive)
    val onNarrateFrom: (Int, Int) -> Unit = { section, paragraph ->
        follow = true
        player.playBook(bookId, ReadingPosition(section, paragraph, 0))
    }
    val ctx = ParagraphContext(current, effectiveMode, colors, readerSettings.fontScale, readerSettings.lineHeight, onNarrateFrom)

    /** Párrafo visible en el modo actual (para "play" desde lo que se ve). */
    fun visibleParagraph(): Int = when (pageMode) {
        PageMode.BUTTONS -> (listState.firstVisibleItemIndex - 1).coerceAtLeast(0)
        PageMode.SWIPE -> (pageState(ui.section).firstVisibleItemIndex - 1).coerceAtLeast(0)
        PageMode.CONTINUOUS -> continuous.firstVisible()?.second ?: 0
    }

    // Saltos pedidos por el ViewModel (índice, posición guardada, cambio de capítulo).
    LaunchedEffect(ui.scrollRequest, pageMode) {
        if (ui.loading) return@LaunchedEffect
        val target = ui.scrollToParagraph + 1
        when (pageMode) {
            PageMode.BUTTONS -> if (ui.paragraphs.isNotEmpty()) listState.scrollToItem(target)
            PageMode.SWIPE -> {
                continuous.pager?.let { pager -> if (pager.currentPage != ui.section) pager.scrollToPage(ui.section) }
                pageState(ui.section).scrollToItem(target)
            }
            PageMode.CONTINUOUS -> continuous.jumpTo(ui.section, ui.scrollToParagraph)
        }
    }

    // Seguir la narración: llevar la vista a la sección y al párrafo que suenan.
    LaunchedEffect(current?.position?.section, current?.position?.paragraph, follow, pageMode) {
        val pos = current?.position ?: return@LaunchedEffect
        if (!follow) return@LaunchedEffect
        when (pageMode) {
            PageMode.BUTTONS -> if (pos.section != ui.section) {
                vm.loadSection(pos.section, pos.paragraph)
            } else {
                listState.revealItem(pos.paragraph + 1)
            }
            PageMode.SWIPE -> {
                val pager = continuous.pager
                if (pager != null && pager.currentPage != pos.section) {
                    if (kotlin.math.abs(pager.currentPage - pos.section) == 1) pager.animateScrollToPage(pos.section)
                    else pager.scrollToPage(pos.section)
                }
                pageState(pos.section).revealItem(pos.paragraph + 1)
            }
            PageMode.CONTINUOUS -> {
                val index = continuous.indexOf(pos.section, pos.paragraph)
                if (index != null) continuous.list.revealItem(index) else vm.loadSection(pos.section, pos.paragraph)
            }
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
                        Text(ui.book?.displayTitle(LocalTitleSource.current).orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
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
                    IconButton(onClick = { showToc = true }) { Icon(Icons.AutoMirrored.Filled.List, "Índice") }
                    IconButton(onClick = { showOptions = true }) { Icon(Icons.Default.Tune, "Opciones de lectura") }
                    var overflow by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { overflow = true }) { Icon(Icons.Default.MoreVert, "Más opciones") }
                        DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                            DropdownMenuItem(
                                text = { Text("Opciones de lectura…") },
                                leadingIcon = { Icon(Icons.Default.Tune, null) },
                                onClick = { overflow = false; showOptions = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Filtro de texto…") },
                                leadingIcon = { Icon(Icons.Default.FilterAlt, null) },
                                onClick = { overflow = false; showFilter = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Pregenerar narración…") },
                                leadingIcon = { Icon(Icons.Default.Download, null) },
                                onClick = { overflow = false; showPrerender = true },
                            )
                            if (ui.isPdf) {
                                DropdownMenuItem(
                                    text = { Text(if (showOriginalPdf) "Ver como texto" else "Ver página original (PDF)") },
                                    leadingIcon = { Icon(if (showOriginalPdf) Icons.Default.TextFields else Icons.Default.Image, null) },
                                    onClick = { overflow = false; showOriginalPdf = !showOriginalPdf },
                                )
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            ReaderPlayerBar(
                state = narration,
                isThisBook = isThisBook,
                speechRate = narrationSettings.speechRate,
                onToggle = {
                    val visibleParagraph = visibleParagraph()
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
                pageMode == PageMode.BUTTONS -> {
                    // Una sección por pantalla, con botones.
                    val content = currentContent ?: SectionContent(ui.section, ui.sectionTitle, ui.paragraphs)
                    SectionList(
                        content = content,
                        state = listState,
                        ctx = ctx,
                        onUserDrag = { follow = false },
                        onFirstVisible = { p -> vm.onParagraphVisible(p, narrationOwnsProgress) },
                        footer = {
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 32.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                if (ui.section > 0) OutlinedButton(onClick = vm::previousSection) { Text("Anterior") } else Spacer(Modifier)
                                if (ui.section < ui.sectionCount - 1) Button(onClick = vm::nextSection) { Text("Siguiente") }
                            }
                        },
                    )
                }
                pageMode == PageMode.SWIPE -> {
                    // Deslizar: derecha→izquierda = siguiente, izquierda→derecha = anterior.
                    val pagerState = rememberPagerState(initialPage = ui.section) { ui.sectionCount }
                    continuous.pager = pagerState
                    LaunchedEffect(pagerState) {
                        snapshotFlow { pagerState.settledPage }.distinctUntilChanged().collect { page ->
                            if (page != ui.section) {
                                vm.onSectionVisible(page, (pageState(page).firstVisibleItemIndex - 1).coerceAtLeast(0), narrationOwnsProgress)
                            }
                        }
                    }
                    LaunchedEffect(pagerState) {
                        pagerState.interactionSource.interactions.collect { if (it is DragInteraction.Start) follow = false }
                    }
                    HorizontalPager(
                        state = pagerState,
                        beyondViewportPageCount = 1,
                        key = { it },
                        modifier = Modifier.fillMaxSize(),
                    ) { page ->
                        LaunchedEffect(page) { vm.requestSection(page) }
                        val content = sections[page]
                        if (content == null) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        } else {
                            SectionList(
                                content = content,
                                state = pageState(page),
                                ctx = ctx,
                                onUserDrag = { follow = false },
                                onFirstVisible = { p -> if (page == ui.section) vm.onParagraphVisible(p, narrationOwnsProgress) },
                                footer = {
                                    Text(
                                        if (page < ui.sectionCount - 1) "Desliza ← para la página siguiente" else "Fin del libro",
                                        color = colors.secondaryText,
                                        style = MaterialTheme.typography.labelMedium,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                                    )
                                },
                            )
                        }
                    }
                }
                else -> ContinuousReader(
                    state = continuous,
                    sections = sections,
                    sectionCount = ui.sectionCount,
                    currentSection = ui.section,
                    ctx = ctx,
                    requestSection = vm::requestSection,
                    onUserDrag = { follow = false },
                    onVisible = { s, p -> vm.onSectionVisible(s, p, narrationOwnsProgress) },
                )
            }
        }
        }
    }

    if (showOptions) {
        ReaderOptionsSheet(
            vm = vm,
            isPdf = ui.isPdf,
            showingOriginalPdf = showOriginalPdf,
            onToggleOriginalPdf = { showOriginalPdf = !showOriginalPdf },
            onOpenFilter = { showFilter = true },
            onOpenPrerender = { showPrerender = true },
            onDismiss = { showOptions = false },
        )
    }
    if (showFilter) TextFilterDialog(vm, isPdf = ui.isPdf, onDismiss = { showFilter = false })
    if (showPrerender) PrerenderDialog(vm, currentSection = ui.section, sectionCount = ui.sectionCount, isPdf = ui.isPdf, onDismiss = { showPrerender = false })

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

/** [3400, 3401, 3402, 3500] -> "3401–3403, 3501" (números para el usuario, base 1). */
private fun formatRanges(sections: List<Int>): String {
    if (sections.isEmpty()) return ""
    val out = ArrayList<String>()
    var start = sections[0]
    var prev = start
    for (s in sections.drop(1) + Int.MIN_VALUE) {
        if (s == prev + 1) {
            prev = s
            continue
        }
        out.add(if (start == prev) "${start + 1}" else "${start + 1}–${prev + 1}")
        start = s
        prev = s
    }
    return out.joinToString(", ")
}

/**
 * Pregenerar narración: genera y guarda el audio de varias páginas/capítulos por adelantado,
 * con la voz e idioma elegidos. Pensado para voces pesadas que no llegan a tiempo real.
 */
@Composable
private fun PrerenderDialog(vm: ReaderViewModel, currentSection: Int, sectionCount: Int, isPdf: Boolean, onDismiss: () -> Unit) {
    val p by vm.prerender.collectAsState()
    LaunchedEffect(Unit) { vm.refreshPrerender() }
    val unitPlural = if (isPdf) "páginas" else "capítulos"
    val unitSingular = if (isPdf) "página" else "capítulo"
    // 0 = página actual, 1 = última leída, 2 = otra
    var origin by remember { mutableStateOf(0) }
    var custom by remember { mutableStateOf("") }
    var count by remember { mutableStateOf(10f) }
    var voiceMenu by remember { mutableStateOf(false) }
    val from = when (origin) {
        1 -> p.lastReadSection ?: currentSection
        2 -> (custom.toIntOrNull()?.minus(1))?.coerceIn(0, (sectionCount - 1).coerceAtLeast(0)) ?: currentSection
        else -> currentSection
    }
    val maxCount = minOf(200, (sectionCount - from).coerceAtLeast(1))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pregenerar narración") },
        text = {
            LazyColumn(Modifier.height(500.dp)) {
                item {
                    Text(
                        "Genera ahora el audio de varias $unitPlural y lo guarda en el móvil. Después se reproduce al instante, aunque la voz sea lenta. Sigue en segundo plano con la pantalla apagada.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("Voz", style = MaterialTheme.typography.titleSmall)
                    Box {
                        OutlinedButton(onClick = { voiceMenu = true }, enabled = !p.running) {
                            Text(p.voiceName ?: "Cargando…", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        DropdownMenu(expanded = voiceMenu, onDismissRequest = { voiceMenu = false }) {
                            p.voices.forEach { v ->
                                DropdownMenuItem(
                                    text = { Text(v.displayName, maxLines = 2) },
                                    trailingIcon = { if (v.key == p.voiceKey) Icon(Icons.Default.Check, null) },
                                    onClick = { voiceMenu = false; vm.choosePrerenderVoice(v) },
                                )
                            }
                        }
                    }
                    p.spokenLanguage?.let { lang ->
                        val name = java.util.Locale.forLanguageTag(lang).getDisplayLanguage(java.util.Locale.forLanguageTag("es"))
                        Text(
                            "Idioma: $name" + if (p.translated) " (traducido)" else " (original)",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        "Usa la traducción, velocidad y pausas actuales. Si las cambias, el audio pregenerado deja de coincidir y se narra en vivo.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("Desde", style = MaterialTheme.typography.titleSmall)
                    listOf(
                        "${unitSingular.replaceFirstChar { it.uppercase() }} actual (${currentSection + 1})",
                        "Última leída (${(p.lastReadSection ?: currentSection) + 1})",
                        "Otra:",
                    ).forEachIndexed { i, label ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { origin = i }) {
                            androidx.compose.material3.RadioButton(selected = origin == i, onClick = { origin = i })
                            Text(label)
                            if (i == 2) {
                                OutlinedTextField(
                                    custom, { custom = it.filter(Char::isDigit).take(6); origin = 2 },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.padding(start = 8.dp).fillMaxWidth(0.6f),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    val n = count.toInt().coerceIn(1, maxCount)
                    Text("¿Cuántas $unitPlural?", style = MaterialTheme.typography.titleSmall)
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(5, 10, 25, 50, 100).filter { it <= maxCount || it == 5 }.forEach { option ->
                            androidx.compose.material3.FilterChip(
                                selected = n == option.coerceAtMost(maxCount),
                                onClick = { count = option.toFloat() },
                                label = { Text("$option") },
                                enabled = !p.running,
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Otra cantidad:", style = MaterialTheme.typography.bodyMedium)
                        OutlinedTextField(
                            value = n.toString(),
                            onValueChange = { v -> v.filter(Char::isDigit).take(3).toIntOrNull()?.let { count = it.toFloat() } },
                            singleLine = true,
                            enabled = !p.running,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.padding(start = 8.dp).fillMaxWidth(0.45f),
                        )
                    }
                    Text(
                        "$n $unitPlural: de la ${from + 1} a la ${from + n} · aprox. ${"%.0f".format(n * 1.2)} MB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (p.running) {
                        Text(
                            "Generando ${p.done} de ${p.total} · $unitSingular ${p.currentSection + 1}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { if (p.total > 0) p.done.toFloat() / p.total else 0f },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        )
                        OutlinedButton(onClick = { vm.cancelPrerender() }) { Text("Cancelar") }
                    } else {
                        Button(onClick = { vm.startPrerender(from, n) }, enabled = p.voiceName != null) { Text("Pregenerar") }
                    }
                    p.error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Text("Ya pregenerado con esta voz", style = MaterialTheme.typography.titleSmall)
                    if (p.rendered.isEmpty()) {
                        Text("Nada todavía.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text(
                            "${unitPlural.replaceFirstChar { it.uppercase() }} ${formatRanges(p.rendered)} · ${"%.1f".format(p.bytes / 1_048_576.0)} MB",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { vm.deletePrerender() }, enabled = !p.running) { Text("Borrar audio pregenerado de este libro") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}

/**
 * Filtro personal: reemplazar o quitar textos/caracteres que rompen la lectura o la narración.
 * Las reglas se aplican al libro actual (o a todos) en el lector, la traducción y la narración.
 */
@Composable
private fun TextFilterDialog(vm: ReaderViewModel, isPdf: Boolean, onDismiss: () -> Unit) {
    val rules by vm.filterRules.collectAsState()
    var find by remember { mutableStateOf("") }
    var replace by remember { mutableStateOf("") }
    var regex by remember { mutableStateOf(false) }
    var global by remember { mutableStateOf(false) }
    val matches by produceState<Int?>(null, find, regex) {
        value = null
        if (find.isNotEmpty()) {
            kotlinx.coroutines.delay(250)
            value = vm.previewMatches(find, regex)
        }
    }
    val suspicious by produceState<List<Pair<String, Int>>?>(null, rules) { value = vm.suspiciousCharacters() }
    val unit = if (isPdf) "esta página" else "este capítulo"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filtro de texto") },
        text = {
            LazyColumn(Modifier.height(480.dp)) {
                item {
                    Text(
                        "Reemplaza o quita textos rotos. Se aplica al leer, traducir y narrar.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(find, { find = it }, label = { Text("Buscar") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(
                        replace, { replace = it },
                        label = { Text("Reemplazar por (vacío = quitar)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(regex, { regex = it })
                        Text("Expresión regular", style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(global, { global = it })
                        Text("Aplicar a todos los libros", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (find.isNotEmpty()) {
                        Text(
                            matches?.let { if (it == 1) "1 coincidencia en $unit" else "$it coincidencias en $unit" } ?: "Buscando…",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Button(
                        onClick = {
                            vm.addFilter(find, replace, regex, global)
                            find = ""; replace = ""; regex = false
                        },
                        enabled = find.isNotEmpty(),
                        modifier = Modifier.padding(top = 4.dp),
                    ) { Text("Añadir regla") }
                }
                val odd = suspicious
                if (!odd.isNullOrEmpty()) {
                    item {
                        Spacer(Modifier.height(12.dp))
                        Text("Caracteres raros en $unit", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Toca uno para quitarlo de este libro.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            odd.forEach { (ch, n) ->
                                androidx.compose.material3.AssistChip(
                                    onClick = { vm.addFilter(ch, "", regex = false, global = false) },
                                    label = { Text("$ch  ×$n  (U+%04X)".format(ch.codePointAt(0))) },
                                )
                            }
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (rules.isEmpty()) "Sin reglas todavía." else "Reglas activas",
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                items(rules.size, key = { rules[it].id }) { i ->
                    val r = rules[i]
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        androidx.compose.material3.Switch(checked = r.enabled, onCheckedChange = { vm.setFilterEnabled(r.id, it) })
                        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                            Text(
                                "«${r.find}» → " + if (r.replace.isEmpty()) "(quitar)" else "«${r.replace}»",
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                listOfNotNull(if (r.regex) "regex" else null, if (r.global) "todos los libros" else "este libro").joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { vm.removeFilter(r.id) }) { Icon(Icons.Default.Delete, "Eliminar regla") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Listo") } },
    )
}

/** Título a mostrar según el modo de traducción (en "Traducido" nunca el original). */
private fun displayTitle(original: String?, translated: String?, mode: TranslationMode): String? = when (mode) {
    TranslationMode.TRANSLATED -> translated
    TranslationMode.BILINGUAL -> if (translated != null && original != null) "$original · $translated" else original
    TranslationMode.ORIGINAL -> original
}

/** Lo que necesita cada párrafo para pintarse (común a los tres modos). */
private class ParagraphContext(
    val current: Utterance?,
    val mode: TranslationMode,
    val colors: PageColors,
    val fontScale: Float,
    val lineHeight: Float,
    val onNarrateFrom: (section: Int, paragraph: Int) -> Unit,
)

/** Desplaza hasta [index] solo si no está ya cómodamente a la vista. */
private suspend fun LazyListState.revealItem(index: Int) {
    val visible = layoutInfo.visibleItemsInfo
    val onScreen = visible.any { it.index == index } && visible.lastOrNull()?.index != index
    if (!onScreen) animateScrollToItem(index, scrollOffset = -120)
}

@Composable
private fun SectionHeader(content: SectionContent, ctx: ParagraphContext) {
    val title = displayTitle(content.title, content.translatedTitle, ctx.mode)
    Column {
        // Muchos EPUB repiten el título como primer párrafo: no duplicarlo.
        title?.takeIf { content.paragraphs.firstOrNull()?.trim() != content.title?.trim() }?.let {
            Text(
                it,
                color = ctx.colors.secondaryText,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Serif,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
        if (content.paragraphs.isEmpty()) {
            Text("(Esta sección no contiene texto.)", color = ctx.colors.secondaryText, modifier = Modifier.padding(vertical = 24.dp))
        }
    }
}

@Composable
private fun SectionParagraph(content: SectionContent, index: Int, ctx: ParagraphContext) {
    val highlight = ctx.current?.takeIf { it.position.section == content.section && it.position.paragraph == index }
    ParagraphItem(
        original = content.paragraphs[index],
        translation = content.translations[index],
        mode = ctx.mode,
        highlight = highlight,
        colors = ctx.colors,
        fontScale = ctx.fontScale,
        lineHeight = ctx.lineHeight,
        onNarrateFromHere = { ctx.onNarrateFrom(content.section, index) },
    )
}

/** Una sección en lista vertical (modos "botones" y "deslizar"). Ítem 0 = cabecera. */
@Composable
private fun SectionList(
    content: SectionContent,
    state: LazyListState,
    ctx: ParagraphContext,
    onUserDrag: () -> Unit,
    onFirstVisible: (paragraph: Int) -> Unit,
    footer: @Composable () -> Unit,
) {
    LaunchedEffect(state, content.section) {
        snapshotFlow { state.firstVisibleItemIndex }.distinctUntilChanged().collect { index ->
            onFirstVisible((index - 1).coerceAtLeast(0))
        }
    }
    LaunchedEffect(state) {
        state.interactionSource.interactions.collect { if (it is DragInteraction.Start) onUserDrag() }
    }
    LazyColumn(
        state = state,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "header") { SectionHeader(content, ctx) }
        items(content.paragraphs.size, key = { "p$it" }) { index -> SectionParagraph(content, index, ctx) }
        item(key = "footer") { footer() }
    }
}

/**
 * Estado del scroll continuo: una "ventana" de secciones consecutivas que crece al acercarse a
 * los bordes y se recorta lejos de la vista (memoria acotada aunque el libro tenga miles de
 * páginas). Las claves estables de cada ítem mantienen la posición al añadir secciones arriba.
 */
@Stable
private class ContinuousState(initialSection: Int) {
    val list = LazyListState()
    var window by mutableStateOf(initialSection..initialSection)
    /** Pager del modo deslizar (se comparte aquí para que los efectos lo encuentren). */
    var pager: PagerState? = null
    /** Claves de los ítems tal como están compuestos ahora. */
    var keys: List<String> = emptyList()
    private var pendingJump: Pair<Int, Int>? = null

    fun indexOf(section: Int, paragraph: Int): Int? = keys.indexOf("p$section:$paragraph").takeIf { it >= 0 }

    fun firstVisible(): Pair<Int, Int>? {
        val key = list.layoutInfo.visibleItemsInfo.firstOrNull()?.key as? String ?: return null
        return parseKey(key)
    }

    suspend fun jumpTo(section: Int, paragraph: Int) {
        indexOf(section, paragraph)?.let {
            list.scrollToItem(it)
            return
        }
        pendingJump = section to paragraph
        window = section..section
    }

    /** Tras recomponer con la ventana nueva, completa el salto pendiente si ya hay contenido. */
    suspend fun completePendingJump() {
        val (s, p) = pendingJump ?: return
        val index = indexOf(s, p) ?: return
        pendingJump = null
        list.scrollToItem(index)
    }

    companion object {
        /** "p12:3" -> (12, 3); "h12" / "l12" -> (12, 0). */
        fun parseKey(key: String): Pair<Int, Int>? = when (key.firstOrNull()) {
            'p' -> key.drop(1).split(':').let { (s, p) -> s.toInt() to p.toInt() }
            'h', 'l' -> key.drop(1).toIntOrNull()?.let { it to 0 }
            else -> null
        }
    }
}

@Composable
private fun ContinuousReader(
    state: ContinuousState,
    sections: Map<Int, SectionContent>,
    sectionCount: Int,
    currentSection: Int,
    ctx: ParagraphContext,
    requestSection: (Int) -> Unit,
    onUserDrag: () -> Unit,
    onVisible: (section: Int, paragraph: Int) -> Unit,
) {
    val window = state.window
    LaunchedEffect(window) { window.forEach(requestSection) }

    // Ítems de la ventana: cabecera + párrafos de cada sección (o un "cargando").
    // paragraph: -1 = cabecera, -2 = cargando.
    val items = remember(window, sections) {
        buildList {
            for (s in window) {
                val content = sections[s]
                if (content == null) {
                    add(ContinuousItem("l$s", s, -2))
                } else {
                    add(ContinuousItem("h$s", s, -1))
                    for (i in content.paragraphs.indices) add(ContinuousItem("p$s:$i", s, i))
                }
            }
        }
    }
    state.keys = items.map { it.key }
    LaunchedEffect(items) { state.completePendingJump() }

    // Ampliar la ventana al acercarse a un borde y recortarla lejos de la vista.
    // (Se lee el mapa de secciones como estado vivo: al terminar de cargar una, se reevalúa.)
    val liveSections by rememberUpdatedState(sections)
    LaunchedEffect(state, sectionCount) {
        snapshotFlow {
            val info = state.list.layoutInfo
            val w = state.window
            EdgeCheck(
                first = info.visibleItemsInfo.firstOrNull()?.index ?: 0,
                last = info.visibleItemsInfo.lastOrNull()?.index ?: 0,
                total = info.totalItemsCount,
                window = w,
                firstLoaded = liveSections[w.first] != null,
                lastLoaded = liveSections[w.last] != null,
            )
        }.distinctUntilChanged().collect { c ->
            val w = c.window
            var next = w
            if (c.total > 0 && c.last >= c.total - 4 && w.last < sectionCount - 1 && c.lastLoaded) next = next.first..(next.last + 1)
            if (c.first <= 2 && w.first > 0 && c.firstLoaded) next = (next.first - 1)..next.last
            if (next.last - next.first + 1 > MAX_WINDOW) {
                val visibleSection = state.firstVisible()?.first ?: currentSection
                next = if (visibleSection - next.first > next.last - visibleSection) (next.first + 1)..next.last else next.first..(next.last - 1)
            }
            if (next != w) state.window = next
        }
    }
    // Sección/párrafo visibles -> título, progreso y precarga.
    LaunchedEffect(state) {
        snapshotFlow { state.firstVisible() }.distinctUntilChanged().collect { pos ->
            if (pos != null) onVisible(pos.first, pos.second)
        }
    }
    LaunchedEffect(state) {
        state.list.interactionSource.interactions.collect { if (it is DragInteraction.Start) onUserDrag() }
    }

    LazyColumn(
        state = state.list,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(items.size, key = { items[it].key }) { index ->
            val item = items[index]
            val content = sections[item.section]
            when {
                content == null || item.paragraph == -2 ->
                    Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                item.paragraph == -1 -> SectionHeader(content, ctx)
                item.paragraph < content.paragraphs.size -> SectionParagraph(content, item.paragraph, ctx)
            }
        }
        if (window.last >= sectionCount - 1) {
            item(key = "end") {
                Text(
                    "Fin del libro",
                    color = ctx.colors.secondaryText,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                )
            }
        }
    }
}

private const val MAX_WINDOW = 6

private data class ContinuousItem(val key: String, val section: Int, val paragraph: Int)

private data class EdgeCheck(
    val first: Int,
    val last: Int,
    val total: Int,
    val window: IntRange,
    val firstLoaded: Boolean,
    val lastLoaded: Boolean,
)

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
