package com.personal.unifiedrecorder.adapter

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.personal.unifiedrecorder.core.port.MediaPlaybackController
import com.personal.unifiedrecorder.core.port.PlaybackResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * [MediaPlaybackController] over [android.media.MediaPlayer] (Requirements 7.6, 7.7, 7.10).
 *
 * Recordings are resolved by name within the bound Target_Directory tree so playback stays
 * confined to the app's own storage. Playback failures surface as an unsuccessful
 * [PlaybackResult] rather than throwing, so the UI can show an error and retain the entry (Req 7.10).
 *
 * Device-only / manual verification: real playback requires audio hardware and a decodable file.
 */
class MediaPlayerController(
    context: Context,
    private val treeUriProvider: () -> String?
) : MediaPlaybackController {

    private val appContext = context.applicationContext

    @Volatile
    private var player: MediaPlayer? = null

    override suspend fun play(name: String): PlaybackResult = withContext(Dispatchers.IO) {
        stop()
        val uri = resolveDocumentUri(name)
            ?: return@withContext PlaybackResult(success = false, errorMessage = "Recording '$name' not found")

        val mp = MediaPlayer()
        runCatching {
            mp.setDataSource(appContext, uri)
            prepare(mp)
            mp.start()
            player = mp
            PlaybackResult(success = true, durationMillis = mp.duration.toLong())
        }.getOrElse { t ->
            runCatching { mp.reset() }
            runCatching { mp.release() }
            if (player === mp) player = null
            PlaybackResult(success = false, errorMessage = t.message ?: "Playback failed")
        }
    }

    override fun pause() {
        player?.let { if (it.isPlaying) runCatching { it.pause() } }
    }

    override fun resume() {
        player?.let { runCatching { it.start() } }
    }

    override fun stop() {
        player?.let { mp ->
            runCatching { if (mp.isPlaying) mp.stop() }
        }
        releasePlayer()
    }

    override val positionMillis: Long
        get() = player?.let { runCatching { it.currentPosition.toLong() }.getOrDefault(0L) } ?: 0L

    private suspend fun prepare(mp: MediaPlayer) = suspendCancellableCoroutine { cont ->
        mp.setOnPreparedListener { if (cont.isActive) cont.resume(Unit) }
        mp.setOnErrorListener { _, what, extra ->
            if (cont.isActive) {
                cont.resumeWith(
                    Result.failure(IllegalStateException("MediaPlayer error what=$what extra=$extra"))
                )
            }
            true // error handled
        }
        mp.prepareAsync()
        cont.invokeOnCancellation { runCatching { mp.reset() } }
    }

    private fun resolveDocumentUri(name: String): Uri? {
        val treeUri = treeUriProvider()?.let(Uri::parse) ?: return null
        val root = DocumentFile.fromTreeUri(appContext, treeUri) ?: return null
        return root.findFile(name)?.takeIf { it.isFile }?.uri
    }

    private fun releasePlayer() {
        player?.let { runCatching { it.reset() }; runCatching { it.release() } }
        player = null
    }
}
