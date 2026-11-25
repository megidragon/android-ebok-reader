package com.lectora.app.ui.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lectora.app.ui.components.BookCover
import com.lectora.app.ui.components.LocalTitleSource
import com.lectora.app.data.displayTitle
import com.lectora.app.data.LibraryView
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import com.lectora.core.database.BookEntity
import com.lectora.core.database.BookWithProgress
import android.text.format.Formatter
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import com.lectora.app.data.LibraryDisplay
import com.lectora.app.data.LibrarySortField
import com.lectora.core.model.BookFormat
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    vm: LibraryViewModel,
    miniPlayer: @Composable () -> Unit,
    onOpenBook: (Long) -> Unit,
    onOpenCollection: (Long) -> Unit,
    onSettings: () -> Unit,
) {
    val reading by vm.reading.collectAsState()
    val all by vm.all.collectAsState()
    val collections by vm.collections.collectAsState()
    val scanning by vm.scanning.collectAsState()
    val busy by vm.busy.collectAsState()
    val message by vm.message.collectAsState()
    val display by vm.display.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(-1) }
    var addMenu by remember { mutableStateOf(false) }
    var newCollectionDialog by remember { mutableStateOf(false) }
    var bookMenu by remember { mutableStateOf<BookEntity?>(null) }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) vm.importFiles(uris)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let { vm.addFolder(it) }
    }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.message.value = null
        }
    }
    // Pestaña inicial: "Leyendo" si hay algo empezado, si no "Biblioteca".
    val currentTab = if (tab >= 0) tab else if (reading.isNotEmpty()) 0 else 1

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lectora") },
                actions = {
                    if (scanning || busy) {
                        CircularProgressIndicator(Modifier.size(24.dp).padding(2.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = vm::sync) { Icon(Icons.Default.Sync, "Sincronizar carpetas") }
                    }
                    Box {
                        IconButton(onClick = { addMenu = true }) { Icon(Icons.Default.Add, "Agregar libros") }
                        DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Sincronizar una carpeta") },
                                leadingIcon = { Icon(Icons.Default.CreateNewFolder, null) },
                                onClick = { addMenu = false; pickFolder.launch(null) },
                            )
                            DropdownMenuItem(
                                text = { Text("Importar archivos") },
                                leadingIcon = { Icon(Icons.Default.FileOpen, null) },
                                onClick = {
                                    addMenu = false
                                    pickFiles.launch(arrayOf("application/epub+zip", "application/pdf"))
                                },
                            )
                        }
                    }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Ajustes") }
                },
            )
        },
        bottomBar = miniPlayer,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (currentTab == 2) {
                ExtendedFloatingActionButton(onClick = { newCollectionDialog = true }) {
                    Icon(Icons.Default.Add, null)
                    Text("  Nueva colección")
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = currentTab) {
                listOf("Leyendo", "Biblioteca", "Colecciones").forEachIndexed { i, label ->
                    Tab(selected = currentTab == i, onClick = { tab = i }, text = { Text(label) })
                }
            }
            when (currentTab) {
                0 -> BookGrid(reading, onOpenBook, { bookMenu = it }, empty = "Aún no empezaste ningún libro.", display = display)
                1 -> when {
                    all == null -> Unit
                    all!!.isEmpty() -> EmptyLibrary(
                        onAddFolder = { pickFolder.launch(null) },
                        onImport = { pickFiles.launch(arrayOf("application/epub+zip", "application/pdf")) },
                    )
                    else -> LibraryBrowser(vm, onOpenBook, onLongPress = { bookMenu = it })
                }
                2 -> CollectionsList(collections, onOpenCollection, vm)
            }
        }
    }

    if (newCollectionDialog) {
        NameDialog("Nueva colección", "", onDismiss = { newCollectionDialog = false }) {
            vm.createCollection(it)
            newCollectionDialog = false
        }
    }
    bookMenu?.let { book -> BookActionsDialog(book, vm, onDismiss = { bookMenu = null }) }
}

