package com.dualscreen.launcher.session

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Display
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import com.dualscreen.launcher.LowerPanelMode
import com.dualscreen.launcher.SupportedGame
import com.wemu.engine.renderer.SecondScreenOutput
import com.wemu.session.secondscreen.Buzz
import com.wemu.session.secondscreen.GuestHapticCue
import com.wemu.session.secondscreen.SecondScreen
import com.wemu.session.secondscreen.StardewScreen
import com.wemu.session.secondscreen.ValheimScreen
import com.thor.core.designsystem.theme.ThorTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln

class GamePanelPresentation(
    context: Context,
    display: Display,
    private val game: SupportedGame,
) : Presentation(context, display) {
    private var nativeSurface: SurfaceView? = null
    private var nativeTouchRouter: NativePanelTouchRouter? = null
    private var launchStatusView: TextView? = null
    private val hapticScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        )
        when (game.panelMode) {
            LowerPanelMode.DIAGNOSTICS -> {
                SecondScreen.shutdown()
                setContentView(TextView(context).apply {
                    gravity = Gravity.CENTER
                    setTextColor(Color.rgb(133, 235, 234))
                    setBackgroundColor(Color.rgb(7, 17, 20))
                    text = "${game.title}\n\nCRASH DIAGNOSTICS\nNative companion not enabled"
                    textSize = 22f
                    launchStatusView = this
                })
            }
            LowerPanelMode.NATIVE_WINDOW -> {
                // Native proxies use the UDP gesture bridge, not the managed
                // mod WebSocket. Clear any connection left by a prior managed
                // session and do not start its three-second retry loop.
                SecondScreen.shutdown()
                if (game.id == STARDEW_GAME_ID) {
                    hapticScope.launch {
                        NativePanelInput.haptics.collect { cue ->
                            when (cue) {
                                NativePanelHaptic.Selection,
                                NativePanelHaptic.Action -> Buzz.short(context)
                                NativePanelHaptic.Unlock -> Buzz.long(context)
                            }
                        }
                    }
                } else {
                    hapticScope.launch {
                        SecondScreen.guestHaptics.collect { cue ->
                            when (cue) {
                                GuestHapticCue.Selection,
                                GuestHapticCue.Action -> Buzz.short(context)
                                GuestHapticCue.Unlock -> Buzz.long(context)
                            }
                        }
                    }
                }
                setContentView(nativePanel())
            }
            LowerPanelMode.VALHEIM -> {
                SecondScreen.start()
                setContentView(managedPanel { ValheimScreen(Modifier) })
            }
            LowerPanelMode.STARDEW -> {
                SecondScreen.start()
                setContentView(managedPanel { StardewScreen(Modifier) })
            }
        }
    }

    private fun managedPanel(content: @androidx.compose.runtime.Composable () -> Unit): View =
        ComposeView(context).apply {
            setBackgroundColor(Color.rgb(9, 12, 15))
            setContent { ThorTheme(content = content) }
        }

    private fun nativePanel(): View {
        val root = FrameLayout(context).apply { setBackgroundColor(Color.rgb(7, 9, 11)) }
        val surface = SurfaceView(context).apply {
            setZOrderMediaOverlay(true)
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) = Unit

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                    SecondScreenOutput.requestAttach(holder.surface, width, height)
                }

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    nativeTouchRouter?.cancel()
                    SecondScreenOutput.requestDetach()
                }
            })
        }
        nativeSurface = surface
        root.addView(surface, FrameLayout.LayoutParams(-1, -1))
        val waiting = TextView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(210, 202, 184))
            setBackgroundColor(Color.rgb(8, 11, 13))
            textSize = 19f
            text = "${game.title}\n\nESPERANDO AL JUEGO"
        }
        launchStatusView = waiting
        root.addView(waiting, FrameLayout.LayoutParams(-1, -1))

        val router = NativePanelTouchRouter(
            context = context,
            stardewNative = game.id == STARDEW_GAME_ID,
            rejectRepeatedCompletions = game.id == TRANSFORMERS_WFC_GAME_ID || game.id == DISHONORED_GAME_ID || game.id == "crysis3",
            continuousContacts = game.id == DISHONORED_GAME_ID || game.id == "crysis3",
        )
        nativeTouchRouter = router
        root.setOnTouchListener(router)
        surface.setOnTouchListener(router)
        SecondScreenOutput.setOnStateListener { _, claimed ->
            waiting.post { waiting.visibility = if (claimed) View.GONE else View.VISIBLE }
        }
        return root
    }

    fun updateLaunchStatus(message: String, failure: Boolean = false) {
        launchStatusView?.post {
            launchStatusView?.apply {
                text = message
                setTextColor(if (failure) Color.rgb(255, 138, 128) else Color.rgb(210, 202, 184))
                visibility = View.VISIBLE
                bringToFront()
            }
        }
    }

    override fun dismiss() {
        nativeTouchRouter?.cancel()
        nativeTouchRouter = null
        SecondScreenOutput.setOnStateListener(null)
        SecondScreenOutput.requestDetach()
        nativeSurface = null
        launchStatusView = null
        SecondScreen.shutdown()
        hapticScope.cancel()
        super.dismiss()
    }

    private companion object {
        const val STARDEW_GAME_ID = "stardew-valley"
        const val TRANSFORMERS_WFC_GAME_ID = "transformers-wfc"
        const val DISHONORED_GAME_ID = "dishonored"
    }
}

