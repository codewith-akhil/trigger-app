package com.example.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Opens this app's system Settings page — the permanent-denial escape hatch
 * for runtime permissions. When Android will no longer show the permission
 * dialog ("Don't ask again"), the user can only grant the permission here;
 * returning to the app resumes the pending action normally.
 */
fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
