package com.funinstaller.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/**
 * The accessibility service: Fun Installer's eyes and hands. It reads the
 * screen, takes screenshots and performs taps, typing, scrolling and keys.
 * It does nothing on its own; the job in [Jobs] drives it.
 */
class AgentService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AgentService? = null
            private set

        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val me = ComponentName(context, AgentService::class.java)
            return enabled.split(':').any {
                it.equals(me.flattenToString(), true) || it.equals(me.flattenToShortString(), true)
            }
        }
    }

    lateinit var overlay: Overlay
        private set

    @Volatile
    private var lastEventAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay = Overlay(this)
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        lastEventAt = SystemClock.uptimeMillis()
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        if (::overlay.isInitialized) overlay.hide()
        Jobs.stop("Accessibility for Fun Installer was turned off.")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // ------------------------------------------------------------------ seeing

    /** Wait until the screen stops changing (or give up after [maxMs]). */
    suspend fun settle(maxMs: Long = 2500) {
        delay(400)
        val start = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - lastEventAt < 350 &&
            SystemClock.uptimeMillis() - start < maxMs
        ) delay(100)
    }

    fun readScreen(): Screen = ScreenReader.read(this)

    /** A JPEG screenshot (Fun Installer's card hidden), base64-encoded, plus its size. */
    suspend fun screenshot(maxDim: Int = 1280): Shot? {
        overlay.setHidden(true)
        delay(150)
        try {
            return suspendCancellableCoroutine { cont ->
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val shot = runCatching {
                            val hb = result.hardwareBuffer
                            val hw = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                            val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                            hb.close()
                            soft?.let { encode(it, maxDim) }
                        }.getOrNull()
                        if (cont.isActive) cont.resume(shot)
                    }

                    override fun onFailure(errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                })
            }
        } finally {
            overlay.setHidden(false)
        }
    }

    class Shot(val base64Jpeg: String, val width: Int, val height: Int)

    private fun encode(src: Bitmap, maxDim: Int): Shot {
        val scale = minOf(1f, maxDim.toFloat() / maxOf(src.width, src.height))
        val bmp = if (scale < 1f) {
            Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
        } else src
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 75, out)
        return Shot(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), bmp.width, bmp.height)
    }

    // ------------------------------------------------------------------ acting

    private suspend fun dispatch(path: Path, durationMs: Long): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
            val started = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }, null)
            if (!started && cont.isActive) cont.resume(false)
        }
    }

    /** Runs [block], hiding Fun Installer's card first if the touch would land on it. */
    private suspend fun <T> clearOfOverlay(x: Int, y: Int, block: suspend () -> T): T {
        val covering = withContext(Dispatchers.Main) { overlay.bounds().contains(x, y) }
        if (covering) {
            overlay.setHidden(true)
            delay(120)
        }
        try {
            return block()
        } finally {
            if (covering) overlay.setHidden(false)
        }
    }

    suspend fun tapAt(x: Int, y: Int, longPress: Boolean = false): Boolean = clearOfOverlay(x, y) {
        val p = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        dispatch(p, if (longPress) 900 else 60)
    }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long = 400): Boolean =
        clearOfOverlay(x1, y1) {
            val p = Path().apply {
                moveTo(x1.toFloat(), y1.toFloat())
                lineTo(x2.toFloat(), y2.toFloat())
            }
            dispatch(p, durationMs)
        }

    suspend fun tapElement(e: UiElement, longPress: Boolean = false): String {
        val size = ScreenReader.displaySize(this)
        if (e.cx in 0 until size.x && e.cy in 0 until size.y && tapAt(e.cx, e.cy, longPress)) {
            return "ok"
        }
        val action = if (longPress) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        var n: AccessibilityNodeInfo? = e.node
        while (n != null) {
            if (n.performAction(action)) return "ok"
            n = n.parent
        }
        return "failed: the tap didn't go through"
    }

    fun typeInto(e: UiElement?, text: String): String {
        val node = e?.node ?: findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return "failed: no text field is selected; tap the field first"
        if (node.isPassword) return "refused: that's a password field, which only the user may fill in"
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) "ok"
        else "failed: that element doesn't accept text"
    }

    fun pressEnter(e: UiElement?): Boolean {
        val node = e?.node ?: findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        return node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
    }

    /** Scroll by swiping inside [e] (or the whole screen) so more content in [direction] appears. */
    suspend fun scroll(direction: String, e: UiElement?): String {
        val size = ScreenReader.displaySize(this)
        val r = e?.bounds ?: android.graphics.Rect(0, 0, size.x, size.y)
        val cx = r.centerX()
        val cy = r.centerY()
        val dx = (r.width() * 0.35).toInt()
        val dy = (r.height() * 0.35).toInt()
        val ok = when (direction) {
            "down" -> swipe(cx, cy + dy, cx, cy - dy)
            "up" -> swipe(cx, cy - dy, cx, cy + dy)
            "right" -> swipe(cx + dx, cy, cx - dx, cy)
            "left" -> swipe(cx - dx, cy, cx + dx, cy)
            else -> return "failed: direction must be up, down, left or right"
        }
        return if (ok) "ok" else "failed: the swipe didn't go through"
    }

    fun pressKey(key: String): String {
        val ok = when (key) {
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "enter" -> pressEnter(null)
            else -> return "failed: unknown key $key"
        }
        return if (ok) "ok" else "failed"
    }

    fun openApp(pkg: String): String {
        if (Safety.isBlocked(pkg)) return "refused: $pkg is a private app Fun Installer must not open"
        if (pkg == packageName) return "refused: that's Fun Installer itself"
        val intent = packageManager.getLaunchIntentForPackage(pkg)
            ?: return "failed: $pkg isn't installed (or has no screen to open)"
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { startActivity(intent); "ok" }.getOrElse { "failed: ${it.message}" }
    }

    fun openLink(url: String): String {
        if (!Safety.linkAllowed(url)) return "refused: only http(s):// and market:// links can be opened"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { startActivity(intent); "ok" }.getOrElse { "failed: ${it.message}" }
    }

    /** Open any screen of ours or of Settings (used for setup steps the user must do). */
    fun launch(intent: Intent): Boolean = runCatching {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    }.getOrDefault(false)
}
