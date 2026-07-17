package com.personal.unifiedrecorder.core.policy

import com.personal.unifiedrecorder.core.model.ExecutionMode

/**
 * Selects the capture [ExecutionMode] from superuser availability (Requirements 3.1, 3.2, 3.3).
 *
 * Pure, framework-independent decision: when a superuser (`su`) shell is available the
 * rooted stealth path is chosen; otherwise the unrooted loudspeaker path is used.
 */
object ExecutionModeSelector {

    /**
     * @param superuserAvailable whether an `su` shell was verified as available.
     * @return [ExecutionMode.ROOTED_STEALTH] when [superuserAvailable] is true,
     *         [ExecutionMode.UNROOTED_LOUDSPEAKER] otherwise.
     */
    fun select(superuserAvailable: Boolean): ExecutionMode =
        if (superuserAvailable) ExecutionMode.ROOTED_STEALTH else ExecutionMode.UNROOTED_LOUDSPEAKER
}
