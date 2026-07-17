package com.personal.unifiedrecorder.adapter

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.personal.unifiedrecorder.core.model.RuntimePermission
import com.personal.unifiedrecorder.core.port.PermissionChecker

/**
 * [PermissionChecker] backed by [ContextCompat.checkSelfPermission] for grant status and
 * [ActivityCompat.shouldShowRequestPermissionRationale] for the permanently-denied heuristic
 * (Requirements 8.2, 8.4, 8.5).
 *
 * [shouldOpenSettings] returns true when a permission is not granted AND the system will no longer
 * show the rationale prompt (the user selected "don't ask again"), meaning the user must enable it
 * from the app settings screen. This requires an [Activity]; when none is available it conservatively
 * returns false.
 *
 * Device-only / manual verification: the "don't ask again" state depends on real user interaction
 * with the system permission dialog.
 */
class AndroidPermissionChecker(
    context: Context,
    private val activityProvider: () -> Activity? = { null }
) : PermissionChecker {

    private val appContext = context.applicationContext

    override fun status(p: RuntimePermission): Boolean {
        val androidPermission = RuntimePermissions.androidPermission(p)
        return ContextCompat.checkSelfPermission(appContext, androidPermission) ==
            PackageManager.PERMISSION_GRANTED
    }

    override fun shouldOpenSettings(p: RuntimePermission): Boolean {
        if (status(p)) return false
        val activity = activityProvider() ?: return false
        val androidPermission = RuntimePermissions.androidPermission(p)
        // Not granted and no rationale should be shown => permanently denied ("don't ask again").
        return !ActivityCompat.shouldShowRequestPermissionRationale(activity, androidPermission)
    }
}
