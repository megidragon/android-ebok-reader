# 📖 Lectora

**Lector de libros para Android que te los lee en voz alta, y si están en inglés, te los traduce al español. Todo funciona sin internet.**

Abres tu EPUB o PDF, pulsas ▶ y Lectora te narra la historia con voz natural, sin pausas raras entre frases ni entre páginas. Puedes apagar la pantalla, guardar el móvil en el bolsillo o usar otras apps: la lectura sigue.

---

## ✨ Qué puede hacer

| | |
|---|---|
| 🎧 **Te lee el libro** | Narración continua y fluida. Resalta la frase que está leyendo y avanza las páginas sola. |
| 🔒 **Con la pantalla apagada** | Sigue leyendo con el móvil bloqueado o la app en segundo plano. Controles en la notificación, la pantalla de bloqueo y los auriculares Bluetooth. |
| 🌍 **Traduce sin internet** | Libros en inglés (u otros idiomas) al español, directamente en el móvil. Puedes ver solo la traducción o el original y la traducción juntos. La narración lee la traducción. |
| 🗣️ **Varias voces** | Voces de Google del sistema y voces de **IA** que se descargan una vez y funcionan sin conexión (España, México, Argentina…). |
| 📚 **Tu biblioteca** | Elige una carpeta y Lectora la mantiene sincronizada: detecta libros nuevos, movidos o borrados. Crea colecciones (sagas, pendientes, favoritos…). |
| 🔖 **Recuerda por dónde ibas** | Cada libro guarda tu página, aunque cambies el archivo de carpeta. |
| 📄 **Libros enormes** | Pensado para PDF de más de 10.000 páginas: abre rápido y puedes saltar a cualquier página escribiendo el número. |
| 😴 **Temporizador** | Para dormirte escuchando: se detiene en X minutos o al terminar el capítulo. |

Formatos: **EPUB** y **PDF** (con texto; los PDF escaneados como imagen todavía no se pueden leer en voz alta).

---

## 📲 Instalar

1. Ve a **[Releases](../../releases)** y descarga el archivo `.apk` de la última versión.
2. Ábrelo en el móvil. Si Android lo pide, permite *"instalar apps de origen desconocido"* para tu navegador o gestor de archivos.
3. Listo. Requiere **Android 8.0 o superior**.

> La primera vez Android te pedirá permiso para mostrar notificaciones: acéptalo, es lo que permite que la lectura siga con la pantalla apagada.

---

## 🚀 Primeros pasos

1. **Añade tus libros** → botón **+** → *Sincronizar una carpeta* (o *Importar archivos*).
2. **Abre un libro** y pulsa ▶ para que te lo lea. Toca cualquier párrafo para *"Narrar desde aquí"*.
3. **¿Está en inglés?** Pulsa el icono de traducir (arriba) → *Traducido* o *Bilingüe*. La primera vez descarga el idioma (unos 30 MB, solo una vez).
4. **Elige tu voz** en ⚙️ *Ajustes*:
   - *Voz en español / Voz en inglés*: toca para elegir y pulsa 🔊 para escucharla.
   - *Voces IA (sin conexión)*: descarga voces más naturales. **Davefx (España)** es rápida y suena muy bien en casi cualquier móvil.
5. **Ir a una página** → icono ☰ (Índice) → escribe el número.

### Consejos

- 🔋 En *Ajustes → Segundo plano*, activa **"Sin restricciones de batería"**. Algunos móviles (Xiaomi, Samsung, OPPO…) cortan las apps en segundo plano si no lo haces.
- 🎚️ Puedes ajustar velocidad, tono y la pausa entre frases y párrafos.
- 🐢 La voz **Kokoro** es la más natural, pero necesita un móvil potente. En gamas media y baja va más lenta que la lectura y habrá pausas: en ese caso usa una voz Piper (Davefx, Sharvard, Daniela…).

---

## ❓ Preguntas frecuentes

**¿Necesito internet?**
Solo para descargar una vez los idiomas de traducción y las voces de IA. Después, todo funciona sin conexión.

**¿Mis libros se suben a algún sitio?**
No. Los libros, las traducciones y la narración se procesan en tu móvil.

**La traducción suena un poco literal.**
Es traducción automática en el dispositivo: se entiende bien, pero no es una traducción profesional.

**No oigo nada / no hay voces en español.**
Ve a *Ajustes → Más voces* o *Descargar datos de voz* para instalar las voces de Google, o descarga una voz de IA desde *Voces IA*.

---

## 🛠️ Para desarrolladores

App nativa en **Kotlin + Jetpack Compose**, organizada en módulos:

| Módulo | Qué hace |
|---|---|
| `app` | Interfaz: biblioteca, colecciones, lector y ajustes. |
| `narration` | Orquesta la narración (texto → traducción → voz → audio) y el servicio en segundo plano (Media3). |
| `engine:tts` | Motores de voz, recorte de silencios y salida de audio continua (un único `AudioTrack`, sin huecos). |
| `engine:tts-neural` | Voces de IA en el dispositivo (Piper y Kokoro con [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)). |
| `engine:translation` | Traducción y detección de idioma sin conexión (ML Kit). |
| `engine:text` | Extracción de texto de EPUB y PDF (Jsoup, PdfBox-Android), optimizada para PDF muy grandes. |
| `core:database` | Base de datos Room: libros, colecciones, progreso y traducciones guardadas. |

**Compilar**

```bash
./gradlew :app:assembleDebug        # APK de pruebas
./gradlew :app:assembleRelease      # APK firmado (requiere keystore.properties)
./gradlew :engine:text:testDebugUnitTest :engine:tts:testDebugUnitTest
```

Para firmar el release, crea `keystore.properties` en la raíz (no se sube al repositorio):

```properties
storeFile=keystore/lectora-release.jks
storePassword=...
keyAlias=lectora
keyPassword=...
```

**Cómo consigue una narración sin pausas:** los motores de voz no reproducen audio; generan sonido por adelantado (unos 20 s), se recorta el silencio que añaden al principio y al final de cada frase, y todo se encadena en un solo flujo de audio continuo. El texto fluye entre páginas y capítulos sin cortes.

### Licencias de terceros

sherpa-onnx (Apache 2.0) · ONNX Runtime (MIT) · PdfBox-Android (Apache 2.0) · Jsoup (MIT) · Apache Commons Compress (Apache 2.0) · ML Kit (Términos de Google) · Voces Piper y Kokoro: cada modelo tiene su propia licencia (ver la tarjeta del modelo en su paquete).
