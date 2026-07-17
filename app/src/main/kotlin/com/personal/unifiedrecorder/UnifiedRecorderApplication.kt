package com.personal.unifiedrecorder

import android.app.Application
import com.personal.unifiedrecorder.di.AppGraph
import com.personal.unifiedrecorder.monitoring.CallMonitoringController

/**
 * Application entry point (Task 23.1). Builds the process-wide [AppGraph] and starts background
 * call monitoring.
 *
 * The graph binds every Android adapter to the pure-core components; the [CallMonitoringController]
 * wires the accessibility event flow into the capture orchestrator. Monitoring only begins a capture
 * once onboarding is complete and consent has been acknowledged, so starting the collector here is
 * safe even before setup finishes — the per-event gates keep it inert until then.
 *
 * Registered via `android:name` in the manifest.
 */
class UnifiedRecorderApplication : Application() {

    lateinit var graph: AppGraph
        private set

    private lateinit var monitoring: CallMonitoringController

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        monitoring = CallMonitoringController(graph)
        monitoring.start()
    }
}
