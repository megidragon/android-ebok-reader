package com.lectora.narration

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.content.ContextCompat

/**
 * Todo lo necesario para que la narración siga con la pantalla apagada y conviva con otras apps:
 * - WakeLock parcial: la CPU sigue sintetizando por delante aunque el teléfono duerma.
 * - Foco de audio: pausa ante llamadas/otras apps y reanuda al recuperar el foco.
 * - "Becoming noisy": pausa al desconectar auriculares.
 */
internal class PlaybackGuard(
    context: Context,
    private val onPauseRequested: () -> Unit,
    private val onResumeRequested: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val wakeLock = appContext.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Lectora:narration")
        .apply { setReferenceCounted(false) }

    private var hasFocus = false
    private var resumeOnFocusGain = false
    private var noisyRegistered = false
    /** Si la narración está sonando ahora mismo (entre onPlay y onPause). */
    private var playing = false

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        // Para voz no tiene sentido "bajar volumen": mejor pausar y retomar.
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({ change -> onFocusChange(change) }, Handler(Looper.getMainLooper()))
        .build()

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) onPauseRequested()
        }
    }

    /** Antes de reproducir. Devuelve false si el sistema niega el foco (p. ej. en llamada). */
    fun onPlay(): Boolean {
        if (!hasFocus) {
            val result = audioManager.requestAudioFocus(focusRequest)
            hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (!hasFocus) return false
        }
        resumeOnFocusGain = false
        playing = true
        if (!noisyRegistered) {
            ContextCompat.registerReceiver(
                appContext, noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            noisyRegistered = true
        }
        // Tope de seguridad: se renueva en cada play.
        wakeLock.acquire(6 * 60 * 60 * 1000L)
        return true
    }

    fun onPause() {
        playing = false
        if (wakeLock.isHeld) wakeLock.release()
        if (noisyRegistered) {
            runCatching { appContext.unregisterReceiver(noisyReceiver) }
            noisyRegistered = false
        }
    }

    /** El usuario pausó a mano: no reanudar solo al recuperar el foco. */
    fun clearAutoResume() {
        resumeOnFocusGain = false
    }

    fun onStop() {
        onPause()
        resumeOnFocusGain = false
        if (hasFocus) {
            audioManager.abandonAudioFocusRequest(focusRequest)
            hasFocus = false
        }
    }

    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnFocusGain) {
                resumeOnFocusGain = false
                onResumeRequested()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Solo reanudar al recuperar el foco si estaba sonando: si el usuario ya había
                // pausado (p. ej. y llega una notificación), debe seguir en pausa.
                val wasPlaying = playing
                if (wasPlaying) onPauseRequested()
                resumeOnFocusGain = wasPlaying
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                onPauseRequested()
                resumeOnFocusGain = false
                hasFocus = false
            }
        }
    }
}
