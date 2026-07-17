package com.personal.unifiedrecorder.adapter

import com.personal.unifiedrecorder.core.port.SuperuserProbe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataOutputStream

/**
 * [SuperuserProbe] that verifies `su` availability by attempting to spawn a superuser shell and
 * running a trivial command (Requirements 3.1, 3.2). Runs off the main thread on [Dispatchers.IO].
 *
 * Device-only / manual verification: the presence and behavior of an `su` binary depends on the
 * device's root state and cannot be exercised on the JVM or a stock emulator.
 */
class ShellSuperuserProbe : SuperuserProbe {

    override suspend fun isSuperuserAvailable(): Boolean = withContext(Dispatchers.IO) {
        var process: Process? = null
        try {
            process = Runtime.getRuntime().exec("su")
            DataOutputStream(process.outputStream).use { out ->
                out.writeBytes("id\n")
                out.writeBytes("exit\n")
                out.flush()
            }
            process.waitFor() == 0
        } catch (_: Exception) {
            false
        } finally {
            runCatching { process?.destroy() }
        }
    }
}
