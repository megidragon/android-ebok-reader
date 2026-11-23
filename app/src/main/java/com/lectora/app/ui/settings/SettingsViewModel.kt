package com.lectora.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lectora.app.AppContainer
import com.lectora.core.database.FolderEntity
import com.lectora.engine.tts.VoiceInfo
import com.lectora.engine.tts.neural.CatalogVoice
import com.lectora.engine.tts.neural.InstallState
import com.lectora.engine.tts.neural.NeuralVoiceCatalog
import com.lectora.engine.tts.neural.NeuralVoiceModel
import com.lectora.engine.tts.neural.PiperOnlineCatalog
import android.net.Uri
import android.provider.OpenableColumns
import com.lectora.narration.NarrationSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    private val settings = container.settings

    val narration: StateFlow<NarrationSettings> =
        settings.narration.stateIn(viewModelScope, SharingStarted.Eagerly, NarrationSettings())
    val wifiOnly: StateFlow<Boolean> = settings.wifiOnlyDownloads.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val folders: StateFlow<List<FolderEntity>> =
        container.library.folders().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _voices = MutableStateFlow<Map<String, List<VoiceInfo>>>(emptyMap())
    val voices: StateFlow<Map<String, List<VoiceInfo>>> = _voices.asStateFlow()

    private val _engines = MutableStateFlow<List<String>>(emptyList())
    val engines: StateFlow<List<String>> = _engines.asStateFlow()

    private val _downloaded = MutableStateFlow<Set<String>>(emptySet())
    val downloaded: StateFlow<Set<String>> = _downloaded.asStateFlow()
    private val _downloading = MutableStateFlow<Set<String>>(emptySet())
    val downloading: StateFlow<Set<String>> = _downloading.asStateFlow()
    val message = MutableStateFlow<String?>(null)

    val supportedLanguages: List<String> = container.translationEngine.supportedLanguages()

    // ---- Voces de IA en el dispositivo ----
    val aiCatalog: List<CatalogVoice> = NeuralVoiceCatalog.voices
    val aiStates: StateFlow<Map<String, InstallState>> = container.voiceInstaller.states

    fun installAiVoice(voice: CatalogVoice) = viewModelScope.launch {
        container.voiceInstaller.install(voice)
        if (aiStates.value[voice.id] == InstallState.Installed) {
            message.value = "Voz «${voice.name}» instalada. Elígela en «Voz en español»."
            _voices.value = emptyMap()
            refreshVoices(force = true)
        }
    }

    fun uninstallAiVoice(voice: CatalogVoice) = uninstallAiVoice(voice.id)

    fun uninstallAiVoice(id: String) = viewModelScope.launch {
        container.voiceInstaller.uninstall(id)
        _voices.value = emptyMap()
        refreshVoices(force = true)
    }

    /** Voces de IA instaladas (incluidas las añadidas por el usuario). */
    val installedAi: StateFlow<List<NeuralVoiceModel>> = container.voiceInstaller.installed

    // ---- Catálogo online de voces Piper (~185 voces, ~40 idiomas) ----
    private val _online = MutableStateFlow<List<CatalogVoice>?>(null)
    val online: StateFlow<List<CatalogVoice>?> = _online.asStateFlow()
    private val _onlineError = MutableStateFlow<String?>(null)
    val onlineError: StateFlow<String?> = _onlineError.asStateFlow()

    fun loadOnlineCatalog() = viewModelScope.launch {
        if (_online.value != null) return@launch
        _onlineError.value = null
        runCatching { PiperOnlineCatalog.fetch() }
            .onSuccess { _online.value = it }
            .onFailure { _onlineError.value = "No se pudo cargar el catálogo: ${it.message}" }
    }

    /** Importa un paquete de voz (.tar.bz2 de sherpa-onnx) elegido por el usuario. */
    fun importVoicePackage(uri: Uri) = viewModelScope.launch {
        val resolver = container.context.contentResolver
        var name = uri.lastPathSegment ?: "voz.tar.bz2"
        var size = 0L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        if (!name.endsWith(".tar.bz2") && !name.endsWith(".tbz2")) {
            message.value = "Elige un paquete de voz .tar.bz2 (formato de sherpa-onnx)."
            return@launch
        }
        message.value = "Importando «$name»…"
        val id = container.voiceInstaller.installFromStream(name, size) {
            resolver.openInputStream(uri) ?: throw java.io.IOException("No se pudo abrir el archivo")
        }
        val failed = container.voiceInstaller.states.value[id] as? InstallState.Failed
        message.value = failed?.let { "No se pudo importar: ${it.message}" } ?: "Voz importada. Elígela en «Voz en …»."
        _voices.value = emptyMap()
        refreshVoices(force = true)
    }

    init {
        refreshVoices()
        refreshModels()
    }

    fun refreshVoices(force: Boolean = false) {
        viewModelScope.launch {
            val registry = container.ttsRegistry
            _engines.value = registry.engines(refresh = force).map { it.displayName }
            val langs = (listOf(narration.value.targetLanguage, "es", "en")).distinct()
            _voices.value = langs.associateWith { registry.voices(it) }
        }
    }

    fun voicesFor(language: String) {
        if (_voices.value.containsKey(language)) return
        viewModelScope.launch {
            val list = container.ttsRegistry.voices(language)
            _voices.update { it + (language to list) }
        }
    }

    fun selectVoice(language: String, voice: VoiceInfo) = viewModelScope.launch { settings.setVoice(language, voice.key) }

    fun preview(voice: VoiceInfo) = viewModelScope.launch {
        val text = when (voice.locale.language) {
            "es" -> "Hola. Así sonará la narración de tus libros con esta voz."
            "en" -> "Hello. This is how your books will sound with this voice."
            "fr" -> "Bonjour. Voici comment vos livres seront lus avec cette voix."
            "de" -> "Hallo. So klingen deine Bücher mit dieser Stimme."
            "it" -> "Ciao. Ecco come suoneranno i tuoi libri con questa voce."
            "pt" -> "Olá. É assim que seus livros vão soar com esta voz."
            else -> "Lectora."
        }
        val s = narration.value
        runCatching { container.narration.previewVoice(voice, text, s.speechRate, s.pitch) }
            .onFailure { message.value = "No se pudo reproducir: ${it.message}" }
    }

    fun setRate(v: Float) = viewModelScope.launch { settings.setSpeechRate(v) }
    fun setPitch(v: Float) = viewModelScope.launch { settings.setPitch(v) }
    fun setSentencePause(v: Int) = viewModelScope.launch { settings.setSentencePause(v) }
    fun setParagraphPause(v: Int) = viewModelScope.launch { settings.setParagraphPause(v) }
    fun setLookahead(v: Int) = viewModelScope.launch { settings.setLookahead(v) }
    fun setWifiOnly(v: Boolean) = viewModelScope.launch { settings.setWifiOnly(v) }

    fun setTargetLanguage(language: String) = viewModelScope.launch {
        settings.setTargetLanguage(language)
        voicesFor(language)
    }

    fun refreshModels() = viewModelScope.launch {
        runCatching { container.translationEngine.downloadedLanguages() }.onSuccess { _downloaded.value = it }
    }

    fun download(language: String) = viewModelScope.launch {
        _downloading.update { it + language }
        runCatching { container.translationEngine.download(language, wifiOnly.value) }
            .onSuccess { message.value = "Modelo «${displayLanguage(language)}» listo para usar sin conexión" }
            .onFailure { message.value = "No se pudo descargar: ${it.message}" }
        _downloading.update { it - language }
        refreshModels()
    }

    fun deleteModel(language: String) = viewModelScope.launch {
        runCatching { container.translationEngine.delete(language) }
        refreshModels()
    }

    fun removeFolder(uri: String) = viewModelScope.launch { container.library.removeFolder(uri) }

    companion object {
        fun displayLanguage(code: String): String =
            Locale.forLanguageTag(code).getDisplayLanguage(Locale.forLanguageTag("es"))
                .replaceFirstChar { it.titlecase() }
    }
}
