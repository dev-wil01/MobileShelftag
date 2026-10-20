package com.waltermart.mobileshelftag

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** What the latest GitHub Release offers. */
data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val notes: String,
    val apkUrl: String,
    val sizeBytes: Long,
    val sha256: String
)

/** Pure rules, unit-tested. */
object UpdateRules {
    /** "v1.0.12" -> 12. The release workflow makes the tag end with the build number, which is also the app's versionCode. */
    fun versionCodeFromTag(tag: String): Int? =
        Regex("""(\d+)\s*$""").find(tag.trim())?.groupValues?.get(1)?.toIntOrNull()

    fun isNewer(latestCode: Int, installedCode: Int) = latestCode > installedCode

    /** The APK may only come from the repository's own GitHub release downloads. */
    fun isTrustedDownloadUrl(url: String, repo: String): Boolean =
        repo.isNotBlank() && url.startsWith("https://github.com/$repo/releases/download/", ignoreCase = true)

    /** "sha256:ABC..." or "ABC..." -> lowercase hex, or "" when absent. */
    fun cleanDigest(digest: String?): String =
        digest.orEmpty().trim().removePrefix("sha256:").trim().lowercase()
}

/**
 * Update check against the PUBLIC GitHub repository's latest Release (no login, no token).
 * [repo] ("owner/name") is filled in at build time by the workflow; it is empty in local builds,
 * and then the update feature is switched off.
 *
 * Android itself refuses an update that is not signed with the same key as the installed app,
 * so a tampered file cannot replace the app.
 */
class UpdateService(
    private val context: Context,
    private val repo: String,
    private val installedCode: Int
) {
    val enabled: Boolean get() = repo.isNotBlank()

    private val folder get() = File(context.cacheDir, "updates")

    /** Returns the newest release when it is newer than the installed app, otherwise null. */
    fun check(): UpdateInfo? {
        if (!enabled) throw ApiException("Update checking is not set up in this build.")

        val c = URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 15000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "MobileShelfTag")

        var code = 0
        var body = ""
        try {
            code = c.responseCode
            body = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        } catch (e: Exception) {
            throw ApiException("Could not reach GitHub (${e.javaClass.simpleName}). Check the Wi-Fi/internet and try again.")
        } finally {
            c.disconnect()
        }

        when {
            code == 404 -> throw ApiException("No update has been published yet.")
            code == 403 || code == 429 -> throw ApiException("GitHub is limiting requests from this network right now. Try again in a while.")
            code !in 200..299 -> throw ApiException("GitHub answered with error $code.")
        }

        val json = JSONObject(body)
        val tag = json.optString("tag_name")
        val latestCode = UpdateRules.versionCodeFromTag(tag)
            ?: throw ApiException("The latest release has an unexpected version name ($tag).")

        if (!UpdateRules.isNewer(latestCode, installedCode)) return null

        val assets = json.optJSONArray("assets")
        var apk: JSONObject? = null
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk", ignoreCase = true)) { apk = a; break }
            }
        }
        if (apk == null) throw ApiException("The latest release has no APK file attached.")

        val url = apk.optString("browser_download_url")
        if (!UpdateRules.isTrustedDownloadUrl(url, repo)) throw ApiException("The update file is not from this repository, so it was refused.")

        return UpdateInfo(
            versionName = tag.trim().removePrefix("v"),
            versionCode = latestCode,
            notes = json.optString("body").trim().take(600),
            apkUrl = url,
            sizeBytes = apk.optLong("size", 0L),
            sha256 = UpdateRules.cleanDigest(apk.optString("digest", ""))
        )
    }

    /** Downloads the APK into the app's private cache folder and checks size and SHA-256 (when GitHub gives one). */
    fun download(info: UpdateInfo, progress: (done: Long, total: Long) -> Unit): File {
        folder.deleteRecursively()
        folder.mkdirs()
        val target = File(folder, "MobileShelfTag-update.apk")

        val c = URL(info.apkUrl).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 30000
        c.setRequestProperty("User-Agent", "MobileShelfTag")
        try {
            if (c.responseCode !in 200..299) throw ApiException("The download failed (error ${c.responseCode}).")
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            c.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        received += n
                        progress(received, info.sizeBytes)
                    }
                }
            }
            if (info.sizeBytes > 0 && received != info.sizeBytes) {
                target.delete()
                throw ApiException("The download was incomplete. Try again.")
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (info.sha256.isNotEmpty() && !actual.equals(info.sha256, ignoreCase = true)) {
                target.delete()
                throw ApiException("The downloaded file did not match its checksum, so it was not installed.")
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            target.delete()
            throw ApiException("The download failed (${e.javaClass.simpleName}). Check the Wi-Fi/internet and try again.")
        } finally {
            c.disconnect()
        }
        return target
    }

    /**
     * Hands the APK to Android's installer (it always asks the user to confirm).
     * Returns false when this app is not yet allowed to install apps: the system screen for that
     * permission is opened instead, and the user has to try again afterwards.
     */
    fun install(apk: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        val uri = FileProvider.getUriForFile(context, context.packageName + ".updates", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }

    /** Removes a leftover download (called when the app starts, i.e. normally after an update). */
    fun cleanUp() {
        folder.deleteRecursively()
    }
}
