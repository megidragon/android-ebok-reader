package com.lectora.app.playback

import com.lectora.app.data.SettingsRepository
import com.lectora.core.model.ReadingPosition
import com.lectora.narration.NarrationController
import com.lectora.narration.SleepTimer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Acciones de reproducción que usa la UI (mini reproductor, lector, notificación). */
class PlayerActions(
    private val scope: CoroutineScope,
    private val connection: PlaybackConnection,
    private val narration: NarrationController,
    private val settings: SettingsRepository,
) {
    val state = narration.state

    fun playBook(bookId: Long, from: ReadingPosition? = null) {
        scope.launch { connection.play(bookId, from) }
    }

    fun toggle() {
        scope.launch { connection.toggle() }
    }

    fun pause() = narration.pause()
    fun nextParagraph() = narration.skipParagraphs(1)
    fun previousParagraph() = narration.skipParagraphs(-1)
    fun nextSection() = narration.skipSections(1)
    fun previousSection() = narration.skipSections(-1)

    fun setSpeechRate(rate: Float) {
        scope.launch { settings.setSpeechRate(rate) }
    }

    fun setSleepTimer(minutes: Int?) {
        narration.setSleepTimer(
            when (minutes) {
                null -> SleepTimer.Off
                0 -> SleepTimer.EndOfSection
                else -> SleepTimer.AtTime(System.currentTimeMillis() + minutes * 60_000L)
            }
        )
    }
}
