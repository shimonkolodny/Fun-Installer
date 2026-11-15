package com.funinstaller.app

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/** A file this job downloaded, referred to by the AI as f1, f2, … */
class JobFile(val id: String, val file: File, val type: String, val sha256: String) {
    var installed = false
    fun describe() = "$id: ${file.name} (${type}, ${Files.human(file.length())})" + if (installed) " [installed]" else ""
}

object Files {
    private const val MAX_BYTES = 4L * 1024 * 1024 * 1024

    fun human(bytes: Long): String = when {
        bytes >= 1L shl 30 -> String.format("%.1f GB", bytes / (1L shl 30).toDouble())
        bytes >= 1L shl 20 -> String.format("%.1f MB", bytes / (1L shl 20).toDouble())
        bytes >= 1L shl 10 -> String.format("%.0f KB", bytes / (1L shl 10).toDouble())
        else -> "$bytes B"
    }

    private fun safeName(name: String, url: String): String {
        val fromUrl = url.substringBefore('?').substringAfterLast('/')
        val base = name.ifBlank { fromUrl }.ifBlank { "download" }
        return base.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(120)
    }

    /** Download [url] into the app's private storage. Follows redirects; reports progress. */
    fun download(context: Context, url: String, fileName: String, progress: (String) -> Unit): File {
        val dir = File(context.cacheDir, "downloads").apply { mkdirs() }
        val dest = File(dir, safeName(fileName, url))
        var current = URL(url)
        var conn: HttpURLConnection
        var redirects = 0
        while (true) {
            conn = (current.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "FunInstaller/1.0 (Android)")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location") ?: error("Redirect without a location")
                conn.disconnect()
                current = URL(current, location)
                if (current.protocol != "https" && current.protocol != "http") error("Bad redirect")
                if (++redirects > 10) error("Too many redirects")
                continue
            }
            if (code !in 200..299) {
                conn.disconnect()
                error("The server answered $code")
            }
            break
        }
        val total = conn.contentLengthLong
        if (total > MAX_BYTES) error("The file is too big (${human(total)})")
        var done = 0L
        var lastReport = 0L
        conn.inputStream.use { input ->
            dest.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (done > MAX_BYTES) error("The file is too big")
                    if (done - lastReport > 2_000_000) {
                        lastReport = done
                        progress(if (total > 0) "${human(done)} of ${human(total)}" else human(done))
                    }
                }
            }
        }
        conn.disconnect()
        return dest
    }

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** What kind of file this really is (not just what its name says). */
    fun sniff(file: File): String {
        val head = ByteArray(512)
        val n = FileInputStream(file).use { it.read(head) }.coerceAtLeast(0)
        val h = head.copyOf(n)
        fun starts(vararg b: Int) = h.size >= b.size && b.indices.all { h[it] == b[it].toByte() }
        if (starts(0x50, 0x4B)) {
            val names = runCatching { ZipFile(file).use { z -> z.entries().toList().map { it.name } } }
                .getOrElse { return "damaged zip (download may be incomplete)" }
            return when {
                "AndroidManifest.xml" in names -> "APK"
                names.any { it.endsWith(".apk", true) } -> "APK bundle"
                else -> "zip archive, ${names.size} files"
            }
        }
        if (starts(0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C)) return "7z archive (can't be extracted here)"
        if (starts(0x52, 0x61, 0x72, 0x21)) return "RAR archive (can't be extracted here)"
        val text = String(h, Charsets.UTF_8).trimStart('﻿', ' ', '\n', '\r', '\t').lowercase()
        if (text.startsWith("<!doctype") || text.startsWith("<html") || text.startsWith("<head")) {
            return "WEB PAGE, not a file (the link points to a page, not the download)"
        }
        return "file"
    }

    /** The APKs to install from an .apk or an APK bundle (.apks/.xapk/.apkm/.zip). */
    fun apksIn(context: Context, file: File): List<File> {
        val names = runCatching { ZipFile(file).use { z -> z.entries().toList().map { it.name } } }
            .getOrElse { throw IllegalStateException("${file.name} isn't a valid APK") }
        if ("AndroidManifest.xml" in names) return listOf(file)
        val outDir = File(context.cacheDir, "bundle-" + file.nameWithoutExtension).apply {
            deleteRecursively(); mkdirs()
        }
        val apks = mutableListOf<File>()
        ZipFile(file).use { z ->
            for (entry in z.entries()) {
                if (entry.isDirectory || !entry.name.endsWith(".apk", true)) continue
                val out = File(outDir, entry.name.substringAfterLast('/'))
                z.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
                apks += out
            }
        }
        if (apks.isEmpty()) throw IllegalStateException("No APK found inside ${file.name}")
        return apks
    }

    private fun saveToPublic(context: Context, input: InputStream, relDir: String, name: String) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.RELATIVE_PATH, relDir)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Couldn't create $relDir$name")
        resolver.openOutputStream(uri)?.use { input.copyTo(it) } ?: error("Couldn't write $name")
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }

    /** Copy a file into /sdcard/Download/FunInstaller/. */
    fun saveToDownloads(context: Context, file: File): String {
        FileInputStream(file).use { saveToPublic(context, it, "Download/FunInstaller/", file.name) }
        return "/sdcard/Download/FunInstaller/${file.name}"
    }

    /** Extract a zip into /sdcard/Download/FunInstaller/<zip name>/ and describe what's inside. */
    fun unpack(context: Context, file: File, progress: (String) -> Unit): String {
        val folder = file.nameWithoutExtension.replace(Regex("[^A-Za-z0-9._ -]"), "_")
        val listing = mutableListOf<String>()
        var count = 0
        ZipInputStream(FileInputStream(file)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                // Ignore unsafe paths ("..", absolute) so nothing lands outside the folder.
                val parts = entry.name.replace('\\', '/').split('/')
                    .filter { it.isNotEmpty() && it != "." && it != ".." }
                if (parts.isEmpty()) continue
                val sub = parts.dropLast(1).joinToString("/")
                val rel = "Download/FunInstaller/$folder/" + if (sub.isEmpty()) "" else "$sub/"
                saveToPublic(context, zip, rel, parts.last())
                count++
                if (listing.size < 40) listing += parts.joinToString("/")
                if (count % 50 == 0) progress("$count files extracted")
            }
        }
        return buildString {
            append("Extracted $count files to /sdcard/Download/FunInstaller/$folder/")
            if (listing.isNotEmpty()) append(":\n  ").append(listing.joinToString("\n  "))
            if (count > listing.size) append("\n  … and ${count - listing.size} more")
        }
    }
}
