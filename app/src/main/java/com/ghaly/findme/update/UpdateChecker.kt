package com.ghaly.findme.update

import android.content.Context
import android.content.pm.PackageInfo
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks the project's GitHub release for a newer build than the one
 * installed, and downloads it. This is the one place in the app that talks
 * to the open internet rather than the offline mesh — every call here only
 * runs when the person explicitly asks to check, or once quietly at
 * startup, and failures (no signal, GitHub unreachable, anything) are always
 * silent: nothing about the app's core offline purpose depends on this ever
 * succeeding.
 */
object UpdateChecker {

    private const val REPO = "ghaly4gyw-cyber/find-me"
    private const val RELEASE_API_URL = "https://api.github.com/repos/$REPO/releases/tags/latest"
    private const val APK_ASSET_NAME = "app-debug.apk"
    private const val VERSION_ASSET_NAME = "version.json"

    data class UpdateInfo(
        val versionCode: Long,
        val versionName: String,
        val apkUrl: String
    )

    /** Null on any failure (offline, GitHub unreachable, malformed release) or
     *  if the installed build is already current. Does network I/O — call
     *  off the main thread. Never throws. */
    fun check(context: Context): UpdateInfo? {
        return try {
            val release = fetchJson(RELEASE_API_URL) ?: return null
            val assets = release.optJSONArray("assets") ?: return null

            var versionUrl: String? = null
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                when (asset.optString("name")) {
                    VERSION_ASSET_NAME -> versionUrl = asset.optString("browser_download_url")
                    APK_ASSET_NAME -> apkUrl = asset.optString("browser_download_url")
                }
            }
            if (versionUrl == null || apkUrl == null) return null

            val versionJson = fetchJson(versionUrl) ?: return null
            val remoteCode = versionJson.optLong("versionCode", -1L)
            val remoteName = versionJson.optString("versionName", "")
            if (remoteCode <= 0) return null

            if (remoteCode <= currentVersionCode(context)) return null

            UpdateInfo(remoteCode, remoteName, apkUrl)
        } catch (e: Exception) {
            null
        }
    }

    fun currentVersionCode(context: Context): Long {
        val info: PackageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return PackageInfoCompat.getLongVersionCode(info)
    }

    /** Downloads the APK to this app's own cache dir, overwriting any
     *  previous attempt. Blocking — call off the main thread. Throws on
     *  failure so the caller can tell the person it didn't work.
     *
     *  [onProgress], if given, is called from this same background thread
     *  with a 0-100 percentage as bytes arrive — the caller is responsible
     *  for hopping back to the main thread before touching any UI with it.
     *  When the server doesn't report a Content-Length, there's no way to
     *  know the real percentage; it's called with -1 once instead so the
     *  caller can fall back to an indeterminate spinner rather than a
     *  progress bar stuck at a wrong number. */
    @Throws(IOException::class)
    fun downloadApk(context: Context, apkUrl: String, onProgress: ((Int) -> Unit)? = null): File {
        val outFile = File(context.cacheDir, "update.apk")
        val connection = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        connection.connect()
        val code = connection.responseCode
        if (code !in 200..299) {
            throw IOException("HTTP $code")
        }
        val totalBytes = connection.contentLength
        if (totalBytes <= 0) onProgress?.invoke(-1)
        BufferedInputStream(connection.inputStream).use { input ->
            FileOutputStream(outFile).use { output ->
                if (totalBytes > 0 && onProgress != null) {
                    val buffer = ByteArray(8 * 1024)
                    var readSoFar = 0L
                    var lastReportedPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        readSoFar += read
                        val percent = (readSoFar * 100 / totalBytes).toInt().coerceIn(0, 100)
                        if (percent != lastReportedPercent) {
                            lastReportedPercent = percent
                            onProgress(percent)
                        }
                    }
                } else {
                    input.copyTo(output)
                }
            }
        }
        return outFile
    }

    private fun fetchJson(url: String): JSONObject? {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        connection.connect()
        if (connection.responseCode !in 200..299) return null
        val text = connection.inputStream.bufferedReader().use { it.readText() }
        return JSONObject(text)
    }
}
