package com.lectora.narration

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Adaptador entre [NarrationController] y Media3: expone la narración como un "Player" para que
 * la MediaSession publique la notificación, los controles de pantalla de bloqueo, Bluetooth,
 * auriculares, relojes y Android Auto.
 *
 * Siguiente/anterior = saltar párrafo.
 */
@OptIn(UnstableApi::class)
class NarrationPlayer(
    private val controller: NarrationController,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        scope.launch { controller.state.collect { invalidateState() } }
    }

    override fun getState(): State {
        val s = controller.state.value
        val commands = Player.Commands.Builder()
            .addAll(
                Player.COMMAND_PLAY_PAUSE,
                Player.COMMAND_STOP,
                Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                Player.COMMAND_GET_METADATA,
                Player.COMMAND_GET_TIMELINE,
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                Player.COMMAND_RELEASE,
            )
            .build()
        val builder = State.Builder().setAvailableCommands(commands)

        val bookId = s.bookId
        if (bookId == null || s.title == null) {
            return builder
                .setPlaybackState(Player.STATE_IDLE)
                .setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .build()
        }

        val metadata = MediaMetadata.Builder()
            .setTitle(s.title)
            .setArtist(s.sectionTitle ?: s.author)
            .setAlbumTitle(s.author)
            .setDisplayTitle(s.title)
            .setSubtitle(s.sectionTitle)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .apply { controller.artwork?.let { setArtworkData(it, MediaMetadata.PICTURE_TYPE_FRONT_COVER) } }
            .build()
        val item = MediaItemData.Builder(bookId)
            .setMediaItem(MediaItem.Builder().setMediaId(bookId.toString()).setMediaMetadata(metadata).build())
            .setMediaMetadata(metadata)
            .setIsSeekable(false)
            .build()

        val (playbackState, playWhenReady) = when (s.status) {
            NarrationStatus.IDLE -> Player.STATE_IDLE to false
            NarrationStatus.PREPARING -> Player.STATE_BUFFERING to true
            NarrationStatus.PLAYING -> Player.STATE_READY to true
            NarrationStatus.PAUSED -> Player.STATE_READY to false
            NarrationStatus.ENDED -> Player.STATE_ENDED to false
            NarrationStatus.ERROR -> Player.STATE_IDLE to false
        }
        builder
            .setPlaylist(listOf(item))
            .setCurrentMediaItemIndex(0)
            .setPlaybackState(playbackState)
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        if (s.status == NarrationStatus.ERROR) {
            builder.setPlayerError(PlaybackException(s.error ?: "Error", null, PlaybackException.ERROR_CODE_UNSPECIFIED))
        }
        return builder.build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady) controller.play() else controller.pause()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleStop(): ListenableFuture<*> {
        controller.stop()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        when (seekCommand) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> controller.skipParagraphs(1)
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> controller.skipParagraphs(-1)
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        scope.cancel()
        return Futures.immediateVoidFuture()
    }
}
