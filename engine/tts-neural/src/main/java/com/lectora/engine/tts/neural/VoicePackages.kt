package com.lectora.engine.tts.neural

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Detecta qué hay dentro de un paquete de voz de sherpa-onnx ya descomprimido y construye su
 * [NeuralVoiceModel]. Soporta:
 *  - VITS/Piper: `*.onnx` + `tokens.txt` + `espeak-ng-data/` (o `lexicon.txt`), con el
 *    `*.onnx.json` de Piper si viene (idioma y hablantes).
 *  - Kokoro: `*.onnx` + `voices.bin` + `tokens.txt` + `espeak-ng-data/`.
 */
object VoicePackageInspector {
    /** Tamaño de cada estilo de voz en voices.bin de Kokoro (510 × 256 floats). */
    private const val KOKORO_VOICE_BYTES = 510L * 256 * 4

    /** Voces de Kokoro v1.0 multilingüe en el orden de voices.bin (sid = índice). */
    private val KOKORO_V1_0 = listOf(
        "af_alloy", "af_aoede", "af_bella", "af_heart", "af_jessica", "af_kore", "af_nicole", "af_nova", "af_river",
        "af_sarah", "af_sky", "am_adam", "am_echo", "am_eric", "am_fenrir", "am_liam", "am_michael", "am_onyx",
        "am_puck", "am_santa", "bf_alice", "bf_emma", "bf_isabella", "bf_lily", "bm_daniel", "bm_fable", "bm_george",
        "bm_lewis", "ef_dora", "em_alex", "ff_siwis", "hf_alpha", "hf_beta", "hm_omega", "hm_psi", "if_sara",
        "im_nicola", "jf_alpha", "jf_gongitsune", "jf_nezumi", "jf_tebukuro", "jm_kumo", "pf_dora", "pm_alex",
        "pm_santa", "zf_xiaobei", "zf_xiaoni", "zf_xiaoxiao", "zf_xiaoyi", "zm_yunjian", "zm_yunxi", "zm_yunxia",
        "zm_yunyang", "em_santa",
    )

    private val KOKORO_LANG = mapOf(
        'a' to "en-us", 'b' to "en-gb", 'e' to "es", 'f' to "fr", 'h' to "hi",
        'i' to "it", 'j' to "ja", 'p' to "pt-br", 'z' to "zh",
    )

    fun inspect(root: File, id: String, name: String): NeuralVoiceModel {
        val files = root.listFiles().orEmpty()
        val onnx = files.filter { it.isFile && it.name.endsWith(".onnx") }
        val tokens = File(root, "tokens.txt")
        if (onnx.isEmpty() || !tokens.exists()) {
            throw IOException("El paquete no parece una voz de sherpa-onnx (falta el modelo .onnx o tokens.txt)")
        }
        val espeak = File(root, "espeak-ng-data").takeIf { it.isDirectory }
        val voicesBin = File(root, "voices.bin")
        return if (voicesBin.exists()) {
            kokoro(root, id, name, onnx, voicesBin, espeak)
        } else {
            vits(root, id, name, onnx, espeak)
        }
    }

    private fun kokoro(root: File, id: String, name: String, onnx: List<File>, voicesBin: File, espeak: File?): NeuralVoiceModel {
        espeak ?: throw IOException("Paquete Kokoro sin espeak-ng-data")
        val model = onnx.firstOrNull { "int8" in it.name } ?: onnx.first()
        val count = (voicesBin.length() / KOKORO_VOICE_BYTES).toInt()
        val speakers = if (count == KOKORO_V1_0.size) {
            KOKORO_V1_0.mapIndexed { sid, voice ->
                NeuralSpeaker(
                    sid = sid,
                    name = voice.substringAfter('_').replaceFirstChar { it.uppercase() },
                    lang = KOKORO_LANG[voice[0]] ?: "en-us",
                    gender = voice.getOrNull(1)?.toString().orEmpty(),
                )
            }
        } else {
            List(count.coerceAtLeast(1)) { NeuralSpeaker(it, "Voz ${it + 1}", "en-us") }
        }
        val files = buildMap {
            put("model", model.name)
            put("voices", "voices.bin")
            put("tokens", "tokens.txt")
            put("dataDir", "espeak-ng-data")
            if (File(root, "lexicon-us-en.txt").exists()) put("lexiconUs", "lexicon-us-en.txt")
            if (File(root, "lexicon-zh.txt").exists()) put("lexiconZh", "lexicon-zh.txt")
            if (File(root, "dict").isDirectory) put("dictDir", "dict")
        }
        return NeuralVoiceModel(id, name, "kokoro", 24000, speakers, files, root)
    }

