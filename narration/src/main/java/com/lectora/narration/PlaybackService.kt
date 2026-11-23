package com.lectora.narration

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Servicio en primer plano (tipo mediaPlayback) que mantiene viva la narración con la pantalla
 * apagada/bloqueada o con la app en segundo plano. Media3 lo promueve a primer plano mientras
 * se reproduce y muestra la notificación con controles.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val controller = (application as NarrationHost).narrationController
        val player = NarrationPlayer(controller)
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_PLAYER, true)
        }
        val builder = MediaSession.Builder(this, player)
        if (launch != null) {
            builder.setSessionActivity(
                PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
        }
        session = builder.build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Si el usuario cierra la app desde recientes y no está sonando, se detiene el servicio.
        // Si está narrando, sigue (como cualquier reproductor de audiolibros).
        val player = session?.player
        if (player == null || !player.playWhenReady) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "com.lectora.OPEN_PLAYER"
    }
}