private class NativePanelTouchRouter(
    context: Context,
    private val stardewNative: Boolean,
    private val rejectRepeatedCompletions: Boolean,
    private val continuousContacts: Boolean,
) : View.OnTouchListener {
    private val completionGate = NativeGestureCompletionGate()
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var pinching = false
    private var pinchGesture = false
    private var contactSent = false
    private var gestureActive = false
    private var width = 1f
    private var height = 1f
    private var pendingPinchLogScale = 0.0
    private var observedPinchLogScale = 0.0
    private var lastPinchPacketTime = 0L
    private var pinchFocusX = 0f
    private var pinchFocusY = 0f
    private var pinchSamples = 0
    private var pinchPackets = 0
    private var pinchQueueWarningLogged = false

    private val gesture = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true
            override fun onLongPress(event: MotionEvent) {
                if (gestureActive && !dragging && !pinching && !pinchGesture) {
                    send("hold", event.x, event.y, 0f)
                }
            }
        },
    )
    private val scale = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                if (continuousContacts && !gestureActive) return false
                pinching = true
                pinchGesture = true
                if (dragging || contactSent) {
                    send("cancel", detector.focusX, detector.focusY, 0f)
                    dragging = false
                    contactSent = false
                }
                pendingPinchLogScale = 0.0
                observedPinchLogScale = 0.0
                lastPinchPacketTime = detector.eventTime
                pinchFocusX = detector.focusX
                pinchFocusY = detector.focusY
                pinchSamples = 0
                pinchPackets = 0
                pinchQueueWarningLogged = false
                Log.i(
                    TAG,
                    "Native guest pinch recognized focus=($pinchFocusX,$pinchFocusY)",
                )
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (continuousContacts && !gestureActive) return false
                val scaleFactor = detector.scaleFactor.toDouble()
                if (!scaleFactor.isFinite() || scaleFactor <= 0.0) return false

                pinchFocusX = detector.focusX
                pinchFocusY = detector.focusY
                val logScale = ln(scaleFactor)
                pendingPinchLogScale += logScale
                observedPinchLogScale += logScale
                pinchSamples++
                sendPendingPinch(detector.eventTime, terminal = false)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (continuousContacts && !gestureActive) {
                    pendingPinchLogScale = 0.0
                    pinching = false
                    return
                }
                pinchFocusX = detector.focusX
                pinchFocusY = detector.focusY
                sendPendingPinch(detector.eventTime, terminal = true)
                Log.i(
                    TAG,
                    "Native guest pinch finished samples=$pinchSamples packets=$pinchPackets " +
                        "observedFactor=${exp(observedPinchLogScale)}",
                )
                pinching = false
            }
        },
    )

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        width = view.width.coerceAtLeast(1).toFloat()
        height = view.height.coerceAtLeast(1).toFloat()
        scale.onTouchEvent(event)
        gesture.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                dragging = false
                pinching = false
                pinchGesture = false
                pendingPinchLogScale = 0.0
                gestureActive = !continuousContacts || !completionGate.isCompleted(event.downTime)
                // Dishonored renders its own pressed state and resolves release
                // against the same semantic target. Send the actual contact,
                // including taps that never cross the drag threshold.
                contactSent = continuousContacts && gestureActive && send("down", event.x, event.y, 0f)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // Even a two-finger contact too small for ScaleGestureDetector's
                // minimum span must not turn into a one-finger tap on release.
                pinchGesture = true
                if (contactSent) {
                    send("cancel", event.x, event.y, 0f)
                    contactSent = false
                }
            }

            MotionEvent.ACTION_MOVE -> if ((!continuousContacts || gestureActive) && !pinching && !pinchGesture) {
                if (!dragging && hypot(event.x - downX, event.y - downY) >= DRAG_THRESHOLD) {
                    dragging = true
                    if (!continuousContacts) send("down", downX, downY, 0f)
                }
                if (contactSent || (dragging && !continuousContacts)) send("move", event.x, event.y, 0f)
            }

            MotionEvent.ACTION_UP -> {
                // SurfaceView and its FrameLayout parent deliberately share
                // this router so the waiting overlay and the live panel both
                // remain touchable. Android normally chooses only one target,
                // but a SurfaceView hand-off during creation/recreation can
                // replay the terminal event through both. Never turn that one
                // physical gesture into two native actions (or into a tap
                // after the first delivery already cleared drag/pinch state).
                if (!rejectRepeatedCompletions ||
                    completionGate.accept(event.downTime, event.eventTime)
                ) {
                    if (continuousContacts) {
                        if (contactSent && !pinchGesture) send("up", event.x, event.y, 0f)
                    } else {
                        if (dragging) send("up", event.x, event.y, 0f)
                        else if (!pinchGesture) send("tap", event.x, event.y, 0f)
                    }
                }
                dragging = false
                pinching = false
                pinchGesture = false
                pendingPinchLogScale = 0.0
                contactSent = false
                gestureActive = false
            }

            MotionEvent.ACTION_CANCEL -> {
                cancel()
            }
        }
        return true
    }

    fun cancel() {
        if (dragging || contactSent || (continuousContacts && gestureActive)) {
            send("cancel", downX, downY, 0f)
        }
        dragging = false
        pinching = false
        pinchGesture = false
        pendingPinchLogScale = 0.0
        contactSent = false
        gestureActive = false
    }

    /**
     * Emits accumulated logarithmic scale while retaining every unsent remainder.
     * MotionEvents commonly contain tiny scale deltas; thresholding each delta
     * independently turns a real pinch into a stream of apparent 1.0 factors.
     */
    private fun sendPendingPinch(eventTime: Long, terminal: Boolean) {
        val magnitude = abs(pendingPinchLogScale)
        val enoughScale = magnitude >= PINCH_PACKET_LOG_THRESHOLD
        val enoughTime = eventTime - lastPinchPacketTime >= PINCH_PACKET_MAX_DELAY_MS &&
            magnitude >= PINCH_TIMED_LOG_THRESHOLD
        if (!terminal && !enoughScale && !enoughTime) return
        if (terminal && magnitude < PINCH_TERMINAL_LOG_EPSILON) return

        do {
            val chunkLogScale = pendingPinchLogScale.coerceIn(
                -PINCH_MAX_PACKET_LOG_SCALE,
                PINCH_MAX_PACKET_LOG_SCALE,
            )
            if (abs(chunkLogScale) < PINCH_TERMINAL_LOG_EPSILON) break
            val factor = exp(chunkLogScale).toFloat()
            if (!factor.isFinite() || factor <= 0f) break

            if (!send("pinch", pinchFocusX, pinchFocusY, factor)) {
                if (!pinchQueueWarningLogged) {
                    pinchQueueWarningLogged = true
                    Log.w(TAG, "Native guest pinch queue rejected a packet; retaining residual")
                }
                return
            }
            pendingPinchLogScale -= chunkLogScale
            pinchPackets++
            lastPinchPacketTime = eventTime
            if (!terminal) break
        } while (abs(pendingPinchLogScale) >= PINCH_TERMINAL_LOG_EPSILON)
    }

    private fun send(kind: String, x: Float, y: Float, value: Float): Boolean {
        val nx = (x / width).coerceIn(0f, 1f)
        val ny = (y / height).coerceIn(0f, 1f)
        val queued = if (stardewNative) {
            NativePanelInput.gesture(kind, nx, ny, value.takeIf { kind == "pinch" })
        } else {
            SecondScreen.sendGuestGesture(kind, nx, ny, value.takeIf { kind == "pinch" })
        }
        // Native mods consume scale factors through the ordered UDP channel.
        // Mirroring every scale packet as an X11 wheel event duplicates zoom
        // and can leak wheel input into the primary gameplay window. X11 does
        // not have our protocol's cancel verb, so release a drag explicitly.
        // Keep Dishonored and Crysis isolated even before window claiming and during
        // transitions. An unclaimed waiting surface must never fall back to
        // an X11 pointer event that can reach the primary game's active grab.
        if (!continuousContacts) when (kind) {
            "pinch" -> Unit
            "cancel" -> SecondScreenOutput.injectTouch("up", nx, ny, value)
            else -> SecondScreenOutput.injectTouch(kind, nx, ny, value)
        }
        return queued
    }

    companion object {
        private const val TAG = "NativePanelTouch"
        private const val DRAG_THRESHOLD = 14f
        private const val PINCH_PACKET_LOG_THRESHOLD = 0.01
        private const val PINCH_TIMED_LOG_THRESHOLD = 0.002
        private const val PINCH_TERMINAL_LOG_EPSILON = 0.000001
        private const val PINCH_PACKET_MAX_DELAY_MS = 50L
        private val PINCH_MAX_PACKET_LOG_SCALE = ln(2.0)
    }
}

/** Rejects only an exact replay of one completed Android gesture. */
internal class NativeGestureCompletionGate {
    private var lastDownTime = Long.MIN_VALUE
    private var lastEventTime = Long.MIN_VALUE

    fun isCompleted(downTime: Long): Boolean = downTime == lastDownTime

    fun accept(downTime: Long, eventTime: Long): Boolean {
        if (downTime == lastDownTime && eventTime == lastEventTime) return false
        lastDownTime = downTime
        lastEventTime = eventTime
        return true
    }
}
