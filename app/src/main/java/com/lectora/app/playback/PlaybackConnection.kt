package com.lectora.app.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.lectora.core.model.ReadingPosition
import com.lectora.narration.NarrationController
import com.lectora.narration.PlaybackService
import kotlinx.coroutines.guava.await

/**
 * Conecta la UI con [PlaybackService]. Conectarse crea el servicio y su MediaSession, de modo
 * que en cuanto empieza a sonar Media3 lo pasa a primer plano (notificación + pantalla bloqueada).
 */
class PlaybackConnection(private val context: Context, private val narration: NarrationController) {
    private var future: ListenableFuture<MediaController>? = null

    fun connect() {
        if (future != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        future = MediaController.Builder(context, token).buildAsync()
    }

    fun disconnect() {
        future?.let { MediaController.releaseFuture(it) }
        future = null
    }

    private suspend fun controller(): MediaController? {
        connect()
        return runCatching { future?.await() }.getOrNull()
    }

    /** Empieza (o retoma) la narración de un libro. */
    suspend fun play(bookId: Long, from: ReadingPosition? = null) {
        controller() // asegura que el servicio exista antes de sonar
        narration.load(bookId, from)
        narration.play()
    }

    suspend fun toggle() {
        controller()
        narration.togglePlayPause()
    }
}
