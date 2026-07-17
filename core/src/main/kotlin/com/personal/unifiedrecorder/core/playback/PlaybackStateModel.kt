package com.personal.unifiedrecorder.core.playback

import com.personal.unifiedrecorder.core.port.PlaybackResult

/** The lifecycle status of the dashboard playback transport. */
enum class PlaybackStatus { IDLE, PLAYING, PAUSED, STOPPED, ERROR }

/**
 * Immutable snapshot of the playback transport (Req 7.7, 7.10).
 *
 * [currentEntry] is the file name of the recording the transport is bound to.
 * It is retained across an error so the dashboard can keep the recording in the
 * list while surfacing the failure (Req 7.10).
 */
data class PlaybackState(
    val currentEntry: String? = null,
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val positionMillis: Long = 0L,
    val durationMillis: Long = 0L,
    val errorMessage: String? = null
)

/** An operation applied to the playback transport. */
sealed interface PlaybackOp {
    /** Attempt to start playback of [name]; [result] carries success/failure. */
    data class Play(val name: String, val result: PlaybackResult) : PlaybackOp

    /** Simulated playback progress of [deltaMillis]; only advances while PLAYING. */
    data class Advance(val deltaMillis: Long) : PlaybackOp

    /** Pause playback, retaining the current position. */
    data object Pause : PlaybackOp

    /** Resume playback from the paused position. */
    data object Resume : PlaybackOp

    /** Stop playback and return the position to the start. */
    data object Stop : PlaybackOp
}

/**
 * Pure state machine for the dashboard playback transport.
 *
 * - `resume` continues from the paused position (position unchanged).
 * - `stop` resets the position to 0.
 * - a failed [PlaybackResult] produces an [PlaybackStatus.ERROR] state while the
 *   bound entry is retained (the recording remains in the caller's list).
 */
object PlaybackStateModel {

    fun initial(): PlaybackState = PlaybackState()

    fun reduce(state: PlaybackState, op: PlaybackOp): PlaybackState =
        when (op) {
            is PlaybackOp.Play ->
                if (op.result.success) {
                    PlaybackState(
                        currentEntry = op.name,
                        status = PlaybackStatus.PLAYING,
                        positionMillis = 0L,
                        durationMillis = op.result.durationMillis,
                        errorMessage = null
                    )
                } else {
                    // Playback could not start: enter error state but retain the
                    // bound entry so the recording stays in the list (Req 7.10).
                    PlaybackState(
                        currentEntry = op.name,
                        status = PlaybackStatus.ERROR,
                        positionMillis = 0L,
                        durationMillis = 0L,
                        errorMessage = op.result.errorMessage ?: "Recording could not be played"
                    )
                }

            is PlaybackOp.Advance ->
                if (state.status == PlaybackStatus.PLAYING) {
                    val next = state.positionMillis + op.deltaMillis
                    val clamped = when {
                        next < 0L -> 0L
                        state.durationMillis > 0L && next > state.durationMillis -> state.durationMillis
                        else -> next
                    }
                    state.copy(positionMillis = clamped)
                } else {
                    state
                }

            PlaybackOp.Pause ->
                if (state.status == PlaybackStatus.PLAYING) {
                    state.copy(status = PlaybackStatus.PAUSED)
                } else {
                    state
                }

            PlaybackOp.Resume ->
                if (state.status == PlaybackStatus.PAUSED) {
                    // Resume from the paused position: position is unchanged.
                    state.copy(status = PlaybackStatus.PLAYING)
                } else {
                    state
                }

            PlaybackOp.Stop ->
                state.copy(status = PlaybackStatus.STOPPED, positionMillis = 0L)
        }
}