    private fun vits(root: File, id: String, name: String, onnx: List<File>, espeak: File?): NeuralVoiceModel {
        val lexicon = File(root, "lexicon.txt").takeIf { it.exists() }
        if (espeak == null && lexicon == null) throw IOException("Paquete VITS sin espeak-ng-data ni lexicon.txt")
        val model = onnx.first()
        val json = File(root, model.name + ".json").takeIf { it.exists() }
            ?: root.listFiles()?.firstOrNull { it.name.endsWith(".onnx.json") }
        var lang = Regex("([a-z]{2,3})_([A-Z]{2})").find(model.name)?.let { m -> toLang(m.groupValues[1], m.groupValues[2]) } ?: "en"
        var sampleRate = 22050
        var speakers = listOf(NeuralSpeaker(0, name, lang))
        if (json != null) runCatching {
            val o = JSONObject(json.readText())
            o.optJSONObject("language")?.let { l ->
                lang = toLang(l.optString("family"), l.optString("region"))
            }
            o.optJSONObject("audio")?.optInt("sample_rate")?.takeIf { it > 0 }?.let { sampleRate = it }
            val map = o.optJSONObject("speaker_id_map")
            if (map != null && map.length() > 1) {
                speakers = map.keys().asSequence()
                    .map { key -> NeuralSpeaker(map.getInt(key), "$name · ${key.replaceFirstChar { it.uppercase() }}", lang) }
                    .sortedBy { it.sid }
                    .toList()
            } else {
                speakers = listOf(NeuralSpeaker(0, name, lang))
            }
        }
        val files = buildMap {
            put("model", model.name)
            put("tokens", "tokens.txt")
            if (espeak != null) put("dataDir", "espeak-ng-data")
            if (lexicon != null) put("lexicon", "lexicon.txt")
        }
        return NeuralVoiceModel(id, name, "piper", sampleRate, speakers, files, root)
    }

    private fun toLang(family: String, region: String): String = when {
        family.isBlank() -> "en"
        family == "en" && region.equals("GB", true) -> "en-gb"
        family == "en" -> "en-us"
        else -> family.lowercase()
    }
}

/**
 * Catálogo online de voces Piper ya preparadas para sherpa-onnx (release "tts-models" de
 * k2-fsa/sherpa-onnx): ~185 voces en ~40 idiomas, consultado con una sola petición.
 */
object PiperOnlineCatalog {
    private const val API = "https://api.github.com/repos/k2-fsa/sherpa-onnx/releases/tags/tts-models"
    private val NAME = Regex("""^vits-piper-(([a-z]{2,3})_([A-Z]{2})-([A-Za-z0-9_]+)-(x_low|low|medium|high))\.tar\.bz2$""")

    suspend fun fetch(): List<CatalogVoice> = withContext(Dispatchers.IO) {
        val c = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            if (c.responseCode != 200) throw IOException("No se pudo obtener el catálogo (HTTP ${c.responseCode})")
            val assets = JSONObject(c.inputStream.bufferedReader().readText()).getJSONArray("assets")
            val es = Locale.forLanguageTag("es")
            (0 until assets.length()).mapNotNull { i ->
                val a = assets.getJSONObject(i)
                val m = NAME.matchEntire(a.getString("name")) ?: return@mapNotNull null
                val (key, lang, region, voice, quality) = m.destructured
                val locale = Locale(lang, region)
                val country = locale.getDisplayCountry(es).ifBlank { region }
                val qualityLabel = when (quality) {
                    "x_low" -> "muy ligera"
                    "low" -> "ligera"
                    "medium" -> "media"
                    else -> "alta"
                }
                // La voz "claude" de Piper (es_MX) se muestra por su país, como en el catálogo fijo.
                val shownVoice = if (voice.equals("claude", ignoreCase = true)) "Voz" else voice.replace('_', ' ').replaceFirstChar { it.uppercase() }
                CatalogVoice(
                    id = "piper-$key",
                    name = "$shownVoice ($country)",
                    family = "piper",
                    description = "${locale.getDisplayLanguage(es).replaceFirstChar { it.uppercase() }} · calidad $qualityLabel",
                    sizeMb = (a.getLong("size") / (1024 * 1024)).toInt().coerceAtLeast(1),
                    sampleRate = 22050,
                    archive = a.getString("name"),
                    // Sin archivos ni hablantes: se detectan al instalar (VoicePackageInspector).
                    files = emptyMap(),
                    speakers = emptyList(),
                    language = lang,
                )
            }.sortedWith(compareBy({ it.language }, { it.name }))
        } finally {
            c.disconnect()
        }
    }
}
