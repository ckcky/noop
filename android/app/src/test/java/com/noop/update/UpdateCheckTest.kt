package com.noop.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the version comparison that drives "Check for updates". The headline case is the string-compare
 * trap: "1.40" must be NEWER than "1.39" (lexicographically it isn't), and "1.9" must be OLDER than
 * "1.10". Also covers the demo flavour's "-demo" suffix and a leading "v".
 */
class UpdateCheckTest {

    @Test
    fun newer() {
        assertTrue(UpdateCheck.isNewer("1.40", "1.39"))   // the trap: "1.40" < "1.39" as strings
        assertTrue(UpdateCheck.isNewer("1.10", "1.9"))    // and "1.10" < "1.9" as strings
        assertTrue(UpdateCheck.isNewer("2.0", "1.39"))
        assertTrue(UpdateCheck.isNewer("1.39.1", "1.39")) // extra patch segment
        assertTrue(UpdateCheck.isNewer("v1.40", "1.39"))  // tolerant of the tag's "v"
    }

    @Test
    fun notNewer() {
        assertFalse(UpdateCheck.isNewer("1.39", "1.39"))      // equal
        assertFalse(UpdateCheck.isNewer("1.38", "1.39"))      // older
        assertFalse(UpdateCheck.isNewer("1.9", "1.10"))
        assertFalse(UpdateCheck.isNewer("1.39-demo", "1.39")) // demo flavour vs the same release
        assertFalse(UpdateCheck.isNewer("garbage", "1.39"))   // unparseable → not newer (no false alarm)
    }

    // ── Preview-channel selection: the safety-critical filter ───────────────────────────────────

    private fun rel(tag: String, prerelease: Boolean, draft: Boolean = false) =
        UpdateCheck.ReleaseInfo(tag = tag, prerelease = prerelease, draft = draft, url = "u/$tag", notes = "")

    @Test
    fun previewNeverPicksAStableRelease() {
        // The dangerous case that shipped: a NEWER stable release sits in the list next to preview
        // pre-releases. The preview app must pick the newest PRE-RELEASE and ignore the stable one
        // entirely — offering a stable APK to a preview install is a wrong-app cross-channel update.
        val releases = listOf(
            rel("v8.2.9", prerelease = false),      // newest overall, but STABLE → must be ignored
            rel("v8.2.8-pre", prerelease = true),   // newest PRE-RELEASE → the correct pick
            rel("v8.2.7-pre", prerelease = true),
        )
        val picked = UpdateCheck.newestPreviewRelease(releases)
        assertEquals("8.2.8-pre", picked?.version())
    }

    @Test
    fun previewIgnoresDraftsAndPicksNewestPrerelease() {
        val releases = listOf(
            rel("v9.0.0-pre", prerelease = true, draft = true), // draft → not published, ignore
            rel("v8.3.0-pre", prerelease = true),
            rel("v8.2.9-pre", prerelease = true),
        )
        assertEquals("8.3.0-pre", UpdateCheck.newestPreviewRelease(releases)?.version())
    }

    @Test
    fun previewReturnsNullWhenOnlyStableReleasesExist() {
        // No pre-releases at all → the preview app finds NOTHING (never a stable), so "check for
        // updates" reports up-to-date instead of walking the user toward a stable APK.
        val releases = listOf(rel("v8.2.9", prerelease = false), rel("v8.2.8", prerelease = false))
        assertNull(UpdateCheck.newestPreviewRelease(releases))
    }

    @Test
    fun previewOrderingHandlesTheFourSegmentBuildVersions() {
        // Branch previews are versioned <base>.<run#> (see the release workflow). The run number only
        // ever grows, so the newest run must win — including against the plain 3-segment base, and
        // across a base bump. A string compare would put "8.2.29.900" above "8.2.29.1300".
        assertTrue(UpdateCheck.isNewer("8.2.29.1300", "8.2.29.900"))
        assertTrue(UpdateCheck.isNewer("8.2.29.1300", "8.2.29"))
        assertTrue(UpdateCheck.isNewer("8.2.30", "8.2.29.1300"))   // next stable outranks any preview of .29
        assertFalse(UpdateCheck.isNewer("8.2.29.1300", "8.2.30"))
        // The preview flavour's own "-preview" versionName suffix must not confuse the parse.
        assertTrue(UpdateCheck.isNewer("8.2.29.1300", "8.2.29.1200-preview"))
    }

    // ── APK asset selection: the second half of channel isolation ────────────────────────────────

    private fun asset(name: String) = UpdateCheck.Asset(name = name, url = "https://x/$name", size = 1)

    @Test
    fun previewPicksOnlyThePreviewApkAndStableOnlyTheStableOne() {
        // A release can carry several APKs. Handing the installer the wrong one is NOT harmless: a
        // stable APK has a different applicationId, so it would install a SECOND app instead of
        // updating this one — the cross-channel mistake this whole design exists to prevent.
        val assets = listOf(
            asset("Choop-v8.3.0.apk"),
            asset("Choop-Preview-v8.3.0.apk"),
            asset("Choop-v8.3.0-demo.apk"),
        )
        assertEquals("Choop-Preview-v8.3.0.apk", UpdateCheck.pickApk(assets, preview = true)?.name)
        assertEquals("Choop-v8.3.0.apk", UpdateCheck.pickApk(assets, preview = false)?.name)
    }

    @Test
    fun apkPickIgnoresTheDemoBuildAndNonApkAssets() {
        // A demo APK is a third applicationId and must never be offered as an update to either
        // channel; non-APK assets (checksums, mapping files) are not installable at all.
        val assets = listOf(
            asset("Choop-v8.3.0-demo.apk"),
            asset("checksums.txt"),
            asset("mapping.txt"),
        )
        assertNull(UpdateCheck.pickApk(assets, preview = false))
        assertNull(UpdateCheck.pickApk(assets, preview = true))
    }

    @Test
    fun apkPickReturnsNullWhenTheChannelHasNoMatchingAsset() {
        // A stable-only release offers nothing to a preview install (and vice versa). Null is the
        // signal for "fall back to opening the release page", never "just take the other one".
        assertNull(UpdateCheck.pickApk(listOf(asset("Choop-v8.3.0.apk")), preview = true))
        assertNull(UpdateCheck.pickApk(listOf(asset("Choop-Preview-v8.3.0.apk")), preview = false))
    }
}
