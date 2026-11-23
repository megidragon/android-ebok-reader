package com.lectora.app.ui.settings

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.lectora.app.ui.settings.SettingsViewModel.Companion.displayLanguage
import com.lectora.engine.tts.VoiceInfo
import androidx.compose.foundation.layout.Box
import com.lectora.engine.tts.neural.CatalogVoice
import com.lectora.engine.tts.neural.InstallState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: SettingsViewModel, onBack: () -> Unit) {
    val s by vm.narration.collectAsState()
    val voices by vm.voices.collectAsState()
    val engines by vm.engines.collectAsState()
    val downloaded by vm.downloaded.collectAsState()
    val downloading by vm.downloading.collectAsState()
    val wifiOnly by vm.wifiOnly.collectAsState()
    val folders by vm.folders.collectAsState()
    val message by vm.message.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    var voiceDialogFor by remember { mutableStateOf<String?>(null) }
    var languageDialog by remember { mutableStateOf<String?>(null) } // "target" | "download"
    var batteryIgnored by remember { mutableStateOf(isIgnoringBattery(context)) }

    // Al volver de los ajustes del sistema, refrescar voces y estado de batería.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) batteryIgnored = isIgnoringBattery(context)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.message.value = null }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ajustes") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Atrás") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            Section("Narración")
            val languages = listOf(s.targetLanguage, "es", "en").distinct()
            languages.forEach { lang ->
                val selectedKey = s.voiceByLanguage[lang]
                val list = voices[lang]
                val selected = list?.firstOrNull { it.key == selectedKey } ?: list?.firstOrNull()
                ListItem(
                    headlineContent = { Text("Voz en ${displayLanguage(lang).lowercase()}") },
                    supportingContent = {
                        Text(
                            when {
                                list == null -> "Cargando voces…"
                                list.isEmpty() -> "Sin voces offline. Instala una desde \"Más voces\"."
                                else -> (selected?.displayName ?: "") + if (selectedKey == null) " (automática)" else ""
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    modifier = Modifier.clickable { voiceDialogFor = lang },
                )
            }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { openTtsSettings(context) }) { Text("Más voces") }
                OutlinedButton(onClick = { installTtsData(context) }) { Text("Descargar datos de voz") }
                IconButton(onClick = { vm.refreshVoices(force = true) }) { Icon(Icons.Default.Refresh, "Recargar voces") }
            }
            if (engines.isNotEmpty()) {
                Text(
                    "Motores instalados: ${engines.joinToString()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            AiVoicesSection(vm)

            SliderSetting("Velocidad", s.speechRate, 0.5f..2.5f, steps = 19, format = { "%.2f×".format(it) }, onDone = vm::setRate)
            SliderSetting("Tono", s.pitch, 0.5f..2.0f, steps = 14, format = { "%.2f".format(it) }, onDone = vm::setPitch)
            SliderSetting(
                "Pausa entre oraciones", s.sentencePauseMs.toFloat(), 0f..800f, steps = 15,
                format = { "${it.toInt()} ms" }, onDone = { vm.setSentencePause(it.toInt()) },
            )
            SliderSetting(
                "Pausa entre párrafos", s.paragraphPauseMs.toFloat(), 0f..1500f, steps = 14,
                format = { "${it.toInt()} ms" }, onDone = { vm.setParagraphPause(it.toInt()) },
            )
            SliderSetting(
                "Audio preparado por adelantado", s.lookaheadSeconds.toFloat(), 5f..60f, steps = 10,
                format = { "${it.toInt()} s" }, onDone = { vm.setLookahead(it.toInt()) },
            )
            Hint("Lectora sintetiza varias frases por delante y las encadena en un único flujo de audio, sin huecos entre oraciones ni páginas. Más adelanto = más fluidez en teléfonos lentos.")

            Section("Traducción (sin conexión)")
            ListItem(
                headlineContent = { Text("Traducir a") },
                supportingContent = { Text(displayLanguage(s.targetLanguage)) },
                modifier = Modifier.clickable { languageDialog = "target" },
            )
            ListItem(
                headlineContent = { Text("Descargar solo con Wi-Fi") },
                trailingContent = { Switch(wifiOnly, vm::setWifiOnly) },
            )
            Text(
                "Modelos descargados (≈30 MB cada uno, se usan sin internet):",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            downloaded.sortedBy { displayLanguage(it) }.forEach { lang ->
                ListItem(
                    headlineContent = { Text(displayLanguage(lang)) },
                    trailingContent = {
                        if (lang != "en") IconButton(onClick = { vm.deleteModel(lang) }) { Icon(Icons.Default.Delete, "Eliminar") }
                    },
                )
            }
            downloading.forEach { lang ->
                ListItem(
                    headlineContent = { Text(displayLanguage(lang)) },
                    supportingContent = { Text("Descargando…") },
                    trailingContent = { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) },
                )
            }
            Row(Modifier.padding(horizontal = 16.dp)) {
                OutlinedButton(onClick = { languageDialog = "download" }) {
                    Icon(Icons.Default.Download, null)
                    Text("  Descargar idioma")
                }
            }
            Hint("Para traducir un libro en inglés al español necesitas los modelos de inglés y español. Activa la traducción desde el lector (icono de traducir).")

            Section("Segundo plano")
            ListItem(
                headlineContent = { Text("Sin restricciones de batería") },
                supportingContent = {
                    Text(
                        if (batteryIgnored) "Activado: Android no cortará la narración con la pantalla apagada."
                        else "Recomendado: algunos fabricantes detienen apps en segundo plano aunque estén reproduciendo."
                    )
                },
                trailingContent = { if (batteryIgnored) Icon(Icons.Default.Check, null) },
                modifier = Modifier.clickable { requestIgnoreBattery(context) },
            )

            val prerenderBytes by vm.prerenderBytes.collectAsState()
            LaunchedEffect(Unit) { vm.refreshPrerenderSize() }
            ListItem(
                headlineContent = { Text("Narración pregenerada") },
                supportingContent = {
                    Text(
                        if (prerenderBytes == 0L) "Nada guardado. Se pregenera desde el lector (menú ⋮)."
                        else "Ocupa ${"%.1f".format(prerenderBytes / 1_048_576.0)} MB en el móvil."
                    )
                },
                trailingContent = {
                    if (prerenderBytes > 0) IconButton(onClick = vm::deleteAllPrerender) { Icon(Icons.Default.Delete, "Borrar todo") }
                },
            )

            Section("Biblioteca")
            val titleSource by vm.titleSource.collectAsState()
            listOf(
                com.lectora.app.data.TitleSource.FILE_NAME to ("Nombre del archivo" to "El nombre original del archivo en tu móvil (recomendado)."),
                com.lectora.app.data.TitleSource.METADATA to ("Título interno del libro" to "El título guardado dentro del EPUB/PDF. A veces viene traducido o es distinto del nombre del archivo."),
            ).forEach { (source, texts) ->
                ListItem(
                    headlineContent = { Text(texts.first) },
                    supportingContent = { Text(texts.second) },
                    leadingContent = {
                        androidx.compose.material3.RadioButton(selected = titleSource == source, onClick = { vm.setTitleSource(source) })
                    },
                    modifier = Modifier.clickable { vm.setTitleSource(source) },
                )
            }

            Section("Carpetas sincronizadas")
            if (folders.isEmpty()) Hint("Ninguna. Agrega una desde la biblioteca (+).")
            folders.forEach { f ->
                ListItem(
                    headlineContent = { Text(f.displayName) },
                    supportingContent = { Text(f.lastScanAt?.let { "Última sincronización: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it))}" } ?: "Sin sincronizar") },
                    trailingContent = { IconButton(onClick = { vm.removeFolder(f.uri) }) { Icon(Icons.Default.Delete, "Quitar carpeta") } },
                )
            }
        }
    }

    voiceDialogFor?.let { lang ->
        LaunchedEffect(lang) { vm.voicesFor(lang) }
        VoiceDialog(
            language = lang,
            voices = voices[lang],
            selectedKey = s.voiceByLanguage[lang],
            onPreview = vm::preview,
            onSelect = { vm.selectVoice(lang, it); voiceDialogFor = null },
            onDismiss = { voiceDialogFor = null },
        )
    }
    languageDialog?.let { kind ->
        LanguageDialog(
            title = if (kind == "target") "Traducir a" else "Descargar modelo",
            languages = vm.supportedLanguages,
            marked = downloaded,
            onSelect = {
                if (kind == "target") {
                    vm.setTargetLanguage(it)
                    if (it !in downloaded) vm.download(it)
                } else {
                    vm.download(it)
                }
                languageDialog = null
            },
            onDismiss = { languageDialog = null },
        )
    }
}

@Composable
private fun AiVoicesSection(vm: SettingsViewModel) {
    val states by vm.aiStates.collectAsState()
    var confirmDelete by remember { mutableStateOf<CatalogVoice?>(null) }
    Text(
        "Voces IA (sin conexión)",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp),
    )
    Hint("Modelos de voz neuronales que corren en el propio móvil (Piper y Kokoro). Se descargan una vez y funcionan sin internet. Tras instalar una, elígela en «Voz en español».")
    vm.aiCatalog.forEach { voice ->
        val state = states[voice.id] ?: InstallState.NotInstalled
        ListItem(
            headlineContent = { Text(voice.name) },
            supportingContent = {
                Column {
                    Text(
                        when (state) {
                            is InstallState.Downloading -> "Descargando… ${(state.fraction * 100).toInt()} %"
                            InstallState.Extracting -> "Instalando…"
                            InstallState.Installed -> "Instalada · ${voice.description}"
                            is InstallState.Failed -> "Error: ${state.message}"
                            InstallState.NotInstalled -> "${voice.sizeMb} MB · ${voice.description}"
                        },
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        color = if (state is InstallState.Failed) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified,
                    )
                    if (state is InstallState.Downloading) {
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { state.fraction },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                    }
                }
            },
            trailingContent = {
                when (state) {
                    InstallState.Installed -> IconButton(onClick = { confirmDelete = voice }) { Icon(Icons.Default.Delete, "Eliminar voz") }
                    is InstallState.Downloading, InstallState.Extracting ->
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    else -> IconButton(onClick = { vm.installAiVoice(voice) }) { Icon(Icons.Default.Download, "Descargar voz") }
                }
            },
        )
    }
    // Voces añadidas por el usuario (catálogo online o paquete importado).
    val installedAi by vm.installedAi.collectAsState()
    val builtinIds = remember { vm.aiCatalog.map { it.id }.toSet() }
    val extra = installedAi.filter { it.id !in builtinIds }
    var confirmDeleteExtra by remember { mutableStateOf<com.lectora.engine.tts.neural.NeuralVoiceModel?>(null) }
    if (extra.isNotEmpty()) {
        Text(
            "Otras voces instaladas",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp),
        )
        extra.forEach { model ->
            ListItem(
                headlineContent = { Text(model.name) },
                supportingContent = {
                    val langs = model.speakers.map { it.lang.substringBefore('-') }.distinct()
                        .joinToString { displayLanguage(it) }
                    val n = model.speakers.size
                    Text("${if (model.family == "kokoro") "Kokoro" else "Piper"} · $langs · ${if (n == 1) "1 voz" else "$n voces"}")
                },
                trailingContent = { IconButton(onClick = { confirmDeleteExtra = model }) { Icon(Icons.Default.Delete, "Eliminar voz") } },
            )
        }
    }

    var showCatalog by remember { mutableStateOf(false) }
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.importVoicePackage(it) } }
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { showCatalog = true; vm.loadOnlineCatalog() }) { Text("Más voces (+180)") }
        OutlinedButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text("Importar paquete") }
    }
    Hint("«Más voces» lista todas las voces Piper disponibles (unos 40 idiomas). «Importar paquete» instala un archivo .tar.bz2 de voz de sherpa-onnx que tengas en el móvil (Piper/VITS o Kokoro).")

    if (showCatalog) VoiceCatalogDialog(vm, states, onDismiss = { showCatalog = false })

    confirmDelete?.let { voice ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("¿Eliminar «${voice.name}»?") },
            text = { Text("Se liberan ${voice.sizeMb} MB. Puedes volver a descargarla cuando quieras.") },
            confirmButton = { TextButton(onClick = { vm.uninstallAiVoice(voice); confirmDelete = null }) { Text("Eliminar") } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancelar") } },
        )
    }
    confirmDeleteExtra?.let { model ->
        AlertDialog(
            onDismissRequest = { confirmDeleteExtra = null },
            title = { Text("¿Eliminar «${model.name}»?") },
            text = { Text("Se borra del móvil. Puedes volver a instalarla cuando quieras.") },
            confirmButton = { TextButton(onClick = { vm.uninstallAiVoice(model.id); confirmDeleteExtra = null }) { Text("Eliminar") } },
            dismissButton = { TextButton(onClick = { confirmDeleteExtra = null }) { Text("Cancelar") } },
        )
    }
}

