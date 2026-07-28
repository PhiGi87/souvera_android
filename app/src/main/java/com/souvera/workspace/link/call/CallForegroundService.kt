/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.link.call

import android.Manifest
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.owncloud.android.R
import com.owncloud.android.ui.notifications.NotificationUtils

/**
 * Keeps a "Link" call alive while the app is backgrounded. The microphone type always applies; the
 * camera type is only added for a video call AND only when CAMERA is actually granted — Android 14+
 * throws a SecurityException (crashing the app) if a camera-typed FGS is started without the
 * permission held, which happens e.g. when answering an incoming voice call.
 */
class CallForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, NotificationUtils.NOTIFICATION_CHANNEL_PUSH)
            .setSmallIcon(R.drawable.notification_icon)
            .setContentTitle(getString(R.string.link_call_ongoing))
            .setOngoing(true)
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, foregroundType(intent))
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure { stopSelf() }
        return START_NOT_STICKY
    }

    private fun foregroundType(intent: Intent?): Int {
        val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        val withCamera = intent?.getBooleanExtra(EXTRA_WITH_CAMERA, false) == true && cameraGranted
        return if (withCamera) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }
    }

    companion object {
        const val EXTRA_WITH_CAMERA = "with_camera"
        private const val NOTIFICATION_ID = 47110816
    }
}
