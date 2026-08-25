/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.link.call

import android.content.Context

/**
 * Tracks which notification ids are currently-ringing incoming "Link" calls (caller name per id),
 * so a later call-ended push can morph the ring into a "missed call" instead of silently vanishing.
 * Backed by SharedPreferences because the push handler and the call UI run in separate processes.
 */
object LinkCallNotifications {

    private const val PREFS = "souvera_link_calls"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun markIncoming(context: Context, nid: Int, caller: String, room: String?) {
        prefs(context).edit()
            .putString(nid.toString(), caller)
            .apply()
        if (!room.isNullOrBlank()) {
            prefs(context).edit().putInt(roomKey(room), nid).apply()
        }
    }

    /** The still-ringing incoming-call notification id for [room], or 0 if none. */
    fun ringNidForRoom(context: Context, room: String?): Int {
        if (room.isNullOrBlank()) return 0
        return prefs(context).getInt(roomKey(room), 0)
    }

    fun clearRoom(context: Context, room: String?) {
        if (room.isNullOrBlank()) return
        prefs(context).edit().remove(roomKey(room)).apply()
    }

    /** Merkt sich, ob die Full-Screen-Intent-Berechtigung verfügbar ist. */
    fun storeFullScreenIntentAllowed(context: Context, allowed: Boolean) {
        prefs(context).edit().putBoolean("fsi_allowed", allowed).apply()
    }

    /** Zuletzt festgestellter Status der Full-Screen-Intent-Berechtigung. */
    fun fullScreenIntentAllowed(context: Context): Boolean =
        prefs(context).getBoolean("fsi_allowed", true)

    private fun roomKey(room: String) = "room_$room"

    /** Anruf wurde als beendet markiert (fuer die Missed-Push-Dedup). */
    fun markEnded(context: Context, room: String?) {
        if (room.isNullOrBlank()) return
        prefs(context).edit().putLong(endedKey(room), System.currentTimeMillis()).apply()
    }

    /** Wurde der Anruf in diesem Raum in den letzten 60s als beendet markiert? */
    fun endedRecently(context: Context, room: String?): Boolean {
        if (room.isNullOrBlank()) return false
        val ended = prefs(context).getLong(endedKey(room), 0)
        return ended != 0L && System.currentTimeMillis() - ended < 60_000
    }

    private fun endedKey(room: String) = "ended_$room"

    /** Beendet-Markierung loeschen (z. B. wenn ein NEUER Anruf erkannt wurde). */
    fun clearEnded(context: Context, room: String?) {
        if (room.isNullOrBlank()) return
        prefs(context).edit().remove(endedKey(room)).apply()
    }

    /** Zeigt die stille "Verpasster Anruf"-Notification. */
    fun showMissed(context: Context, nid: Int, caller: String) {
        val intent = android.content.Intent(context, com.souvera.workspace.link.ui.LinkActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = android.app.PendingIntent.getActivity(
            context,
            nid,
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val notification = androidx.core.app.NotificationCompat.Builder(context, CHANNEL_LINK_CALL)
            .setSmallIcon(com.owncloud.android.R.drawable.notification_icon)
            .setContentTitle(context.getString(com.owncloud.android.R.string.link_missed_call))
            .setContentText(caller)
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_CALL)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setOngoing(false)
            .setContentIntent(pending)
            .build()
        val manager = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(nid, notification)
    }

    const val CHANNEL_LINK_CALL = "souvera_link_call"

    /** Call was answered/opened; drop it so no missed-call notification is shown for it. */
    fun markAnswered(context: Context, nid: Int) {
        if (nid == 0) return
        prefs(context).edit().remove(nid.toString()).apply()
    }

    /** Returns the caller name if [nid] was an unanswered incoming call (and clears it), else null. */
    fun consumeForMissed(context: Context, nid: Int): String? {
        val store = prefs(context)
        val caller = store.getString(nid.toString(), null) ?: return null
        store.edit().remove(nid.toString()).apply()
        return caller
    }
}