/** Columnas y espaciado de cada tamaño de vista. */
@Composable
private fun BooksLazyGrid(display: LibraryDisplay, content: LazyGridScope.() -> Unit) {
    LazyVerticalGrid(
        columns = when (display) {
            LibraryDisplay.LARGE -> GridCells.Adaptive(112.dp)
            LibraryDisplay.COMPACT -> GridCells.Adaptive(76.dp)
            LibraryDisplay.LIST -> GridCells.Fixed(1)
        },
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(if (display == LibraryDisplay.COMPACT) 8.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(
            when (display) {
                LibraryDisplay.LARGE -> 16.dp
                LibraryDisplay.COMPACT -> 10.dp
                LibraryDisplay.LIST -> 2.dp
            }
        ),
        modifier = Modifier.fillMaxSize(),
        content = content,
    )
}

private fun LazyGridScope.bookItems(
    books: List<BookWithProgress>,
    display: LibraryDisplay,
    onOpenBook: (Long) -> Unit,
    onLongPress: (BookEntity) -> Unit,
) {
    items(books, key = { it.book.id }) { item ->
        when (display) {
            LibraryDisplay.LARGE -> BookTile(item, onOpenBook, onLongPress)
            LibraryDisplay.COMPACT -> CompactBookTile(item, onOpenBook, onLongPress)
            LibraryDisplay.LIST -> BookRow(item, onOpenBook, onLongPress)
        }
    }
}

@Composable
fun BookGrid(
    books: List<BookWithProgress>,
    onOpenBook: (Long) -> Unit,
    onLongPress: (BookEntity) -> Unit,
    empty: String,
    display: LibraryDisplay = LibraryDisplay.LARGE,
) {
    if (books.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(empty, style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    BooksLazyGrid(display) { bookItems(books, display, onOpenBook, onLongPress) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookTile(item: BookWithProgress, onOpenBook: (Long) -> Unit, onLongPress: (BookEntity) -> Unit) {
    val title = item.book.displayTitle(LocalTitleSource.current)
    Column(
        Modifier.combinedClickable(
            onClick = { onOpenBook(item.book.id) },
            onLongClick = { onLongPress(item.book) },
        )
    ) {
        BookCover(item.book.coverPath, title, Modifier.fillMaxWidth().aspectRatio(0.68f))
        ProgressBar(item, Modifier.fillMaxWidth().padding(top = 4.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        item.book.author?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Portada pequeña con el título en una línea: caben unas 4-5 por fila. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CompactBookTile(item: BookWithProgress, onOpenBook: (Long) -> Unit, onLongPress: (BookEntity) -> Unit) {
    val title = item.book.displayTitle(LocalTitleSource.current)
    Column(
        Modifier.combinedClickable(
            onClick = { onOpenBook(item.book.id) },
            onLongClick = { onLongPress(item.book) },
        )
    ) {
        BookCover(item.book.coverPath, title, Modifier.fillMaxWidth().aspectRatio(0.68f))
        ProgressBar(item, Modifier.fillMaxWidth().padding(top = 3.dp))
        Text(
            title,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/** Una fila por libro: miniatura, título, autor, formato, tamaño, idioma y progreso. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookRow(item: BookWithProgress, onOpenBook: (Long) -> Unit, onLongPress: (BookEntity) -> Unit) {
    val book = item.book
    val title = book.displayTitle(LocalTitleSource.current)
    val context = LocalContext.current
    val fraction = item.progress?.fraction ?: 0f
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .combinedClickable(onClick = { onOpenBook(book.id) }, onLongClick = { onLongPress(book) })
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(book.coverPath, title, Modifier.width(48.dp).aspectRatio(0.68f))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            book.author?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val meta = buildList {
                add(book.format.name)
                if (book.sizeBytes > 0) add(Formatter.formatShortFileSize(context, book.sizeBytes))
                book.primaryLanguage?.let { add(it.uppercase()) }
                if (fraction >= 0.999f) add("Terminado") else if (fraction > 0f) add("${(fraction * 100).roundToInt()} %")
            }.joinToString(" · ")
            Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ProgressBar(item, Modifier.fillMaxWidth().padding(top = 4.dp))
        }
    }
}

@Composable
private fun ProgressBar(item: BookWithProgress, modifier: Modifier) {
    val fraction = item.progress?.fraction ?: 0f
    if (fraction > 0f) LinearProgressIndicator(progress = { fraction }, modifier = modifier.height(3.dp))
}

/** Nombre del idioma en español ("en" → "Inglés"). */
private fun languageName(code: String): String =
    Locale.forLanguageTag(code).getDisplayLanguage(Locale("es")).replaceFirstChar { it.titlecase(Locale("es")) }
        .ifBlank { code.uppercase() }

/**
 * Pestaña Biblioteca: por defecto respeta la estructura de subcarpetas de la carpeta
 * sincronizada; con "Todo" muestra todos los libros juntos. Arriba: buscador, orden,
 * filtros y tamaño de vista.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryBrowser(vm: LibraryViewModel, onOpenBook: (Long) -> Unit, onLongPress: (BookEntity) -> Unit) {
    val view by vm.libraryView.collectAsState()
    val all by vm.all.collectAsState()
    val visible by vm.visible.collectAsState()
    val content by vm.folderContent.collectAsState()
    val location by vm.location.collectAsState()
    val display by vm.display.collectAsState()
    val filters by vm.filters.collectAsState()
    val query by vm.query.collectAsState()
    val results by vm.searchResults.collectAsState()
    var showFilters by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current

    // Atrás: primero borra la búsqueda; si no, sube una carpeta (en vez de salir de la app).
    androidx.activity.compose.BackHandler(enabled = query.isNotEmpty()) {
        vm.setQuery("")
        focus.clearFocus()
    }
    androidx.activity.compose.BackHandler(
        enabled = query.isEmpty() && view == LibraryView.FOLDERS && location != null && content?.isTop == false
    ) {
        vm.goUp()
    }

    Column(Modifier.fillMaxSize()) {
        SearchField(
            query = query,
            onQueryChange = vm::setQuery,
            onDone = { focus.clearFocus() },
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = view == LibraryView.FOLDERS,
                onClick = { vm.setLibraryView(LibraryView.FOLDERS) },
                label = { Text("Carpetas") },
            )
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = view == LibraryView.ALL,
                onClick = { vm.setLibraryView(LibraryView.ALL) },
                label = { Text("Todo (${all?.size ?: 0})") },
            )
            Spacer(Modifier.weight(1f))
            SortButton(vm)
            IconButton(onClick = { showFilters = true }) {
                BadgedBox(badge = { if (filters.active) Badge { Text("${filters.count}") } }) {
                    Icon(Icons.Default.FilterList, "Filtros")
                }
            }
            DisplayButton(display, vm::setDisplay)
        }

        val noMatches: @Composable (String) -> Unit = { text ->
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text, style = MaterialTheme.typography.bodyLarge)
                if (filters.active) TextButton(onClick = vm::clearFilters) { Text("Quitar filtros") }
            }
        }

        // Búsqueda: resultados de toda la biblioteca, sin importar la carpeta en la que estés.
        results?.let { found ->
            if (found.isEmpty()) {
                noMatches("Ningún libro coincide con «${query.trim()}».")
            } else {
                Text(
                    if (found.size == 1) "1 resultado" else "${found.size} resultados",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                BooksLazyGrid(display) { bookItems(found, display, onOpenBook, onLongPress) }
            }
            return@Column
        }

        if (view == LibraryView.ALL) {
            if (visible.isEmpty()) noMatches("Ningún libro coincide con los filtros.")
            else BooksLazyGrid(display) { bookItems(visible, display, onOpenBook, onLongPress) }
            return@Column
        }
        val c = content ?: return@Column
        // Ruta: Biblioteca › Carpeta › Subcarpeta (cada tramo es pulsable).
        if (c.path.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { vm.openFolder(null) }) { Text("Biblioteca") }
                c.path.forEachIndexed { i, entry ->
                    Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { vm.openFolder(entry.location) }, enabled = i < c.path.size - 1) {
                        Text(entry.name, maxLines = 1)
                    }
                }
            }
        }
        if (c.folders.isEmpty() && c.books.isEmpty()) {
            if (filters.active) noMatches("Ningún libro de esta carpeta coincide con los filtros.")
            else Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) { Text("Carpeta vacía.") }
            return@Column
        }
        BooksLazyGrid(display) {
            items(c.folders, key = { "f:" + it.location.rootUri + "/" + it.location.dir }, span = { GridItemSpan(maxLineSpan) }) { folder ->
                Surface(
                    onClick = { vm.openFolder(folder.location) },
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Folder, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text(folder.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (folder.bookCount == 1) "1 libro" else "${folder.bookCount} libros",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            bookItems(c.books, display, onOpenBook, onLongPress)
        }
    }

    if (showFilters) FiltersSheet(vm, onDismiss = { showFilters = false })
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, onDone: () -> Unit, modifier: Modifier) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier.height(44.dp)) {
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text("Buscar por título o autor", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onDone() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange(""); onDone() }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Close, "Borrar búsqueda")
                }
            }
        }
    }
}

@Composable
private fun SortButton(vm: LibraryViewModel) {
    val sort by vm.sort.collectAsState()
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Ordenar") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                "Ordenar por",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LibrarySortField.entries.forEach { field ->
                val selected = sort.field == field
                DropdownMenuItem(
                    text = {
                        Text(field.label, color = if (selected) MaterialTheme.colorScheme.primary else Color.Unspecified)
                    },
                    leadingIcon = {
                        if (selected) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                        else Spacer(Modifier.size(24.dp))
                    },
                    trailingIcon = {
                        if (selected) {
                            Icon(
                                if (sort.ascending) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                if (sort.ascending) "Ascendente" else "Descendente",
                            )
                        }
                    },
                    // Se queda abierto: así se puede volver a pulsar para invertir el sentido.
                    onClick = { vm.selectSort(field) },
                )
            }
        }
    }
}

@Composable
private fun DisplayButton(display: LibraryDisplay, onSelect: (LibraryDisplay) -> Unit) {
    var open by remember { mutableStateOf(false) }
    fun icon(d: LibraryDisplay) = when (d) {
        LibraryDisplay.LARGE -> Icons.Default.GridView
        LibraryDisplay.COMPACT -> Icons.Default.Apps
        LibraryDisplay.LIST -> Icons.AutoMirrored.Filled.ViewList
    }
    Box {
        IconButton(onClick = { open = true }) { Icon(icon(display), "Vista") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(
                LibraryDisplay.LARGE to "Portadas grandes",
                LibraryDisplay.COMPACT to "Portadas compactas",
                LibraryDisplay.LIST to "Lista",
            ).forEach { (d, label) ->
                val selected = d == display
                DropdownMenuItem(
                    text = { Text(label, color = if (selected) MaterialTheme.colorScheme.primary else Color.Unspecified) },
                    leadingIcon = { Icon(icon(d), null, tint = if (selected) MaterialTheme.colorScheme.primary else LocalContentColor.current) },
                    trailingIcon = { if (selected) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary) },
                    onClick = { onSelect(d); open = false },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun FiltersSheet(vm: LibraryViewModel, onDismiss: () -> Unit) {
    val filters by vm.filters.collectAsState()
    val languages by vm.languages.collectAsState()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Filtros", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = vm::clearFilters, enabled = filters.active) { Text("Quitar todos") }
            }
            @Composable
            fun Group(title: String, chips: @Composable () -> Unit) {
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
            }
            Group("Estado") {
                ReadState.entries.forEach { s ->
                    FilterChip(selected = s in filters.states, onClick = { vm.toggleState(s) }, label = { Text(s.label) })
                }
            }
            Group("Formato") {
                BookFormat.entries.forEach { f ->
                    FilterChip(selected = f in filters.formats, onClick = { vm.toggleFormat(f) }, label = { Text(f.name) })
                }
            }
            if (languages.isNotEmpty()) {
                Group("Idioma") {
                    languages.forEach { l ->
                        FilterChip(selected = l in filters.languages, onClick = { vm.toggleLanguage(l) }, label = { Text(languageName(l)) })
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(onAddFolder: () -> Unit, onImport: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Default.Folder, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("Tu biblioteca está vacía", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Elige la carpeta donde guardas tus EPUB y PDF. Lectora la mantendrá sincronizada.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onAddFolder) { Text("Elegir carpeta") }
        TextButton(onClick = onImport) { Text("o importar archivos sueltos") }
    }
}

@Composable
private fun CollectionsList(
    collections: List<com.lectora.core.database.CollectionWithCount>,
    onOpen: (Long) -> Unit,
    vm: LibraryViewModel,
) {
    var renaming by remember { mutableStateOf<com.lectora.core.database.CollectionEntity?>(null) }
    if (collections.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text("Crea colecciones para agrupar tus libros (sagas, pendientes, favoritos…). Mantén pulsado un libro para añadirlo.")
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
        items(collections, key = { it.collection.id }) { c ->
            var menu by remember { mutableStateOf(false) }
            ListItem(
                headlineContent = { Text(c.collection.name) },
                supportingContent = { Text(if (c.bookCount == 1) "1 libro" else "${c.bookCount} libros") },
                trailingContent = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Opciones") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Renombrar") },
                                leadingIcon = { Icon(Icons.Default.Edit, null) },
                                onClick = { menu = false; renaming = c.collection },
                            )
                            DropdownMenuItem(
                                text = { Text("Eliminar colección") },
                                leadingIcon = { Icon(Icons.Default.Delete, null) },
                                onClick = { menu = false; vm.deleteCollection(c.collection.id) },
                            )
                        }
                    }
                },
                modifier = Modifier.clickable { onOpen(c.collection.id) },
            )
            HorizontalDivider()
        }
    }
    renaming?.let { col ->
        NameDialog("Renombrar colección", col.name, onDismiss = { renaming = null }) {
            vm.renameCollection(col.id, it)
            renaming = null
        }
    }
}

@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Nombre") }) },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun BookActionsDialog(book: BookEntity, vm: LibraryViewModel, onDismiss: () -> Unit) {
    val collections by vm.collections.collectAsState()
    val inCollections by vm.collectionIdsFor(book.id).collectAsState(initial = emptyList())
    var newName by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(book.displayTitle(LocalTitleSource.current), maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                Text("Colecciones", style = MaterialTheme.typography.titleSmall)
                collections.forEach { c ->
                    val checked = c.collection.id in inCollections
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.setInCollection(book.id, c.collection.id, !checked) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked, { vm.setInCollection(book.id, c.collection.id, it) })
                        Text(c.collection.name)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        newName, { newName = it },
                        singleLine = true,
                        label = { Text("Nueva colección") },
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { vm.createCollection(newName, thenAddBook = book.id); newName = "" },
                        enabled = newName.isNotBlank(),
                    ) { Icon(Icons.Default.Add, "Crear") }
                }
                Spacer(Modifier.height(12.dp))
                Text("${book.format.name} · ${book.fileName}", style = MaterialTheme.typography.bodySmall)
                book.language?.let { Text("Idioma: $it", style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Listo") } },
        dismissButton = {
            TextButton(onClick = { confirmDelete = true }) { Text("Quitar de la biblioteca") }
        },
    )
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("¿Quitar \"${book.displayTitle(LocalTitleSource.current)}\"?") },
            text = { Text("Se borra de la biblioteca junto con su progreso. El archivo original no se toca.") },
            confirmButton = {
                TextButton(onClick = { vm.removeBook(book); confirmDelete = false; onDismiss() }) { Text("Quitar") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionScreen(
    vm: LibraryViewModel,
    collectionId: Long,
    miniPlayer: @Composable () -> Unit,
    onBack: () -> Unit,
    onOpenBook: (Long) -> Unit,
) {
    val collection by vm.collection(collectionId).collectAsState(initial = null)
    val books by vm.booksIn(collectionId).collectAsState(initial = emptyList())
    val display by vm.display.collectAsState()
    var bookMenu by remember { mutableStateOf<BookEntity?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(collection?.name ?: "") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") } },
            )
        },
        bottomBar = miniPlayer,
    ) { padding ->
        Box(Modifier.padding(padding)) {
            BookGrid(books, onOpenBook, { bookMenu = it }, empty = "Esta colección está vacía. Mantén pulsado un libro en la biblioteca para añadirlo.", display = display)
        }
    }
    bookMenu?.let { BookActionsDialog(it, vm, onDismiss = { bookMenu = null }) }
}
