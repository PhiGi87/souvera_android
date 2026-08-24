/*
 * Nextcloud - Android Client
 *
 * SPDX-FileCopyrightText: 2026 Nextcloud GmbH and Nextcloud contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package com.souvera.workspace.link.call

import android.Manifest
import android.os.Handler
import android.os.Looper
import androidx.appcompat.widget.SwitchCompat
import android.widget.ImageView
import android.accounts.AccountManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.owncloud.android.R
import com.souvera.workspace.dav.SouveraSyncManager
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * Full-screen 1:1 "Link" call: sets up local/remote [SurfaceViewRenderer]s, runs a [CallSession]
 * against the HPB signaling + TURN infrastructure, and offers mute/video/hang-up controls. Requires
 * CAMERA + RECORD_AUDIO at runtime. Call correctness/stability is validated on-device.
 */
class CallActivity :
    AppCompatActivity(),
    CallSession.Callbacks {

    private lateinit var remoteRenderer: SurfaceViewRenderer
    private lateinit var localRenderer: SurfaceViewRenderer
    private var session: CallSession? = null
    private var muted = false
    private var videoOn = true
    private var withVideo = true
    private var ringback: ToneGenerator? = null
    private var proximityLock: android.os.PowerManager.WakeLock? = null
    private var callEndReceiver: android.content.BroadcastReceiver? = null
    private var callerVideoPoll: Handler? = null
    private var callerVideoPollRunnable: Runnable? = null
    private var callerHasVideo = false
    private var roomType = -1
    private var answered = false
    private val ringingTimeoutHandler = Handler(Looper.getMainLooper())
    private val ringingTimeoutRunnable = Runnable {
        if (!answered && !isFinishing) {
            CallDebugLog.log(TAG, "ringing timeout — unanswered call ended")
            cancelIncomingNotification()
            finish()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) startCall() else denyAndFinish()
    }

    private val cameraUpgradeLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) enableVideoNow() }

    private fun enableVideoNow() {
        findViewById<ImageButton>(R.id.call_video).alpha = 1f
        session?.enableVideo()
        videoOn = true
        localRenderer.visibility = View.VISIBLE
        releaseProximityLock()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        callEndReceiver = CallEndReceiver()
        androidx.core.content.ContextCompat.registerReceiver(
            this,
            callEndReceiver,
            android.content.IntentFilter(LinkCallEnd.ACTION_CALL_ENDED),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Incoming calls launch this over the lockscreen and must wake the screen.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        setContentView(R.layout.activity_link_call)
        remoteRenderer = findViewById(R.id.call_remote)
        localRenderer = findViewById(R.id.call_local)
        // Ohne Remote-Video bleibt der Renderer unsichtbar, damit der
        // Verlaufs-Hintergrund + Poster (Name/Status) sichtbar bleiben —
        // ein SurfaceView ohne Frames wuerde sonst schwarz ueberdecken.
        remoteRenderer.visibility = View.INVISIBLE
        withVideo = intent.getBooleanExtra(EXTRA_VIDEO, true)
        videoOn = withVideo
        findViewById<TextView>(R.id.call_title).text =
            intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.drawer_item_link)

        // Decline direkt aus der Notification: Anruf serverseitig beenden
        // (Join + Hangup), damit der Anrufer nicht weiterklingelt.
        if (intent.getBooleanExtra(EXTRA_DECLINE, false)) {
            declineAndFinish()
            return
        }
        // An incoming call opens a ringing screen (Accept/Decline) instead of joining immediately;
        // only Accept proceeds. Outgoing calls / the notification "Answer" action join directly.
        if (intent.getBooleanExtra(EXTRA_INCOMING, false)) {
            showRinging()
        } else {
            proceedToCall()
        }
    }

    private fun showRinging() {
        answered = false
        findViewById<TextView>(R.id.call_status).setText(R.string.link_call_incoming_ringing)
        findViewById<LinearLayout>(R.id.call_controls).visibility = View.GONE
        findViewById<LinearLayout>(R.id.call_incoming).visibility = View.VISIBLE
        findViewById<LinearLayout>(R.id.call_incoming_video_row).visibility = View.VISIBLE
        val videoCheck = findViewById<SwitchCompat>(R.id.call_incoming_video)
        videoCheck.isChecked = withVideo
        startCallerVideoPoll()
        findViewById<ImageButton>(R.id.call_accept).setOnClickListener {
            answered = true
            stopCallerVideoPoll()
            ringingTimeoutHandler.removeCallbacks(ringingTimeoutRunnable)
            findViewById<LinearLayout>(R.id.call_incoming).visibility = View.GONE
            findViewById<LinearLayout>(R.id.call_incoming_video_row).visibility = View.GONE
            findViewById<LinearLayout>(R.id.call_controls).visibility = View.VISIBLE
            findViewById<TextView>(R.id.call_status).setText(R.string.link_call_connecting)
            cancelIncomingNotification()
            withVideo = videoCheck.isChecked
            videoOn = withVideo
            proceedToCall()
        }
        findViewById<ImageButton>(R.id.call_decline).setOnClickListener {
            ringingTimeoutHandler.removeCallbacks(ringingTimeoutRunnable)
            declineAndFinish()
        }
        ringingTimeoutHandler.postDelayed(ringingTimeoutRunnable, RING_TIMEOUT_MS)
    }

    /**
     * Prueft waehrend des Klingelns alle 2 s, ob der Anrufer Video aktiv
     * hat (Talk inCall-Bit 4) und laesst das Kamera-Icon dann blinken;
     * ohne Video bleibt es ausgegraut sichtbar.
     */
    private fun startCallerVideoPoll() {
        stopCallerVideoPoll()
        val handler = Handler(Looper.getMainLooper())
        callerVideoPoll = handler
        val token = intent.getStringExtra(EXTRA_TOKEN) ?: return
        val account = AccountManager.get(this)
            .getAccountsByType(getString(R.string.account_type)).firstOrNull()
        val dav = account?.let { SouveraSyncManager(this).resolve(it) } ?: return
        val api = com.souvera.workspace.link.net.OcsApi(dav)
        val icon = findViewById<ImageView>(R.id.call_incoming_video_icon)
        val blink = object : Runnable {
            private var bright = false
            private var successfulPolls = 0
            override fun run() {
                if (answered || isFinishing || isDestroyed) return
                var callActive = true
                runCatching {
                    val flags = api.participantInCallFlags(token)
                    callerHasVideo = flags.any { (it and 4) != 0 }
                    callActive = flags.any { (it and 1) != 0 }
                    successfulPolls++
                }
                if (callerHasVideo) {
                    bright = !bright
                    icon.alpha = if (bright) 1f else 0.45f
                } else {
                    bright = false
                    icon.alpha = 0.35f
                }
                if (!callActive && successfulPolls > 0) {
                    // Anrufer hat aufgelegt / Anruf beendet — Klingeln stoppen.
                    CallDebugLog.log(TAG, "ringing: call no longer active — dismissing")
                    stopCallerVideoPoll()
                    runOnUiThread {
                        val nid = intent.getIntExtra(EXTRA_NID, 0)
                        if (nid != 0) {
                            val caller = LinkCallNotifications.consumeForMissed(this@CallActivity, nid)
                            if (caller != null) {
                                LinkCallNotifications.markEnded(this@CallActivity, intent.getStringExtra(EXTRA_TOKEN))
                                LinkCallNotifications.showMissed(this@CallActivity, nid, caller)
                            }
                        }
                        cancelIncomingNotification()
                        if (!answered) finish()
                    }
                    return
                }
                handler.postDelayed(this, CALLER_VIDEO_POLL_MS)
            }
        }
        callerVideoPollRunnable = blink
        handler.post(blink)
    }

    private fun stopCallerVideoPoll() {
        callerVideoPoll?.removeCallbacksAndMessages(null)
        callerVideoPoll = null
        callerVideoPollRunnable = null
    }

    /**
     * Ablehnen beendet den Anruf fuer BEIDE Seiten: kurz joinen und sofort
     * auflegen, damit der Server den anderen Teilnehmern das Anrufende
     * signalisiert (sonst klingelt der Anrufer weiter).
     */
    /** Gruppenanruf: fragen, ob fuer alle beendet oder nur selbst verlassen wird. */
    private fun showHangupDialog(call: CallSession) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.link_hangup_title)
            .setMessage(R.string.link_hangup_question)
            .setPositiveButton(R.string.link_hangup_all) { _, _ -> call.hangup(endForAll = true) }
            .setNegativeButton(R.string.link_hangup_self) { _, _ -> call.hangup() }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }

    private fun declineAndFinish() {
        stopCallerVideoPoll()
        ringingTimeoutHandler.removeCallbacks(ringingTimeoutRunnable)
        cancelIncomingNotification()
        // Direktes Ablehnen OHNE Join: Talk kennt kein "Decline beendet fuer
        // alle" ohne aktive Session — ein Join wuerde den Anruf beim Anrufer
        // kurz als "angenommen" erscheinen lassen (und bei Call-Ende sogar
        // einen NEUEN Anruf ausloesen). Der Anrufer klingelt daher bis zu
        // seinem eigenen Timeout weiter (Talk-Standard).
        CallDebugLog.log(TAG, "decline: dismissing directly (no join)")
        finish()
    }

    private fun cancelIncomingNotification() {
        val nid = intent.getIntExtra(EXTRA_NID, 0)
        LinkCallNotifications.markAnswered(this, nid)
        if (nid != 0) {
            (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager).cancel(nid)
        }
    }

    private fun proceedToCall() {
        val required = if (withVideo) {
            arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        } else {
            arrayOf(Manifest.permission.RECORD_AUDIO)
        }
        val granted = required.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (granted) startCall() else permissionLauncher.launch(required)
    }

    private fun startCall() {
        val token = intent.getStringExtra(EXTRA_TOKEN) ?: return finish()
        val account = AccountManager.get(this).getAccountsByType(getString(R.string.account_type)).firstOrNull()
        val dav = account?.let { SouveraSyncManager(this).resolve(it) } ?: return denyAndFinish()

        val call = CallSession(this, dav, token, withVideo, this)
        session = call
        remoteRenderer.init(call.eglBaseContext, null)
        localRenderer.init(call.eglBaseContext, null)
        localRenderer.setZOrderMediaOverlay(true)
        localRenderer.setMirror(true)
        localRenderer.visibility = if (withVideo) View.VISIBLE else View.GONE
        remoteRenderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)

        val muteButton = findViewById<ImageButton>(R.id.call_mute)
        muteButton.setOnClickListener {
            muted = !muted
            call.setMuted(muted)
            muteButton.setImageResource(if (muted) R.drawable.ic_mic_off else R.drawable.ic_mic)
        }
        findViewById<ImageButton>(R.id.call_video).apply {
            visibility = View.VISIBLE
            alpha = if (withVideo) 1f else 0.4f
            setOnClickListener {
                if (!call.hasVideo) {
                    // Audio call → turn the camera on (request permission first if needed).
                    if (ContextCompat.checkSelfPermission(this@CallActivity, Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        enableVideoNow()
                    } else {
                        cameraUpgradeLauncher.launch(Manifest.permission.CAMERA)
                    }
                    return@setOnClickListener
                }
                videoOn = !videoOn
                call.setVideoEnabled(videoOn)
                localRenderer.visibility = if (videoOn) View.VISIBLE else View.GONE
                alpha = if (videoOn) 1f else 0.4f
                if (videoOn) releaseProximityLock() else acquireProximityLock()
            }
        }
        findViewById<ImageButton>(R.id.call_chat).setOnClickListener {
            // Öffnet den Chat für dieses Gespräch; der Anruf läuft im
            // Hintergrund weiter (CallActivity bleibt im Back-Stack).
            startActivity(
                Intent(this, com.souvera.workspace.link.ui.LinkActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(com.souvera.workspace.push.MailPushNotifier.EXTRA_CHAT_TOKEN, token)
                    .putExtra(Intent.EXTRA_TITLE, intent.getStringExtra(EXTRA_TITLE).orEmpty())
            )
        }
        findViewById<ImageButton>(R.id.call_hangup).setOnClickListener {
            if (roomType == 1) {
                // 1:1: Auflegen beendet den Anruf fuer beide Seiten.
                call.hangup(endForAll = true)
            } else {
                showHangupDialog(call)
            }
        }

        if (!withVideo) acquireProximityLock()
        Thread {
            runCatching {
                roomType = com.souvera.workspace.link.net.OcsApi(dav).getRoomType(token)
                CallDebugLog.log(TAG, "roomType=$roomType")
            }
        }.start()
        startRingback()
        registerWithTelecom(call)
        ContextCompat.startForegroundService(
            this,
            Intent(this, CallForegroundService::class.java)
                .putExtra(CallForegroundService.EXTRA_WITH_CAMERA, withVideo)
        )
        Thread { call.start() }.start()
    }

    // Registers the call with Telecom (self-managed) so audio routes through a connected car/Bluetooth
    // kit and the call can be ended from the car; also puts the audio stack in communication mode.
    private fun registerWithTelecom(call: CallSession) {
        runCatching {
            (getSystemService(AUDIO_SERVICE) as AudioManager).mode = AudioManager.MODE_IN_COMMUNICATION
        }
        val caller = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.drawer_item_link)
        com.souvera.workspace.link.call.telecom.LinkTelecom.registerAccount(this)
        com.souvera.workspace.link.call.telecom.LinkTelecom.reportOngoingCall(
            this,
            caller,
            incoming = false
        ) { runOnUiThread { call.hangup() } }
    }

    private fun startRingback() {
        runCatching {
            ringback = ToneGenerator(AudioManager.STREAM_VOICE_CALL, RINGBACK_VOLUME).apply {
                startTone(ToneGenerator.TONE_SUP_RINGTONE)
            }
        }
    }

    private fun stopRingback() {
        runCatching { ringback?.stopTone() }
        runCatching { ringback?.release() }
        ringback = null
    }

    // Turns the screen off while the phone is held to the ear during a voice call, so the cheek
    // cannot tap controls (e.g. hang up). Not used for video calls where the screen must stay on.
    private fun acquireProximityLock() {
        if (proximityLock != null) return
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (!pm.isWakeLockLevelSupported(android.os.PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return
        proximityLock = pm.newWakeLock(
            android.os.PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
            "souvera:link-call"
        ).apply { runCatching { acquire() } }
    }

    private fun releaseProximityLock() {
        runCatching { if (proximityLock?.isHeld == true) proximityLock?.release() }
        proximityLock = null
    }

    override fun onLocalVideo(track: VideoTrack) = runOnUiThread {
        track.addSink(localRenderer)
        localRenderer.visibility = View.VISIBLE
    }

    override fun onRemoteVideo(track: VideoTrack) = runOnUiThread {
        track.addSink(remoteRenderer)
        remoteRenderer.visibility = View.VISIBLE
        findViewById<LinearLayout>(R.id.call_poster).visibility = View.GONE
    }

    override fun onRemoteConnected() = runOnUiThread {
        stopRingback()
        findViewById<TextView>(R.id.call_status).setText(R.string.link_call_connected)
    }

    override fun onRecovering(recovering: Boolean) = runOnUiThread {
        findViewById<TextView>(R.id.call_status).setText(
            if (recovering) R.string.link_call_reconnecting
            else if (answered) R.string.link_call_connected
            else R.string.link_call_connecting
        )
    }

    override fun onEnded() = runOnUiThread {
        stopRingback()
        stopService(Intent(this, CallForegroundService::class.java))
        if (!isFinishing) finish()
    }

    private fun denyAndFinish() {
        Toast.makeText(this, R.string.link_call_permission_denied, Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { callEndReceiver?.let { unregisterReceiver(it) } }
        stopCallerVideoPoll()
        ringingTimeoutHandler.removeCallbacks(ringingTimeoutRunnable)
        releaseProximityLock()
        stopRingback()
        com.souvera.workspace.link.call.telecom.LinkTelecom.endCall()
        runCatching { (getSystemService(AUDIO_SERVICE) as AudioManager).mode = AudioManager.MODE_NORMAL }
        session?.hangup()
        runCatching { remoteRenderer.release() }
        runCatching { localRenderer.release() }
    }

    /** Schliesst den Klingel-Screen, sobald der Anruf serverseitig endet. */
    private inner class CallEndReceiver : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            val token = intent?.getStringExtra(EXTRA_TOKEN) ?: return
            if (token != this@CallActivity.intent.getStringExtra(EXTRA_TOKEN)) return
            if (answered) return
            CallDebugLog.log(TAG, "call ended broadcast — closing ringing screen")
            runOnUiThread {
                val nid = this@CallActivity.intent.getIntExtra(EXTRA_NID, 0)
                if (nid != 0) {
                    val caller = LinkCallNotifications.consumeForMissed(this@CallActivity, nid)
                    if (caller != null) {
                        LinkCallNotifications.markEnded(this@CallActivity, this@CallActivity.intent.getStringExtra(EXTRA_TOKEN))
                        LinkCallNotifications.showMissed(this@CallActivity, nid, caller)
                    }
                }
                stopCallerVideoPoll()
                cancelIncomingNotification()
                finish()
            }
        }

    }

    companion object {
        const val EXTRA_TOKEN = "link_call_token"
        const val EXTRA_DECLINE = "link_call_decline"
        const val EXTRA_TITLE = "link_call_title"
        const val EXTRA_VIDEO = "link_call_video"
        const val EXTRA_NID = "link_call_nid"
        const val EXTRA_INCOMING = "link_call_incoming"
        private const val RINGBACK_VOLUME = 80
        private const val RING_TIMEOUT_MS = 35000L
        private const val CALLER_VIDEO_POLL_MS = 2000L
        private const val TAG = "CallActivity"
    }
}
