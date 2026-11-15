package com.funinstaller.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Installs APKs with Android's PackageInstaller. Android shows its own
 * "Do you want to install this app?" screen and the user decides.
 */
object Installer {
    private const val ACTION = "com.funinstaller.app.INSTALL_RESULT"
    private val waiting = ConcurrentHashMap<Int, CompletableDeferred<String>>()

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Returns "ok: …" or a reason it failed. */
    suspend fun install(context: Context, apks: List<File>): String {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        val result = CompletableDeferred<String>()
        waiting[sessionId] = result
        try {
            installer.openSession(sessionId).use { session ->
                for (apk in apks) {
                    session.openWrite(apk.name, 0, apk.length()).use { out ->
                        apk.inputStream().use { it.copyTo(out) }
                        session.fsync(out)
                    }
                }
                val intent = Intent(context, InstallReceiver::class.java).setAction(ACTION)
                val pending = PendingIntent.getBroadcast(
                    context, sessionId, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(pending.intentSender)
            }
            return withTimeout(15 * 60 * 1000L) { result.await() }
        } catch (t: Throwable) {
            runCatching { installer.abandonSession(sessionId) }
            if (t is kotlinx.coroutines.TimeoutCancellationException) {
                return "failed: nobody confirmed the install within 15 minutes"
            }
            throw t
        } finally {
            waiting.remove(sessionId)
        }
    }

    internal fun onResult(context: Context, intent: Intent) {
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(confirm) }
                    .onFailure { waiting[sessionId]?.complete("failed: couldn't show the install screen") }
            }
            return
        }
        val pkg = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME) ?: ""
        val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
        val message = when (status) {
            PackageInstaller.STATUS_SUCCESS -> "ok: installed $pkg"
            PackageInstaller.STATUS_FAILURE_ABORTED -> "failed: the user cancelled the install"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "failed: Android blocked the install ($detail)"
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                "failed: a version of this app signed by someone else is already installed. Replacing it means uninstalling it first, which deletes its data; ask the user ($detail)"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                "failed: this APK doesn't work on this tablet (wrong CPU type or Android version) ($detail)"
            PackageInstaller.STATUS_FAILURE_INVALID -> "failed: the file isn't a valid APK ($detail)"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "failed: not enough storage space"
            else -> "failed: $detail"
        }
        waiting[sessionId]?.complete(message)
    }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Installer.onResult(context, intent)
}
