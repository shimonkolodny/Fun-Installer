package com.funinstaller.pairing

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fun Installer Pairing: lets you type the Wireless debugging pairing code into a
 * notification. Pulling down the notification shade doesn't close Settings, so the
 * code stays valid (switching apps would cancel it). The code is passed to
 * `phone pair --wait` in Termux on this tablet, which does the actual pairing.
 */

/** Port `phone pair --wait` listens on (127.0.0.1 only). */
const val TERMUX_PORT = 47123

object PairNotify {
    private const val CHANNEL = "pairing"
    private const val ID = 1
    const val KEY = "code"

    private fun manager(ctx: Context): NotificationManager =
        ctx.getSystemService(NotificationManager::class.java)!!

    private fun base(ctx: Context, text: String): Notification.Builder {
        manager(ctx).createNotificationChannel(
            NotificationChannel(CHANNEL, "Pairing code", NotificationManager.IMPORTANCE_HIGH),
        )
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Fun Installer pairing")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
    }

    /** A notification with an "Enter pairing code" reply field. */
    fun ask(ctx: Context, text: String) {
        val reply = PendingIntent.getBroadcast(
            ctx, 1, Intent(ctx, ReplyReceiver::class.java),
            // Must be mutable: Android adds the typed text to it.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val input = RemoteInput.Builder(KEY).setLabel("6-digit pairing code").build()
        val action = Notification.Action.Builder(
            Icon.createWithResource(ctx, android.R.drawable.ic_menu_edit), "Enter pairing code", reply,
        ).addRemoteInput(input).build()
        manager(ctx).notify(ID, base(ctx, text).addAction(action).setOngoing(true).build())
    }

    /** A plain status notification (no reply field). */
    fun info(ctx: Context, text: String, done: Boolean) {
        manager(ctx).notify(ID, base(ctx, text).setOngoing(!done).setAutoCancel(done).build())
    }
}

class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val typed = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(PairNotify.KEY)?.toString().orEmpty()
        val code = typed.filter { it.isDigit() }
        if (code.length != 6) {
            PairNotify.ask(context, "That wasn't 6 digits. Type the code shown under \"Pair device with pairing code\".")
            return
        }
        PairNotify.info(context, "Pairing with $code… this takes a few seconds.", done = false)
        val app = context.applicationContext
        val pending = goAsync()
        val finished = AtomicBoolean(false)
        val finishOnce = { if (finished.compareAndSet(false, true)) pending.finish() }
        Handler(Looper.getMainLooper()).postDelayed({ finishOnce() }, 9000)
        Thread {
            val answer = try {
                Socket().use { s ->
                    s.connect(InetSocketAddress("127.0.0.1", TERMUX_PORT), 3000)
                    s.soTimeout = 120_000
                    s.getOutputStream().apply { write("$code\n".toByteArray()); flush() }
                    s.getInputStream().bufferedReader().readLine() ?: "ERR Termux didn't answer."
                }
            } catch (e: Exception) {
                "ERR Termux isn't waiting for a code. In Termux, run the setup command again " +
                    "(or: phone pair --wait), then send the code again."
            }
            if (answer.startsWith("OK")) {
                PairNotify.info(app, "Paired! Go back to Termux.", done = true)
            } else {
                PairNotify.ask(app, answer.removePrefix("ERR").trim())
            }
            finishOnce()
        }.start()
    }
}

class MainActivity : Activity() {
    private val steps = """
        1. In Termux, keep the setup waiting for the pairing code.

        2. Tap "Open Developer options" below. Turn on Wireless debugging (allow it for this Wi-Fi), then tap "Pair device with pairing code".

        3. Without leaving Settings, pull down the notification shade. On the "Fun Installer pairing" notification, tap "Enter pairing code", type the 6-digit code and send it.

        4. When the notification says Paired, go back to Termux.

        Why the notification? Android cancels the pairing code as soon as Settings leaves the screen, but pulling down the notification shade doesn't count as leaving.
    """.trimIndent()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        column.addView(TextView(this).apply {
            text = "Fun Installer Pairing"
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
        })
        column.addView(TextView(this).apply {
            text = steps
            textSize = 16f
            setPadding(0, pad / 2, 0, pad / 2)
        })
        column.addView(Button(this).apply {
            text = "Open Developer options"
            isAllCaps = false
            setOnClickListener {
                showNotification()
                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            }
        })
        column.addView(Button(this).apply {
            text = "Show the notification again"
            isAllCaps = false
            setOnClickListener { showNotification() }
        })
        column.addView(TextView(this).apply {
            text = "No notification? Allow notifications for Fun Installer Pairing in Settings > Apps."
            textSize = 13f
            alpha = 0.7f
            setPadding(0, pad / 2, 0, 0)
        })
        setContentView(ScrollView(this).apply { addView(column) })

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        } else {
            showNotification()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        showNotification()
    }

    private fun showNotification() =
        PairNotify.ask(this, "Type the 6-digit code from \"Pair device with pairing code\" here.")
}
