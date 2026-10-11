package com.doxigo.tv30

import android.content.Context
import android.net.Uri
import android.view.KeyEvent
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.Util
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession

/**
 * The player screen's media session: the system and Google Assistant see what plays (the title,
 * the episode, the cover) and can pause, seek and skip it. It lives and dies with the screen: no
 * service, no notification, nothing playing on in the background.
 *
 * The remote's media keys come in through [onKey] instead, at the activity and before Leanback, so
 * they act once wherever focus is (controls hidden, the panel open, the skip pill). Left to
 * Leanback, the glue swallows next, previous and the seek keys without acting on them; left to the
 * system's route into the session, play/pause waits 300 ms to rule out a double tap.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class PlayerSession(context: Context, player: Player, private val previous: () -> Unit, private val next: () -> Unit) {
    private var hasPrevious = false
    private var hasNext = false
    private val player = Episodic(player)
    private val session = MediaSession.Builder(context.applicationContext, this.player).build()

    /** What plays now, for the MediaItem to carry: the session announces it as the item changes. */
    var metadata: MediaMetadata = MediaMetadata.EMPTY
        private set

    /** A title or episode about to start; [previous] and [next] reach the episodes either side. */
    fun nowPlaying(title: CharSequence?, episode: CharSequence?, cover: String?, hasPrevious: Boolean, hasNext: Boolean) {
        this.hasPrevious = hasPrevious
        this.hasNext = hasNext
        metadata = MediaMetadata.Builder().setTitle(title).setSubtitle(episode).setArtworkUri(cover?.let(Uri::parse)).build()
    }

    /** True for a media key, down and up alike. A held seek key keeps seeking; the rest act once a press. */
    fun onKey(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        val once = down && event.repeatCount == 0
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> if (once) Util.handlePlayPauseButtonAction(player)
            KeyEvent.KEYCODE_MEDIA_PLAY -> if (once) Util.handlePlayButtonAction(player)
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> if (once) player.pause()
            KeyEvent.KEYCODE_MEDIA_NEXT -> if (once) player.seekToNext()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> if (once) player.seekToPrevious()
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> if (down) player.seekForward()
            KeyEvent.KEYCODE_MEDIA_REWIND -> if (down) player.seekBack()
            else -> return false
        }
        return true
    }

    /** Before the player's own release. */
    fun release() = session.release()

    /**
     * The player as the session and the keys drive it: next and previous are the series' episodes
     * (the playlist only ever holds the one file), seeks step 10 s as the transport row's do, and
     * stop only pauses: leaving the screen is what ends playback.
     */
    private inner class Episodic(player: Player) : ForwardingPlayer(player) {
        override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
            .removeAll(Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .addIf(Player.COMMAND_SEEK_TO_PREVIOUS, hasPrevious).addIf(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM, hasPrevious)
            .addIf(Player.COMMAND_SEEK_TO_NEXT, hasNext).addIf(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM, hasNext)
            .build()

        override fun isCommandAvailable(command: Int) = availableCommands.contains(command)
        override fun seekToPrevious() = seekToPreviousMediaItem()
        override fun seekToPreviousMediaItem() {
            if (hasPrevious) previous()
        }
        override fun seekToNext() = seekToNextMediaItem()
        override fun seekToNextMediaItem() {
            if (hasNext) next()
        }
        override fun seekBack() = seekTo((currentPosition - SEEK_MS).coerceAtLeast(0))
        override fun seekForward() {
            seekTo(currentPosition + SEEK_MS)
            play()
        }
        override fun stop() = pause()
    }

    private companion object {
        /** The transport row's rewind and fast-forward step. */
        const val SEEK_MS = 10_000L
    }
}