/** Catálogo online de voces Piper, filtrado por idioma. */
@Composable
private fun VoiceCatalogDialog(vm: SettingsViewModel, states: Map<String, InstallState>, onDismiss: () -> Unit) {
    val online by vm.online.collectAsState()
    val error by vm.onlineError.collectAsState()
    val target by vm.narration.collectAsState()
    var language by remember { mutableStateOf(target.targetLanguage) }
    var languageMenu by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Más voces de IA") },
        text = {
            Column {
                val list = online
                when {
                    error != null -> Column {
                        Text(error!!, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { vm.loadOnlineCatalog() }) { Text("Reintentar") }
                    }
                    list == null -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    else -> {
                        val languages = list.map { it.language }.distinct().sortedBy { displayLanguage(it) }
                        Box {
                            OutlinedButton(onClick = { languageMenu = true }) {
                                Text("Idioma: ${displayLanguage(language)} (${list.count { it.language == language }})")
                            }
                            androidx.compose.material3.DropdownMenu(expanded = languageMenu, onDismissRequest = { languageMenu = false }) {
                                languages.forEach { l ->
                                    androidx.compose.material3.DropdownMenuItem(
                                        text = { Text("${displayLanguage(l)} (${list.count { it.language == l }})") },
                                        onClick = { language = l; languageMenu = false },
                                    )
                                }
                            }
                        }
                        val voices = list.filter { it.language == language }
                        LazyColumn(Modifier.height(400.dp)) {
                            items(voices, key = { it.id }) { voice ->
                                val state = states[voice.id] ?: InstallState.NotInstalled
                                ListItem(
                                    headlineContent = { Text(voice.name) },
                                    supportingContent = {
                                        Text(
                                            when (state) {
                                                is InstallState.Downloading -> "Descargando… ${(state.fraction * 100).toInt()} %"
                                                InstallState.Extracting -> "Instalando…"
                                                InstallState.Installed -> "Instalada · ${voice.description}"
                                                is InstallState.Failed -> "Error: ${state.message}"
                                                InstallState.NotInstalled -> "${voice.sizeMb} MB · ${voice.description}"
                                            },
                                            color = if (state is InstallState.Failed) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified,
                                        )
                                    },
                                    trailingContent = {
                                        when (state) {
                                            InstallState.Installed -> Icon(Icons.Default.Check, "Instalada")
                                            is InstallState.Downloading, InstallState.Extracting ->
                                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                            else -> IconButton(onClick = { vm.installAiVoice(voice) }) { Icon(Icons.Default.Download, "Descargar voz") }
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}

@Composable
private fun Section(title: String) {
    Spacer(Modifier.height(12.dp))
    HorizontalDivider()
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun SliderSetting(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    format: (Float) -> String,
    onDone: (Float) -> Unit,
) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(format(local), style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onDone(local) },
            valueRange = range,
            steps = steps,
        )
    }
}

@Composable
private fun VoiceDialog(
    language: String,
    voices: List<VoiceInfo>?,
    selectedKey: String?,
    onPreview: (VoiceInfo) -> Unit,
    onSelect: (VoiceInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Voces en ${displayLanguage(language).lowercase()}") },
        text = {
            when {
                voices == null -> CircularProgressIndicator()
                voices.isEmpty() -> Text("No hay voces offline para este idioma. Pulsa \"Más voces\" o \"Descargar datos de voz\" para instalarlas en el motor de Android.")
                else -> LazyColumn(Modifier.height(440.dp)) {
                    items(voices, key = { it.key }) { v ->
                        ListItem(
                            headlineContent = { Text(v.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                val kind = if (v.engineId.startsWith("neural:")) "IA en el dispositivo" else v.engineId.removePrefix("system:")
                                Text("$kind · calidad ${qualityLabel(v.quality)}")
                            },
                            leadingContent = { if (v.key == selectedKey) Icon(Icons.Default.Check, "Elegida") },
                            trailingContent = {
                                IconButton(onClick = { onPreview(v) }) { Icon(Icons.AutoMirrored.Filled.VolumeUp, "Escuchar") }
                            },
                            modifier = Modifier.clickable { onSelect(v) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar") } },
    )
}

private fun qualityLabel(q: Int) = when {
    q >= 400 -> "muy alta"
    q >= 300 -> "alta"
    q >= 200 -> "normal"
    else -> "baja"
}

@Composable
private fun LanguageDialog(
    title: String,
    languages: List<String>,
    marked: Set<String>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.height(440.dp)) {
                items(languages) { lang ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onSelect(lang) }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(displayLanguage(lang), modifier = Modifier.weight(1f))
                        if (lang in marked) Icon(Icons.Default.Check, "Descargado")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

private fun isIgnoringBattery(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

@SuppressLint("BatteryLife")
private fun requestIgnoreBattery(context: Context) {
    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    runCatching { context.startActivity(intent) }.onFailure {
        runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }
}

private fun openTtsSettings(context: Context) {
    try {
        context.startActivity(Intent("com.android.settings.TTS_SETTINGS"))
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_SETTINGS))
    }
}

private fun installTtsData(context: Context) {
    runCatching { context.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) }
        .onFailure { openTtsSettings(context) }
}
