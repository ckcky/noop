package com.noop.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app update installation: download the release APK and hand it straight to Android's package
 * installer. Replaces the old "open the GitHub release page in a browser, find the asset, tap it,
 * find it in Downloads, tap again" detour — for a sideloaded app that is four chances to install the
 * WRONG file (a stable APK over a preview install, or a stale version still sitting in Downloads).
 *
 * Nothing is automatic: the user taps "Download & install", Android then shows its own install
 * confirmation, and the APK's signature must match the installed app or Android refuses it. We never
 * poll, never install in the background, and only ever fetch the asset URL that [UpdateCheck.pickApk]
 * resolved for THIS channel.
 *
 * Why the plain `HttpURLConnection` + [Intent.ACTION_VIEW] path rather than `DownloadManager` or the
 * `PackageInstaller` session API:
 *   - `DownloadManager` writes to shared Downloads (a public folder, needs its own notification
 *     plumbing) and gives us no clean way to hand the file straight on. The app-private cache keeps
 *     the APK invisible to everything else and self-cleaning.
 *   - the `PackageInstaller` session API needs a status receiver + its own UI; ACTION_VIEW on an
 *     `application/vnd.android.package-archive` URI is the sideload-standard route, works unchanged
 *     from API 26 through 34, and shows the system installer the user already expects.
 */
object UpdateInstaller {

    /** Where downloaded APKs land — must match the `updates` cache-path in `res/xml/file_paths.xml`. */
    private const val CACHE_DIR = "updates"

    /** [Progress.Failed.reason] sentinel meaning "the OS grant is missing", not "something broke" —
     *  the UI turns it into a link to the exact settings toggle rather than an error. */
    const val NEEDS_PERMISSION = "NEEDS_UNKNOWN_SOURCES"

    /** Reported back to the UI while a download runs. */
    sealed interface Progress {
        /** [percent] is -1 when the server sends no Content-Length (indeterminate). */
        data class Downloading(val percent: Int) : Progress

        /** The APK is on disk; the system installer has been launched. */
        object Handoff : Progress

        data class Failed(val reason: String) : Progress
    }

    /**
     * True when the OS will let us hand it an APK at all. From Android 8 "install unknown apps" is a
     * PER-APP grant, so a fresh install has it off and the install intent would silently bounce —
     * [unknownSourcesIntent] takes the user to the exact toggle.
     */
    fun canInstallPackages(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    /** Settings screen for THIS app's "install unknown apps" permission. */
    fun unknownSourcesIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${context.packageName}"))

    /**
     * Download [url] into the app's private cache and launch the system installer on it.
     *
     * Runs on IO; [onProgress] is invoked from that thread, so a Compose caller should hop back to the
     * main dispatcher (or use a snapshot state write, which is thread-safe) before touching UI state.
     * Never throws — every failure resolves to [Progress.Failed] with a short, user-showable reason.
     *
     * @param fileName the asset's own name (e.g. `Choop-Preview-v8.2.30.1234.apk`), used verbatim on
     *   disk so the installer's confirmation shows a name the user recognises from the release.
     */
    suspend fun downloadAndInstall(
        context: Context,
        url: String,
        fileName: String,
        onProgress: (Progress) -> Unit,
    ): Progress = withContext(Dispatchers.IO) {
        val result = runCatching {
            // Ask BEFORE spending 20 MB of the user's data: without this grant the install intent
            // would bounce at the very end and the whole download would have been wasted. The caller
            // turns this reason into a "grant permission, then tap Update again" prompt.
            if (!canInstallPackages(context)) return@runCatching Progress.Failed(NEEDS_PERMISSION)

            val dir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
            // Drop anything from an earlier check so the cache can't accumulate 20 MB APKs.
            dir.listFiles()?.forEach { it.delete() }

            val target = File(dir, sanitize(fileName))
            val downloaded = download(url, target, onProgress)
            if (!downloaded) return@runCatching Progress.Failed("Download failed")

            launchInstaller(context, target)
            Progress.Handoff
        }.getOrElse { Progress.Failed(it.message ?: "Download failed") }
        onProgress(result)
        result
    }

    /** Stream [url] to [target], reporting percentage as it goes. Returns false on a non-200. */
    private fun download(url: String, target: File, onProgress: (Progress) -> Unit): Boolean {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true   // release assets 302 to the CDN
        }
        try {
            if (conn.responseCode != 200) return false
            val total = conn.contentLengthLong
            var read = 0L
            var lastPct = -1
            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        read += n
                        val pct = if (total > 0) ((read * 100) / total).toInt() else -1
                        // Only emit on a real change, so a 20 MB download posts ~100 updates, not 300.
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress(Progress.Downloading(pct))
                        }
                    }
                }
            }
            return target.length() > 0
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Hand [apk] to the system installer. The URI must come from our [FileProvider] (a raw `file://`
     * URI throws FileUriExposedException since Android 7), and the read grant must ride along on the
     * intent or the installer process can't open it.
     */
    private fun launchInstaller(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // Launched from a non-Activity context in some call paths, so declare a new task.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** Keep the asset's name but strip any path separators a crafted release name could smuggle in. */
    private fun sanitize(name: String): String =
        name.substringAfterLast('/').substringAfterLast('\\')
            .ifEmpty { "update.apk" }
}
