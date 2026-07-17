package com.personal.unifiedrecorder.adapter

import com.personal.unifiedrecorder.core.port.Clock

/**
 * [Clock] backed by the system wall clock. Used for timestamping recordings and status entries.
 */
class SystemClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}
