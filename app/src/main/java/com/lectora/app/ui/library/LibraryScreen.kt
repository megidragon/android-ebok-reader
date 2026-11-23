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
import com.lectora.core.database.BookEntity
import com.lectora.core.database.BookWithProgress

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
                0 -> BookGrid(reading, onOpenBook, { bookMenu = it }, empty = "Aún no empezaste ningún libro.")
                1 -> when {
                    all == null -> Unit
                    all!!.isEmpty() -> EmptyLibrary(
                        onAddFolder = { pickFolder.launch(null) },
                        onImport = { pickFiles.launch(arrayOf("application/epub+zip", "application/pdf")) },
                    )
                    else -> BookGrid(all!!, onOpenBook, { bookMenu = it }, empty = "")
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookGrid(
    books: List<BookWithProgress>,
    onOpenBook: (Long) -> Unit,
    onLongPress: (BookEntity) -> Unit,
    empty: String,
) {
    if (books.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(empty, style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(112.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(books, key = { it.book.id }) { item ->
            Column(
                Modifier.combinedClickable(
                    onClick = { onOpenBook(item.book.id) },
                    onLongClick = { onLongPress(item.book) },
                )
            ) {
                BookCover(item.book.coverPath, item.book.title, Modifier.fillMaxWidth().aspectRatio(0.68f))
                val fraction = item.progress?.fraction ?: 0f
                if (fraction > 0f) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(3.dp),
                    )
                }
                Text(
                    item.book.title,
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
        title = { Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
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
            title = { Text("¿Quitar \"${book.title}\"?") },
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
            BookGrid(books, onOpenBook, { bookMenu = it }, empty = "Esta colección está vacía. Mantén pulsado un libro en la biblioteca para añadirlo.")
        }
    }
    bookMenu?.let { BookActionsDialog(it, vm, onDismiss = { bookMenu = null }) }
}
