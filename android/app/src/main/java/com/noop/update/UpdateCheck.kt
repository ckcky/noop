package com.noop.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * User-initiated "Check for updates": a single call to the project's PUBLIC releases API (GitHub) that reads the
 * latest version and compares it to the installed one. It runs ONLY when the user taps the button —
 * there is no background polling and no auto-update. Nothing about the user is sent; it just reads a
 * version number. (Android already holds INTERNET for the opt-in AI Coach, so this adds no new
 * capability.)
 */
object UpdateCheck {

    // This fork's releases. The original NoopApp/noop repo is gone, so the check reads THIS repo's
    // GitHub Releases. The Android Release APK workflow attaches the versioned Choop APK to a
    // Release on every cut, so tapping through to the release page lands on a downloadable APK.
    //
    // Two channels (BuildConfig.CHANNEL, see the `preview` product flavor):
    //   stable  → /releases/latest — GitHub EXCLUDES pre-releases here, so the stable app is never
    //             offered a preview build.
    //   preview → /releases?per_page=… — the full list INCLUDING pre-releases; the newest
    //             non-draft version wins, so "Choop Preview" updates onto the next preview cut.
    private const val LATEST_ENDPOINT = "https://api.github.com/repos/kimchaily/noop/releases/latest"
    private const val LIST_ENDPOINT = "https://api.github.com/repos/kimchaily/noop/releases?per_page=20"

    sealed interface Result {
        data class UpToDate(val version: String) : Result

        /**
         * A newer build for THIS channel exists.
         *
         * @property url the Release page — the human fallback (and what we open if there is no asset).
         * @property apkUrl direct download for the channel-correct APK asset, when the release carries
         *   one. Non-null is what lets [UpdateInstaller] update in place without a browser detour;
         *   null means the release had no matching asset and we fall back to opening [url].
         */
        data class Available(
            val version: String,
            val url: String,
            val notes: String,
            val apkUrl: String? = null,
            val apkName: String? = null,
        ) : Result

        object Failed : Result
    }

    /** One downloadable file attached to a GitHub release. */
    data class Asset(val name: String, val url: String, val size: Long)

    /**
     * Pick the APK that belongs to THIS channel — the second half of the channel-isolation guarantee.
     * [newestPreviewRelease] already makes sure a preview install only ever looks at pre-releases; this
     * makes sure that, once we auto-download, we hand the installer the right FILE. A release can carry
     * several APKs (stable, preview, demo), and installing the wrong one is not a no-op: a stable APK
     * has a different applicationId, so it would install a SECOND app rather than update this one.
     *
     * Naming contract with the release workflow's "Stage APKs" step:
     *   stable  → `Choop-v<version>.apk`
     *   preview → `Choop-Preview-v<version>.apk`
     *   demo    → `Choop-v<version>-demo.apk`   (never offered as an update)
     * So "Preview" in the filename is the channel marker, and we require an exact match on it in BOTH
     * directions — a preview install takes only Preview-named APKs, a stable install only takes the
     * ones without it. Anything unrecognised yields null and the UI falls back to the release page.
     */
    internal fun pickApk(assets: List<Asset>, preview: Boolean): Asset? =
        assets.firstOrNull { a ->
            a.name.endsWith(".apk", ignoreCase = true) &&
                !a.name.contains("-demo", ignoreCase = true) &&
                a.name.contains("Preview", ignoreCase = true) == preview
        }

    /** Fetch the latest release for the channel and classify it against [currentVersion]. Pass
     *  [includePrereleases] = true on the preview channel (`BuildConfig.CHANNEL == "preview"`).
     *  Never throws — any error (offline, rate-limited, malformed) resolves to [Result.Failed] so
     *  the caller shows a calm "try again" rather than crashing. */
    suspend fun check(currentVersion: String, includePrereleases: Boolean = false): Result =
        withContext(Dispatchers.IO) {
            runCatching {
                if (includePrereleases) checkList(currentVersion) else checkLatest(currentVersion)
            }.getOrDefault(Result.Failed)
        }

    /** Stable channel: GitHub's `/releases/latest` (pre-releases are excluded by GitHub itself). */
    private fun checkLatest(currentVersion: String): Result {
        val body = fetch(LATEST_ENDPOINT) ?: return Result.Failed
        val rel = parseRelease(JSONObject(body)) ?: return Result.Failed
        val latest = rel.version()
        if (!isNewer(latest, currentVersion)) return Result.UpToDate(latest)
        val apk = pickApk(rel.assets, preview = false)
        return Result.Available(latest, rel.url, rel.notes, apk?.url, apk?.name)
    }

