/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.link.call

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lightweight file-backed log for the "Link" call stack so the signaling exchange can be inspected
 * on-device without a PC/adb: every call step is appended to a shareable text file and mirrored to
 * logcat. Enable [attach] once (from the Link entry point) to start recording.
 */
object CallDebugLog {

    private const val TAG = "LinkCall"
    private const val FILE_NAME = "link-call.log"
    private const val MAX_BYTES = 512 * 1024

    @Volatile
    private var file: File? = null

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun attach(context: Context) {
        if (file != null) return
        file = File(context.getExternalFilesDir(null), FILE_NAME)
    }

    fun logFile(context: Context): File {
        attach(context)
        return file ?: File(context.getExternalFilesDir(null), FILE_NAME)
    }

    fun clear() {
        runCatching { file?.writeText("") }
    }

    @Synchronized
    fun log(tag: String, message: String) {
        Log.d(TAG, "$tag: $message")
        val target = file ?: return
        runCatching {
            if (target.exists() && target.length() > MAX_BYTES) {
                target.writeText("")
            }
            target.appendText("${timeFormat.format(Date())} $tag: $message\n")
        }
    }
}
