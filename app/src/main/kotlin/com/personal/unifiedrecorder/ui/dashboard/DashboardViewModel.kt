package com.personal.unifiedrecorder.ui.dashboard

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.personal.unifiedrecorder.core.model.CallTypeFilter
import com.personal.unifiedrecorder.core.model.DashboardFilterState
import com.personal.unifiedrecorder.core.model.DirectionFilter
import com.personal.unifiedrecorder.core.playback.PlaybackOp
import com.personal.unifiedrecorder.core.playback.PlaybackState
import com.personal.unifiedrecorder.core.playback.PlaybackStateModel

/**
 * Lightweight ViewModel that holds the dashboard's transient UI state: the
 * active filter/search selections, the bookmark-overlay target, and the pure
 * playback state. Playback transitions are computed by the core
 * `PlaybackStateModel`; this ViewModel only holds the resulting snapshot and
 * exposes intent methods, so it stays free of any Android adapter dependency.
 * The application wiring feeds real playback results in via [applyPlayback].
 */
class DashboardViewModel : ViewModel() {

    var filterState by mutableStateOf(DashboardFilterState())
        private set

    var playback by mutableStateOf(PlaybackStateModel.initial())
        private set

    var bookmarkTarget by mutableStateOf<String?>(null)
        private set

    fun setCallTypeFilter(filter: CallTypeFilter) {
        filterState = filterState.copy(callTypeFilter = filter)
    }

    fun setDirectionFilter(filter: DirectionFilter) {
        filterState = filterState.copy(directionFilter = filter)
    }

    fun setSearchText(text: String) {
        filterState = filterState.copy(searchText = text)
    }

    fun openBookmark(fileName: String) {
        bookmarkTarget = fileName
    }

    fun dismissBookmark() {
        bookmarkTarget = null
    }

    /** Reduce the playback snapshot with [op] using the pure core state model. */
    fun applyPlayback(op: PlaybackOp) {
        playback = PlaybackStateModel.reduce(playback, op)
    }

    /** Directly set the playback snapshot (e.g. from an adapter position poll). */
    fun updatePlayback(state: PlaybackState) {
        playback = state
    }
}