    /** One GitHub release as the list endpoint reports it, reduced to what the preview selector needs. */
    data class ReleaseInfo(
        val tag: String,
        val prerelease: Boolean,
        val draft: Boolean,
        val url: String,
        val notes: String,
        val assets: List<Asset> = emptyList(),
    ) {
        /** The numeric version the tag carries, "v" stripped (e.g. "v8.3.0-pre" → "8.3.0-pre"). */
        fun version(): String = tag.removePrefix("v")
    }

    /**
     * Preview channel: pick the newest PRE-RELEASE from [releases]. This is the safety-critical
     * filter — a preview install must NEVER be offered a STABLE release. Stable Choop and Choop
     * Preview are different apps (different applicationId), so a stable APK can't even update the
     * preview app; worse, tapping through would walk the user into installing/updating the *stable*
     * app by mistake. So we drop every release that is not marked `prerelease` (and every draft),
     * and only then take the newest by the same numeric [isNewer] compare the stable path uses.
     * Pure + unit-tested (UpdateCheckTest) — the network layer just feeds it parsed [ReleaseInfo]s.
     */
    internal fun newestPreviewRelease(releases: List<ReleaseInfo>): ReleaseInfo? {
        var best: ReleaseInfo? = null
        for (r in releases) {
            if (!r.prerelease || r.draft || r.version().isEmpty()) continue
            if (best == null || isNewer(r.version(), best.version())) best = r
        }
        return best
    }

    /** Preview channel: the release LIST (which includes stable + pre-releases); [newestPreviewRelease]
     *  keeps ONLY pre-releases, so a newer stable can never leak into the preview app's update check. */
    private fun checkList(currentVersion: String): Result {
        val body = fetch(LIST_ENDPOINT) ?: return Result.Failed
        val arr = JSONArray(body)
        val releases = ArrayList<ReleaseInfo>(arr.length())
        for (i in 0 until arr.length()) {
            releases += parseRelease(arr.optJSONObject(i) ?: continue) ?: continue
        }
        val found = newestPreviewRelease(releases) ?: return Result.UpToDate(currentVersion)
        if (!isNewer(found.version(), currentVersion)) return Result.UpToDate(found.version())
        val apk = pickApk(found.assets, preview = true)
        return Result.Available(found.version(), found.url, found.notes, apk?.url, apk?.name)
    }

    /** One release JSON object → [ReleaseInfo], assets included. Null when it carries no usable tag. */
    private fun parseRelease(o: JSONObject): ReleaseInfo? {
        val tag = o.optString("tag_name", "")
        if (tag.isEmpty()) return null
        val assetsArr = o.optJSONArray("assets")
        val assets = ArrayList<Asset>(assetsArr?.length() ?: 0)
        for (i in 0 until (assetsArr?.length() ?: 0)) {
            val a = assetsArr?.optJSONObject(i) ?: continue
            val name = a.optString("name", "")
            // browser_download_url is the unauthenticated direct link — no API token, no redirect dance.
            val url = a.optString("browser_download_url", "")
            if (name.isNotEmpty() && url.isNotEmpty()) assets += Asset(name, url, a.optLong("size", 0L))
        }
        return ReleaseInfo(
            tag = tag,
            prerelease = o.optBoolean("prerelease", false),
            draft = o.optBoolean("draft", false),
            url = o.optString("html_url", ""),
            notes = cleanNotes(o.optString("body", "")),
            assets = assets,
        )
    }

    /** One GET against the GitHub API; null on any non-200 / transport problem. */
    private fun fetch(endpoint: String): String? {
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        return try {
            if (conn.responseCode != 200) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * True iff [latest] is a strictly newer version than [current]. Compares dot-separated numeric
     * segments left to right — so `1.40 > 1.39` and `1.9 < 1.10`, both of which a plain string compare
     * gets WRONG. Tolerant of a leading "v" and any non-numeric suffix (e.g. the demo flavour's
     * "1.39-demo", or build metadata). Pure + unit-tested.
     */
    fun isNewer(latest: String, current: String): Boolean {
        val a = segments(latest)
        val b = segments(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun segments(s: String): List<Int> =
        s.trim().removePrefix("v").removePrefix("V")
            .takeWhile { it.isDigit() || it == '.' }   // stop at "-demo" / build metadata
            .split(".")
            .mapNotNull { it.toIntOrNull() }

    /** Turn a GitHub release body into a short, readable "what's new" for an inline preview: drop the
     *  "Downloads"/footer boilerplate, strip the heaviest markdown markers, and cap the length. */
    fun cleanNotes(body: String): String {
        var s = body.substringBefore("Downloads")
        for (marker in listOf("**", "## ", "# ")) s = s.replace(marker, "")
        s = s.trim()
        // The update card scrolls its notes, so show the whole release rather than a teaser — these
        // are now generated from the PR and are the user's only in-app description of what they are
        // about to install. The cap is just a guard against a pathological body.
        return if (s.length > MAX_NOTES) s.take(MAX_NOTES).trim() + "…" else s
    }

    private const val MAX_NOTES = 2500
}
