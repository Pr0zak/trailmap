package com.trailmap.update

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.trailmap.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A newer release available on GitHub. */
data class UpdateInfo(val version: String, val apkUrl: String, val notes: String?)

/**
 * In-app self-update via the public GitHub Releases API (no backend). Polls the latest
 * `app-v*` release, compares its version to the installed [BuildConfig.VERSION_NAME], and
 * if newer downloads the APK and launches the system installer (FileProvider + ACTION_VIEW;
 * needs REQUEST_INSTALL_PACKAGES).
 */
object UpdateChecker {
    private const val LATEST_API = "https://api.github.com/repos/Pr0zak/trailmap/releases/latest"

    private val client = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class GhRelease(
        val tag_name: String = "",
        val body: String? = null,
        val assets: List<GhAsset> = emptyList(),
    )

    @Serializable
    private data class GhAsset(val name: String = "", val browser_download_url: String = "")

    fun currentVersion(): String = BuildConfig.VERSION_NAME

    /** Returns an [UpdateInfo] if GitHub's latest release is newer than the installed build. */
    suspend fun check(): UpdateInfo? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(LATEST_API)
                .header("User-Agent", "trailmap-android")
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@use null
                val body = r.body?.string() ?: return@use null
                val rel = json.decodeFromString<GhRelease>(body)
                val remote = rel.tag_name.removePrefix("app-v").removePrefix("v").trim()
                val apk = rel.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                if (remote.isNotBlank() && apk != null && isNewer(remote, currentVersion())) {
                    UpdateInfo(remote, apk.browser_download_url, rel.body)
                } else {
                    null
                }
            }
        }.onFailure { Log.w("trailmap.update", "update check failed: ${it.message}") }.getOrNull()
    }

    /** Semantic version compare: is [remote] strictly greater than [local]? */
    fun isNewer(remote: String, local: String): Boolean {
        fun parts(v: String) = v.trim().split(".").map { it.toIntOrNull() ?: 0 }
        val a = parts(remote)
        val b = parts(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    suspend fun downloadApk(context: Context, url: String): File = withContext(Dispatchers.IO) {
        val out = File(context.externalCacheDir, "trailmap-update.apk")
        val req = Request.Builder().url(url).header("User-Agent", "trailmap-android").build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            out.outputStream().use { os -> r.body!!.byteStream().copyTo(os) }
        }
        Log.i("trailmap.update", "downloaded ${out.length()} bytes")
        out
    }

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
